package bmps.core.io

import cats.effect.IO
import fs2.Stream
import bmps.core.models.Candle
import java.time.ZoneId

trait DataSource {
  /**
   * Stream candles within an inclusive start (ms) and exclusive end (ms) range.
   * @param startMs Start time in epoch milliseconds (inclusive)
   * @param endMs End time in epoch milliseconds (exclusive)
   * @return Stream of candles in the specified range
   */
  def candlesInRangeStream(startMs: Long, endMs: Long): Stream[IO, Candle]
}