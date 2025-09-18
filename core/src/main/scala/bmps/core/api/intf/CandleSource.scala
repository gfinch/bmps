package bmps.core.api.intf

import cats.effect.IO
import fs2.Stream
import bmps.core.models.{Candle, SystemState}

trait CandleSource {
  def candles(state: SystemState): Stream[IO, Candle]
}
