package bmps.core.io

import cats.effect.IO
import java.sql.DriverManager
import java.time.Instant
import bmps.core.models._
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
          case null =>
            0L
          case t: java.sql.Timestamp =>
            t.toInstant.toEpochMilli
          case s: String =>
            Instant.parse(s).toEpochMilli
          case l: java.lang.Long =>
            l.longValue()
          case i: java.lang.Integer =>
            i.longValue()
          case d: java.lang.Double =>
            d.longValue()
          case bd: java.math.BigDecimal =>
            bd.longValue()
          case inst: java.time.Instant =>
            inst.toEpochMilli
          case ldt: java.time.LocalDateTime =>
            ldt.atZone(java.time.ZoneId.systemDefault()).toInstant.toEpochMilli
          case odt: java.time.OffsetDateTime =>
            odt.toInstant.toEpochMilli
          case other =>
            // Emit a debug line to help diagnose unexpected JDBC types coming from DuckDB
            println(s"ParquetSource: unexpected timestamp object class=${other.getClass.getName} value=${other.toString}")
            0L
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

  /**
   * Read candles within an inclusive start (ms) and exclusive end (ms) range.
   * Uses DuckDB SQL to filter rows inside the database so only matching rows are returned.
   */
  def readParquetAsCandlesInRange(path: String, startMs: Long, endMs: Long, zone: ZoneId): IO[List[Candle]] = IO.blocking {
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    try {
      val stmt = conn.createStatement()
      val safePath = path.replace("'", "''")
      // Format timestamps as local date-time strings for DuckDB TIMESTAMP literal
      val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
      val startStr = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDateTime.format(fmt)
      val endStr = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDateTime.format(fmt)
      val q = s"SELECT symbol, timestamp, timeframe, open, high, low, close, volume FROM read_parquet('" + safePath + "') WHERE timestamp >= TIMESTAMP '" + startStr + "' AND timestamp < TIMESTAMP '" + endStr + "'"
      val rs = stmt.executeQuery(q)
      val buf = scala.collection.mutable.ListBuffer.empty[Candle]
      while (rs.next()) {
        val tf = Option(rs.getString("timeframe")).getOrElse("1h")
        val tsObj = rs.getObject("timestamp")
        val epochMillis = tsObj match {
          case null => 0L
          case t: java.sql.Timestamp => t.toInstant.toEpochMilli
          case s: String => Instant.parse(s).toEpochMilli
          case l: java.lang.Long => l.longValue()
          case i: java.lang.Integer => i.longValue()
          case d: java.lang.Double => d.longValue()
          case bd: java.math.BigDecimal => bd.longValue()
          case inst: java.time.Instant => inst.toEpochMilli
          case ldt: java.time.LocalDateTime => ldt.atZone(ZoneId.systemDefault()).toInstant.toEpochMilli
          case odt: java.time.OffsetDateTime => odt.toInstant.toEpochMilli
          case other =>
            println(s"ParquetSource(range): unexpected timestamp object class=${other.getClass.getName} value=${other.toString}")
            0L
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

