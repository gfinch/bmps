package bmps.core.io

import cats.effect.IO
import fs2.Stream
import bmps.core.models._
import java.time.{Instant, LocalDate, ZoneId, ZonedDateTime}
import java.time.format.DateTimeFormatter
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{Socket, URI}
import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import scala.util.{Try, Success, Failure}
import scala.concurrent.duration._
import java.security.MessageDigest

/**
 * Data source for Databento API that provides both historical and live streaming candle data.
 * 
 * This implementation seamlessly transitions from historical data (via HTTP REST API) to 
 * live streaming data (via TCP socket) when the requested time range crosses into the current day.
 * 
 * Features:
 * - Historical data: Uses Databento's HTTP REST API for dates before today
 * - Live data: Uses Databento's TCP socket Live API with intraday replay for today's data
 * - Automatic reconnection with exponential backoff on connection failures
 * - CRAM authentication for secure API key exchange
 * - Timezone conversion from UTC to Eastern time
 * - Supports OneMinute and OneHour candle durations
 * 
 * Requires the DATABENTO_KEY environment variable to be set with a valid Databento API key.
 * 
 * @param duration The candle duration - supports OneMinute and OneHour only
 * 
 * Example usage:
 * {{{
 * val source = new DatabentoSource(CandleDuration.OneMinute)
 * val candles = source.candlesInRangeStream(startMs, endMs, ZoneId.of("America/New_York"))
 * }}}
 */
class DatabentoSource(duration: CandleDuration) extends DataSource {
  
  // Java 11+ HTTP client for historical API requests
  private val httpClient = HttpClient.newHttpClient()
  
  // Load API key from environment variable
  private lazy val apiKey: String = sys.env.getOrElse("DATABENTO_KEY", 
    throw new IllegalArgumentException("DATABENTO_KEY environment variable must be set"))
  
  // Databento dataset for ES futures on CME Globex
  private val dataset = "GLBX.MDP3"
  
  // Live API host and port
  private val liveHost = "glbx-mdp3.lsg.databento.com"
  private val livePort = 13000
  
  // Historical API endpoint
  private val historicalBaseUrl = "https://hist.databento.com/v0"
  
  // Validate that only supported durations are used
  require(duration == CandleDuration.OneMinute || duration == CandleDuration.OneHour, 
    s"Only OneMinute and OneHour durations are supported, got: $duration")
  
  // Convert duration to Databento schema
  private val schema = duration match {
    case CandleDuration.OneMinute => "ohlcv-1m"
    case CandleDuration.OneHour => "ohlcv-1h"
    case _ => throw new IllegalArgumentException(s"Unsupported duration: $duration")
  }

  /**
   * Stream candles within an inclusive start (ms) and exclusive end (ms) range.
   * 
   * This method intelligently routes requests:
   * - For historical data (before today): Uses HTTP REST API
   * - For live data (today): Uses TCP socket with intraday replay
   * - For mixed ranges: Combines both seamlessly
   */
  def candlesInRangeStream(startMs: Long, endMs: Long, zone: ZoneId): Stream[IO, Candle] = {
    println(s"[DatabentoSource] Got request for : $startMs to $endMs in $zone.")
    // Convert to LocalDate in Eastern time for day boundary detection
    val easternZone = ZoneId.of("America/New_York")
    val startDate = Instant.ofEpochMilli(startMs).atZone(easternZone).toLocalDate
    val endDate = Instant.ofEpochMilli(endMs).atZone(easternZone).toLocalDate
    val today = LocalDate.now(easternZone)
    
    // Determine the split point between historical and live data
    if (endDate.isBefore(today)) {
      // All historical - use HTTP API only
      fetchHistoricalCandlesStream(startMs, endMs, zone)
    } else if (startDate.isAfter(today) || startDate.isEqual(today)) {
      // All live/future - use TCP socket only
      streamLiveCandles(startMs, endMs, zone)
    } else {
      // Mixed - fetch historical then stream live
      val todayStartMs = today.atStartOfDay(easternZone).toInstant.toEpochMilli
      val historicalStream = fetchHistoricalCandlesStream(startMs, todayStartMs, zone)
      val liveStream = streamLiveCandles(todayStartMs, endMs, zone)
      
      historicalStream ++ liveStream
    }
  }
  
