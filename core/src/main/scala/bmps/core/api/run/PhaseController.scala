package bmps.core.api.run

import cats.effect.{IO, Ref}
import cats.implicits._
import cats.effect.std.Semaphore
import fs2.Stream
import cats.effect.kernel.Outcome
import bmps.core.models.{SystemState, SystemStatePhase}
import bmps.core.models.Event
import bmps.core.api.impl.PhaseRunner
import scala.collection.immutable.Map

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
  def startPhase(phase: SystemStatePhase, options: Option[Map[String, String]] = None): IO[Unit] =
    semaphore.permit.use { _ =>
      for {
        current <- stateRef.get
        _ <- IO(println(s"[DEBUG] PhaseController.startPhase: Attempting to start $phase from ${current.systemStatePhase}"))
        _ <- if (!canStart(phase, current)) {
          IO(println(s"[ERROR] PhaseController.startPhase: Cannot start $phase from ${current.systemStatePhase}")) *>
          IO.raiseError(new IllegalStateException(s"Cannot start $phase from ${current.systemStatePhase}"))
        } else {
          IO(println(s"[DEBUG] PhaseController.startPhase: Transition $phase from ${current.systemStatePhase} is allowed"))
        }
        runnerOpt = runners.get(phase)
        runner <- runnerOpt match {
          case Some(r) => 
            IO(println(s"[DEBUG] PhaseController.startPhase: Found runner for $phase")) *>
            IO.pure(r)
          case None => 
            IO(println(s"[ERROR] PhaseController.startPhase: No runner configured for $phase")) *>
            IO.raiseError(new IllegalStateException(s"No runner configured for $phase"))
        }
        // create a local stateRef to run the phase and capture the final state
        localRef <- Ref.of[IO, SystemState](current)

        // initialize the local state for this phase before starting the runner
        _ <- IO(println(s"[DEBUG] PhaseController.startPhase: Initializing runner for $phase"))
        _ <- runner.initialize(localRef, options)
        
        // Log some key state after initialization
        initState <- localRef.get
        _ <- IO(println(s"[DEBUG] PhaseController.startPhase: $phase initialized - tradingDay: ${initState.tradingDay}, planningDays: ${initState.planningDays}, systemStatePhase: ${initState.systemStatePhase}"))

        // run the stream in a background fiber so this call returns immediately
        _ <- IO(println(s"[DEBUG] PhaseController.startPhase: Starting runner stream for $phase"))
        
        // Event counting
        eventCountRef <- Ref.of[IO, Map[String, Int]](Map.empty)
        
        fiber <- runner.run(localRef, options).evalMap { e =>
          eventCountRef.update { counts =>
            val eventType = e.eventType.toString
            counts.updated(eventType, counts.getOrElse(eventType, 0) + 1)
          } *> broadcaster.publish(phase, e)
        }.compile.drain.start

        _ <- IO(println(s"[DEBUG] PhaseController.startPhase: Waiting for $phase completion"))
        outcome <- fiber.join
        _ <- outcome match {
          case Outcome.Succeeded(io) =>
            eventCountRef.get.flatMap { counts =>
              IO(println(s"[DEBUG] PhaseController.startPhase: $phase completed successfully")) *>
              IO(println(s"[DEBUG] PhaseController.startPhase: Event counts for $phase: $counts")) *>
              // On success, run the runner.finalize to allow the processor to
              // emit any final events (zones, liquidity, etc.), publish those
              // events, then compute and replace canonical state with the
              // final initializedRef value and publish the final phase event.
              (for {
                finalizeEvents <- runner.finalize(localRef)
                _ <- IO(println(s"[DEBUG] PhaseController.startPhase: Publishing ${finalizeEvents.length} finalize events for $phase"))
                _ <- finalizeEvents.traverse_(e => broadcaster.publish(phase, e))
                finalState <- localRef.get
                _ <- stateRef.set(finalState)
                _ <- IO(println(s"[DEBUG] PhaseController.startPhase: Publishing final event for $phase"))
                _ <- broadcaster.publish(phase, buildFinalEvent(finalState))
              } yield ())
            }
          case Outcome.Errored(err) =>
            // On error, don't run finalize; just publish an errored final event
            eventCountRef.get.flatMap { counts =>
              IO(println(s"[ERROR] PhaseController.startPhase: $phase errored: ${err.getMessage}")) *>
              IO(println(s"[DEBUG] PhaseController.startPhase: Event counts before error for $phase: $counts")) *>
              IO(err.printStackTrace()) *>
              (for {
                finalState <- localRef.get
                _ <- broadcaster.publish(phase, buildFinalEvent(finalState, isError = true))
              } yield())
            }
          case Outcome.Canceled() =>
            eventCountRef.get.flatMap { counts =>
              IO(println(s"[DEBUG] PhaseController.startPhase: $phase was canceled")) *>
              IO(println(s"[DEBUG] PhaseController.startPhase: Event counts before cancellation for $phase: $counts"))
            }
        }
      } yield ()
    }

    private def buildFinalEvent(finalState: SystemState, isError: Boolean = false): Event = {
        finalState.systemStatePhase match {
            case SystemStatePhase.Planning if !isError => Event.phaseComplete(finalState.planningCandles.last.timestamp)
            case SystemStatePhase.Planning if isError => Event.phaseErrored(finalState.planningCandles.last.timestamp)
            case _ if !isError => Event.phaseComplete(finalState.tradingCandles.last.timestamp)
            case _ if isError => Event.phaseErrored(finalState.tradingCandles.last.timestamp)
        }
    }
}

