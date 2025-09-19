package bmps.core.api.io

import cats.effect.{IO, Resource}
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.middleware.Logger
import org.http4s.Headers
import org.http4s.server.staticcontent._
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.headers.`Content-Type`
import org.http4s.websocket.WebSocketFrame
import fs2.Stream
import scala.concurrent.duration._
import cats.effect.std.Queue
import scodec.bits.ByteVector
import io.circe.syntax._
import io.circe.parser
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._

import com.comcast.ip4s.Port

import bmps.core.api.protocol._
import bmps.core.api.run.Broadcaster
import bmps.core.api.run.PhaseController
import bmps.core.models.{SystemStatePhase, SystemState}
import bmps.core.models.Event
import cats.effect.Ref

object Server {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  // Create an http app that serves static files from the repo `web` directory
  // and exposes a websocket at /ws. The websocket decodes ClientCommand JSON
  // and uses the provided controller and broadcaster to start phases and
  // stream events back to the client.
  def resource(stateRef: Ref[IO, SystemState], controller: PhaseController, broadcaster: Broadcaster, webRoot: String, port: Int): Resource[IO, org.http4s.server.Server] = {
    // Serve static files from the `webRoot` directory. Keep simple custom handler
    // but do not shadow the /ws websocket endpoint - wsRoutes must be tried first.
    val staticRoutes = HttpRoutes.of[IO] {
      case GET -> Root =>
        val path = Paths.get(webRoot).resolve("index.html").normalize()
        IO.blocking(Files.readAllBytes(path)).attempt.flatMap {
          case Right(bytes) => Ok(new String(bytes, StandardCharsets.UTF_8)).map(_.withContentType(headers.`Content-Type`(MediaType.text.html)))
          case Left(_) => NotFound("Not found")
        }
      // Serve any nested path under the web root (e.g. /src/main.js, /src/style.css)
      case req if req.method == Method.GET =>
        val rel = req.uri.path.renderString.stripPrefix("/")
        if (rel.isEmpty) NotFound("Not found")
        else {
          val file = Paths.get(webRoot).resolve(rel).normalize()
          IO.blocking(if (Files.exists(file) && !Files.isDirectory(file)) Some(Files.readAllBytes(file)) else None).flatMap {
            case Some(bytes) =>
              // Basic content-type hints for common static files
              val ct = if (rel.endsWith(".js")) `Content-Type`(MediaType.application.javascript)
                       else if (rel.endsWith(".css")) `Content-Type`(MediaType.text.css)
                       else if (rel.endsWith(".html")) `Content-Type`(MediaType.text.html)
                       else `Content-Type`(MediaType.text.plain)
              Ok(new String(bytes, StandardCharsets.UTF_8)).map(_.withContentType(ct))
            case None => NotFound("Not found")
          }
        }
    }

    def wsRoutes(wsb: WebSocketBuilder2[IO]) = HttpRoutes.of[IO] {
      // Use standard extractor; http4s will handle handshake checks.
      case req @ GET -> Root / "ws" =>
        // Minimal debug log: note the websocket route was hit.
        logger.debug(s"WebSocket route hit: uri=${req.uri.renderString}")

        // per-connection queue used to send frames to client
  val mkSocket: Resource[IO, Response[IO]] = Resource.make(
          for {
            q <- Queue.bounded[IO, WebSocketFrame](256)
            fibersRef <- Ref.of[IO, List[cats.effect.Fiber[IO, Throwable, Unit]]](List.empty)

            // receive pipe: handle incoming frames using circe JSON parsing
            val receive: fs2.Pipe[IO, WebSocketFrame, Unit] = { in: fs2.Stream[IO, WebSocketFrame] =>
              in.evalMap {
                case WebSocketFrame.Text(msg, _) =>
                  IO.fromEither(io.circe.parser.parse(msg).leftMap(err => new Exception(err))).flatMap { json =>
                    IO.fromEither(json.as[ClientCommand].leftMap(err => new Exception(err))).flatMap {
                      case ClientCommand.StartPhase(phaseStr, options) =>
                        val phaseOpt = phaseStr.toLowerCase match {
                          case "planning" => Some(SystemStatePhase.Planning)
                          case "preparing" => Some(SystemStatePhase.Preparing)
                          case "trading" => Some(SystemStatePhase.Trading)
                          case _ => None
                        }
                        phaseOpt.fold(IO.unit) { p =>
                          controller.startPhase(p, options).start.flatMap { _ =>
                            val sub = broadcaster.subscribe(p).map(ev => WebSocketFrame.Text(ServerMessage.PhaseEvent(p.toString, ev).asJson.noSpaces)).evalMap(q.offer)
                            sub.compile.drain.start.flatMap(sf => fibersRef.update(sf :: _))
                          }
                        }
                      case ClientCommand.Status =>
                        for {
                          st <- stateRef.get
                          _ <- q.offer(WebSocketFrame.Text(ServerMessage.Lifecycle(st.systemStatePhase.toString, "status").asJson.noSpaces))
                        } yield ()
                    }
                  }.handleErrorWith(_ => IO.unit)
                case _ => IO.unit
              }
            }

            // send stream dequeues frames
            val sendStream: fs2.Stream[IO, WebSocketFrame] = Stream.repeatEval(q.take)

            // Start a background heartbeat that periodically offers a small
            // lifecycle/heartbeat message so the connection isn't idle for long
            // periods. We push the heartbeat into the same queue so ordering is
            // preserved. The fiber is tracked in `fibersRef` so it will be
            // cancelled when the connection is released.
            val heartbeat: IO[Unit] = for {
              hb <- Stream.awakeEvery[IO](30.seconds).evalMap { _ =>
                // Use a protocol-level ping frame; clients/browsers will reply
                // with a pong automatically. Empty payload used here.
                q.offer(WebSocketFrame.Ping(ByteVector.empty))
              }.compile.drain.start
              _ <- fibersRef.update(hb :: _)
            } yield ()

            // Kick off the heartbeat but don't fail the connection if it cannot
            // be started for any reason. This keeps the acquire happy.
            _ <- heartbeat.handleErrorWith(_ => IO.unit)

            // Use the server-provided builder to perform the upgrade.
            resp <- wsb.build(sendStream, receive)
          } yield (q, fibersRef, resp)
        ) { resources =>
          // on connection release cancel subscription fibers; `resources`
          // is the tuple we created above (q, fibersRef, resp).
          val (_, fibersRef, _) = resources
          fibersRef.get.flatMap(_.traverse_(_.cancel)).handleErrorWith(_ => IO.unit)
        }.map { case (_, _, resp) => resp }

        // Use the response returned by the builder directly; avoid noisy logging
        // of handshake details here.
        mkSocket.use(resp => IO.pure(resp))
    }

    // Try websocket routes first, then fall back to static routes. We'll
    // construct the HttpApp inside withHttpWebSocketApp so we can use the
    // provided WebSocketBuilder2 (wsb) which is wired into Ember's upgrade
    // machinery.

    val portVal = Port.fromInt(port).getOrElse(Port.fromInt(8080).get)
    EmberServerBuilder.default[IO]
      .withPort(portVal)
      .withIdleTimeout(60.minutes)
      .withHttpWebSocketApp { wsb =>
        val routes = wsRoutes(wsb) <+> staticRoutes
        val baseApp = routes.orNotFound
        Logger.httpApp(false, false)(baseApp)
      }
      .build
  }

  def eventToFrame(phase: SystemStatePhase, ev: Event): WebSocketFrame.Text = {
    WebSocketFrame.Text(ServerMessage.PhaseEvent(phase.toString, ev).asJson.noSpaces)
  }

}


