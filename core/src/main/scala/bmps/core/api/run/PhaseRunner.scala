package bmps.core.api.run

import cats.effect.IO
import cats.effect.Ref
import fs2.Stream
import bmps.core.api.intf.{CandleSource, EventGenerator}
import bmps.core.api.storage.EventStore
import bmps.core.models.{SystemState, Candle, Event}

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
      _ <- source.candles(state).evalMap { candle =>
        stateRef.modify { state =>
          val (newState, events) = processor.process(state, candle)
          (newState, (candle, events))
        }.flatMap { case (candle, events) =>
          val candleEvent = Event.fromCandle(candle)
          val allEvents = candleEvent :: events
          eventStore.addEvents(tradingDate, phase, allEvents)
        }
      }.compile.drain
      
      // After all candles processed, run finalize and store those events too
      finalEvents <- finalize(stateRef)
      _ <- eventStore.addEvents(tradingDate, phase, finalEvents)
      
      // Mark phase as complete
      _ <- eventStore.markComplete(tradingDate, phase)
    } yield ())
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


