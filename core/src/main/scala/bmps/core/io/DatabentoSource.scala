package bmps.core.io

import cats.effect.IO
import cats.effect.unsafe.implicits.global
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
import bmps.core.utils.{TimestampUtils, MarketCalendar}
import java.sql.Time
import java.sql.Timestamp

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
class DatabentoSource(durations: Set[CandleDuration]) extends DataSource {
  
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

  private val mostLiquidSymbol = "ES.n.0"
  
  // Validate that only supported durations are used
  require(durations.subsetOf(Set(CandleDuration.OneSecond, CandleDuration.OneMinute, CandleDuration.OneHour)), 
    s"Only OneSecond, OneMinute, and OneHour durations are supported, got: $durations")
  
  // Convert duration to Databento schema
  private val schemas = durations.map(d => d match {
    case CandleDuration.OneSecond => "ohlcv-1s"
    case CandleDuration.OneMinute => "ohlcv-1m"
    case CandleDuration.OneHour => "ohlcv-1h"
    case _ => throw new IllegalArgumentException(s"Unsupported duration: $d")
  }).toList

  /**
   * Resolve the current contract symbol from the continuous contract symbol.
   * Uses the symbology.resolve endpoint to map ES.n.0 to the actual front month contract (e.g., ESZ4).
   * Uses the last trading day before today since historical data may not be available for today yet.
   */
  override lazy val currentContractSymbol: String = {
    val lastTradingDay = MarketCalendar.getTradingDaysBack(LocalDate.now(), 1)
    println(s"[DatabentoSource] Using last trading day: $lastTradingDay")
    
    resolveSymbol(mostLiquidSymbol, lastTradingDay) match {
      case Some(symbol) => 
        println(s"[DatabentoSource] Resolved $mostLiquidSymbol to $symbol")
        symbol
      case None =>
        throw new RuntimeException(s"Failed to resolve symbol $mostLiquidSymbol to raw symbol")
    }
  }

  /**
   * Stream candles within an inclusive start (ms) and exclusive end (ms) range.
   * 
   * This method intelligently routes requests:
   * - For historical data (before today): Uses HTTP REST API
   * - For live data (today): Uses TCP socket with intraday replay
   * - For mixed ranges: Combines both seamlessly
   */
  def candlesInRangeStream(startMs: Long, endMs: Long): Stream[IO, Candle] = {
    println(s"[DatabentoSource] Got request for : $startMs to $endMs.")
    
    val startDate = TimestampUtils.toNewYorkLocalDate(startMs)
    val endDate = TimestampUtils.toNewYorkLocalDate(endMs)
    val today = TimestampUtils.today()
    
    // Determine the split point between historical and live data
    if (endDate.isBefore(today)) {
      // All historical - use HTTP API only
      fetchHistoricalCandlesStream(startMs, endMs)
    } else if (startDate.isAfter(today) || startDate.isEqual(today)) {
      // All live/future - use TCP socket only
      streamLiveCandles(startMs, endMs)
    } else {
      // Mixed - fetch historical then stream live
      val todayStartMs = TimestampUtils.midnight(today)
      val historicalStream = fetchHistoricalCandlesStream(startMs, todayStartMs)
      val liveStream = streamLiveCandles(todayStartMs, endMs)
      
      historicalStream ++ liveStream
    }
  }
  
  /**
   * Fetch historical candles using the HTTP REST API for dates before today.
   */
  private def fetchHistoricalCandlesStream(startMs: Long, endMs: Long): Stream[IO, Candle] = {
    schemas.filterNot(_ == "ohlcv-1s").map { schema => 
      Stream.eval(fetchHistoricalCandles(schema, startMs, endMs)).flatMap(Stream.emits)
    }.reduce(_ ++ _)
  }
  
