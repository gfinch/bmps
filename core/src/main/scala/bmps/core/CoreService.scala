package bmps.core

import cats.effect.{IO, IOApp, Ref}
import fs2.Stream
import _root_.io.circe.syntax._
import _root_.io.circe.generic.auto._
import java.time.{Instant, ZoneId}
import java.sql.DriverManager
import bmps.core.models._
import bmps.core.services.SwingService
import scala.jdk.CollectionConverters._
import com.typesafe.config.ConfigFactory
import java.net.http.{HttpClient, WebSocket}
import java.net.URI
import java.util.concurrent.CompletionStage
// Embedded HTTP/WebSocket server
import org.java_websocket.server.WebSocketServer
import org.java_websocket.{WebSocket => JWebSocket}
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.framing.CloseFrame
import java.net.InetSocketAddress
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * CoreService reads market data (currently from a Parquet file via DuckDB),
 * publishes each candle as an Event, runs the SwingService to detect swing
 * points and publishes any new swing point events.
 *
 * Future services (order manager, zone detector, risk manager, etc.) should
 * be hooked into the event publishing pipeline where indicated below.
 */
class CoreService(params: InitParams, parquetPath: String = "core/src/main/resources/samples/es_futures_1h_60days.parquet") {

  private val swingService = new SwingService()
  private val zoneId = ZoneId.systemDefault()

  // Parquet reading lives in ParquetSource to keep CoreService data-source agnostic.
  import bmps.core.io.ParquetSource

  // Public events stream so other modules (web) can consume core events directly.
  def events: Stream[IO, Event] = {
    val initialState = SystemState(Nil, Direction.Up, Nil)
    for {
      // read all candles (delegated to ParquetSource)
      candles <- Stream.eval(ParquetSource.readParquetAsCandles(parquetPath))

      // keep mutable state in Ref so each candle can be processed sequentially
      stateRef <- Stream.eval(Ref.of[IO, SystemState](initialState))

      // publish each candle and any newly discovered swing points
      event <- Stream.emits(candles).covary[IO].evalMap { candle =>
        // Atomically update state and compute new swings
        stateRef.modify { state =>
          val updatedCandles = state.candles :+ candle
          val updatedState = swingService.computeSwings(state.copy(candles = updatedCandles))
          val newSwingPoints = updatedState.swingPoints.drop(state.swingPoints.length)
          (updatedState, (candle, newSwingPoints))
        }
      }.flatMap { case (candle, newSwings) =>
        val candleEvent = Event(EventType.Candle, candle.timestamp, Some(candle), None)
        val swingEvents = newSwings.map(sp => Event(EventType.SwingPoint, sp.timestamp, None, Some(sp)))
        Stream.emit(candleEvent) ++ Stream.emits(swingEvents)
      }
    } yield event
  }

  // Start the core processing and optionally forward events to an external websocket
  def start: Stream[IO, Unit] = {
    val cfg = ConfigFactory.load()
    val webPort = try { cfg.getInt("bmps.core.web-port") } catch { case _: Throwable => 9001 }

  // readiness map and buffer: clients can send a "READY" message to start receiving
  val readyMap = new java.util.concurrent.ConcurrentHashMap[org.java_websocket.WebSocket, java.lang.Boolean]()
  val eventBuffer = new java.util.concurrent.ConcurrentLinkedQueue[String]()
  val conns = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap[org.java_websocket.WebSocket, java.lang.Boolean]())
  // queue to receive CONNECT commands (date) from clients; blocks the replay runner until a client requests a replay
  // queue receives strings of the form "YYYY-MM-DD|N" where N is optional number of prior trading days
  val connectQueue = new java.util.concurrent.LinkedBlockingQueue[String]()

