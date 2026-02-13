package bmps.console

import cats.effect.{IO, IOApp, ExitCode}
import bmps.core.io.{ParquetSource, DatabentoSource}
import bmps.core.models.{Candle, CandleDuration}
import bmps.core.utils.TimestampUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.Instant

/**
 * Console app to compare candle data from ParquetSource vs DatabentoSource.
 * 
 * This collects all candles from both sources for a given time range and compares them
 * to identify discrepancies in ordering, values, or missing candles.
 * 
 * Usage: sbt "console/runMain bmps.console.SourceComparator"
 */
object SourceComparator extends IOApp {

  private val formatter = DateTimeFormatter.ISO_LOCAL_DATE
  
  // Comparison results
  case class CandleKey(timestamp: Long, duration: CandleDuration)
  case class CandleDiff(
    key: CandleKey,
    parquetCandle: Option[Candle],
    databentoCandle: Option[Candle],
    diffType: String,
    details: String
  )

  override def run(args: List[String]): IO[ExitCode] = {
    // Default date range: Jan 1, 2025 to Jan 15, 2025
    val startDate = LocalDate.of(2025, 1, 1)
    val endDate = LocalDate.of(2025, 1, 15)
    
    val startMs = TimestampUtils.midnight(startDate)
    val endMs = TimestampUtils.midnight(endDate.plusDays(1)) // Exclusive end
    
    IO.println("=" * 80) *>
    IO.println("BMPS Source Comparator: ParquetSource vs DatabentoSource") *>
    IO.println("=" * 80) *>
    IO.println(s"Date Range: $startDate to $endDate") *>
    IO.println(s"Timestamp Range: $startMs to $endMs") *>
    IO.println(s"Start: ${Instant.ofEpochMilli(startMs)}") *>
    IO.println(s"End:   ${Instant.ofEpochMilli(endMs)}") *>
    IO.println("=" * 80) *>
    IO.println("") *>
    compareAllDurations(startMs, endMs).as(ExitCode.Success).handleErrorWith { err =>
      IO.println(s"Error: ${err.getMessage}") *>
      IO(err.printStackTrace()) *>
      IO.pure(ExitCode.Error)
    }
  }

  /**
   * Compare both 1-minute and 1-second candles from both sources
   */
  private def compareAllDurations(startMs: Long, endMs: Long): IO[Unit] = {
    for {
      _ <- IO.println(">>> Comparing COMBINED (1s + 1m) candle stream ordering <<<")
      _ <- IO.println("-" * 80)
      _ <- compareCombinedStreams(startMs, endMs)
      _ <- IO.println("")
      _ <- IO.println(">>> Comparing ONE MINUTE candles <<<")
      _ <- IO.println("-" * 80)
      _ <- compareDuration(CandleDuration.OneMinute, startMs, endMs)
      _ <- IO.println("")
      _ <- IO.println(">>> Comparing ONE SECOND candles <<<")
      _ <- IO.println("-" * 80)
      _ <- compareDuration(CandleDuration.OneSecond, startMs, endMs)
      _ <- IO.println("")
      _ <- IO.println(">>> Comparison Complete <<<")
    } yield ()
  }

  /**
   * Compare combined 1s + 1m streams to check interleaving order
   */
  private def compareCombinedStreams(startMs: Long, endMs: Long): IO[Unit] = {
    val durations: Set[CandleDuration] = Set(CandleDuration.OneSecond, CandleDuration.OneMinute)
    val parquetSource = new ParquetSource(durations)
    val databentoSource = new DatabentoSource(durations)
    
    for {
      _ <- IO.println("[Combined] Fetching from ParquetSource (1s + 1m)...")
      parquetCandles <- parquetSource.candlesInRangeStream(startMs, endMs).compile.toList
      _ <- IO.println(s"[Combined] Parquet got ${parquetCandles.size} candles")
      
      _ <- IO.println("[Combined] Fetching from DatabentoSource (1s + 1m)...")
      databentoCandles <- databentoSource.candlesInRangeStream(startMs, endMs).compile.toList
      _ <- IO.println(s"[Combined] Databento got ${databentoCandles.size} candles")
      
      _ <- IO.println("")
      _ <- IO.println("=== Interleaving Analysis ===")
      _ <- analyzeInterleaving(parquetCandles, databentoCandles)
    } yield ()
  }

