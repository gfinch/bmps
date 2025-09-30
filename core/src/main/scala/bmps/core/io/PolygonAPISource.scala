package bmps.core.io

import cats.effect.IO
import fs2.Stream
import bmps.core.models._
import java.time.{Instant, LocalDate, ZoneId}
import java.time.format.DateTimeFormatter
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URI
import scala.util.{Try, Success, Failure}
import scala.concurrent.duration._

/**
 * Data source for Polygon.io REST API that provides streaming candle data.
 * Implements the same interface as ParquetSource but fetches data from the Polygon API.
 * 
 * Requires the POLY_KEY environment variable to be set with a valid Polygon.io API key.
 * 
 * Features:
 * - Automatic retry with 60-second delay for rate limit (HTTP 429) errors
 * - Built-in 1-second delay between API calls to respect rate limits
 * - Optimized multi-day requests (up to 30 days per API call) to minimize API usage
 * - Automatic timezone conversion from UTC to Eastern time for consistency with ParquetSource
 * - Supports OneMinute and OneHour candle durations only
 * 
 * @param duration The candle duration - supports OneMinute and OneHour only
 * 
 * Example usage:
 * {{{
 * // For 1-minute candles
 * val minuteSource = new PolygonAPISource(CandleDuration.OneMinute)
 * 
 * // For 1-hour candles  
 * val hourSource = new PolygonAPISource(CandleDuration.OneHour)
 * 
 * // Get candles for a time range
 * val candles = source.candlesInRangeStream(startMs, endMs, ZoneId.of("America/New_York"))
 * }}}
 */
class PolygonAPISource(duration: CandleDuration) extends DataSource {
  
  // Java 11+ HTTP client for making API requests
  private val httpClient = HttpClient.newHttpClient()
  
  // Load API key from environment variable
  private val apiKey: String = sys.env.getOrElse("POLY_KEY", 
    throw new IllegalArgumentException("POLY_KEY environment variable must be set"))
  
  // Validate that only supported durations are used
  require(duration == CandleDuration.OneMinute || duration == CandleDuration.OneHour, 
    s"Only OneMinute and OneHour durations are supported, got: $duration")
  
  // Convert duration to API parameters
  private val (multiplier, timespan) = duration match {
    case CandleDuration.OneMinute => (1, "minute")
    case CandleDuration.OneHour => (1, "hour")
    case _ => throw new IllegalArgumentException(s"Unsupported duration: $duration")
  }

  /**
   * Stream candles within an inclusive start (ms) and exclusive end (ms) range.
   * Fetches data from Polygon API for the SPY symbol (S&P 500 ETF).
   */
  def candlesInRangeStream(startMs: Long, endMs: Long, zone: ZoneId): Stream[IO, Candle] = {
    // Convert epoch milliseconds to LocalDate for API calls
    val startDate = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate
    val endDate = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate

    println(s"Looking at date range $startDate to $endDate!")
    
    // Generate date ranges to minimize API calls (group up to 30 trading days per request)
    val dateRanges = generateDateRanges(startDate, endDate)
    
    // For each date range, fetch candle data and convert to candles
    // This significantly reduces API calls compared to one-per-day requests
    // Add a small delay between requests to be respectful of rate limits
    Stream.emits(dateRanges)
      .evalMap(fetchCandlesForDateRange)
      .evalTap(_ => IO.sleep(1.second)) // Wait 1 second between API calls to avoid hitting rate limits
      .flatMap(Stream.emits)
    // .evalTap { candle =>
    //     if (candle.timestamp >= startMs && candle.timestamp < endMs) {
    //         IO.println(s"$startMs -> ${candle.timestamp} -> $endMs")
    //     } else {
    //         IO.unit
    //     }
    // }
      .filter(candle => candle.timestamp >= startMs && candle.timestamp < endMs)
  }
  
  /**
   * Generate optimal date ranges for API calls to minimize the number of requests.
   * Groups consecutive trading days together, with a maximum of 30 days per request.
   */
  private def generateDateRanges(startDate: LocalDate, endDate: LocalDate): List[(LocalDate, LocalDate)] = {
    val maxDaysPerRequest = 30 // Polygon API limit, adjust if needed
    
    // Generate all trading days in the range (excluding weekends)
    val allDays = Iterator.iterate(startDate)(_.plusDays(1))
      .takeWhile(!_.isAfter(endDate))
      .filter(date => date.getDayOfWeek.getValue < 6) // Monday = 1, Sunday = 7
      .toList
    
    if (allDays.isEmpty) {
      List.empty
    } else {
      // Group trading days into chunks of maxDaysPerRequest
      allDays.grouped(maxDaysPerRequest).map { chunk =>
        (chunk.head, chunk.last)
      }.toList
    }
  }
  
