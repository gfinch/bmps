package bmps.core.api.io

import cats.effect.IO
import cats.effect.{Resource, Fiber}
import cats.effect.unsafe.IORuntime
import fs2.Stream
import cats.effect.std.Queue
// Note: this module intentionally uses unsafe fire-and-forget for background tasks
import _root_.io.circe.syntax._
import _root_.io.circe.generic.auto._
import bmps.core.api.intf.CoreService
import bmps.core.api.util.DateUtils
import java.net.InetSocketAddress
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import com.sun.net.httpserver.HttpServer
import org.java_websocket.server.WebSocketServer
import org.java_websocket.{WebSocket => JWebSocket}
import org.java_websocket.handshake.ClientHandshake
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._
import cats.effect.std.Queue
import bmps.core.Event
import bmps.core.api.intf.StateHolder
import bmps.core.api.impl.ReplayProcessor
import bmps.core.models._
import java.time.ZoneId
import org.slf4j.LoggerFactory

object ServerModule {
  /**
   * Start embedded WS + HTTP servers and return a Stream that never ends while servers run.
   * sendEventFn is used to forward serialized events to clients or buffer.
   */
  def start(webPort: Int, httpPort: Int, webRootPath: String, stateHolderRef: AtomicReference[SystemState], sendEventFn: String => Unit, core: CoreService): Stream[IO, Unit] = {
  val webRoot = Paths.get(webRootPath).toAbsolutePath.normalize()
  val logger = LoggerFactory.getLogger(getClass)

    val readyMap = new java.util.concurrent.ConcurrentHashMap[org.java_websocket.WebSocket, java.lang.Boolean]()
    val eventBuffer = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val conns = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap[org.java_websocket.WebSocket, java.lang.Boolean]())
    val connectQueue = new java.util.concurrent.LinkedBlockingQueue[String]()

    val server: WebSocketServer = new WebSocketServer(new InetSocketAddress(webPort)) {
      override def onOpen(conn: JWebSocket, handshake: ClientHandshake): Unit = {
        readyMap.put(conn, java.lang.Boolean.FALSE)
        conns.add(conn)
      }

      override def onClose(conn: org.java_websocket.WebSocket, code: Int, reason: String, remote: Boolean): Unit = {
        readyMap.remove(conn)
        conns.remove(conn)
      }

      override def onMessage(conn: org.java_websocket.WebSocket, message: String): Unit = {
        try {
          if (message != null && message.trim.equalsIgnoreCase("READY")) {
            readyMap.put(conn, java.lang.Boolean.TRUE)
            var m = eventBuffer.poll()
            while (m != null) {
              val readyConns = conns.asScala.filter(c => Option(readyMap.get(c)).contains(java.lang.Boolean.TRUE))
              readyConns.foreach { c => try c.send(m) catch { case _: Throwable => () } }
              m = eventBuffer.poll()
            }
          } else {
            // If the client sends a PLAN or TRADE message we should mark that
            // connection as ready and flush buffered events to ready clients so
            // the requester receives prior events immediately (matches original behavior).
            try {
              val msgUpper = if (message != null) message.toUpperCase else ""
              if (msgUpper.contains("\"CMD\"") && (msgUpper.contains("PLAN") || msgUpper.contains("TRADE"))) {
                try {
                  readyMap.put(conn, java.lang.Boolean.TRUE)
                  var m = eventBuffer.poll()
                  while (m != null) {
                    val readyConns = conns.asScala.filter(c => Option(readyMap.get(c)).contains(java.lang.Boolean.TRUE))
                    readyConns.foreach { c => try c.send(m) catch { case _: Throwable => () } }
                    m = eventBuffer.poll()
                  }
                } catch { case _: Throwable => () }
              }
            } catch { case _: Throwable => () }
            // Enqueue raw message for the connectQueue listener to pick up
            try connectQueue.put(message) catch { case _: Throwable => () }
          }
        } catch { case _: Throwable => () }
      }

  override def onError(conn: org.java_websocket.WebSocket, ex: Exception): Unit = { () }
      override def onStart(): Unit = { setConnectionLostTimeout(0) }
    }

