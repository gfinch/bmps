package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.models.Event
import bmps.core.models.DaytimeExtreme
import bmps.core.models.ExtremeType
import bmps.core.models.Candle

import java.time.{LocalDate, LocalTime, ZoneId, ZonedDateTime, Instant}
import bmps.core.utils.TimestampUtils
import bmps.core.utils.MarketCalendar
import bmps.core.models.Market
import bmps.core.models.Market._
import bmps.core.models.ExtremeType
import bmps.core.models.ExtremeType._

case class ExtremeKey(market: Market, extremeType: ExtremeType)

object LiquidityZoneService {

    private final val AllKeys = Seq(
        ExtremeKey(NewYork, High),
        ExtremeKey(NewYork, Low),
        ExtremeKey(Asia, High),
        ExtremeKey(Asia, Low),
        ExtremeKey(London, High),
        ExtremeKey(London, Low)
    )

    def processLiquidityZones(state: SystemState, lastCandle: Candle): (SystemState, List[Event]) = {
        val tradingDay = state.tradingDay
        val priorTradingDay = MarketCalendar.getTradingDaysBack(tradingDay, 1)

        val updatedState = AllKeys.foldLeft(state) { (rState, key) => 
            key match { 
                case ExtremeKey(NewYork, High) if TimestampUtils.isInMarketOpen(priorTradingDay, lastCandle.timestamp) =>
                    ensureAndUpdateHigh(rState, lastCandle, NewYork)
                case ExtremeKey(NewYork, Low) if TimestampUtils.isInMarketOpen(priorTradingDay, lastCandle.timestamp) =>
                    ensureAndUpdateLow(rState, lastCandle, NewYork)
                case ExtremeKey(Asia, High) if TimestampUtils.isInAsiaOpen(tradingDay, lastCandle.timestamp) =>
                    ensureAndUpdateHigh(rState, lastCandle, Asia)
                case ExtremeKey(Asia, Low) if TimestampUtils.isInAsiaOpen(tradingDay, lastCandle.timestamp) =>
                    ensureAndUpdateLow(rState, lastCandle, Asia)
                case ExtremeKey(London, High) if TimestampUtils.isInLondonOpen(tradingDay, lastCandle.timestamp) =>
                    ensureAndUpdateHigh(rState, lastCandle, London)
                case ExtremeKey(London, Low) if TimestampUtils.isInLondonOpen(tradingDay, lastCandle.timestamp) =>
                    ensureAndUpdateLow(rState, lastCandle, London)
                case _ => rState
            }
        }

        val events = {
            val changedExtremes = updatedState.daytimeExtremes.filter(e => !state.daytimeExtremes.contains(e))
            changedExtremes.map(Event.fromDaytimeExtreme)
        }

        (updatedState, events)
    }

    private def ensureAndUpdateHigh(state: SystemState, candle: Candle, market: Market): SystemState = {
        val newHigh = state.daytimeExtremes.find(e => e.extremeType == High && e.market == market).map { existingHigh =>
            if (candle.high > existingHigh.level) {
                DaytimeExtreme(candle.high, High, existingHigh.timestamp, None, market)
            } else existingHigh
        }.getOrElse(DaytimeExtreme(candle.high, High, candle.timestamp, None, market))

        val newExtremes = state.daytimeExtremes.filterNot(e => e.extremeType == High && e.market == market) :+ newHigh
        state.copy(daytimeExtremes = newExtremes)
    }

    private def ensureAndUpdateLow(state: SystemState, candle: Candle, market: Market): SystemState = {
        val newLow = state.daytimeExtremes.find(e => e.extremeType == Low && e.market == market).map { existingLow =>
            if (candle.low < existingLow.level) {
                DaytimeExtreme(candle.low, Low, existingLow.timestamp, None, market)
            } else existingLow
        }.getOrElse(DaytimeExtreme(candle.low, Low, candle.timestamp, None, market))

        val newExtremes = state.daytimeExtremes.filterNot(e => e.extremeType == Low && e.market == market) :+ newLow
        state.copy(daytimeExtremes = newExtremes)
    }
}