package bmps.core.api.intf

import cats.effect.IO
import fs2.Stream
import bmps.core.models.Candle

trait CandleSource {
  def candles: Stream[IO, Candle]
}