  /**
   * Analyze how 1s and 1m candles are interleaved in both streams
   */
  private def analyzeInterleaving(parquetCandles: List[Candle], databentoCandles: List[Candle]): IO[Unit] = {
    // Check if both streams have the same order
    val orderMatches = parquetCandles.zip(databentoCandles).forall { case (p, d) =>
      p.timestamp == d.timestamp && p.duration == d.duration
    }
    
    // Find first mismatch
    val firstMismatch = parquetCandles.zip(databentoCandles).zipWithIndex.find { case ((p, d), _) =>
      p.timestamp != d.timestamp || p.duration != d.duration
    }
    
    for {
      _ <- IO.println(s"Stream lengths match: ${parquetCandles.size == databentoCandles.size}")
      _ <- IO.println(s"Order matches exactly: $orderMatches")
      _ <- firstMismatch match {
        case Some(((p, d), idx)) =>
          IO.println(s"First mismatch at index $idx:") *>
          IO.println(s"  Parquet:  ts=${formatTimestamp(p.timestamp)} (${p.timestamp}) dur=${p.duration}") *>
          IO.println(s"  Databento: ts=${formatTimestamp(d.timestamp)} (${d.timestamp}) dur=${d.duration}")
        case None =>
          IO.println("No mismatches found in common elements")
      }
      
      // Analyze look-ahead bias in each source
      _ <- IO.println("")
      _ <- IO.println("=== Look-Ahead Bias Analysis ===")
      _ <- IO.println("Checking if 1m candles arrive BEFORE all their constituent 1s candles...")
      _ <- IO.println("")
      _ <- analyzeLookAhead(parquetCandles, "Parquet")
      _ <- IO.println("")
      _ <- analyzeLookAhead(databentoCandles, "Databento")
      
      // Show sample of ordering around minute boundaries
      _ <- IO.println("")
      _ <- IO.println("=== Sample: Candles around first minute boundary ===")
      _ <- showMinuteBoundary(parquetCandles, "Parquet")
      _ <- IO.println("")
      _ <- showMinuteBoundary(databentoCandles, "Databento")
    } yield ()
  }

