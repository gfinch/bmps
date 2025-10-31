package bmps.console

import cats.effect.{IO, IOApp, ExitCode}
import okhttp3._
import io.circe.parser._
import io.circe.generic.auto._
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import scala.concurrent.duration._

/**
 * Console client for running BMPS trading phases across a date range.
 * 
 * Usage: ConsoleClient <start-date> <end-date>
 * Example: ConsoleClient 2025-09-01 2025-09-20
 * 
 * The client will:
 * 1. Iterate through each date in the range
 * 2. Filter for trading days (not weekends, holidays, or early close days)
 * 3. For each trading day:
 *    - Trigger planning phase
 *    - Poll until planning completes
 *    - Wait for preparing phase to complete (auto-triggered)
 *    - Wait for trading phase to complete (auto-triggered)
 * 4. Continue to the next trading day
 * 5. Exit when all days are processed
 */
object ConsoleClient extends IOApp {

  private val API_URL = "http://localhost:8081"
  // private val API_URL = "https://bmps.misfortunesheir.com:444"
  private val client = new OkHttpClient()
  private val formatter = DateTimeFormatter.ISO_LOCAL_DATE

  // Response models matching the REST API
  case class StartPhaseResponse(message: String, phase: String)
  case class PhaseEventsResponse(events: List[io.circe.Json], isComplete: Boolean)
  case class ErrorResponse(error: String)

  override def run(args: List[String]): IO[ExitCode] = {
    if (args.length != 2) {
      IO.println("Usage: ConsoleClient <start-date> <end-date>") *>
      IO.println("Example: ConsoleClient 2025-09-01 2025-09-20") *>
      IO.pure(ExitCode.Error)
    } else {
      parseAndRun(args(0), args(1)).as(ExitCode.Success).handleErrorWith { err =>
        IO.println(s"Error: ${err.getMessage}") *>
        IO.pure(ExitCode.Error)
      }
    }
  }

  /**
   * Parse dates and run the trading workflow
   */
  private def parseAndRun(startStr: String, endStr: String): IO[Unit] = {
    for {
      startDate <- IO(LocalDate.parse(startStr, formatter))
      endDate <- IO(LocalDate.parse(endStr, formatter))
      _ <- IO.println(s"Processing trading days from $startDate to $endDate")
      _ <- IO.println("=" * 60)
      _ <- processDateRange(startDate, endDate)
      _ <- IO.println("=" * 60)
      _ <- IO.println("All trading days processed successfully!")
    } yield ()
  }

  /**
   * Process all trading days in the given date range
   */
  private def processDateRange(startDate: LocalDate, endDate: LocalDate): IO[Unit] = {
    val dates = generateDateRange(startDate, endDate)
    val tradingDays = dates.filter(isTradingDay)

    IO.println(s"Found ${tradingDays.length} trading days to process") *>
    tradingDays.zipWithIndex.foldLeft(IO.unit) { case (acc, (date, idx)) =>
      acc *> processTradingDay(date, idx + 1, tradingDays.length)
    }
  }

  /**
   * Process a single trading day through all phases
   */
  private def processTradingDay(date: LocalDate, current: Int, total: Int): IO[Unit] = {
    val dateStr = date.format(formatter)
    
    IO.println("") *>
    IO.println(s"[$current/$total] Processing $dateStr") *>
    IO.println("-" * 60) *>
    // Start and complete planning phase
    startPhase("planning", dateStr) *>
    pollUntilComplete("planning", dateStr) *>
    IO.println(s"  ✓ Planning phase completed") *>
    // Start and complete preparing phase
    startPhase("preparing", dateStr) *>
    pollUntilComplete("preparing", dateStr) *>
    IO.println(s"  ✓ Preparing phase completed") *>
    // Start and complete trading phase
    startPhase("trading", dateStr) *>
    pollUntilComplete("trading", dateStr) *>
    IO.println(s"  ✓ Trading phase completed") *>
    IO.println(s"✓ Completed $dateStr")
  }

  /**
   * Start a phase via REST API
   */
  private def startPhase(phase: String, tradingDate: String): IO[Unit] = {
    IO.println(s"  Starting $phase phase...") *>
    IO.blocking {
      val json = s"""{"phase":"$phase","tradingDate":"$tradingDate","options":{}}"""
      val body = RequestBody.create(json, MediaType.parse("application/json"))
      val request = new Request.Builder()
        .url(s"$API_URL/phase/start")
        .put(body)
        .build()

      val response = client.newCall(request).execute()
      try {
        if (!response.isSuccessful) {
          throw new RuntimeException(s"Failed to start $phase: ${response.code()} ${response.message()}")
        }
      } finally {
        response.close()
      }
    }
  }

  /**
   * Poll a phase until it completes
   */
  private def pollUntilComplete(phase: String, tradingDate: String, pollInterval: FiniteDuration = 1.second): IO[Unit] = {
    def poll(): IO[Boolean] = IO.blocking {
      val request = new Request.Builder()
        .url(s"$API_URL/phase/events?tradingDate=$tradingDate&phase=$phase")
        .get()
        .build()

      val response = client.newCall(request).execute()
      try {
        if (!response.isSuccessful) {
          throw new RuntimeException(s"Failed to poll $phase: ${response.code()} ${response.message()}")
        }
        val body = response.body().string()
        val json = parse(body).getOrElse(throw new RuntimeException(s"Failed to parse response: $body"))
        val eventsResponse = json.as[PhaseEventsResponse].getOrElse(
          throw new RuntimeException(s"Failed to decode response: $json")
        )
        eventsResponse.isComplete
      } finally {
        response.close()
      }
    }

    def pollLoop(attempt: Int): IO[Unit] = {
      poll().flatMap { isComplete =>
        if (isComplete) {
          IO.unit
        } else {
          if (attempt % 10 == 0) {
            IO.print(".") *> IO.sleep(pollInterval) *> pollLoop(attempt + 1)
          } else {
            IO.sleep(pollInterval) *> pollLoop(attempt + 1)
          }
        }
      }
    }

    IO.print(s"  Polling $phase phase") *> pollLoop(1) *> IO.println("")
  }

