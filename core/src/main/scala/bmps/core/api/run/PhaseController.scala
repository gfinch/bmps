package bmps.core.api.run

import cats.effect.{IO, Ref}
import cats.effect.std.Semaphore
import fs2.Stream
import cats.effect.kernel.Outcome
import bmps.core.models.{SystemState, SystemStatePhase}
import bmps.core.Event
import bmps.core.api.impl.PhaseRunner

class PhaseController(
    stateRef: Ref[IO, SystemState],
    broadcaster: Broadcaster,
    runners: Map[SystemStatePhase, PhaseRunner],
    semaphore: Semaphore[IO]
) {

  private def canStart(requested: SystemStatePhase, current: SystemState): Boolean = {
    (current.systemStatePhase, requested) match {
      case (SystemStatePhase.Planning, SystemStatePhase.Planning) => true
      case (SystemStatePhase.Planning, SystemStatePhase.Preparing) => true
      case (SystemStatePhase.Preparing, SystemStatePhase.Preparing) => true
      case (SystemStatePhase.Preparing, SystemStatePhase.Trading) => true
      case (SystemStatePhase.Trading, SystemStatePhase.Trading) => true
      case _ => false
    }
  }

  /** Start the requested phase. The runner stream will be run into a local
    * Ref and every emitted event is published to the broadcaster. On successful
    * completion the canonical stateRef is replaced with the final state.
    */
  def startPhase(phase: SystemStatePhase): IO[Unit] =
    semaphore.permit.use { _ =>
      for {
        current <- stateRef.get
        _ <- if (!canStart(phase, current)) IO.raiseError(new IllegalStateException(s"Cannot start $phase from ${current.systemStatePhase}")) else IO.unit
        runnerOpt = runners.get(phase)
        runner <- runnerOpt match {
          case Some(r) => IO.pure(r)
          case None => IO.raiseError(new IllegalStateException(s"No runner configured for $phase"))
        }
        // create a local stateRef to run the phase and capture the final state
        localRef <- Ref.of[IO, SystemState](current)

        // run the stream in a background fiber so this call returns immediately
        fiber <- runner.runInto(localRef).evalMap { e =>
          broadcaster.publish(phase, e)
        }.compile.drain.start

        outcome <- fiber.join
        _ <- outcome match {
          case Outcome.Succeeded(io) =>
            // On success, compute and replace canonical state with localRef value
            for {
              finalState <- localRef.get
              _ <- stateRef.set(finalState)
              _ <- broadcaster.publish(phase, bmps.core.Event.fromSwingPoint(bmps.core.models.SwingPoint(bmps.core.models.Level(0f), bmps.core.models.Direction.Doji, 0L)))
            } yield ()
          case Outcome.Errored(err) =>
            // leave canonical state untouched; broadcast an error lifecycle message
            broadcaster.publish(phase, bmps.core.Event.fromCandle(bmps.core.models.Candle(bmps.core.models.Level(0f), bmps.core.models.Level(0f), bmps.core.models.Level(0f), bmps.core.models.Level(0f), 0L, bmps.core.models.CandleDuration.OneMinute))) *> IO.raiseError(err)
          case Outcome.Canceled() =>
            IO.unit
        }
      } yield ()
    }
}

