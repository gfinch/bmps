
/* DISABLED api_old
package bmps.core.api.run

import java.time.ZoneId
import cats.effect.IO
import bmps.core.api.impl.ParquetCandleSource
import bmps.core.api.impl.PlanningProcessor
import bmps.core.api.impl.PreparingProcessor
import bmps.core.api.intf.CandleSource
import bmps.core.api.intf.CandleProcessor

object PhaseRunners {
  def parquetPlanning(parquetPath: String, zoneId: ZoneId = ZoneId.systemDefault()): (CandleSource, CandleProcessor) = {
    (new ParquetCandleSource(parquetPath, 0L, Long.MaxValue, zoneId), new PlanningProcessor())
  }

  def parquetPreparing(parquetPath: String, startMs: Long = 0L, endMs: Long = Long.MaxValue, zoneId: ZoneId = ZoneId.systemDefault()): (CandleSource, CandleProcessor) = {
    (new ParquetCandleSource(parquetPath, startMs, endMs, zoneId), new PreparingProcessor())
  }
}

*/