    // Start a simple embedded WebSocket server
  val server: WebSocketServer = new WebSocketServer(new InetSocketAddress(webPort)) {
      override def onOpen(conn: JWebSocket, handshake: ClientHandshake): Unit = {
        println(s"WS client connected: ${conn.getRemoteSocketAddress}")
        // mark as not-ready until client signals READY
        readyMap.put(conn, java.lang.Boolean.FALSE)
        conns.add(conn)
      }

      override def onClose(conn: org.java_websocket.WebSocket, code: Int, reason: String, remote: Boolean): Unit = {
        println(s"WS client disconnected: ${conn.getRemoteSocketAddress} code=$code reason=$reason")
        // clean up readiness state
        readyMap.remove(conn)
        conns.remove(conn)
      }

      override def onMessage(conn: org.java_websocket.WebSocket, message: String): Unit = {
        // Control messages: expect either a simple "READY" or a JSON CONNECT command
        try {
          if (message != null && message.trim.equalsIgnoreCase("READY")) {
            readyMap.put(conn, java.lang.Boolean.TRUE)
            // flush buffered events to all ready clients
            var m = eventBuffer.poll()
            while (m != null) {
              val readyConns = conns.asScala.filter(c => Option(readyMap.get(c)).contains(java.lang.Boolean.TRUE))
              readyConns.foreach { c => try c.send(m) catch { case _: Throwable => () } }
              m = eventBuffer.poll()
            }
          } else {
            // Try to parse a CONNECT JSON: { "cmd": "CONNECT", "date": "YYYY-MM-DD" }
            try {
              // Expect a simple JSON like {"cmd":"CONNECT","date":"YYYY-MM-DD"}
              val msgLower = if (message != null) message.toUpperCase else ""
              if (msgLower.contains("\"CMD\"") && msgLower.contains("CONNECT")) {
                // crude date extraction
                val DateRegex = (""".*\"date\"\s*:\s*\"(\d{4}-\d{2}-\d{2})\".*""").r
                message match {
                  case DateRegex(dateStr) => 
                    // try to extract optional days value
                    val DaysRegex = (""".*\"days\"\s*:\s*(\d+).*""").r
                    val combined = message match {
                      case DaysRegex(d) => s"${dateStr}|${d.toInt}"
                      case _ => s"${dateStr}|2"
                    }
                    try connectQueue.put(combined) catch { case _: Throwable => () }
                    // mark this connection ready and flush buffered events
                    try {
                      readyMap.put(conn, java.lang.Boolean.TRUE)
                      var m = eventBuffer.poll()
                      while (m != null) {
                        val readyConns = conns.asScala.filter(c => Option(readyMap.get(c)).contains(java.lang.Boolean.TRUE))
                        readyConns.foreach { c => try c.send(m) catch { case _: Throwable => () } }
                        m = eventBuffer.poll()
                      }
                    } catch { case _: Throwable => () }
                  case _ => ()
                }
              }
            } catch { case _: Throwable => () }
          }
        } catch { case _: Throwable => () }
      }

      override def onError(conn: JWebSocket, ex: Exception): Unit = {
        System.err.println("WS server error: " + ex.toString)
      }

      override def onStart(): Unit = {
        println(s"Embedded WS server started on port $webPort")
        setConnectionLostTimeout(0)
      }
    }

