package bmps.core.io

import cats.effect.IO
import fs2.Stream
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
  /**
   * Read candles within an inclusive start (ms) and exclusive end (ms) range.
   * Uses DuckDB SQL to filter rows inside the database so only matching rows are returned.
   */
  // Streaming version of range-read: emits matching candles lazily.
  // This is the single public API exposed by ParquetSource now.
  def readParquetAsCandlesInRangeStream(path: String, startMs: Long, endMs: Long, zone: ZoneId): Stream[IO, Candle] = {
    // Build query (inspects parquet timestamp column type where possible) in a blocking IO
    def buildQuery(path: String, startMs: Long, endMs: Long, zone: ZoneId): IO[String] = IO.blocking {
      val safePath = path.replace("'", "''")
      val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
      val startStr = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDateTime.format(fmt)
      val endStr = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDateTime.format(fmt)
      // Open a connection to inspect the timestamp column type
      val conn = DriverManager.getConnection("jdbc:duckdb:")
      try {
        val stmt = conn.createStatement()
        val typeRs = stmt.executeQuery(s"SELECT typeof(timestamp) as t FROM read_parquet('" + safePath + "') LIMIT 1")
        val ttype = if (typeRs.next()) Option(typeRs.getString("t")).getOrElse("") else ""
        typeRs.close()
        stmt.close()
        conn.close()
        val whereClause = if (ttype.toUpperCase.contains("BIGINT") || ttype.toUpperCase.contains("INT") || ttype.toUpperCase.contains("INT64") || ttype.toUpperCase.contains("LONG") || ttype.toUpperCase.contains("DECIMAL") || ttype.toUpperCase.contains("DOUBLE")) {
          s"timestamp >= ${startMs} AND timestamp <= ${endMs}"
        } else if (ttype.toUpperCase.contains("TIMESTAMP WITH TIME ZONE")) {
          // For timezone-aware timestamps, use epoch-based filtering to avoid timezone confusion
          s"(EXTRACT(epoch FROM timestamp) * 1000) >= ${startMs} AND (EXTRACT(epoch FROM timestamp) * 1000) <= ${endMs}"
        } else {
          s"timestamp >= TIMESTAMP '${startStr}' AND timestamp <= TIMESTAMP '${endStr}'"
        }
        s"SELECT * FROM read_parquet('" + safePath + "') WHERE " + whereClause
      } catch { case _: Throwable =>
        try { conn.close() } catch { case _: Throwable => () }
        // fallback to timestamp literal
        s"SELECT * FROM read_parquet('" + safePath + "') WHERE timestamp >= TIMESTAMP '" + startStr + "' AND timestamp <= TIMESTAMP '" + endStr + "'"
      }
    }

    // Map a ResultSet row to Candle (keeps same tolerant conversions as before)
    def candleFromRow(rs: java.sql.ResultSet, md: java.sql.ResultSetMetaData): Candle = {
      val colCount = md.getColumnCount
      def idx(name: String): Int = (1 to colCount).find(i => md.getColumnLabel(i).equalsIgnoreCase(name)).getOrElse(-1)
      val idxTimestamp = idx("timestamp")
      val idxTimeframe = idx("timeframe")
      val idxOpen = idx("open")
      val idxHigh = idx("high")
      val idxLow = idx("low")
      val idxClose = idx("close")

      val tf = if (idxTimeframe > 0) Option(rs.getString(idxTimeframe)).getOrElse("1h") else "1h"
      val tsObj = if (idxTimestamp > 0) rs.getObject(idxTimestamp) else null
      val epochMillis = tsObj match {
        case null => 0L
        case t: java.sql.Timestamp => t.toInstant.toEpochMilli
        case odt: java.time.OffsetDateTime => 
          // DuckDB returns UTC timestamps, but we want the local Eastern time as epoch millis
          // Convert UTC to Eastern timezone and extract the local date/time components
          import java.time.{ZoneId, LocalDateTime}
          val easternZone = ZoneId.of("America/New_York")
          val utcInstant = odt.toInstant
          val easternZoned = utcInstant.atZone(easternZone)
          val easternLocal = easternZoned.toLocalDateTime
          // Convert the Eastern local time back to epoch millis as if it were UTC
          // This gives us the "wall clock time" in milliseconds
          easternLocal.atZone(ZoneId.of("UTC")).toInstant.toEpochMilli
        case other =>
          // The parquet data contains TIMESTAMP WITH TIME ZONE which DuckDB returns as OffsetDateTime
          // If we get anything else, it's likely a data format issue
          throw new IllegalArgumentException(s"Unexpected timestamp format: ${other.getClass.getName} with value: $other")
      }
      val open = if (idxOpen > 0) rs.getDouble(idxOpen).toFloat else 0.0f
      val high = if (idxHigh > 0) rs.getDouble(idxHigh).toFloat else 0.0f
      val low = if (idxLow > 0) rs.getDouble(idxLow).toFloat else 0.0f
      val close = if (idxClose > 0) rs.getDouble(idxClose).toFloat else 0.0f
      val duration = timeframeToDuration(tf)
      Candle(Level(open), Level(high), Level(low), Level(close), epochMillis, duration)
    }

    val qIO = buildQuery(path, startMs, endMs, zone)

    Stream.bracket(qIO.flatMap { q =>
      IO.blocking {
        val conn = DriverManager.getConnection("jdbc:duckdb:")
        val stmt = conn.createStatement()
        try {
          val rs = stmt.executeQuery(q)
          (conn, stmt, rs)
        } catch {
          case sqe: java.sql.SQLException if Option(sqe.getMessage).exists(_.toLowerCase.contains("timestamp")) =>
            // DuckDB failed due to timestamp conversion on some parquet rows. Try a safer epoch-based predicate.
            try {
              // close the previous statement and use a fresh one for retry
              try { stmt.close() } catch { case _: Throwable => () }
              val stmt2 = conn.createStatement()
              val epochQ = q.replaceAll("WHERE (?s).*", s"WHERE (EXTRACT(epoch FROM timestamp) * 1000) >= ${startMs} AND (EXTRACT(epoch FROM timestamp) * 1000) <= ${endMs}")
              val rs2 = stmt2.executeQuery(epochQ)
              (conn, stmt2, rs2)
            } catch {
              case _: Throwable =>
                // Last resort: fall back to reading the whole parquet and handle bad rows while iterating
                try {
                  try { /* ensure any prior stmt closed */ } catch { case _: Throwable => () }
                  val stmt3 = conn.createStatement()
                  val rs3 = stmt3.executeQuery(s"SELECT * FROM read_parquet('${path.replace("'","''")}')")
                  (conn, stmt3, rs3)
                } catch { case e: Throwable => try { conn.close() } catch { case _: Throwable => () }; throw e }
            }
          case other: Throwable => try { conn.close() } catch { case _: Throwable => () }; throw other
        }
      }
    })( { case (conn, stmt, rs) => IO.blocking { try rs.close() catch { case _: Throwable => () }; try stmt.close() catch { case _: Throwable => () }; try conn.close() catch { case _: Throwable => () } } } )
    .flatMap { case (_, stmt, rs) =>
        val md = rs.getMetaData()
        def nextRow: IO[Option[Candle]] = IO.blocking {
        try {
          if (rs.next()) {
            try Some(candleFromRow(rs, md)) catch { case _: Throwable => None }
          } else None
        } catch { case _: Throwable => None }
      }

      Stream.unfoldEval(()) { _ => nextRow.map(_.map(c => (c, ()))) }
    }
  }
}

