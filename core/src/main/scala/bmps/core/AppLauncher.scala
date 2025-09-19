package bmps.core

import cats.effect.{IO, Resource}
import cats.effect.Ref
import cats.effect.std.Semaphore
import bmps.core.models.SystemState
import bmps.core.models.SystemStatePhase
import bmps.core.api.run.Broadcaster
import bmps.core.api.run.PhaseController
import bmps.core.api.impl.PhaseRunner
import bmps.core.api.io.Server
import bmps.core.phases.PlanningPhaseBuilder
import bmps.core.phases.PreparingPhaseBuilder
import bmps.core.phases.TradingPhaseBuilder
import cats.effect.{IO, IOApp}
import java.time.LocalDate

object AppLauncher extends IOApp.Simple {
  /** Create the shared pieces and return resources that include a running
    * HTTP/WebSocket server that serves `webRoot` on the given port. The
    * runners map is left empty; populate when wiring real CandleSource/
    * EventGenerator implementations.
    */
  def createResource(webRoot: String = "web-react/dist", port: Int = 8080): Resource[IO, (Ref[IO, SystemState], Broadcaster, PhaseController, org.http4s.server.Server)] = for {
    stateRef <- Resource.eval(Ref.of[IO, SystemState](SystemState()))
    broadcaster <- Resource.eval(Broadcaster.create(Some(10000)))
    sem <- Resource.eval(Semaphore[IO](1))
    // populate with stub PhaseRunner instances so the server can be smoke-tested
    runners: Map[SystemStatePhase, PhaseRunner] = Map(
      SystemStatePhase.Planning -> PlanningPhaseBuilder.build(),
      SystemStatePhase.Preparing -> PreparingPhaseBuilder.build(),
      SystemStatePhase.Trading -> TradingPhaseBuilder.build()
    )
    controller = new PhaseController(stateRef, broadcaster, runners, sem)
    server <- Server.resource(stateRef, controller, broadcaster, webRoot, port)
  } yield (stateRef, broadcaster, controller, server)

  val run: IO[Unit] = createResource().use { case (stateRef, broadcaster, controller, server) =>
    IO.println("Server started on port 8080") *> IO.never
  }

}

