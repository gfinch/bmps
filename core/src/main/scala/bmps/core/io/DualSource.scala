package bmps.core.io

import cats.effect.IO
import fs2.Stream
import bmps.core.models.{Candle, CandleDuration}

/**
 * A DataSource that intelligently chooses between ParquetSource and DatabentoSource.
 * 
 * If any data exists in the Parquet files for the requested time range, it uses ParquetSource.
 * Otherwise, it falls back to DatabentoSource.
 * 
 * This is useful for using cached Parquet data when available while automatically
 * fetching from Databento when needed.
 * 
 * @param durations The set of candle durations to support
 */
class DualSource(durations: Set[CandleDuration]) extends DataSource {
  
  def this(duration: CandleDuration) = this(Set(duration))
  
  private val parquetSource = new ParquetSource(durations)
  private val databentoSource = new DatabentoSource(durations)
  
  /**
   * Check if ParquetSource has any data for the given time range.
   * Does this by trying to read just one candle from the range.
   */
  private def hasParquetData(startMs: Long, endMs: Long): IO[Boolean] = {
    parquetSource
      .candlesInRangeStream(startMs, endMs)
      .take(1)
      .compile
      .toList
      .map(_.nonEmpty)
      .handleErrorWith { error =>
        println(s"[DualSource] Error checking Parquet data: ${error.getMessage}")
        IO.pure(false)
      }
  }
  
  override val currentContractSymbol: String = databentoSource.currentContractSymbol
  
  override def candlesInRangeStream(startMs: Long, endMs: Long): Stream[IO, Candle] = {
    Stream.eval(hasParquetData(startMs, endMs)).flatMap { hasData =>
      if (hasData) {
        println(s"[DualSource] Using ParquetSource for range $startMs to $endMs")
        parquetSource.candlesInRangeStream(startMs, endMs)
      } else {
        println(s"[DualSource] Using DatabentoSource for range $startMs to $endMs")
        databentoSource.candlesInRangeStream(startMs, endMs)
      }
    }
  }
}