  /**
   * Fetch historical candles using the HTTP REST API for dates before today.
   */
  private def fetchHistoricalCandlesStream(startMs: Long, endMs: Long, zone: ZoneId): Stream[IO, Candle] = {
    Stream.eval(fetchHistoricalCandles(startMs, endMs)).flatMap(Stream.emits)
  }
  
  /**
   * Make HTTP request to historical API and parse response.
   */
  private def fetchHistoricalCandles(startMs: Long, endMs: Long): IO[List[Candle]] = {
    // Convert milliseconds to ISO 8601 format in UTC
    val startTime = Instant.ofEpochMilli(startMs).toString
    val endTime = Instant.ofEpochMilli(endMs).toString
    
    // Build request URL - uses continuous contract ES.c.0 (front month) same as live API
    val url = s"$historicalBaseUrl/timeseries.get_range?" +
              s"dataset=$dataset" +
              s"&schema=$schema" +
              s"&symbols=ES.c.0" +
              s"&stype_in=continuous" +
              s"&start=$startTime" +
              s"&end=$endTime" +
              s"&encoding=json"
    
    println(s"[DatabentoSource] Fetching historical data: $startTime to $endTime")
    
    makeHistoricalRequest(url).flatMap {
      case Right(jsonLines) => parseHistoricalJsonToCandles(jsonLines)
      case Left(error) => 
        IO.println(s"[DatabentoSource] Failed to fetch historical data: $error").as(List.empty[Candle])
    }
  }
  
  /**
   * Make HTTP request to historical API with authentication and retry logic.
   */
  private def makeHistoricalRequest(url: String): IO[Either[String, String]] = {
    IO.blocking {
      Try {
        // Databento uses HTTP Basic Auth with API key as username and empty password
        val basicAuth = java.util.Base64.getEncoder.encodeToString(s"$apiKey:".getBytes("UTF-8"))
        
        val request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Authorization", s"Basic $basicAuth")
          .header("User-Agent", "bmps-scala-client/1.0")
          .GET()
          .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        response.statusCode() match {
          case 200 => Right(response.body())
          case 429 => Left(s"RATE_LIMIT:HTTP 429: ${response.body()}")
          case other => Left(s"HTTP $other: ${response.body()}")
        }
      } match {
        case Success(result) => result
        case Failure(e) => Left(s"Request failed: ${e.getMessage}")
      }
    }.flatMap {
      case Left(error) if error.startsWith("RATE_LIMIT:") =>
        IO.println(s"[DatabentoSource] Rate limit hit, waiting 60 seconds...") *>
        IO.sleep(60.seconds) *>
        makeHistoricalRequest(url)
      case result => IO.pure(result)
    }
  }
  
  /**
   * Parse JSON lines response from historical API to Candle objects.
   * Databento returns JSON lines format (one record per line).
   */
  private def parseHistoricalJsonToCandles(jsonLines: String): IO[List[Candle]] = {
    IO {
      jsonLines.split("\n").flatMap(line => parseJsonLineToCandle(line.trim)).toList
    }
  }
  
