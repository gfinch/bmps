package bmps.core.phases

import java.time.format.DateTimeFormatter
import java.time.LocalDate

trait TradingDate {
    protected def parseTradingDate(tradeDate: String): LocalDate = {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE
        LocalDate.parse(tradeDate, formatter)
    }

    protected def isInTradingDate(tradeDate: LocalDate, timestamp: Long): Boolean = {
        val zoneId = java.time.ZoneId.of("America/New_York")
        val instant = java.time.Instant.ofEpochMilli(timestamp)
        instant.atZone(zoneId).toLocalDate == tradeDate
    }
}
