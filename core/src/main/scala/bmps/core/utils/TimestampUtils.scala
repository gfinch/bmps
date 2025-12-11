package bmps.core.utils

import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.Duration

object TimestampUtils {
    final lazy val NewYorkZone = ZoneId.of("America/New_York")
    final lazy val LondonZone = ZoneId.of("Europe/London")
    final lazy val TokyoZone = ZoneId.of("Asia/Tokyo")
    final lazy val TenMinutes = Duration.ofMinutes(10).toMillis()
    final lazy val TwoMinutes = Duration.ofMinutes(2).toMillis()

    def nanosToMillis(nanos: Long): Long = nanos / 1_000_000L
    def millisToNanos(millis: Long): Long = millis * 1_000_000L

    def toNewYorkLocalDate(timestamp: Long): LocalDate = {
        Instant.ofEpochMilli(timestamp).atZone(NewYorkZone).toLocalDate
    }

    def toNewYorkLocalDate(date: String): LocalDate = {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE
        LocalDate.parse(date, formatter)
    }

    def newYorkOffset(date: LocalDate): Long = {
        date.atStartOfDay(NewYorkZone).getOffset.getTotalSeconds * 1000L
    }

    def today(): LocalDate = LocalDate.now(NewYorkZone)

    def midnight(localDate: LocalDate) = {
        localDate.atStartOfDay(NewYorkZone).toInstant.toEpochMilli
    }

    def sevenThirty(localDate: LocalDate) = {
        localDate.atTime(7, 30).atZone(NewYorkZone).toInstant().toEpochMilli()
    }

    def nineAM(localDate: LocalDate) = {
        localDate.atTime(9, 0).atZone(NewYorkZone).toInstant().toEpochMilli()
    }

    def newYorkOpen(localDate: LocalDate) = {
        localDate.atTime(9, 30).atZone(NewYorkZone).toInstant.toEpochMilli
    }

    def newYorkClose(localDate: LocalDate) = {
        localDate.atTime(16, 0).atZone(NewYorkZone).toInstant.toEpochMilli
    }

    def newYorkVeryUncertain(localDate: LocalDate) = { //After volatile opening - 9:35 am
        localDate.atTime(9, 35).atZone(NewYorkZone).toInstant().toEpochMilli
    }

    def newYorkQuiet(localDate: LocalDate) = { //After volatile opening - 10:00 am
        localDate.atTime(10, 0).atZone(NewYorkZone).toInstant().toEpochMilli
    }

    def londonOpen(localDate: LocalDate) = {
        localDate.atTime(8, 0).atZone(LondonZone).toInstant.toEpochMilli
    }

    def londonClose(localDate: LocalDate) = {
        localDate.atTime(16, 30).atZone(LondonZone).toInstant.toEpochMilli
    }

    def asiaOpen(localDate: LocalDate) = {
        localDate.atTime(9, 0).atZone(TokyoZone).toInstant.toEpochMilli
    }

    def asiaClose(localDate: LocalDate) = {
        localDate.atTime(15, 0).atZone(TokyoZone).toInstant.toEpochMilli
    }

    def isInTradingDate(tradingDate: LocalDate, timestamp: Long): Boolean = {
        val instant = java.time.Instant.ofEpochMilli(timestamp)
        instant.atZone(NewYorkZone).toLocalDate == tradingDate
    }

    def isInMarketOpen(tradingDate: LocalDate, timestamp: Long): Boolean = {
        newYorkOpen(tradingDate) <= timestamp && timestamp <= newYorkClose(tradingDate)
    }

    def isInPriorMarketOpen(tradingDate: LocalDate, timestamp: Long): Boolean = {
        val priorMarketDay = MarketCalendar.getTradingDaysBack(tradingDate, 1)
        isInMarketOpen(priorMarketDay, timestamp)
    }

    def isInLondonOpen(tradingDate: LocalDate, timestamp: Long): Boolean = {
        londonOpen(tradingDate) <= timestamp && timestamp <= londonClose(tradingDate)
    }

    def isInAsiaOpen(tradingDate: LocalDate, timestamp: Long): Boolean = {
        asiaOpen(tradingDate) <= timestamp && timestamp <= asiaClose(tradingDate)
    }

    def isNearTradingClose(timestamp: Long): Boolean = {
        val tradingDate = toNewYorkLocalDate(timestamp)
        timestamp >= newYorkClose(tradingDate) - TwoMinutes
    }

    def isInEarlyOpen(timestamp: Long): Boolean = {
        val tradingDate = toNewYorkLocalDate(timestamp)
        timestamp <= newYorkVeryUncertain(tradingDate)
    }

    def isInQuiet(timestamp: Long): Boolean = {
        val tradingDate = toNewYorkLocalDate(timestamp)
        timestamp > newYorkQuiet(tradingDate)
    }

    def toNewYorkTimeString(timestamp: Long): String = {
        val instant = Instant.ofEpochMilli(timestamp)
        val zonedDateTime = instant.atZone(NewYorkZone)
        zonedDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}