  /**
   * Parse a single JSON line to a Candle.
   * 
   * Databento live stream format:
   * {"hd":{"ts_event":"1759928400000000000","rtype":34,...},"open":"6771500000000","high":"6776500000000",...}
   * 
   * Historical API format:
   * {"open":4100.25,"high":4100.5,"low":4100.0,"close":4100.25,"ts_event":1234567890000000000,...}
   * 
   * Note: Live stream prices are in fixed-point format (multiply by 1e-9 to get actual price)
   */
  private def parseJsonLineToCandle(jsonLine: String): Option[Candle] = {
    if (jsonLine.isEmpty || jsonLine == "{}") {
      return None
    }
    
    try {
      // Extract values - handle both quoted strings and plain numbers
      val openPattern = """"open":"?([0-9.]+)"?""".r
      val highPattern = """"high":"?([0-9.]+)"?""".r
      val lowPattern = """"low":"?([0-9.]+)"?""".r
      val closePattern = """"close":"?([0-9.]+)"?""".r
      
      // ts_event can be in root (historical) or nested in hd object (live)
      val tsEventRootPattern = """"ts_event":"?(\d+)"?""".r
      val tsEventHdPattern = """"hd":\{[^}]*"ts_event":"?(\d+)"?""".r
      
      for {
        openRaw <- openPattern.findFirstMatchIn(jsonLine).map(_.group(1).toDouble)
        highRaw <- highPattern.findFirstMatchIn(jsonLine).map(_.group(1).toDouble)
        lowRaw <- lowPattern.findFirstMatchIn(jsonLine).map(_.group(1).toDouble)
        closeRaw <- closePattern.findFirstMatchIn(jsonLine).map(_.group(1).toDouble)
        tsEventNanos <- tsEventHdPattern.findFirstMatchIn(jsonLine).map(_.group(1).toLong)
                        .orElse(tsEventRootPattern.findFirstMatchIn(jsonLine).map(_.group(1).toLong))
      } yield {
        // Databento live stream uses fixed-point integers (divide by 1e9 to get actual price)
        // Historical API uses regular decimals
        // We can detect by checking if values are > 1000 (no stock/future trades that high without decimals)
        val isFixedPoint = openRaw > 100000.0
        
        val open = if (isFixedPoint) openRaw / 1e9 else openRaw
        val high = if (isFixedPoint) highRaw / 1e9 else highRaw
        val low = if (isFixedPoint) lowRaw / 1e9 else lowRaw
        val close = if (isFixedPoint) closeRaw / 1e9 else closeRaw
        
        // Convert UTC nanoseconds to Eastern time milliseconds
        val easternTimestampMs = convertUtcNanosToEasternMillis(tsEventNanos)
        
        Candle(
          open = Level(open.toFloat),
          high = Level(high.toFloat),
          low = Level(low.toFloat),
          close = Level(close.toFloat),
          timestamp = easternTimestampMs,
          duration = duration
        )
      }
    } catch {
      case e: Exception =>
        println(s"[DatabentoSource] Failed to parse JSON line: $jsonLine - ${e.getMessage}")
        None
    }
  }
  
  /**
   * Stream live candles using TCP socket connection with intraday replay.
   */
  private def streamLiveCandles(startMs: Long, endMs: Long, zone: ZoneId): Stream[IO, Candle] = {
    Stream.eval(IO.println(s"[DatabentoSource] Starting live stream: $startMs to $endMs")) >>
    Stream.resource(createLiveConnection(startMs)).flatMap { case (reader, writer) =>
      // Subscribe to the data
      Stream.eval(subscribeLive(writer, startMs)) >>
      // Read and parse candles until we reach endMs
      readLiveCandlesUntil(reader, endMs)
    }.handleErrorWith { error =>
      Stream.eval(IO.println(s"[DatabentoSource] Live connection error: ${error.getMessage}, retrying in 5 seconds...")) >>
      Stream.sleep[IO](5.seconds) >>
      streamLiveCandles(startMs, endMs, zone)
    }
  }
  
  /**
   * Create a live TCP connection and authenticate using CRAM.
   */
  private def createLiveConnection(startMs: Long): cats.effect.Resource[IO, (BufferedReader, PrintWriter)] = {
    cats.effect.Resource.make {
      IO.blocking {
        println(s"[DatabentoSource] Connecting to $liveHost:$livePort")
        val socket = new Socket(liveHost, livePort)
        val reader = new BufferedReader(new InputStreamReader(socket.getInputStream))
        val writer = new PrintWriter(socket.getOutputStream, true)
        
        // Read version string
        val version = reader.readLine()
        println(s"[DatabentoSource] Server version: $version")
        
        // Read CRAM challenge
        val cramLine = reader.readLine()
        val cramChallenge = cramLine.stripPrefix("cram=")
        println(s"[DatabentoSource] CRAM challenge: $cramChallenge")
        
        // Compute CRAM response
        val cramResponse = computeCramResponse(cramChallenge, apiKey)
        
        // Send authentication request
        val authMessage = s"auth=$cramResponse|dataset=$dataset|encoding=json|ts_out=1"
        writer.println(authMessage)
        println(s"[DatabentoSource] Sent auth request")
        
        // Read authentication response
        val authResponse = reader.readLine()
        println(s"[DatabentoSource] Auth response: $authResponse")
        
        if (!authResponse.contains("success=1")) {
          throw new RuntimeException(s"Authentication failed: $authResponse")
        }
        
        (reader, writer)
      }
    } { case (reader, writer) =>
      IO.blocking {
        println(s"[DatabentoSource] Closing connection")
        reader.close()
        writer.close()
      }
    }
  }
  
  /**
   * Compute CRAM response: SHA256(challenge|apiKey) + last 5 chars of apiKey.
   */
  private def computeCramResponse(challenge: String, apiKey: String): String = {
    val input = s"$challenge|$apiKey"
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(input.getBytes("UTF-8"))
    val hash = hashBytes.map("%02x".format(_)).mkString
    val suffix = apiKey.takeRight(5)
    s"$hash-$suffix"
  }
  
