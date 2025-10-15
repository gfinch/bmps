package bmps.core.utils

import java.time.{LocalDate, DayOfWeek}
import java.time.ZoneId

/**
 * Utility for determining if a date is a valid US market trading day
 * Handles weekends and major US market holidays
 */
object MarketCalendar {
  
  /**
    * Check if today is a trading day.
    */
  def isTodayTradingDay(): Boolean = {
     val today = LocalDate.now(ZoneId.of("America/New_York"))
     isTradingDay(today)
  }
  
  /**
   * Check if a date is a trading day (not weekend, not holiday)
   */
  def isTradingDay(date: LocalDate): Boolean = {
    !isWeekend(date) && !isMarketHoliday(date)
  }
  
  /**
   * Check if a date falls on a weekend
   */
  def isWeekend(date: LocalDate): Boolean = {
    val dayOfWeek = date.getDayOfWeek
    dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY
  }
  
  /**
   * Check if a date is a US market holiday
   * Includes major holidays when US markets are closed
   */
  def isMarketHoliday(date: LocalDate): Boolean = {
    val year = date.getYear
    val holidays = getMarketHolidays(year)
    holidays.contains(date)
  }

  /**
    * Check if a date is an early close day
    * Markets close early (typically 1pm ET) on the day before Independence Day, Thanksgiving, and Christmas
    * Only applies if the early close day is a weekday
    */
  def isEarlyCloseDay(date: LocalDate): Boolean = {
     if (isWeekend(date)) return false
     
     val year = date.getYear
     val earlyCloseDays = getEarlyCloseDays(year)
     earlyCloseDays.contains(date)
  }
  
  /**
   * Get the date that is N trading days before the given date
   * @param date The reference date
   * @param tradingDays Number of trading days to go back
   * @return The date N trading days before
   */
  def getTradingDaysBack(date: LocalDate, tradingDays: Int): LocalDate = {
    var currentDate = date.minusDays(1) // Start from the day before
    var count = 0
    
    while (count < tradingDays) {
      if (isTradingDay(currentDate)) {
        count += 1
      }
      if (count < tradingDays) {
        currentDate = currentDate.minusDays(1)
      }
    }
    
    currentDate
  }
  
  /**
   * Generate list of US market holidays for a given year
   * Includes: New Year's Day, MLK Day, Presidents Day, Good Friday,
   * Memorial Day, Independence Day, Labor Day, Thanksgiving, Christmas
   */
  private def getMarketHolidays(year: Int): Set[LocalDate] = {
    Set(
      observedHoliday(LocalDate.of(year, 1, 1)),   // New Year's Day
      nthWeekdayOfMonth(year, 1, DayOfWeek.MONDAY, 3), // MLK Day (3rd Monday in January)
      nthWeekdayOfMonth(year, 2, DayOfWeek.MONDAY, 3), // Presidents Day (3rd Monday in February)
      getGoodFriday(year),                          // Good Friday
      lastWeekdayOfMonth(year, 5, DayOfWeek.MONDAY), // Memorial Day (last Monday in May)
      observedHoliday(LocalDate.of(year, 6, 19)), //Juneteenth national independence day
      observedHoliday(LocalDate.of(year, 7, 4)),   // Independence Day
      nthWeekdayOfMonth(year, 9, DayOfWeek.MONDAY, 1), // Labor Day (1st Monday in September)
      nthWeekdayOfMonth(year, 11, DayOfWeek.THURSDAY, 4), // Thanksgiving (4th Thursday in November)
      observedHoliday(LocalDate.of(year, 12, 25))  // Christmas
    )
  }
  
  /**
    * Generate list of early close days for a given year
    * Day before Independence Day, Thanksgiving, and Christmas (if weekday)
    */
  private def getEarlyCloseDays(year: Int): Set[LocalDate] = {
     val independenceDay = LocalDate.of(year, 7, 4)
     val thanksgiving = nthWeekdayOfMonth(year, 11, DayOfWeek.THURSDAY, 4)
     val christmas = LocalDate.of(year, 12, 25)
     
     Set(
        independenceDay.minusDays(1),
        thanksgiving.minusDays(1),
        christmas.minusDays(1)
     ).filterNot(isWeekend)
  }
  
  /**
   * If a holiday falls on a weekend, return the observed date (typically Monday)
   */
  private def observedHoliday(date: LocalDate): LocalDate = {
    date.getDayOfWeek match {
      case DayOfWeek.SATURDAY => date.plusDays(2) // Observed on Monday
      case DayOfWeek.SUNDAY => date.plusDays(1)   // Observed on Monday
      case _ => date
    }
  }
  
  /**
   * Get the nth occurrence of a weekday in a given month
   * E.g., 3rd Monday in January
   */
  private def nthWeekdayOfMonth(year: Int, month: Int, dayOfWeek: DayOfWeek, n: Int): LocalDate = {
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
   * Get the last occurrence of a weekday in a given month
   * E.g., last Monday in May
   */
  private def lastWeekdayOfMonth(year: Int, month: Int, dayOfWeek: DayOfWeek): LocalDate = {
    var date = LocalDate.of(year, month, 1).plusMonths(1).minusDays(1) // Last day of month
    
    while (date.getDayOfWeek != dayOfWeek) {
      date = date.minusDays(1)
    }
    
    date
  }
  
  /**
   * Calculate Good Friday for a given year using Easter calculation
   * Uses Computus algorithm (Anonymous Gregorian algorithm)
   */
  private def getGoodFriday(year: Int): LocalDate = {
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
    
    // Easter Sunday
    val easter = LocalDate.of(year, month, day)
    // Good Friday is 2 days before Easter
    easter.minusDays(2)
  }
}