  /**
   * Fetch all candles for a date range
   */
  private def fetchCandlesForDateRange(dateRange: (LocalDate, LocalDate)): IO[List[Candle]] = {
    val (startDate, endDate) = dateRange
    val startDateStr = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
    val endDateStr = endDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
    val url = s"https://api.polygon.io/v2/aggs/ticker/SPY/range/$multiplier/$timespan/$startDateStr/$endDateStr?apikey=$apiKey"
    
    makeApiRequest(url).flatMap {
      case Right(jsonStr) => parseJsonToCandles(jsonStr)
      case Left(error) => 
        // Log error but don't fail the stream - just return empty list for this range
        IO.println(s"Failed to fetch data for $startDateStr to $endDateStr: $error").as(List.empty[Candle])
    }
  }
  
  /**
   * Makes an HTTP request to the Polygon API with retry logic for rate limiting
   */
  private def makeApiRequest(url: String): IO[Either[String, String]] = {
    makeApiRequestWithRetry(url, maxRetries = 3)
  }
  
  /**
   * Makes an HTTP request with retry logic for 429 (rate limit) errors
   */
  private def makeApiRequestWithRetry(url: String, maxRetries: Int): IO[Either[String, String]] = {
    def attemptRequest(retriesLeft: Int): IO[Either[String, String]] = {
      IO.blocking {
        Try {
          val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
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
        case Left(error) if error.startsWith("RATE_LIMIT:") && retriesLeft > 0 =>
          IO.println(s"Rate limit hit, waiting 60 seconds before retry... (${retriesLeft} retries left)") *>
          IO.sleep(60.seconds) *>
          attemptRequest(retriesLeft - 1)
        case result => IO.pure(result)
      }
    }
    
    attemptRequest(maxRetries)
  }
  
  /**
   * Parse JSON response to list of Candle objects
   * Uses simple string parsing to avoid dependencies on JSON libraries
   */
  private def parseJsonToCandles(jsonStr: String): IO[List[Candle]] = {
    IO {
      try {
        // Very basic JSON parsing - look for the results array
        val resultsStart = jsonStr.indexOf("\"results\":[")
        if (resultsStart == -1) {
          List.empty[Candle]
        } else {
          val resultsContent = jsonStr.substring(resultsStart + 11) // Skip '"results":['
          val resultsEnd = resultsContent.indexOf("]")
          if (resultsEnd == -1) {
            List.empty[Candle]
          } else {
            val resultsArray = resultsContent.substring(0, resultsEnd)
            
            // Split by objects (simple approach - assumes no nested objects)
            val objectPattern = """\{[^}]+\}""".r
            objectPattern.findAllIn(resultsArray).map(parseJsonObject).toList.flatten
          }
        }
      } catch {
        case e: Exception =>
          println(s"Failed to parse JSON: ${e.getMessage}")
          List.empty[Candle]
      }
    }
  }
  
  /**
   * Convert UTC timestamp to Eastern time timestamp.
   * Polygon API returns timestamps in UTC, but we need them in Eastern time for consistency
   * with the rest of the system.
   * 
   * This matches the behavior in ParquetSource where timestamps are converted to Eastern 
   * "wall clock time" and stored as epoch milliseconds. This approach handles daylight 
   * saving time automatically.
   */
  private def convertUtcToEasternTime(utcTimestampMs: Long): Long = {
    val utcInstant = Instant.ofEpochMilli(utcTimestampMs)
    val easternZone = ZoneId.of("America/New_York")
    
    // Convert UTC instant to Eastern zoned time (handles DST automatically)
    val easternZoned = utcInstant.atZone(easternZone)
    
    // Get the local date/time components in Eastern timezone
    val easternLocal = easternZoned.toLocalDateTime
    
    // Convert the Eastern local time back to epoch millis as if it were UTC
    // This gives us the "wall clock time" in milliseconds, matching ParquetSource behavior
    easternLocal.atZone(ZoneId.of("UTC")).toInstant.toEpochMilli
  }
  
  /**
   * Parse a single JSON object to a Candle
   */
  private def parseJsonObject(jsonObj: String): Option[Candle] = {
    try {
      // Extract values using simple regex patterns
      val timestampPattern = """"t":(\d+)""".r
      val openPattern = """"o":([0-9.]+)""".r
      val highPattern = """"h":([0-9.]+)""".r
      val lowPattern = """"l":([0-9.]+)""".r
      val closePattern = """"c":([0-9.]+)""".r
      
      for {
        utcTimestamp <- timestampPattern.findFirstMatchIn(jsonObj).map(_.group(1).toLong)
        open <- openPattern.findFirstMatchIn(jsonObj).map(_.group(1).toFloat)
        high <- highPattern.findFirstMatchIn(jsonObj).map(_.group(1).toFloat)
        low <- lowPattern.findFirstMatchIn(jsonObj).map(_.group(1).toFloat)
        close <- closePattern.findFirstMatchIn(jsonObj).map(_.group(1).toFloat)
      } yield {
        // Convert UTC timestamp to Eastern time to match ParquetSource behavior
        val easternTimestamp = convertUtcToEasternTime(utcTimestamp)
        
        Candle(
          open = Level(open),
          high = Level(high),
          low = Level(low),
          close = Level(close),
          timestamp = easternTimestamp,
          duration = duration
        )
      }
    } catch {
      case _: Exception => None
    }
  }
}