  /**
   * Send subscription message and start session.
   * 
   * Uses continuous contract ES.c.0 which automatically maps to the front month
   * (most active contract). When ES rolls from ESZ5 to ESH6, this will automatically
   * switch to the new front month without code changes.
   */
  private def subscribeLive(writer: PrintWriter, startMs: Long): IO[Unit] = {
    IO.blocking {
      // Convert startMs to UTC nanoseconds
      val startNanos = startMs * 1_000_000L
      
      // Subscribe to front month continuous contract (ES.c.0)
      // stype_in=continuous tells Databento we're using continuous contract notation
      // ES.c.0 = front month (most active), ES.c.1 = second month, etc.
      val subscribeMessage = s"schema=$schema|stype_in=continuous|symbols=ES.c.0|start=$startNanos"
      writer.println(subscribeMessage)
      println(s"[DatabentoSource] Sent subscription: $subscribeMessage")
      
      // Start the session
      writer.println("start_session=1")
      println(s"[DatabentoSource] Started session")
    }
  }
  
  /**
   * Read and parse candles from live stream until we reach endMs.
   */
  private def readLiveCandlesUntil(reader: BufferedReader, endMs: Long): Stream[IO, Candle] = {
    // Convert endMs (UTC millis) to Eastern millis to match candle timestamps
    val easternZone = ZoneId.of("America/New_York")
    val endInstant = Instant.ofEpochMilli(endMs)
    val offsetSeconds = easternZone.getRules.getOffset(endInstant).getTotalSeconds
    val endMsEastern = endMs + (offsetSeconds * 1000L)
    
    Stream.repeatEval {
      IO.blocking {
        Option(reader.readLine())
      }
    }.unNoneTerminate
      .evalMap { line =>
        IO {
          // Log EVERY message received from the stream
          println(s"[DatabentoSource] <<< Received: $line")
          
          // Only parse rtype:34 (OHLCV data records)
          // rtype:22 = instrument definitions, rtype:23 = system messages
            if (line.contains("\"rtype\":34") || line.contains("\"rtype\":33")) {
            // rtype:34 = hourly OHLCV data record
            // rtype:33 = minute OHLCV data record
            val recordType = if (line.contains("\"rtype\":34")) "hourly" else "minute"
            println(s"[DatabentoSource]     -> Type: OHLCV data record ($recordType)")
            val result = parseJsonLineToCandle(line)
            if (result.isDefined) {
              println(s"[DatabentoSource]     -> SUCCESS: Parsed candle: ${result.get}")
            } else {
              println(s"[DatabentoSource]     -> FAILED: Could not parse as candle")
            }
            result
          } else if (line.contains("\"rtype\":22")) {
            // Instrument definition / symbology mapping - skip
            println(s"[DatabentoSource]     -> Type: Instrument definition (rtype:22) - skipping")
            None
          } else if (line.contains("\"rtype\":23")) {
            // System message - skip
            println(s"[DatabentoSource]     -> Type: System message (rtype:23) - skipping")
            None
          } else {
            // Unknown message type
            println(s"[DatabentoSource]     -> Type: Unknown - skipping")
            None
          }
        }
      }
      .unNone
      .takeWhile(candle => candle.timestamp <= endMsEastern)
  }
  
  /**
   * Convert UTC nanoseconds to UTC milliseconds minus the Eastern timezone offset.
   * Databento returns timestamps in UTC nanoseconds, but we need UTC millis adjusted by NY offset.
   * For example, during EST (UTC-5), we subtract 5 hours. During EDT (UTC-4), we subtract 4 hours.
   */
  private def convertUtcNanosToEasternMillis(utcNanos: Long): Long = {
    val utcInstant = Instant.ofEpochSecond(utcNanos / 1_000_000_000L, utcNanos % 1_000_000_000L)
    val utcMillis = utcInstant.toEpochMilli
    val easternZone = ZoneId.of("America/New_York")
    
    // Get the offset from UTC for New York at this instant (handles DST automatically)
    val offsetSeconds = easternZone.getRules.getOffset(utcInstant).getTotalSeconds
    val offsetMillis = offsetSeconds * 1000L
    
    // Return UTC millis plus the NY offset
    utcMillis + offsetMillis
  }
}
