package bmps.core.models
import bmps.core.models.OrderStatus.Planned
import bmps.core.models.OrderStatus.Placed
import java.security.KeyStore.Entry
import java.time.ZoneId
import java.time.Instant
import java.time.Duration
import java.time.LocalTime
import bmps.core.utils.TimestampUtils
import bmps.core.models.CandleDuration.OneSecond

case class Line(Float: Float, startTime: Long, endTime: Option[Long])

case class Range(low: Float, high: Float)

case class Zone(low: Float, high: Float, startTime: Long, endTime: Option[Long])

sealed trait CandleDuration
object CandleDuration {
    case object OneSecond extends CandleDuration
    case object OneMinute extends CandleDuration
    case object TwoMinute extends CandleDuration
    case object FiveMinute extends CandleDuration
    case object FifteenMinute extends CandleDuration
    case object ThirtyMinute extends CandleDuration
    case object OneHour extends CandleDuration
    case object OneDay extends CandleDuration
}

sealed trait Direction
object Direction {
    case object Up extends Direction
    case object Down extends Direction
    case object Doji extends Direction
}

case class Candle(
    open: Float,
    high: Float,
    low: Float,
    close: Float,
    volume: Long,
    timestamp: Long,
    duration: CandleDuration,
    createdAt: Long
) {
    final val DojiThreshold = 0.25
    lazy val durationMillis = duration match {
        case OneSecond => 1000
        case CandleDuration.OneMinute => 60 * 1000
        case CandleDuration.TwoMinute => 2 * 60 * 1000
        case CandleDuration.FiveMinute => 5 * 60 * 1000
        case CandleDuration.FifteenMinute => 15 * 60 * 1000
        case CandleDuration.ThirtyMinute => 30 * 60 * 1000
        case CandleDuration.OneHour => 60 * 60 * 1000
        case CandleDuration.OneDay => 24 * 60 * 60 * 1000
    }

    lazy val isLive: Boolean = {
        createdAt - (timestamp + durationMillis) <= durationMillis
    }

    lazy val isBullish: Boolean = (close - DojiThreshold) > open
    lazy val isBearish: Boolean = (close + DojiThreshold) < open
    lazy val isDoji: Boolean = !isBearish && !isBearish
    lazy val direction: Direction = {
        if (isBullish) Direction.Up
        else if (isBearish) Direction.Down
        else Direction.Doji
    }

    lazy val bodyHeight = if (isBullish) close - open else open - close

    def engulfs(other: Candle): Boolean = bodyHeight > other.bodyHeight
    def isOpposite(other: Candle): Boolean = isBullish && other.isBearish || isBearish && other.isBullish
    def mergeWithPrevious(previous: Candle): Candle = {
        require(duration == CandleDuration.OneMinute, "Can only merge one minute candles")
        Candle(
            previous.open,
            Seq(previous.high, high).max,
            Seq(previous.low, low).min,
            close,
            previous.volume + volume,
            previous.timestamp,
            CandleDuration.TwoMinute,
            Seq(previous.createdAt, createdAt).max
        )
    }
}

case class SwingPoint(
    level: Float,
    direction: Direction,
    timestamp: Long
)

sealed trait PlanZoneType
object PlanZoneType {
    case object Supply extends PlanZoneType
    case object Demand extends PlanZoneType
}

case class PlanZone(planZoneType: PlanZoneType, low: Float, high: Float, startTime: Long, endTime: Option[Long] = None) {
    def closedOut(candle: Candle): PlanZone = {
        if (endTime.isDefined) this else {
            planZoneType match {
                case PlanZoneType.Supply if candle.close > this.high =>
                    this.copy(endTime = Some(candle.timestamp))
                case PlanZoneType.Demand if candle.close < this.low =>
                    this.copy(endTime = Some(candle.timestamp))
                case _ => this
            }
        }
    }

    def mergeWith(other: PlanZone): (PlanZone, PlanZone) = {
        val newLow = Seq(this.low, other.low).min
        val newHigh = Seq(this.high, other.high).max

        val laterStart = Seq(this.startTime, other.startTime).max
        val merged = PlanZone(planZoneType, newLow, newHigh, laterStart)

        val closedOlder = if (this.startTime < other.startTime) this.copy(endTime = Some(laterStart))
                                                else other.copy(endTime = Some(laterStart))

        (merged, closedOlder)
    }

    def engulfs(other: PlanZone): Boolean = {
        if (planZoneType == other.planZoneType && endTime.isEmpty && other.endTime.isEmpty) {
            if (high >= other.high && low <= other.low) true else false
        } else false
    }

    def overlaps(other: PlanZone): Boolean = {
        if (planZoneType == other.planZoneType && endTime.isEmpty && other.endTime.isEmpty) {
            val thisLow = this.low
            val thisHigh = this.high
            val otherLow = other.low
            val otherHigh = other.high
            if ((otherLow < thisHigh && otherHigh > thisLow)) true else false
        } else false
    }

    lazy val isActive = endTime.isEmpty
}

object PlanZone {
    def apply(low: SwingPoint, high: SwingPoint, zoneType: PlanZoneType): PlanZone = {
        val minTimestamp = Math.min(low.timestamp, high.timestamp)
        PlanZone(zoneType, low.level, high.level, minTimestamp)
    }
}

sealed trait ExtremeType
object ExtremeType {
    case object High extends ExtremeType
    case object Low extends ExtremeType
}

sealed trait Market
object Market {
    case object NewYork extends Market
    case object Asia extends Market
    case object London extends Market
}

case class DaytimeExtreme(level: Float, extremeType: ExtremeType, timestamp: Long, endTime: Option[Long], market: Market)

case class ModelPrediction(level: Float, timestamp: Long, horizon: String)