  val httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0)
    httpServer.createContext("/", { exchange =>
      val rawPath = exchange.getRequestURI.getPath
      val path = if (rawPath == "/" || rawPath == "") "/index.html" else rawPath
      val resolved = webRoot.resolve(path.stripPrefix("/")).normalize()
      // Log resolved path and checks for debugging 404 issues
      try {
        logger.info(s"HTTP request path='$path' resolvedTo='${resolved.toString}' webRoot='${webRoot.toString}' exists=${Files.exists(resolved)} isDirectory=${Files.isDirectory(resolved)}")
      } catch { case _: Throwable => () }
      if (!resolved.startsWith(webRoot) || !Files.exists(resolved) || Files.isDirectory(resolved)) {
        val notFound = "404 Not Found".getBytes(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(404, notFound.length)
        val os = exchange.getResponseBody
        os.write(notFound)
        os.close()
      } else {
        val bytes = Files.readAllBytes(resolved)
        val ct = if (path.endsWith(".js")) "application/javascript" else if (path.endsWith(".css")) "text/css" else "text/html"
        exchange.getResponseHeaders.add("Content-Type", ct)
        exchange.sendResponseHeaders(200, bytes.length)
        val os = exchange.getResponseBody
        os.write(bytes)
        os.close()
      }
    })


    // create cats queue for Events and start a consumer and listener as managed fibers
  // runtime used for unsafe fire-and-forget starts
  implicit val runtime: IORuntime = IORuntime.global

  // create cats queue for Events and start a consumer and listener as fire-and-forget tasks
  def processMsg(msg: String, q: Queue[IO, Event]): IO[Unit] = {
      val msgUpper = if (msg != null) msg.toUpperCase else ""
      if (msgUpper.contains("\"CMD\"") && msgUpper.contains("PLAN")) {
        val DateRegex = (""".*\"date\"\s*:\s*\"(\d{4}-\d{2}-\d{2})\".*""").r
        msg match {
          case DateRegex(dateStr) =>
            val DaysRegex = (""".*\"days\"\s*:\s*(\d+).*""").r
            val combined = msg match { case DaysRegex(d) => s"${dateStr}|${d.toInt}" case _ => s"${dateStr}|2" }
            val parts = combined.split("\\|")
            val reqDate = parts.lift(0).getOrElse("")
            val reqDays = parts.lift(1).flatMap(s => try Some(s.toInt) catch { case _: Throwable => None }).getOrElse(2)
            val prevDates = DateUtils.computePrevTradingDates(reqDate, reqDays)
            if (prevDates.nonEmpty) {
              val sorted = prevDates.toList.sorted
              val startDate = sorted.min
              val tradingDay = try java.time.LocalDate.parse(reqDate) catch { case _: Throwable => java.time.LocalDate.now() }
              val eastern = ZoneId.of("America/New_York")
              val startMs = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant.toEpochMilli
              val endMs = tradingDay.atTime(9,30).atZone(eastern).toInstant.toEpochMilli
              // start replay as fire-and-forget
              IO { core.streamParquetRangeToQueue(startMs, endMs, tradingDay, q).unsafeRunAndForget() }
            } else IO.unit
          case _ => IO.unit
        }
      } else if (msgUpper.contains("\"CMD\"") && msgUpper.contains("TRADE")) {
        for {
          stOpt <- IO(Option(stateHolderRef.get()))
          _ <- stOpt.fold(IO.unit) { state =>
            val openPlanZones = state.planZones.filter(pz => pz.endTime.isEmpty)
            val openDaytime = state.daytimeExtremes.filter(de => de.endTime.isEmpty)
            if (openPlanZones.nonEmpty || openDaytime.nonEmpty) {
              val planTs = openPlanZones.map(_.startTime)
              val dayTs = openDaytime.map(_.timestamp)
              val earliestOpt = (planTs ++ dayTs).sorted.headOption
              earliestOpt.fold(IO.unit) { earliest =>
                val oneHourMs = 3600L * 1000L
                val startMs = Math.max(0L, earliest - oneHourMs)
                val eastern = ZoneId.of("America/New_York")
                val tradingDay = state.tradingDay
                val endMs = tradingDay.atTime(9,30).atZone(eastern).toInstant.toEpochMilli
                val fiveMinPath = "core/src/main/resources/samples/es_futures_5min_60days.parquet"
                if (startMs < endMs) {
                  val publisher: Event => IO[Unit] = ev => q.offer(ev)
                  // fire-and-forget replay
                  IO { ReplayProcessor.replayFiveMin(fiveMinPath, startMs, endMs, ZoneId.systemDefault(), stateHolderRef, openPlanZones, openDaytime, publisher).unsafeRunAndForget() }
                } else IO.unit
              }
            } else IO.unit
          }
        } yield ()
      } else IO.unit
    }

    val wiring: IO[Queue[IO, Event]] = for {
      q <- Queue.unbounded[IO, Event]
      // start consumer as a fire-and-forget task
      _ <- IO {
        fs2.Stream.repeatEval(q.take).evalMap { ev => IO {
          val json = ev.asJson.noSpaces
          val readyConns = conns.asScala.filter(c => Option(readyMap.get(c)).contains(java.lang.Boolean.TRUE))
          if (readyConns.nonEmpty) {
            readyConns.foreach { c => try { c.send(json); logger.info(s"sent event to client: ${json}") } catch { case t: Throwable => logger.warn("failed to send event to client", t) } }
          } else {
            eventBuffer.add(json);
            logger.info(s"buffered event (no ready clients): ${json}")
          }
        }}.compile.drain.unsafeRunAndForget()
      }

      // start listener as a fire-and-forget task
      _ <- IO {
        fs2.Stream.repeatEval(IO.blocking(connectQueue.take())).evalMap { msg =>
          processMsg(msg, q).handleErrorWith(t => IO(logger.warn("connectQueue listener error", t)))
        }.compile.drain.unsafeRunAndForget()
      }
    } yield q

    val acquire = for {
      _ <- IO { logger.info(s"starting servers on webPort=$webPort httpPort=$httpPort"); server.start(); httpServer.start() }
      wiringRes <- wiring
    } yield wiringRes

    def release(q: Queue[IO, Event]): IO[Unit] = for {
      // background tasks are fire-and-forget and cannot be reliably cancelled
      _ <- IO { try { server.stop() } catch { case t: Throwable => logger.warn("error stopping server", t) } }
      _ <- IO { try { httpServer.stop(0) } catch { case t: Throwable => logger.warn("error stopping http server", t) } }
      _ <- IO { logger.info("servers stopped (background tasks were fire-and-forget and may continue running)") }
    } yield ()

  fs2.Stream.bracket(acquire)(release).flatMap { q => Stream.eval(IO.never) }
  }

}