  /**
   * Check for look-ahead bias: does a 1m candle appear before all 1s candles for that minute?
   */
  private def analyzeLookAhead(candles: List[Candle], sourceName: String): IO[Unit] = {
    // Build a map: for each 1m candle timestamp, what index does it appear at?
    val oneMinuteIndices = candles.zipWithIndex.collect {
      case (c, idx) if c.duration == CandleDuration.OneMinute => (c.timestamp, idx)
    }.toMap
    
    // For each 1s candle, check if there's a 1m candle at the same minute that appeared earlier
    val lookAheadViolations = candles.zipWithIndex.collect {
      case (c, idx) if c.duration == CandleDuration.OneSecond =>
        // Round down to minute boundary
        val minuteTs = (c.timestamp / 60000L) * 60000L
        oneMinuteIndices.get(minuteTs) match {
          case Some(oneMinIdx) if oneMinIdx < idx =>
            // The 1m candle for this minute appeared BEFORE this 1s candle
            Some((c.timestamp, idx, oneMinIdx, minuteTs))
          case _ => None
        }
    }.flatten
    
    // Also check: do 1m candles arrive before their endTime worth of 1s candles?
    // A 1m candle at timestamp T should not arrive until after all 1s candles from T to T+59s
    val prematureMinuteCandles = candles.zipWithIndex.collect {
      case (c, idx) if c.duration == CandleDuration.OneMinute =>
        val minuteStart = c.timestamp
        val minuteEnd = c.timestamp + 59000L // Last second of this minute
        
        // Find any 1s candles for this minute that appear AFTER the 1m candle
        val laterSeconds = candles.drop(idx + 1).filter { later =>
          later.duration == CandleDuration.OneSecond &&
          later.timestamp >= minuteStart &&
          later.timestamp <= minuteEnd
        }
        
        if (laterSeconds.nonEmpty) Some((c.timestamp, idx, laterSeconds.size)) else None
    }.flatten
    
    for {
      _ <- IO.println(s"[$sourceName] Look-ahead analysis:")
      _ <- IO.println(s"  1s candles that appear AFTER their corresponding 1m candle: ${lookAheadViolations.size}")
      _ <- IO.println(s"  1m candles that appear BEFORE all their 1s candles complete: ${prematureMinuteCandles.size}")
      _ <- if (prematureMinuteCandles.nonEmpty) {
        IO.println(s"  First 5 premature 1m candles:") *>
        prematureMinuteCandles.take(5).traverse_ { case (ts, idx, laterCount) =>
          IO.println(s"    1m @ ${formatTimestamp(ts)} (idx=$idx) has $laterCount 1s candles after it")
        }
      } else IO.unit
      _ <- if (lookAheadViolations.isEmpty && prematureMinuteCandles.isEmpty) {
        IO.println(s"  ✓ Realistic ordering: 1m candles arrive after all their 1s candles")
      } else {
        IO.println(s"  ✗ Has look-ahead bias: 1m candles arrive before 1s data completes")
      }
    } yield ()
  }

  /**
   * Show candles around the first minute boundary to visualize ordering
   */
  private def showMinuteBoundary(candles: List[Candle], sourceName: String): IO[Unit] = {
    // Find the first 1m candle
    val firstMinuteIdx = candles.indexWhere(_.duration == CandleDuration.OneMinute)
    if (firstMinuteIdx < 0) {
      IO.println(s"[$sourceName] No 1m candles found")
    } else {
      val start = math.max(0, firstMinuteIdx - 5)
      val end = math.min(candles.size, firstMinuteIdx + 10)
      val window = candles.slice(start, end).zipWithIndex
      
      IO.println(s"[$sourceName] Candles around first 1m (indices $start to ${end-1}):") *>
      window.traverse_ { case (c, i) =>
        val globalIdx = start + i
        val durStr = if (c.duration == CandleDuration.OneSecond) "1s" else "1m"
        val marker = if (globalIdx == firstMinuteIdx) " <-- FIRST 1m CANDLE" else ""
        IO.println(f"  [$globalIdx%5d] ${formatTimestamp(c.timestamp)} $durStr$marker")
      }
    }
  }

  /**
   * Compare a specific duration from both sources
   */
  private def compareDuration(duration: CandleDuration, startMs: Long, endMs: Long): IO[Unit] = {
    val parquetSource = new ParquetSource(duration)
    val databentoSource = new DatabentoSource(Set(duration))
    
    for {
      // Collect all candles from both sources
      _ <- IO.println(s"[Parquet] Fetching $duration candles...")
      parquetCandles <- parquetSource.candlesInRangeStream(startMs, endMs).compile.toList
      _ <- IO.println(s"[Parquet] Got ${parquetCandles.size} candles")
      
      _ <- IO.println(s"[Databento] Fetching $duration candles...")
      databentoCandles <- databentoSource.candlesInRangeStream(startMs, endMs).compile.toList
      _ <- IO.println(s"[Databento] Got ${databentoCandles.size} candles")
      
      _ <- IO.println("")
      _ <- analyzeAndReport(duration, parquetCandles, databentoCandles)
    } yield ()
  }

