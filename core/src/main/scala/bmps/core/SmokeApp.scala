package bmps.core

import cats.effect.{IO, IOApp}
import java.time.LocalDate

object SmokeApp extends IOApp.Simple {
  val run: IO[Unit] = AppLauncher.createResource(LocalDate.now()).use { case (stateRef, broadcaster, controller, server) =>
    IO.println("Server started for smoke test on port 8080") *> IO.never
  }
}


