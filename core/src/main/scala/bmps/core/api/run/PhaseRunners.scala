package bmps.core.api.run

import cats.effect.IO
import fs2.Stream
import bmps.core.api.intf.{CandleSource, EventGenerator}
import bmps.core.models.{Candle, Level, CandleDuration, SystemState}
import bmps.core.Event
import bmps.core.api.impl.PhaseRunner

object PhaseRunners {
  // Simple CandleSource that emits a fixed small sequence of candles
  private def simpleSource(count: Int = 5): CandleSource = new CandleSource {
    override def candles: Stream[IO, Candle] = {
      val now = System.currentTimeMillis()
      val candles = (0 until count).toList.map { i =>
        Candle(Level(100f + i), Level(101f + i), Level(99f + i), Level(100f + i), now + i * 60000L, CandleDuration.OneMinute)
      }
      Stream.emits(candles).covary[IO]
    }
  }

  // EventGenerator stub that does not change state and emits no events
  private val noopGenerator: EventGenerator = new EventGenerator {
    override def process(state: SystemState, candle: Candle): (SystemState, List[Event]) = (state, List.empty)
  }

  def stubPlanner(): PhaseRunner = new PhaseRunner(simpleSource(5), noopGenerator)
  def stubPreparer(): PhaseRunner = new PhaseRunner(simpleSource(3), noopGenerator)
  def stubTrader(): PhaseRunner = new PhaseRunner(simpleSource(10), noopGenerator)
}


