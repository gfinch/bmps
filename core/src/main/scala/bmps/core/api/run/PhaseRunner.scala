package bmps.core.api.impl

import cats.effect.IO
import cats.effect.Ref
import fs2.Stream
import bmps.core.api.intf.{CandleSource, EventGenerator}
import bmps.core.models.{SystemState, Candle}
import bmps.core.models.Event

/**
 * Generic runner for a phase: it reads candles from a CandleSource, applies
 * a CandleProcessor to each candle (updating a local Ref[SystemState]) and
 * emits Events.
 */
class PhaseRunner(source: CandleSource, processor: EventGenerator) {
  
  def initialize(stateRef: Ref[IO, SystemState], options: Option[Map[String, String]]): IO[Unit] = {
    stateRef.update { state =>
      processor.initialize(state, options.getOrElse(Map.empty))
    }
  }
  
  def run(stateRef: Ref[IO, SystemState], options: Option[Map[String, String]] = None): Stream[IO, Event] = {
    Stream.eval(stateRef.get).flatMap { state =>
      source.candles(state).evalMap { candle =>
        stateRef.modify { state =>
          val (newState, events) = processor.process(state, candle)
          (newState, (candle, events))
        }
      }.flatMap { case (candle, events) =>
        val candleEvent = Event.fromCandle(candle)
        Stream.emit(candleEvent) ++ Stream.emits(events)
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