  /**
   * Generate a list of dates from start to end (inclusive)
   */
  private def generateDateRange(start: LocalDate, end: LocalDate): List[LocalDate] = {
    Iterator.iterate(start)(_.plusDays(1))
      .takeWhile(!_.isAfter(end))
      .toList
  }

  /**
   * Check if a date is a trading day (not weekend, not holiday, not early close day)
   * This is a simplified version - for production, use MarketCalendar from core
   */
  private def isTradingDay(date: LocalDate): Boolean = {
    val dayOfWeek = date.getDayOfWeek.getValue
    val isWeekend = dayOfWeek == 6 || dayOfWeek == 7 // Saturday or Sunday
    
    if (isWeekend) return false
    
    // Check for major holidays (simplified - in production use core.utils.MarketCalendar)
    val year = date.getYear
    val holidays = getMarketHolidays(year)
    val earlyCloseDays = getEarlyCloseDays(year)
    
    !holidays.contains(date) && !earlyCloseDays.contains(date)
  }

  /**
   * Get market holidays for a year (simplified version)
   * Note: This should match the logic in core/src/main/scala/bmps/core/utils/MarketCalendar.scala
   */
  private def getMarketHolidays(year: Int): Set[LocalDate] = {
    import java.time.DayOfWeek
    
    Set(
      observedHoliday(LocalDate.of(year, 1, 1)),   // New Year's Day
      nthWeekdayOfMonth(year, 1, DayOfWeek.MONDAY, 3), // MLK Day
      nthWeekdayOfMonth(year, 2, DayOfWeek.MONDAY, 3), // Presidents Day
      getGoodFriday(year),                          // Good Friday
      lastWeekdayOfMonth(year, 5, DayOfWeek.MONDAY), // Memorial Day
      observedHoliday(LocalDate.of(year, 6, 19)),  // Juneteenth
      observedHoliday(LocalDate.of(year, 7, 4)),   // Independence Day
      nthWeekdayOfMonth(year, 9, DayOfWeek.MONDAY, 1), // Labor Day
      nthWeekdayOfMonth(year, 11, DayOfWeek.THURSDAY, 4), // Thanksgiving
      observedHoliday(LocalDate.of(year, 12, 25))  // Christmas
    )
  }

  /**
   * Get early close days for a year
   */
  private def getEarlyCloseDays(year: Int): Set[LocalDate] = {
    import java.time.DayOfWeek
    
    val independenceDay = LocalDate.of(year, 7, 4)
    val thanksgiving = nthWeekdayOfMonth(year, 11, DayOfWeek.THURSDAY, 4)
    val christmas = LocalDate.of(year, 12, 25)
    
    Set(
      independenceDay.minusDays(1),
      thanksgiving.minusDays(1),
      christmas.minusDays(1)
    ).filterNot { date =>
      val dow = date.getDayOfWeek.getValue
      dow == 6 || dow == 7 // Remove weekends
    }
  }

  /**
   * If holiday falls on weekend, return observed date
   */
  private def observedHoliday(date: LocalDate): LocalDate = {
    import java.time.DayOfWeek
    date.getDayOfWeek match {
      case DayOfWeek.SATURDAY => date.plusDays(2)
      case DayOfWeek.SUNDAY => date.plusDays(1)
      case _ => date
    }
  }

  /**
   * Get nth occurrence of a weekday in a month
   */
  private def nthWeekdayOfMonth(year: Int, month: Int, dayOfWeek: java.time.DayOfWeek, n: Int): LocalDate = {
    var date = LocalDate.of(year, month, 1)
    var count = 0
    
    while (count < n) {
      if (date.getDayOfWeek == dayOfWeek) {
        count += 1
        if (count == n) return date
      }
      date = date.plusDays(1)
    }
    date
  }

  /**
   * Get last occurrence of a weekday in a month
   */
  private def lastWeekdayOfMonth(year: Int, month: Int, dayOfWeek: java.time.DayOfWeek): LocalDate = {
    var date = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1) // Last day of month
    
    while (date.getDayOfWeek != dayOfWeek) {
      date = date.minusDays(1)
    }
    date
  }

  /**
   * Calculate Good Friday for a given year (Easter - 2 days)
   */
  private def getGoodFriday(year: Int): LocalDate = {
    val easter = calculateEaster(year)
    easter.minusDays(2)
  }

  /**
   * Calculate Easter Sunday using Meeus's Julian algorithm
   */
  private def calculateEaster(year: Int): LocalDate = {
    val a = year % 19
    val b = year / 100
    val c = year % 100
    val d = b / 4
    val e = b % 4
    val f = (b + 8) / 25
    val g = (b - f + 1) / 3
    val h = (19 * a + b - d - g + 15) % 30
    val i = c / 4
    val k = c % 4
    val l = (32 + 2 * e + 2 * i - h - k) % 7
    val m = (a + 11 * h + 22 * l) / 451
    val month = (h + l - 7 * m + 114) / 31
    val day = ((h + l - 7 * m + 114) % 31) + 1
    LocalDate.of(year, month, day)
  }
}
