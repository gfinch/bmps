
/* DISABLED api_old
package bmps.core.api.util

import java.time.LocalDate
import java.time.format.DateTimeParseException

object DateUtils {
  /** Compute previous trading dates (skips weekends). Returns empty set if parse fails. */
  def computePrevTradingDates(dateStr: String, days: Int): Set[LocalDate] = {
    try {
      val dt = LocalDate.parse(dateStr)
      val buf = scala.collection.mutable.ListBuffer.empty[LocalDate]
      var d = dt.minusDays(1)
      while (buf.size < days) {
        val dow = d.getDayOfWeek
        if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) buf += d
        d = d.minusDays(1)
      }
      buf.toList.toSet
    } catch { case _: DateTimeParseException => Set.empty[LocalDate] }
  }
}

*/


