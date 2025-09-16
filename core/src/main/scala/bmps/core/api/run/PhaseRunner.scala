package bmps.core.api.impl

import cats.effect.IO
import cats.effect.Ref
import fs2.Stream
import bmps.core.api.intf.{CandleSource, EventGenerator}
import bmps.core.models.{SystemState, Candle}
import bmps.core.Event

/**
 * Generic runner for a phase: it reads candles from a CandleSource, applies
 * a CandleProcessor to each candle (updating a local Ref[SystemState]) and
 * emits Events.
 */
class PhaseRunner(source: CandleSource, processor: EventGenerator) {
  /**
    * Run using an externally-provided stateRef so callers can observe and
    * persist the evolving SystemState. The stream emits events as usual and
    * updates the provided stateRef with each processed candle.
    */
  def run(stateRef: Ref[IO, SystemState], options: Option[Map[String, String]] = None): Stream[IO, Event] = {
    for {
      event <- source.candles.evalMap { candle =>
        stateRef.modify { state =>
          val (newState, events) = processor.process(state, candle)
          (newState, (candle, events))
        }
      }.flatMap { case (candle, events) =>
        val candleEvent = bmps.core.Event.fromCandle(candle)
        Stream.emit(candleEvent) ++ Stream.emits(events)
      }
    } yield event
  }
}