    // Start both servers inside a Stream effect, then continue with broadcast of events
    val httpPort = try { cfg.getInt("bmps.core.http-port") } catch { case _: Throwable => 5173 }
    val webRoot = Paths.get("web").toAbsolutePath.normalize()
    val httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0)
    httpServer.createContext("/", { exchange =>
      val rawPath = exchange.getRequestURI.getPath
      val path = if (rawPath == "/" || rawPath == "") "/index.html" else rawPath
      // Prevent directory traversal
      val resolved = webRoot.resolve(path.stripPrefix("/")).normalize()
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
    httpServer.setExecutor(null)

    val acquire: IO[Unit] = IO {
      server.start()
      httpServer.start()
      println(s"Embedded WS server started on port $webPort")
      println(s"Embedded HTTP server started on http://localhost:${httpPort}")
    }

  def release(u: Unit): IO[Unit] = IO {
      try {
        server.stop()
      } catch { case _: Throwable => () }
      try {
        httpServer.stop(0)
      } catch { case _: Throwable => () }
      println(s"Embedded WS server stopped on port $webPort")
      println(s"Embedded HTTP server stopped on http://localhost:${httpPort}")
    }

    // Helper: compute the previous two trading dates (skip weekends)
    def computePrevTradingDates(dateStr: String, days: Int): Set[LocalDate] = {
      try {
        val dt = LocalDate.parse(dateStr)
        val buf = scala.collection.mutable.ListBuffer.empty[LocalDate]
        var d = dt.minusDays(1)
        while (buf.size < days) {
          val dow = d.getDayOfWeek
          if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) buf += d
          d = d.minusDays(1)
        }
        buf.toList.toSet
      } catch { case _: DateTimeParseException => Set.empty[LocalDate] }
    }

    // Build events stream from an explicit list of candles (like the existing `events` but parameterized)
    def eventsFromCandles(candles: List[Candle]): Stream[IO, Event] = {
      val initialState = SystemState(Nil, Direction.Up, Nil)
      for {
        stateRef <- Stream.eval(Ref.of[IO, SystemState](initialState))
        event <- Stream.emits(candles).covary[IO].evalMap { candle =>
          stateRef.modify { state =>
            val updatedCandles = state.candles :+ candle
            val updatedState = swingService.computeSwings(state.copy(candles = updatedCandles))
            val newSwingPoints = updatedState.swingPoints.drop(state.swingPoints.length)
            (updatedState, (candle, newSwingPoints))
          }
        }.flatMap { case (candle, newSwings) =>
          val candleEvent = Event(EventType.Candle, candle.timestamp, Some(candle), None)
          val swingEvents = newSwings.map(sp => Event(EventType.SwingPoint, sp.timestamp, None, Some(sp)))
          Stream.emit(candleEvent) ++ Stream.emits(swingEvents)
        }
      } yield event
    }

    // Prepare broadcaster as an IO that waits for CONNECT commands, then reads parquet and broadcasts filtered events.
    val broadcasterIO: IO[Unit] = fs2.Stream
      .repeatEval(IO.blocking(connectQueue.take()))
      .evalMap { dateStr =>
        IO.blocking(println(s"Received CONNECT for date=$dateStr")).flatMap { _ =>
  val parts = dateStr.split("\\|")
  val reqDate = parts.lift(0).getOrElse("")
  val reqDays = parts.lift(1).flatMap(s => try Some(s.toInt) catch { case _: Throwable => None }).getOrElse(2)
  val prevDates = computePrevTradingDates(reqDate, reqDays)
  if (prevDates.isEmpty) IO.unit else {
          // compute start (inclusive) and end (exclusive) epoch millis covering the two previous trading days
          val sorted = prevDates.toList.sorted
          val startDate = sorted.min
          val endDate = sorted.max.plusDays(1)
          val startMs = startDate.atStartOfDay(zoneId).toInstant.toEpochMilli
          val endMs = endDate.atStartOfDay(zoneId).toInstant.toEpochMilli
          ParquetSource.readParquetAsCandlesInRange(parquetPath, startMs, endMs, zoneId).flatMap { candlesInRange =>
            println(s"Parquet read: candlesInRange=${candlesInRange.size} for dates=${prevDates}")
            eventsFromCandles(candlesInRange).evalMap { event =>
            val json = event.asJson.noSpaces
            IO {
              try {
                val readyClients = conns.asScala.filter(c => Option(readyMap.get(c)).contains(java.lang.Boolean.TRUE))
                if (readyClients.nonEmpty) {
                  println(s"sending event to ${readyClients.size} clients: ${json.take(200)}")
                  readyClients.foreach { c => try c.send(json) catch { case _: Throwable => () } }
                } else {
                  println(s"buffering event: ${json.take(200)}")
                  eventBuffer.add(json)
                }
              } catch { case e: Throwable => println("broadcast error: " + e.toString) }
            }
          }.compile.drain
          }
        }
  }
  }
  .compile
  .drain

    // Start servers with a bracket so they are stopped when the stream is canceled (e.g., Ctrl+C).
    // Start the broadcaster as a background fiber and then keep the stream alive forever.
    fs2.Stream.bracket(acquire)(release).flatMap { _ =>
      Stream.eval(broadcasterIO.start).flatMap(_ => Stream.eval(IO.never))
    }
    
  }
}

object CoreService extends IOApp.Simple {
  def run: IO[Unit] = {
    val params = InitParams(TradingMode.Simulation)
    new CoreService(params).start.compile.drain
  }
}
