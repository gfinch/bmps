
/* DISABLED api_old
package bmps.core.api.impl

import cats.effect.IO
import fs2.Stream
import java.time.ZoneId
import bmps.core.api.intf.CandleSource
import bmps.core.models.Candle
import bmps.core.io.ParquetSource

class ParquetCandleSource(parquetPath: String, startMs: Long = 0L, endMs: Long = Long.MaxValue, zoneId: ZoneId = ZoneId.systemDefault()) extends CandleSource {
  override def candles: Stream[IO, Candle] = ParquetSource.readParquetAsCandlesInRangeStream(parquetPath, startMs, endMs, zoneId)
}
*/


