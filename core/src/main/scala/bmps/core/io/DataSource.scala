package bmps.core.io

import cats.effect.IO
import fs2.Stream
import bmps.core.models.Candle
import java.time.ZoneId

/**
 * Common trait for all data sources that provide streaming candle data.
 * This allows the system to be agnostic about the data source (parquet, API, websocket, etc.).
 */
trait DataSource {
  /**
   * Stream candles within an inclusive start (ms) and exclusive end (ms) range.
   * @param startMs Start time in epoch milliseconds (inclusive)
   * @param endMs End time in epoch milliseconds (exclusive)
   * @param zone Time zone to use for timestamp filtering
   * @return Stream of candles in the specified range
   */
  def candlesInRangeStream(startMs: Long, endMs: Long, zone: ZoneId): Stream[IO, Candle]
}