package bmps.core

import cats.effect.{IO, IOApp, Ref}
import fs2.Stream
import cats.effect.unsafe.IORuntime
import _root_.io.circe.syntax._
import _root_.io.circe.generic.auto._
import java.time.{Instant, ZoneId}
import java.util.concurrent.atomic.AtomicReference
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
import bmps.core.services.PlanZoneService
import scala.collection.mutable.ListBuffer
import bmps.core.services.LiquidityZoneService

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

  // Keep a snapshot of the latest SystemState so other threads (WS handler) can
  // access the planning state when a TRADE command arrives.
  private val initialState = SystemState(params.tradingDate, Nil, Direction.Up, Nil)
  private val latestStateRef: AtomicReference[SystemState] = new AtomicReference[SystemState](initialState)
  // Reference to an optional outbound offer function; using a function type
  // avoids referencing fs2.concurrent.Queue in the class signature and lets
  // the WS handler publish events into the running stream.
  private val outboundOfferRef: AtomicReference[Option[Event => IO[Unit]]] = new AtomicReference[Option[Event => IO[Unit]]](None)

  // Public events stream so other modules (web) can consume core events directly.
  def events: Stream[IO, Event] = {
  // Stream the parquet file via the single range-stream API (wide open range)
  val candleStream: Stream[IO, Candle] = ParquetSource.readParquetAsCandlesInRangeStream(parquetPath, 0L, Long.MaxValue, zoneId)
  processCandlesStream(candleStream, params.tradingDate)
  }

  // Build events stream from a stream of candles. This is the shared implementation
  // used by both `events` (full parquet) and replays (range reads).
  private def processCandlesStream(candles: Stream[IO, Candle], tradingDay: LocalDate): Stream[IO, Event] = {
    // Each stream keeps its own Ref for sequential updates, but mirror the
    // latest computed state into latestStateRef so the WS handler can read it.
    // Use a stream-local initial state with the requested tradingDay so the
    // replayed/restated planning state reflects the date the client requested.
    val streamInitial = SystemState(tradingDay, Nil, Direction.Up, Nil)
    for {
      stateRef <- Stream.eval(Ref.of[IO, SystemState](streamInitial))
      // Mirror the stream initial state into the shared latestStateRef so
      // WebSocket handlers that read the planning snapshot see the correct
      // tradingDay even before the first candle is processed.
      _ <- Stream.eval(IO { try { latestStateRef.set(streamInitial) } catch { case _: Throwable => () } })
      event <- candles.evalMap { candle =>
        stateRef.modify { state =>
          val updatedCandles = state.candles :+ candle
          val withSwings = swingService.computeSwings(state.copy(candles = updatedCandles))
          val (withZones, zoneEvents) = PlanZoneService.processPlanZones(withSwings)
          val (updatedState, liquidEvents) = LiquidityZoneService.processLiquidityZones(withZones)
          println(s"***************${withZones.tradingDay}")
          println(s"***************${updatedState.daytimeExtremes.size}")

          // keep a live snapshot for TRADE handling
          try { latestStateRef.set(updatedState) } catch { case _: Throwable => () }

          val newSwingPoints = updatedState.swingPoints.drop(state.swingPoints.length)
          (updatedState, (candle, newSwingPoints, zoneEvents, liquidEvents))
        }
      }.flatMap { case (candle, newSwings, zoneEvents, liquidEvents) =>
        val candleEvent = Event.fromCandle(candle)
        val swingEvents = newSwings.map(sp => Event.fromSwingPoint(sp))

        if (liquidEvents.nonEmpty) println(s"[CoreService] candle ${candle.timestamp} produced ${liquidEvents.size} liquidity events")
        Stream.emit(candleEvent) ++ Stream.emits(swingEvents) ++ Stream.emits(zoneEvents) ++ Stream.emits(liquidEvents)
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
  // queue to receive PLAN commands (date) from clients; blocks the replay runner until a client requests a replay
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
  // Control messages: expect either a simple "READY" or a JSON PLAN command (or TRADE placeholder)
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
              // Expect a simple JSON like {"cmd":"PLAN","date":"YYYY-MM-DD"} or {"cmd":"TRADE",...}
              val msgLower = if (message != null) message.toUpperCase else ""
              // Handle PLAN (used previously as CONNECT)
              if (msgLower.contains("\"CMD\"") && msgLower.contains("PLAN")) {
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
              } else if (msgLower.contains("\"CMD\"") && msgLower.contains("TRADE")) {
                // TRADE command received from client: convert planning SystemState
                // into a sequence of events and publish them so the trading side
                // can pick up the plan that was created during planning.
                try {
                  println(s"Received TRADE command from ${conn.getRemoteSocketAddress}: ${message.take(200)}")

                  // mark this connection ready and flush any buffered events so the
                  // client (and other ready clients) receive prior events before
                  // we send the TRADE-derived events.
                  try { readyMap.put(conn, java.lang.Boolean.TRUE) } catch { case _: Throwable => () }
                  var m = eventBuffer.poll()
                  while (m != null) {
                    val readyConns = conns.asScala.filter(c => Option(readyMap.get(c)).contains(java.lang.Boolean.TRUE))
                    readyConns.foreach { c => try c.send(m) catch { case _: Throwable => () } }
                    m = eventBuffer.poll()
                  }

                  // Read current planning state snapshot
                  val st = Option(latestStateRef.get())
                  st.foreach { state =>
                    // 1) All non-closed PlanZone (endTime.isEmpty)
                    val openPlanZones = state.planZones.filter(pz => pz.endTime.isEmpty)
                    // 2) All non-closed Liquidity zones (daytimeExtremes with endTime.isEmpty)
                    val openDaytime = state.daytimeExtremes.filter(de => de.endTime.isEmpty)

                    println(s"[CoreService][TRADE] openPlanZones=${openPlanZones.size} openDaytime=${openDaytime.size} outboundOfferPresent=${outboundOfferRef.get().isDefined}")

                    // If there are no active items, nothing to playback
                    if (openPlanZones.isEmpty && openDaytime.isEmpty) {
                      // nothing to send
                    } else {
                      // Determine earliest timestamp among open plan zones and daytime extremes
                      val planTs = openPlanZones.map(_.startTime)
                      val dayTs = openDaytime.map(_.timestamp)
                      val earliestOpt = (planTs ++ dayTs).sorted.headOption

                      earliestOpt.foreach { earliest =>
                        // Start playback one hour earlier than the earliest plan zone / daytime extreme
                        val oneHourMs = 3600L * 1000L
                        val startMs = Math.max(0L, earliest - oneHourMs)

                        val eastern = ZoneId.of("America/New_York")
                        val tradingDay = state.tradingDay
                        val endMs = tradingDay.atTime(9, 30).atZone(eastern).toInstant.toEpochMilli

                        // 5-min parquet path (resource included in project)
                        val fiveMinPath = "core/src/main/resources/samples/es_futures_5min_60days.parquet"

                        if (startMs < endMs) {
                          // Track which existing items we've emitted so we only emit once
                          val emittedPlanStarts = scala.collection.mutable.Set.empty[Long]
                          val emittedDayStarts = scala.collection.mutable.Set.empty[Long]

                          // Stream 5-min candles from earliest to market open and process them
                          val candlesStream = ParquetSource.readParquetAsCandlesInRangeStream(fiveMinPath, startMs, endMs, zoneId)

                          // Process each 5-min candle: update latestStateRef.fiveMinCandles and compute fiveMin swings
                          try {
                            val processIO = candlesStream.evalMap { candle =>
                              IO {
                                try {
                                  // Update fiveMinCandles and compute swings
                                  val prev = latestStateRef.get()
                                  val newFiveCandles = prev.fiveMinCandles :+ candle
                                  // Build a SystemState containing fiveMinCandles to compute swings
                                  val tmp = prev.copy(fiveMinCandles = newFiveCandles, fiveMinSwingPoints = List.empty)
                                  val swingsComputed = swingService.computeSwings(tmp)
                                  val newFiveSwings = swingsComputed.swingPoints
                                  val updated = prev.copy(fiveMinCandles = newFiveCandles, fiveMinSwingPoints = newFiveSwings)
                                  try { latestStateRef.set(updated) } catch { case _: Throwable => () }

                                  // Emit the candle event (forward into pipeline like broadcaster did)
                                  val candleEvent = Event.fromCandle(candle)
                                  val candleJson = candleEvent.asJson.noSpaces
                                  try conn.send(candleJson) catch { case _: Throwable => () }
                                  outboundOfferRef.get() match {
                                    case Some(offerFn) => try { offerFn(candleEvent).unsafeRunAndForget()(IORuntime.global) } catch { case _: Throwable => () }
                                    case None =>
                                      val readyConns = conns.asScala.filter(c => Option(readyMap.get(c)).contains(java.lang.Boolean.TRUE))
                                      if (readyConns.nonEmpty) readyConns.foreach { c => try c.send(candleJson) catch { case _: Throwable => () } }
                                      else eventBuffer.add(candleJson)
                                  }

                                  // Emit any new five-min swing events (diff by timestamp)
                                  val prevSwings = prev.fiveMinSwingPoints.map(_.timestamp).toSet
                                  val newSwings = newFiveSwings.filterNot(sp => prevSwings.contains(sp.timestamp))
                                  val swingEvents = newSwings.map(sp => Event.fromSwingPoint(sp))
                                  // send swing events to requesting conn and pipeline
                                  swingEvents.foreach { ev =>
                                    val json = ev.asJson.noSpaces
                                    try conn.send(json) catch { case _: Throwable => () }
                                  }
                                  outboundOfferRef.get() match {
                                    case Some(offerFn) => newSwings.foreach(sp => try { offerFn(Event.fromSwingPoint(sp)).unsafeRunAndForget()(IORuntime.global) } catch { case _: Throwable => () })
                                    case None =>
                                      val jsons = swingEvents.map(_.asJson.noSpaces)
                                      val readyConns = conns.asScala.filter(c => Option(readyMap.get(c)).contains(java.lang.Boolean.TRUE))
                                      if (readyConns.nonEmpty) readyConns.foreach { c => jsons.foreach { j => try c.send(j) catch { case _: Throwable => () } } }
                                      else jsons.foreach(j => eventBuffer.add(j))
                                  }

                                  // If we've reached or passed any existing plan zone startTime or daytime timestamp, emit those existing events
                                  openPlanZones.filter(pz => !emittedPlanStarts.contains(pz.startTime) && candle.timestamp >= pz.startTime).foreach { pz =>
                                    emittedPlanStarts += pz.startTime
                                    val ev = Event.fromPlanZone(pz)
                                    val json = ev.asJson.noSpaces
                                    try conn.send(json) catch { case _: Throwable => () }
                                    outboundOfferRef.get() match {
                                      case Some(offerFn) => try { offerFn(ev).unsafeRunAndForget()(IORuntime.global) } catch { case _: Throwable => () }
                                      case None =>
                                        val readyConns = conns.asScala.filter(c => Option(readyMap.get(c)).contains(java.lang.Boolean.TRUE))
                                        if (readyConns.nonEmpty) readyConns.foreach { c => try c.send(json) catch { case _: Throwable => () } }
                                        else eventBuffer.add(json)
                                    }
                                  }

                                  openDaytime.filter(de => !emittedDayStarts.contains(de.timestamp) && candle.timestamp >= de.timestamp).foreach { de =>
                                    emittedDayStarts += de.timestamp
                                    val ev = Event.fromDaytimeExtreme(de)
                                    val json = ev.asJson.noSpaces
                                    try conn.send(json) catch { case _: Throwable => () }
                                    outboundOfferRef.get() match {
                                      case Some(offerFn) => try { offerFn(ev).unsafeRunAndForget()(IORuntime.global) } catch { case _: Throwable => () }
                                      case None =>
                                        val readyConns = conns.asScala.filter(c => Option(readyMap.get(c)).contains(java.lang.Boolean.TRUE))
                                        if (readyConns.nonEmpty) readyConns.foreach { c => try c.send(json) catch { case _: Throwable => () } }
                                        else eventBuffer.add(json)
                                    }
                                  }

                                } catch { case _: Throwable => () }
                              }
                            }.compile.drain

                            // Start processing asynchronously so we don't block the WebSocket handler
                            try { processIO.unsafeRunAndForget()(IORuntime.global) } catch { case _: Throwable => () }
                          } catch { case _: Throwable => () }
                        }
                      }
                    }
                  }
                } catch { case _: Throwable => () }
              } else if (msgLower.contains("\"CMD\"") && msgLower.contains("SPEED")) {
                // Placeholder: SPEED command received from client — log the speed value if present
                try {
                  val SpeedRegex = (".*\"speed\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?).*").r
                  val speedStr = message match {
                    case SpeedRegex(s) => s
                    case _ => "<unknown>"
                  }
                  println(s"Received SPEED command from ${conn.getRemoteSocketAddress}: speed=${speedStr} message=${message.take(200)}")
                } catch { case _: Throwable => () }
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

    val zoneEventBuffer: ListBuffer[Event] = ListBuffer.empty[Event]
    

  // Broadcaster builder that uses a supplied global queue for outbound events.
  def broadcasterIO(globalQueue: cats.effect.std.Queue[IO, Event]): IO[Unit] = fs2.Stream
      .repeatEval(IO.blocking(connectQueue.take()))
      .evalMap { dateStr =>
    IO.blocking(println(s"Received PLAN for date=$dateStr")).flatMap { _ =>
  val parts = dateStr.split("\\|")
        val reqDate = parts.lift(0).getOrElse("")
        val reqDays = parts.lift(1).flatMap(s => try Some(s.toInt) catch { case _: Throwable => None }).getOrElse(2)
        val prevDates = computePrevTradingDates(reqDate, reqDays)
        if (prevDates.isEmpty) IO.unit else {
                // compute start (inclusive) and end (exclusive) epoch millis covering the two previous trading days
                // End should be 9:30 AM Eastern on the trading day (market open), not the start of the trading day.
                val sorted = prevDates.toList.sorted
                val startDate = sorted.min
                // determine the requested trading day; fall back to params.tradingDate if parsing fails
                val tradingDay = try { LocalDate.parse(reqDate) } catch { case _: Throwable => params.tradingDate }
                val eastern = ZoneId.of("America/New_York")
                val startMs = startDate.atStartOfDay(zoneId).toInstant.toEpochMilli
                // end at 9:30 AM Eastern on tradingDay
                val endMs = tradingDay.atTime(9, 30).atZone(eastern).toInstant.toEpochMilli
                // Use streaming parquet range read to avoid materializing the entire slice
                val candlesStream = ParquetSource.readParquetAsCandlesInRangeStream(parquetPath, startMs, endMs, zoneId)
                println(s"Parquet streaming read for dates=${prevDates}")
                // Producer: offer events from the parquet stream into the global queue
                processCandlesStream(candlesStream, tradingDay).evalMap(e => globalQueue.offer(e)).compile.drain
        }
      }
    }.compile.drain

    // Start servers with a bracket so they are stopped when the stream is canceled (e.g., Ctrl+C).
    // Start the broadcaster as a background fiber and then keep the stream alive forever.
    // Create a persistent global outbound queue and consumer, then start broadcaster using it
    val globalQueueStream: Stream[IO, cats.effect.std.Queue[IO, Event]] =
      Stream.eval(cats.effect.std.Queue.unbounded[IO, Event]).flatMap { q =>
        val setOffer = IO.blocking(outboundOfferRef.set(Some((e: Event) => q.offer(e))))
        Stream.eval(setOffer) >>
        // start consumer fiber that forwards queued events to clients/buffer
        Stream.eval(Stream.repeatEval(q.take).evalMap { event =>
          val json = event.asJson.noSpaces
          IO {
            try {
              val readyClients = conns.asScala.filter(c => Option(readyMap.get(c)).contains(java.lang.Boolean.TRUE))
              if (readyClients.nonEmpty) {
                println(s"sending event to ${readyClients.size} clients: ${json.take(400)}")
                readyClients.foreach { c => try c.send(json) catch { case _: Throwable => () } }
              } else {
                println(s"buffering event: ${json.take(200)}")
                eventBuffer.add(json)
              }
            } catch { case e: Throwable => println("broadcast error: " + e.toString) }
          }
        }.compile.drain.start).evalMap(_ => IO.unit) >> Stream.emit(q)
      }

    fs2.Stream.bracket(acquire)(release).flatMap { _ =>
      globalQueueStream.flatMap { q =>
        Stream.eval(broadcasterIO(q).start).flatMap(_ => Stream.eval(IO.never))
      }
    }
    
  }
}

object CoreService extends IOApp.Simple {
  def run: IO[Unit] = {
    val params = InitParams(TradingMode.Simulation)
    new CoreService(params).start.compile.drain
  }
}