  /**
   * Make HTTP request to historical API and parse response.
   */
  private def fetchHistoricalCandles(schema: String, startMs: Long, endMs: Long): IO[List[Candle]] = {
    // Convert milliseconds to ISO 8601 format in UTC
    val startTime = Instant.ofEpochMilli(startMs).toString
    val endTime = Instant.ofEpochMilli(endMs).toString
    
    // Build request URL - uses continuous contract ES.n.0 (front month) same as live API
    val url = s"$historicalBaseUrl/timeseries.get_range?" +
              s"dataset=$dataset" +
              s"&schema=$schema" +
              s"&symbols=$mostLiquidSymbol" +
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
    // println(jsonLine)
    if (jsonLine.isEmpty || jsonLine == "{}") {
      return None
    }
    
    try {
      // Extract values - handle both quoted strings and plain numbers
      val rtypePattern = """"rtype":"?(\d+)"?""".r
      val openPattern = """"open":"?([0-9.]+)"?""".r
      val highPattern = """"high":"?([0-9.]+)"?""".r
      val lowPattern = """"low":"?([0-9.]+)"?""".r
      val closePattern = """"close":"?([0-9.]+)"?""".r
      
      // ts_event can be in root (historical) or nested in hd object (live)
      val tsEventRootPattern = """"ts_event":"?(\d+)"?""".r
      val tsEventHdPattern = """"hd":\{[^}]*"ts_event":"?(\d+)"?""".r
      
      // ts_out is the timestamp when the message was sent from the server
      val tsOutPattern = """"ts_out":"?(\d+)"?""".r
      
      for {
        rtype <- rtypePattern.findFirstMatchIn(jsonLine).map(_.group(1).toInt)
        openRaw <- openPattern.findFirstMatchIn(jsonLine).map(_.group(1).toDouble)
        highRaw <- highPattern.findFirstMatchIn(jsonLine).map(_.group(1).toDouble)
        lowRaw <- lowPattern.findFirstMatchIn(jsonLine).map(_.group(1).toDouble)
        closeRaw <- closePattern.findFirstMatchIn(jsonLine).map(_.group(1).toDouble)
        tsEventNanos <- tsEventHdPattern.findFirstMatchIn(jsonLine).map(_.group(1).toLong)
                        .orElse(tsEventRootPattern.findFirstMatchIn(jsonLine).map(_.group(1).toLong))
        tsOutNanos <- tsOutPattern.findFirstMatchIn(jsonLine).map(_.group(1).toLong)
                      .orElse(Some(TimestampUtils.millisToNanos(Instant.now().toEpochMilli)))
      } yield {
        // Databento live stream uses fixed-point integers (divide by 1e9 to get actual price)
        val candleDuration = rtype match {
          case 32 => CandleDuration.OneSecond
          case 33 => CandleDuration.OneMinute
          case 34 => CandleDuration.OneHour
          case _ => throw new IllegalStateException(s"Unsupported rtype for candle: $rtype")
        }

        val open = openRaw / 1e9
        val high = highRaw / 1e9
        val low = lowRaw / 1e9
        val close = closeRaw / 1e9
        
        val timestampMs = TimestampUtils.nanosToMillis(tsEventNanos)
        val currentTimestampMs = TimestampUtils.nanosToMillis(tsOutNanos)
        
        Candle(
          open = open.toFloat,
          high = high.toFloat,
          low = low.toFloat,
          close = close.toFloat,
          timestamp = timestampMs,
          duration = candleDuration,
          currentTimestampMs
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
  private def streamLiveCandles(startMs: Long, endMs: Long): Stream[IO, Candle] = {
    Stream.eval(IO.println(s"[DatabentoSource] Starting live stream: $startMs to $endMs")) >>
    Stream.resource(createLiveConnection(startMs)).flatMap { case (reader, writer) =>
      Stream.eval(subscribeLive(writer, startMs)) >>
      readLiveCandlesUntil(reader, endMs)
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
   * Uses continuous contract ES.n.0 which automatically maps to the front month
   * (most active contract). When ES rolls from ESZ5 to ESH6, this will automatically
   * switch to the new front month without code changes.
   */
  private def subscribeLive(writer: PrintWriter, startMs: Long): IO[Unit] = {
    IO.blocking {
      // Convert startMs to UTC nanoseconds
      val startNanos = TimestampUtils.millisToNanos(startMs)
      
      schemas.foreach { schema => 
        val subscribeMessage = s"schema=$schema|stype_in=continuous|symbols=$mostLiquidSymbol|start=$startNanos"
        writer.println(subscribeMessage)
        println(s"[DatabentoSource] Sent subscription: $subscribeMessage")
      }

      // Start the session
      writer.println("start_session=1")
      println(s"[DatabentoSource] Started session")
    }
  }
  
  /**
   * Read and parse candles from live stream until we reach endMs.
   */
  private def readLiveCandlesUntil(reader: BufferedReader, endMs: Long): Stream[IO, Candle] = {
    Stream.repeatEval {
      IO.blocking {
        Option(reader.readLine())
      }
    }.unNoneTerminate
      .evalMap { line =>
        IO {
          // Log EVERY message received from the stream
          // println(s"[DatabentoSource] <<< Received: $line")

          if (line.contains("\"rtype\":23") && line.contains("Heartbeat")) {
            // Extract ts_event from heartbeat to check if we're past endMs
            val tsEventPattern = """"ts_event":"?(\d+)"?""".r
            tsEventPattern.findFirstMatchIn(line).map(_.group(1).toLong) match {
              case Some(tsEventNanos) =>
                val heartbeatMs = TimestampUtils.nanosToMillis(tsEventNanos)
                
                if (heartbeatMs >= endMs) {
                  println(s"[DatabentoSource]     -> Heartbeat passed end time, terminating stream")
                  None //Terminate the stream
                } else {
                  Some(None) //Continue but don't emit
                }
              case None =>
                println(s"[DatabentoSource]     -> Unable to parse heartbeat message.")
                Some(None) //Ignore bad messages
            }
          } else if (line.contains("\"rtype\":34") || line.contains("\"rtype\":33") || line.contains("\"rtype\":32")) {
            parseJsonLineToCandle(line) match {
              case None => 
                println(s"[DatabentoSource]     -> FAILED: Could not parse as candle")
                Some(None) //Ignore and continue
              case Some(candle) if candle.timestamp >= endMs =>
                println(s"[DatabentoSource]     -> Got a candle with ts ${candle.timestamp} > $endMs.")
                None // End the stream
              case c @ Some(candle) => 
                // println(s"[DatabentoSource]     -> SUCCESS: Parsed candle: ${candle}")
                Some(c) //Emit and continue
            }
          } else {
            Some(None) //Ignore other messages and continue
          }
        }
      }
      .unNoneTerminate  // Terminates when we return None (heartbeat past end time)
      .unNone           // Remove all the None values (skipped messages)
  }

  /**
   * Resolve a continuous contract symbol to its raw symbol for a given date.
   * 
   * This is a two-step process:
   * 1. continuous -> instrument_id
   * 2. instrument_id -> raw_symbol
   * 
   * @param symbol The continuous contract symbol (e.g., "ES.n.0")
   * @param date The date to resolve for (typically today)
   * @return The resolved raw symbol (e.g., "ESZ4") or None if resolution fails
   */
  private def resolveSymbol(symbol: String, date: LocalDate): Option[String] = {
    val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
    
    println(s"[DatabentoSource] Resolving symbol $symbol for date $dateStr")
    
    // Step 1: continuous -> instrument_id
    val step1Url = s"$historicalBaseUrl/symbology.resolve?" +
                   s"dataset=$dataset" +
                   s"&symbols=$symbol" +
                   s"&stype_in=continuous" +
                   s"&stype_out=instrument_id" +
                   s"&start_date=$dateStr"
    
    println(s"[DatabentoSource] Step 1: continuous -> instrument_id")
    
    val instrumentIdOpt = makeSymbologyRequest(step1Url, symbol)
    
    instrumentIdOpt.flatMap { instrumentId =>
      println(s"[DatabentoSource] Step 1 result: $instrumentId")
      
      // Step 2: instrument_id -> raw_symbol
      val step2Url = s"$historicalBaseUrl/symbology.resolve?" +
                     s"dataset=$dataset" +
                     s"&symbols=$instrumentId" +
                     s"&stype_in=instrument_id" +
                     s"&stype_out=raw_symbol" +
                     s"&start_date=$dateStr"
      
      // println(s"[DatabentoSource] Step 2: instrument_id -> raw_symbol")
      
      val rawSymbol = makeSymbologyRequest(step2Url, instrumentId)
      println(s"[DatabentoSource] Step 2 result: $rawSymbol")
      rawSymbol
    }
  }
  
  /**
   * Make a symbology resolution HTTP request.
   */
  private def makeSymbologyRequest(url: String, inputSymbol: String): Option[String] = {
    Try {
      val basicAuth = java.util.Base64.getEncoder.encodeToString(s"$apiKey:".getBytes("UTF-8"))
      
      val request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Authorization", s"Basic $basicAuth")
        .header("User-Agent", "bmps-scala-client/1.0")
        .GET()
        .build()
      
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      
      response.statusCode() match {
        case 200 => parseSymbologyResponse(response.body(), inputSymbol)
        case other => 
          println(s"[DatabentoSource] Symbol resolution failed with HTTP $other: ${response.body()}")
          None
      }
    } match {
      case Success(result) => result
      case Failure(e) => 
        println(s"[DatabentoSource] Symbol resolution request failed: ${e.getMessage}")
        None
    }
  }  /**
   * Parse the JSON response from symbology.resolve endpoint.
   * 
   * Example response:
   * {
   *   "result": {
   *     "ES.n.0": [{"d0": "2024-12-01", "d1": "2024-12-20", "s": "ESZ4"}]
   *   },
   *   "status": 0
   * }
   */
  private def parseSymbologyResponse(jsonResponse: String, symbol: String): Option[String] = {
    try {
      // Extract the resolved symbol from the result object
      val resultPattern = s""""result":\\s*\\{\\s*"${java.util.regex.Pattern.quote(symbol)}":\\s*\\[\\s*\\{[^}]*"s":\\s*"([^"]+)"""".r
      
      resultPattern.findFirstMatchIn(jsonResponse).map { m =>
        val resolvedSymbol = m.group(1)
        resolvedSymbol
      }
    } catch {
      case e: Exception =>
        println(s"[DatabentoSource] Failed to parse symbology response: ${e.getMessage}")
        println(s"[DatabentoSource] Response was: $jsonResponse")
        None
    }
  }
}
