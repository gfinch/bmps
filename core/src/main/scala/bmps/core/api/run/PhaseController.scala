package bmps.core.api.run

import cats.effect.{IO, Ref}
import cats.implicits._
import cats.effect.std.Semaphore
import cats.effect.kernel.Outcome
import bmps.core.models.{SystemState, SystemStatePhase, Candle}
import bmps.core.models.Event
import bmps.core.api.storage.EventStore
import scala.collection.immutable.Map

class PhaseController(
    stateRef: Ref[IO, SystemState],
    eventStore: EventStore,
    runners: Map[SystemStatePhase, PhaseRunner],
    semaphore: Semaphore[IO]
) {

  /** Start the requested phase. The runner will process candles and sink events
    * to the EventStore. On successful completion the canonical stateRef is 
    * replaced with the final state.
    * 
    * Note: No state validation is performed - any phase can be started at any time.
    * Each phase is keyed by trading date, so different days are independent.
    */
  def startPhase(phase: SystemStatePhase, options: Option[Map[String, String]] = None): IO[Unit] =
    semaphore.permit.use { _ =>
      for {
        current <- stateRef.get
        _ <- IO(println(s"[DEBUG] PhaseController.startPhase: Starting $phase (current state: ${current.systemStatePhase})"))
        
        // Extract trading date from options if available
        tradingDateOpt = options.flatMap(_.get("tradingDate")).flatMap { dateStr =>
          scala.util.Try(java.time.LocalDate.parse(dateStr)).toOption
        }
        
        // Check if this phase is already complete for this trading date
        alreadyComplete <- tradingDateOpt match {
          case Some(tradingDate) => 
            eventStore.isComplete(tradingDate, phase).flatMap { isComplete =>
              if (isComplete) {
                IO(println(s"[DEBUG] PhaseController.startPhase: $phase for $tradingDate is already complete, skipping")) *>
                IO.pure(true)
              } else {
                IO.pure(false)
              }
            }
          case None => IO.pure(false)
        }
        
        // If already complete, exit early
        _ <- if (alreadyComplete) IO.unit else IO.unit
        
        runnerOpt = runners.get(phase)
        runner <- if (alreadyComplete) {
          IO.pure(null) // Won't be used
        } else {
          runnerOpt match {
            case Some(r) => 
              IO(println(s"[DEBUG] PhaseController.startPhase: Found runner for $phase")) *>
              IO.pure(r)
            case None => 
              IO(println(s"[ERROR] PhaseController.startPhase: No runner configured for $phase")) *>
              IO.raiseError(new IllegalStateException(s"No runner configured for $phase"))
          }
        }
        
        // Only proceed if not already complete
        _ <- if (alreadyComplete) {
          IO.unit
        } else {
          for {
            // For Planning phase, create a fresh SystemState. For other phases, use current state.
            initialState <- phase match {
              case SystemStatePhase.Planning => 
                IO(println(s"[DEBUG] PhaseController.startPhase: Creating fresh SystemState for Planning phase")) *>
                IO.pure(SystemState())
              case _ => 
                IO.pure(current)
            }
            // create a local stateRef to run the phase and capture the final state
            localRef <- Ref.of[IO, SystemState](initialState)

            // initialize the local state for this phase before starting the runner
            _ <- IO(println(s"[DEBUG] PhaseController.startPhase: Initializing runner for $phase"))
            _ <- runner.initialize(localRef, options)
            
            // Log some key state after initialization
            initState <- localRef.get
            _ <- IO(println(s"[DEBUG] PhaseController.startPhase: $phase initialized - tradingDay: ${initState.tradingDay}, systemStatePhase: ${initState.systemStatePhase}"))

            // Run the phase - this will process all candles and sink events to EventStore
            _ <- IO(println(s"[DEBUG] PhaseController.startPhase: Starting runner for $phase"))
            
            fiber <- runner.run(localRef, eventStore, options).start

            _ <- IO(println(s"[DEBUG] PhaseController.startPhase: Waiting for $phase completion"))
            outcome <- fiber.join
            _ <- outcome match {
              case Outcome.Succeeded(io) =>
                IO(println(s"[DEBUG] PhaseController.startPhase: $phase completed successfully")) *>
                (for {
                  finalState <- localRef.get
                  _ <- stateRef.set(finalState)
                  _ <- IO(println(s"[DEBUG] PhaseController.startPhase: Updated canonical state for $phase"))
                } yield ())
              case Outcome.Errored(err) =>
                IO(println(s"[ERROR] PhaseController.startPhase: $phase errored: ${err.getMessage}")) *>
                IO(err.printStackTrace())
              case Outcome.Canceled() =>
                IO(println(s"[DEBUG] PhaseController.startPhase: $phase was canceled"))
            }
          } yield ()
        }
      } yield ()
    }
}

