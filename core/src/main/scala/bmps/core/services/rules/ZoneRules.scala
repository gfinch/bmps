package bmps.core.services.rules

import bmps.core.models.Candle
import bmps.core.models.SystemState
import bmps.core.models.EntryType.Trendy
import bmps.core.models.Order
import java.time.Duration
import bmps.core.models.ExtremeType

object ZoneId {
    val NewLow = 0
    val Long = 1
    val MidLow = 2
    val MidHigh = 3
    val Short = 4
    val NewHigh = 5
}

object Zones {
    def fromState(state: SystemState): Zones = {
        val init = state.tradingCandles.init
        val high = init.map(_.high).max
        val low = init.map(_.low).min
        
        Zones(high, low)
    }
}

case class Zones(high: Float, low: Float) {
    
    val spread = high - low
    val seventyFive = high - (spread * 0.3)
    val twentyFive = low + (spread * 0.3)
    val fifty = low + (spread * 0.5)

    def newHigh(candle: Candle): Boolean = candle.close > high
    def short(candle: Candle): Boolean = candle.close > seventyFive && candle.close <= high
    def midHigh(candle: Candle): Boolean = candle.close > fifty && candle.close <= seventyFive
    def midLow(candle: Candle): Boolean = candle.close > twentyFive && candle.close <= fifty
    def long(candle: Candle): Boolean = candle.close > low && candle.close <= twentyFive
    def newLow(candle: Candle): Boolean = candle.close <= low

    def zoneId(candle: Candle): Int = {
        if (newLow(candle)) ZoneId.NewLow
        else if (long(candle)) ZoneId.Long
        else if (midLow(candle)) ZoneId.MidLow
        else if (midHigh(candle)) ZoneId.MidHigh
        else if (short(candle)) ZoneId.Short
        else if (newHigh(candle)) ZoneId.NewHigh
        else throw new IllegalStateException("Unexpected state - no zone found")
    }
}

trait ZoneRules extends CalcAtrs {
    def lastOrderFromZone(state: SystemState, zoneId: Int): Boolean = {
        val now = state.tradingCandles.last.timestamp
        state.orders.filter(o => inTheLastHour(o, now)).lastOption.exists { order => 
            order.entryType match {
                case Trendy(description) => description.contains(s"ZoneId:$zoneId")
                case _ => false
            }
        }
    }

    def inTheLastHour(order: Order, now: Long): Boolean = {
        now - order.timestamp < Duration.ofHours(1).toMillis()
    }

    def nearingSummit(state: SystemState, useAtrs: Double, profitMultiplier: Double = 2.0): Boolean = {
        val lastClose = state.tradingCandles.last.close
        val someAtrs = calcAtrs(state, useAtrs) * profitMultiplier
        val peak = peakRed(state)
        lastClose < peak && (lastClose + someAtrs) > peak
    }
    
    def nearingFloor(state: SystemState, useAtrs: Double, profitMultiplier: Double = 2.0): Boolean = {
        val lastClose = state.tradingCandles.last.close
        val someAtrs = calcAtrs(state, useAtrs) * profitMultiplier
        val peakGreen = lowGreen(state)
        lastClose > peakGreen && (lastClose - someAtrs) < peakGreen
    }

    def peakRed(state: SystemState): Double = {
        val highestRed = state.daytimeExtremes.filter(_.extremeType == ExtremeType.High).map(_.level.toDouble).reduceOption(_ max _).getOrElse(Double.MinValue)
        val highestToday = state.tradingCandles.map(_.high.toDouble).reduceOption(_ max _).getOrElse(Double.MinValue)
        math.max(highestRed, highestToday)
    }

    def lowGreen(state: SystemState): Double = {
        val lowestGreen = state.daytimeExtremes.filter(_.extremeType == ExtremeType.Low).map(_.level.toDouble).reduceOption(_ min _).getOrElse(Double.MaxValue)
        val lowestToday = state.tradingCandles.map(_.low.toDouble).reduceOption(_ min _).getOrElse(Double.MaxValue)
        math.min(lowestGreen, lowestToday)
    }

    def peakRedHours(state: SystemState, hours: Int): Double = {
        state.tradingCandles
            .filter(_.timestamp >= state.tradingCandles.last.timestamp - Duration.ofHours(hours).toMillis()) //Max from last three hours
            .map(_.high.toDouble).reduceOption(_ max _).getOrElse(Double.MinValue)
    }

    def lowGreenHours(state: SystemState, hours: Int): Double = {
        state.tradingCandles
            .filter(_.timestamp >= state.tradingCandles.last.timestamp - Duration.ofHours(hours).toMillis()) //Min from last three hours
            .map(_.low.toDouble).reduceOption(_ min _).getOrElse(Double.MaxValue)
    }

    def zonesFromState(state: SystemState): Zones = {
        val init = state.tradingCandles.init
        val high = init.map(_.high).max
        val low = init.map(_.low).min
        
        Zones(high, low)
    }
}
