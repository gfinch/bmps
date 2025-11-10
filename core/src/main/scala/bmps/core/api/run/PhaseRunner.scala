package bmps.core.api.run

import cats.effect.IO
import cats.effect.Ref
import fs2.Stream
import bmps.core.api.intf.{CandleSource, EventGenerator}
import bmps.core.api.storage.EventStore
import bmps.core.models.{SystemState, Candle, Event, CandleDuration}
import java.time.LocalDate
import bmps.core.models.SystemStatePhase

/**
 * Generic runner for a phase: it reads candles from a CandleSource, applies
 * an EventGenerator to each candle (updating a local Ref[SystemState]) and
 * sinks Events to an EventStore.
 */
class PhaseRunner(source: CandleSource, processor: EventGenerator) {
  
  def initialize(stateRef: Ref[IO, SystemState], options: Option[Map[String, String]]): IO[Unit] = {
    stateRef.update { state =>
      processor.initialize(state, options.getOrElse(Map.empty))
    }
  }
  
  /**
   * Run the phase: process candles and sink events to the EventStore.
   * Returns IO[Unit] - events are stored in the EventStore, not streamed.
   */
  def run(stateRef: Ref[IO, SystemState], eventStore: EventStore, options: Option[Map[String, String]] = None): IO[Unit] = {
    (for {
      state <- stateRef.get
      tradingDate = state.tradingDay
      phase = state.systemStatePhase
      
      // Process all candles, storing events as we go
      _ <- processCandles(stateRef, eventStore, tradingDate, phase)
      
      // After all candles processed, run finalize and store those events too
      finalEvents <- finalize(stateRef)
      _ <- eventStore.addEvents(tradingDate, phase, finalEvents)
      
      // Mark phase as complete
      _ <- eventStore.markComplete(tradingDate, phase)
    } yield ())
  }

  /**
   * Process candles with automatic retry on stream errors.
   */
  private def processCandles(
    stateRef: Ref[IO, SystemState], 
    eventStore: EventStore, 
    tradingDate: LocalDate, 
    phase: SystemStatePhase
  ): IO[Unit] = {
    stateRef.get.flatMap { state =>
      source.candles(state).evalMap { candle =>
        stateRef.modify { state =>
          val (newState, events) = processor.process(state, candle)
          (newState, (candle, events))
        }.flatMap { case (candle, events) =>
          val eventsToAdd = if (candle.duration != CandleDuration.OneSecond) {
            val candleEvent = Event.fromCandle(candle)
            candleEvent :: events
          } else events
          eventStore.addEvents(tradingDate, phase, eventsToAdd)
        }
      }.compile.drain
        .handleErrorWith { error =>
          IO.println(s"[PhaseRunner] Stream error for phase $phase: ${error.getMessage}") >>
          IO.println(s"[PhaseRunner] Error class: ${error.getClass.getName}") >>
          IO(error.printStackTrace()) >>
          IO.println(s"[PhaseRunner] Restarting stream...") >>
          IO.sleep(scala.concurrent.duration.DurationInt(2).seconds) >>
          processCandles(stateRef, eventStore, tradingDate, phase) // Recursive retry
        }
    }
  }

  def finalize(stateRef: Ref[IO, SystemState]): IO[List[Event]] = {
    // Apply the processor.finalize to the current state, update it and
    // return the list of events that were produced during finalization.
    stateRef.modify { state =>
      val (newState, events) = processor.finalize(state)
      (newState, events)
    }
  }
}


