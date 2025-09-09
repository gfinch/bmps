package bmps.core.io

import cats.effect.IO
import java.sql.DriverManager
import java.time.Instant
import bmps.core.models._

/**
 * ParquetSource encapsulates reading candles from a Parquet file using DuckDB JDBC.
 * This keeps CoreService independent of how data is sourced (parquet, websocket, etc.).
 */
object ParquetSource {

  private def timeframeToDuration(tf: String): CandleDuration = tf match {
    case "1m" | "1min"  => CandleDuration.OneMinute
    case "2m"            => CandleDuration.TwoMinute
    case "5m"            => CandleDuration.FiveMinute
    case "15m"           => CandleDuration.FifteenMinute
    case "30m"           => CandleDuration.ThirtyMinute
    case "1h" | "60m"   => CandleDuration.OneHour
    case "1d" | "1day"  => CandleDuration.OneDay
    case _                 => CandleDuration.OneHour
  }

  def readParquetAsCandles(path: String): IO[List[Candle]] = IO.blocking {
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    try {
      val stmt = conn.createStatement()
      val safePath = path.replace("'", "''")
      val q = s"SELECT symbol, timestamp, timeframe, open, high, low, close, volume FROM read_parquet('" + safePath + "')"
      val rs = stmt.executeQuery(q)
      val buf = scala.collection.mutable.ListBuffer.empty[Candle]
      while (rs.next()) {
        val tf = Option(rs.getString("timeframe")).getOrElse("1h")
        val tsObj = rs.getObject("timestamp")
        val epochMillis = tsObj match {
          case t: java.sql.Timestamp => t.toInstant.toEpochMilli
          case s: String => Instant.parse(s).toEpochMilli
          case l: java.lang.Long => l.longValue()
          case _ => 0L
        }
        val open = rs.getDouble("open").toFloat
        val high = rs.getDouble("high").toFloat
        val low = rs.getDouble("low").toFloat
        val close = rs.getDouble("close").toFloat
        val duration = timeframeToDuration(tf)
        buf += Candle(Level(open), Level(high), Level(low), Level(close), epochMillis, duration)
      }
      rs.close()
      stmt.close()
      buf.toList
    } finally {
      conn.close()
    }
  }
}