  /**
   * Analyze and report differences between the two candle lists
   */
  private def analyzeAndReport(duration: CandleDuration, parquetCandles: List[Candle], databentoCandles: List[Candle]): IO[Unit] = {
    // Create maps keyed by timestamp for easy lookup
    val parquetMap = parquetCandles.map(c => CandleKey(c.timestamp, c.duration) -> c).toMap
    val databentoMap = databentoCandles.map(c => CandleKey(c.timestamp, c.duration) -> c).toMap
    
    val allKeys = (parquetMap.keySet ++ databentoMap.keySet).toList.sortBy(_.timestamp)
    
    // Find differences
    val diffs = allKeys.flatMap { key =>
      (parquetMap.get(key), databentoMap.get(key)) match {
        case (Some(p), Some(d)) if candlesMatch(p, d) => 
          None // Perfect match
        case (Some(p), Some(d)) => 
          Some(CandleDiff(key, Some(p), Some(d), "VALUE_MISMATCH", describeDiff(p, d)))
        case (Some(p), None) => 
          Some(CandleDiff(key, Some(p), None, "ONLY_IN_PARQUET", formatCandle(p)))
        case (None, Some(d)) => 
          Some(CandleDiff(key, None, Some(d), "ONLY_IN_DATABENTO", formatCandle(d)))
        case (None, None) => 
          None // Should never happen
      }
    }
    
    // Report summary
    val valueMismatches = diffs.filter(_.diffType == "VALUE_MISMATCH")
    val onlyInParquet = diffs.filter(_.diffType == "ONLY_IN_PARQUET")
    val onlyInDatabento = diffs.filter(_.diffType == "ONLY_IN_DATABENTO")
    val totalMatches = allKeys.size - diffs.size
    
    for {
      _ <- IO.println(s"=== $duration Summary ===")
      _ <- IO.println(s"Total unique timestamps: ${allKeys.size}")
      _ <- IO.println(s"Perfect matches: $totalMatches")
      _ <- IO.println(s"Value mismatches: ${valueMismatches.size}")
      _ <- IO.println(s"Only in Parquet: ${onlyInParquet.size}")
      _ <- IO.println(s"Only in Databento: ${onlyInDatabento.size}")
      _ <- IO.println("")
      
      // Check ordering
      _ <- IO.println("=== Ordering Analysis ===")
      _ <- analyzeOrdering(parquetCandles, "Parquet")
      _ <- analyzeOrdering(databentoCandles, "Databento")
      _ <- IO.println("")
      
      // Report first 20 of each type of difference
      _ <- if (valueMismatches.nonEmpty) {
        IO.println(s"=== Value Mismatches (showing first 50 of ${valueMismatches.size}) ===") *>
        valueMismatches.take(50).traverse_(d => reportDiff(d))
      } else IO.unit
      
      _ <- if (onlyInParquet.nonEmpty) {
        IO.println(s"=== Only in Parquet (showing first 50 of ${onlyInParquet.size}) ===") *>
        onlyInParquet.take(50).traverse_(d => reportDiff(d))
      } else IO.unit
      
      _ <- if (onlyInDatabento.nonEmpty) {
        IO.println(s"=== Only in Databento (showing first 50 of ${onlyInDatabento.size}) ===") *>
        onlyInDatabento.take(50).traverse_(d => reportDiff(d))
      } else IO.unit
      
      // Timestamp range check
      _ <- IO.println("=== Timestamp Range Check ===")
      _ <- if (parquetCandles.nonEmpty) {
        IO.println(s"Parquet first: ${formatTimestamp(parquetCandles.head.timestamp)} (${parquetCandles.head.timestamp})") *>
        IO.println(s"Parquet last:  ${formatTimestamp(parquetCandles.last.timestamp)} (${parquetCandles.last.timestamp})")
      } else IO.println("Parquet: NO CANDLES")
      _ <- if (databentoCandles.nonEmpty) {
        IO.println(s"Databento first: ${formatTimestamp(databentoCandles.head.timestamp)} (${databentoCandles.head.timestamp})") *>
        IO.println(s"Databento last:  ${formatTimestamp(databentoCandles.last.timestamp)} (${databentoCandles.last.timestamp})")
      } else IO.println("Databento: NO CANDLES")
      
    } yield ()
  }

