package bmps.core.io

import cats.effect.IO
import fs2.Stream
import java.sql.DriverManager
import bmps.core.models._
import bmps.core.models.CandleDuration.OneHour
import bmps.core.models.CandleDuration.OneMinute
import bmps.core.models.CandleDuration.OneSecond
import java.time.Instant

class ParquetSource(durations: Set[CandleDuration]) extends DataSource {

  def this(duration: CandleDuration) = this(Set(duration))

  private def getPath(duration: CandleDuration): String = duration match {
    case OneHour => "/Users/gf26229/personal/bmps/core/src/main/resources/databento/es-1h.parquet"
    case OneMinute => "/Users/gf26229/personal/bmps/core/src/main/resources/databento/es-1m.parquet"
    case OneSecond => "/Users/gf26229/personal/bmps/core/src/main/resources/databento/es-1s.parquet"
    case _ => throw new IllegalArgumentException(s"$duration not supported")
  }

  private val paths = durations.map(getPath).toList

  private def timeframeToDuration(tf: String): CandleDuration = tf match {
    case "1s"           => CandleDuration.OneSecond
    case "1m"           => CandleDuration.OneMinute
    case "1h" | "60m"   => CandleDuration.OneHour
    case _              => throw new IllegalArgumentException(s"$tf not supported.")
  }

  val currentContractSymbol = "ES" //ParquetSource will never be live

  /**
   * Read candles within an inclusive start (ms) and exclusive end (ms) range.
   * Uses DuckDB SQL to filter rows inside the database so only matching rows are returned.
   * 
   * Parquet files store timestamps as int64 UTC epoch milliseconds.
   * startMs and endMs are also UTC epoch milliseconds.
   */
  def candlesInRangeStream(startMs: Long, endMs: Long): Stream[IO, Candle] = {
    def buildQuery(startMs: Long, endMs: Long): IO[String] = IO.blocking {
      val safePaths = paths.map(p => s"'${p.replace("'", "''")}'").mkString("[", ", ", "]")
      // Timestamps are stored as int64 UTC millis, so we can compare directly
      val whereClause = s"timestamp >= $startMs AND timestamp <= $endMs"
      
      // Sort by endTime to match DatabentoSource behavior
      // timestamp is start time, so we add duration in ms
      // Tie-breaker: smaller duration first (e.g. 1s before 1m)
      val orderByClause = """
        (timestamp + CASE timeframe 
          WHEN '1s' THEN 1000 
          WHEN '1m' THEN 60000 
          WHEN '1h' THEN 3600000 
          ELSE 0 
        END),
        CASE timeframe
          WHEN '1s' THEN 1
          WHEN '1m' THEN 2
          WHEN '1h' THEN 3
          ELSE 4
        END
      """
      
      s"SELECT * FROM read_parquet($safePaths) WHERE $whereClause ORDER BY $orderByClause"
    }

    // Map a ResultSet row to Candle
    def candleFromRow(rs: java.sql.ResultSet): Candle = {
      // Parquet stores UTC epoch millis as int64
      val epochMillis = rs.getLong("timestamp")
      val timeframe = rs.getString("timeframe")
      val open = rs.getDouble("open").toFloat
      val high = rs.getDouble("high").toFloat
      val low = rs.getDouble("low").toFloat
      val close = rs.getDouble("close").toFloat
      val volume = rs.getLong("volume")
      val duration = timeframeToDuration(timeframe)
      val createdAt = Instant.now().toEpochMilli
      
      Candle(open, high, low, close, volume, epochMillis, duration, createdAt)
    }

    val qIO = buildQuery(startMs, endMs)

    Stream.bracket(qIO.flatMap { q =>
      IO.blocking {
        val conn = DriverManager.getConnection("jdbc:duckdb:")
        val stmt = conn.createStatement()
        val rs = stmt.executeQuery(q)
        (conn, stmt, rs)
      }
    })( { case (conn, stmt, rs) => 
      IO.blocking { 
        try rs.close() catch { case _: Throwable => () }
        try stmt.close() catch { case _: Throwable => () }
        try conn.close() catch { case _: Throwable => () }
      } 
    } )
    .flatMap { case (_, stmt, rs) =>
      def nextRow: IO[Option[Candle]] = IO.blocking {
        try {
          if (rs.next()) {
            try {
              val candle = candleFromRow(rs)
              Some(candle)
            } catch { 
              case e: Throwable => 
                println(s"[ParquetSource] ERROR parsing row: ${e.getMessage}")
                e.printStackTrace()
                None 
            }
          } else {
            None
          }
        } catch { 
          case e: Throwable => 
            println(s"[ParquetSource] ERROR reading next row: ${e.getMessage}")
            e.printStackTrace()
            None 
        }
      }

      Stream.unfoldEval(()) { _ => nextRow.map(_.map(c => (c, ()))) }
    }
  }
}

