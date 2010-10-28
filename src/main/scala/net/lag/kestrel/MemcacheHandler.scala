/*
 * Copyright 2009 Twitter, Inc.
 * Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.lag.kestrel

import java.io.IOException
import java.net.InetSocketAddress
import scala.collection.mutable
import com.twitter.actors.Actor
import com.twitter.actors.Actor._
import com.twitter.naggati.{NettyMessage, ProtocolError}
import com.twitter.naggati.codec.{MemcacheRequest, MemcacheResponse}
import com.twitter.xrayspecs.Time
import com.twitter.xrayspecs.TimeConversions._
import net.lag.configgy.{Config, Configgy}
import net.lag.logging.Logger
import org.jboss.netty.channel.Channel
import org.jboss.netty.handler.timeout.IdleStateHandler

/**
 * Memcache protocol handler for a kestrel connection.
 */
class MemcacheHandler(val channel: Channel, config: Config)
      extends KestrelHandler(config) with Actor {
  private val log = Logger.get

  private val IDLE_TIMEOUT = 60
  private val remoteAddress = channel.getRemoteAddress.asInstanceOf[InetSocketAddress]

  // config can be null in unit tests
  val idleTimeout = if (config == null) IDLE_TIMEOUT else config.getInt("timeout", IDLE_TIMEOUT)
  if (idleTimeout > 0) {
    channel.getPipeline.addFirst("idle", new IdleStateHandler(Kestrel.timer, 0, 0, idleTimeout))
  }

  Kestrel.channels.add(channel)
  log.debug("New session %d from %s:%d", sessionID, remoteAddress.getHostName, remoteAddress.getPort)
  start

  protected def clientDescription: String = {
    "%s:%d".format(remoteAddress.getHostName, remoteAddress.getPort)
  }

  def act = {
    loop {
      react {
        case NettyMessage.MessageReceived(msg) =>
          handle(msg.asInstanceOf[MemcacheRequest])

        case NettyMessage.ExceptionCaught(cause) =>
          cause match {
            case _: ProtocolError =>
              new MemcacheResponse("CLIENT_ERROR").writeTo(channel)
            case _: IOException =>
              log.debug("I/O Exception on session %d: %s", sessionID, cause.toString)
            case _ =>
              log.error(cause, "Exception caught on session %d: %s", sessionID, cause.toString)
              new MemcacheResponse("ERROR").writeTo(channel)
          }
          channel.close()

        case NettyMessage.ChannelDisconnected() =>
          finish()
          exit()

        case NettyMessage.ChannelIdle(status) =>
          log.debug("Idle timeout on session %s", channel)
          channel.close()
      }
    }
  }

  private def handle(request: MemcacheRequest) = {
    KestrelStats.bytesRead.incr(request.bytesRead)
    request.line(0) match {
      case "get" =>
        get(request.line(1))
      case "set" =>
        try {
          if (setItem(request.line(1), request.line(2).toInt, request.line(3).toInt, request.data.get)) {
            new MemcacheResponse("STORED").writeTo(channel)
          } else {
            new MemcacheResponse("NOT_STORED").writeTo(channel)
          }
        } catch {
          case e: NumberFormatException =>
            throw new ProtocolError("bad request: " + request)
        }
      case "stats" =>
        stats()
      case "shutdown" =>
        shutdown()
      case "reload" =>
        Configgy.reload()
        new MemcacheResponse("Reloaded config.").writeTo(channel)
      case "flush" =>
        flush(request.line(1))
        new MemcacheResponse("END").writeTo(channel)
      case "flush_all" =>
        flushAllQueues()
        new MemcacheResponse("Flushed all queues.").writeTo(channel)
      case "dump_config" =>
        dumpConfig()
      case "dump_stats" =>
        dumpStats()
      case "delete" =>
        delete(request.line(1))
        new MemcacheResponse("END").writeTo(channel)
      case "flush_expired" =>
        new MemcacheResponse(flushExpired(request.line(1)).toString).writeTo(channel)
      case "flush_all_expired" =>
        val flushed = Kestrel.queues.flushAllExpired()
        new MemcacheResponse(flushed.toString).writeTo(channel)
      case "roll" =>
        rollJournal(request.line(1))
        new MemcacheResponse("END").writeTo(channel)
      case "version" =>
        version()
      case "quit" =>
        quit()
      case x =>
        new MemcacheResponse("CLIENT_ERROR").writeTo(channel)
    }
  }

  private def get(name: String): Unit = {
    var key = name
    var timeout = 0
    var closing = false
    var opening = false
    var aborting = false
    var peeking = false

    if (name contains '/') {
      val options = name.split("/")
      key = options(0)
      for (i <- 1 until options.length) {
        val opt = options(i)
        if (opt startsWith "t=") {
          timeout = opt.substring(2).toInt
        }
        if (opt == "close") closing = true
        if (opt == "open") opening = true
        if (opt == "abort") aborting = true
        if (opt == "peek") peeking = true
      }
    }

    if ((key.length == 0) || ((peeking || aborting) && (opening || closing)) || (peeking && aborting)) {
      new MemcacheResponse("CLIENT_ERROR").writeTo(channel)
      channel.close()
      return
    }

    try {
      if (aborting) {
        abortTransaction(key)
        new MemcacheResponse("END").writeTo(channel)
      } else {
        if (closing) {
          closeTransaction(key)
          if (!opening) new MemcacheResponse("END").writeTo(channel)
        }
        if (opening || !closing) {
          if (pendingTransaction.isDefined && !peeking) {
            log.warning("Attempt to perform a non-transactional fetch with an open transaction on " +
                        " '%s' (sid %d, %s:%d)", key, sessionID, remoteAddress.getHostName,
                        remoteAddress.getPort)
            new MemcacheResponse("ERROR").writeTo(channel)
            channel.close()
            return
          }
          getItem(key, timeout, opening, peeking) {
            case None =>
              new MemcacheResponse("END").writeTo(channel)
            case Some(item) =>
              new MemcacheResponse("VALUE %s 0 %d".format(key, item.data.length), item.data).writeTo(channel)
          }
        }
      }
    } catch {
      case e: MismatchedQueueException =>
        new MemcacheResponse("ERROR").writeTo(channel)
        channel.close()
    }
  }

  private def stats() = {
    var report = new mutable.ArrayBuffer[(String, String)]
    report += (("uptime", Kestrel.uptime.toString))
    report += (("time", (Time.now.inMilliseconds / 1000).toString))
    report += (("version", Kestrel.runtime.jarVersion))
    report += (("curr_items", Kestrel.queues.currentItems.toString))
    report += (("total_items", Kestrel.queues.totalAdded.toString))
    report += (("bytes", Kestrel.queues.currentBytes.toString))
    report += (("curr_connections", KestrelStats.sessions.toString))
    report += (("total_connections", KestrelStats.totalConnections.toString))
    report += (("cmd_get", KestrelStats.getRequests.toString))
    report += (("cmd_set", KestrelStats.setRequests.toString))
    report += (("cmd_peek", KestrelStats.peekRequests.toString))
    report += (("get_hits", Kestrel.queues.queueHits.toString))
    report += (("get_misses", Kestrel.queues.queueMisses.toString))
    report += (("bytes_read", KestrelStats.bytesRead.toString))
    report += (("bytes_written", KestrelStats.bytesWritten.toString))

    for (qName <- Kestrel.queues.queueNames) {
      report ++= Kestrel.queues.stats(qName).map { case (k, v) => ("queue_" + qName + "_" + k, v) }
    }

    val summary = {
      for ((key, value) <- report) yield "STAT %s %s".format(key, value)
    }.mkString("", "\r\n", "\r\nEND")
    new MemcacheResponse(summary).writeTo(channel)
  }

  private def dumpConfig() = {
    val dump = new mutable.ListBuffer[String]
    for (qName <- Kestrel.queues.queueNames) {
      dump += "queue '" + qName + "' {"
      dump += Kestrel.queues.dumpConfig(qName).mkString("  ", "\r\n  ", "")
      dump += "}"
    }
    new MemcacheResponse(dump.mkString("", "\r\n", "\r\nEND\r\n")).writeTo(channel)
  }

  private def dumpStats() = {
    val dump = new mutable.ListBuffer[String]
    for (qName <- Kestrel.queues.queueNames) {
      dump += "queue '" + qName + "' {"
      dump += Kestrel.queues.stats(qName).map { case (k, v) => k + "=" + v }.mkString("  ", "\r\n  ", "")
      dump += "}"
    }
    new MemcacheResponse(dump.mkString("", "\r\n", "\r\nEND\r\n")).writeTo(channel)
  }

  private def version() = {
    new MemcacheResponse("VERSION " + Kestrel.runtime.jarVersion + "\r\n").writeTo(channel)
  }

  private def quit() = {
    channel.close()
  }
}
