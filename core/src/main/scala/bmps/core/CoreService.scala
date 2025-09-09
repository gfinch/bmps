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

  // Start a simple embedded WebSocket server
  val server = new WebSocketServer(new InetSocketAddress(webPort)) {
      override def onOpen(conn: JWebSocket, handshake: ClientHandshake): Unit = {
        println(s"WS client connected: ${conn.getRemoteSocketAddress}")
      }

      override def onClose(conn: JWebSocket, code: Int, reason: String, remote: Boolean): Unit = {
        println(s"WS client disconnected: ${conn.getRemoteSocketAddress} code=$code reason=$reason")
      }

      override def onMessage(conn: JWebSocket, message: String): Unit = {
        // No incoming messages expected; ignore or could be used for control messages
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

    val startServers = fs2.Stream.eval(IO {
      server.start()
      httpServer.start()
      println(s"Embedded WS server started on port $webPort")
      println(s"Embedded HTTP server started on http://localhost:${httpPort}")
      ()
    })

    // Start servers then continue with broadcasting events
    startServers.flatMap(_ =>

      // Broadcast each event to all connected WS clients
      events.evalMap { event =>
        val json = event.asJson.noSpaces
        IO {
          // send to all connected clients
          server.getConnections.asScala.foreach { c =>
            try c.send(json) catch { case _: Throwable => () }
          }
        }
      }
    ) ++ Stream.eval(IO.never)
    
  }
}

object CoreService extends IOApp.Simple {
  def run: IO[Unit] = {
    val params = InitParams(TradingMode.Simulation)
    new CoreService(params).start.compile.drain
  }
}