  /**
   * Check if candle streams are in proper timestamp order
   */
  private def analyzeOrdering(candles: List[Candle], sourceName: String): IO[Unit] = {
    if (candles.isEmpty) {
      IO.println(s"  $sourceName: Empty - no ordering to check")
    } else {
      val outOfOrder = candles.sliding(2).zipWithIndex.collect {
        case (Seq(a, b), idx) if a.timestamp > b.timestamp => (idx, a.timestamp, b.timestamp)
      }.toList
      
      if (outOfOrder.isEmpty) {
        IO.println(s"  $sourceName: ✓ All candles in ascending timestamp order")
      } else {
        IO.println(s"  $sourceName: ✗ ${outOfOrder.size} out-of-order occurrences!") *>
        outOfOrder.take(10).traverse_ { case (idx, prev, curr) =>
          IO.println(s"    At index $idx: ${formatTimestamp(prev)} > ${formatTimestamp(curr)}")
        }
      }
    }
  }

  /**
   * Check if two candles match (same OHLCV values with small tolerance for floats)
   */
  private def candlesMatch(a: Candle, b: Candle): Boolean = {
    val tolerance = 0.0001f
    math.abs(a.open - b.open) < tolerance &&
    math.abs(a.high - b.high) < tolerance &&
    math.abs(a.low - b.low) < tolerance &&
    math.abs(a.close - b.close) < tolerance &&
    a.volume == b.volume
  }

  /**
   * Describe the differences between two candles
   */
  private def describeDiff(p: Candle, d: Candle): String = {
    val diffs = List(
      if (p.open != d.open) Some(f"open: ${p.open}%.2f vs ${d.open}%.2f (diff: ${p.open - d.open}%.4f)") else None,
      if (p.high != d.high) Some(f"high: ${p.high}%.2f vs ${d.high}%.2f (diff: ${p.high - d.high}%.4f)") else None,
      if (p.low != d.low) Some(f"low: ${p.low}%.2f vs ${d.low}%.2f (diff: ${p.low - d.low}%.4f)") else None,
      if (p.close != d.close) Some(f"close: ${p.close}%.2f vs ${d.close}%.2f (diff: ${p.close - d.close}%.4f)") else None,
      if (p.volume != d.volume) Some(s"volume: ${p.volume} vs ${d.volume} (diff: ${p.volume - d.volume})") else None
    ).flatten
    diffs.mkString("; ")
  }

  /**
   * Format a single candle for logging
   */
  private def formatCandle(c: Candle): String = {
    f"O=${c.open}%.2f H=${c.high}%.2f L=${c.low}%.2f C=${c.close}%.2f V=${c.volume}"
  }

  /**
   * Format timestamp for human-readable output
   */
  private def formatTimestamp(ts: Long): String = {
    val instant = Instant.ofEpochMilli(ts)
    val zdt = instant.atZone(TimestampUtils.NewYorkZone)
    zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
  }

  /**
   * Report a single difference
   */
  private def reportDiff(diff: CandleDiff): IO[Unit] = {
    val tsStr = formatTimestamp(diff.key.timestamp)
    IO.println(s"  [${diff.diffType}] $tsStr (${diff.key.timestamp}) ${diff.key.duration}") *>
    IO.println(s"    ${diff.details}")
  }

  // Extension for traverse_
  implicit class ListOps[A](list: List[A]) {
    def traverse_(f: A => IO[Unit]): IO[Unit] = 
      list.foldLeft(IO.unit)((acc, a) => acc *> f(a))
  }
}
