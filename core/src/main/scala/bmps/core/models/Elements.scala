package bmps.core.models
import bmps.core.models.OrderStatus.Planned
import bmps.core.models.OrderStatus.Placed
import java.security.KeyStore.Entry
import java.time.ZoneId
import java.time.Instant
import java.time.Duration
import java.time.LocalTime

case class Level(value: Float) {
    def <(other: Level): Boolean = this.value < other.value
    def <=(other: Level): Boolean = this.value <= other.value
    def >(other: Level): Boolean = this.value > other.value
    def >=(other: Level): Boolean = this.value >= other.value
    def ==(other: Level): Boolean = this.value == other.value
    def !=(other: Level): Boolean = this.value != other.value

    def +(other: Level): Level = Level(this.value + other.value)
    def -(other: Level): Level = Level(this.value - other.value)
}

case class Line(level: Float, startTime: Long, endTime: Option[Long])

case class Range(low: Level, high: Level)

case class Zone(low: Level, high: Level, startTime: Long, endTime: Option[Long])

sealed trait CandleDuration
object CandleDuration {
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
    open: Level,
    high: Level,
    low: Level,
    close: Level,
    timestamp: Long,
    duration: CandleDuration
) {
    final val DojiThreshold = 0.25
    lazy val isBullish: Boolean = (close.value - DojiThreshold) > open.value
    lazy val isBearish: Boolean = (close.value + DojiThreshold) < open.value
    lazy val isDoji: Boolean = !isBearish && !isBearish
    lazy val direction: Direction = {
        if (isBullish) Direction.Up
        else if (isBearish) Direction.Down
        else Direction.Doji
    }

    lazy val bodyHeight = if (isBullish) close - open else open - close

    def engulfs(other: Candle): Boolean = bodyHeight > other.bodyHeight
    def isOpposite(other: Candle): Boolean = isBullish && other.isBearish || isBearish && other.isBullish

    lazy val isEndOfDay: Boolean = {
        val zone = ZoneId.of("UTC") //Because the candles are offset to NY Time already.
        val localDate = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate
        val closingZdt = localDate.atTime(LocalTime.of(16, 0)).atZone(zone)
        val closingMillis = closingZdt.toInstant.toEpochMilli
        val tenMinutesMillis = Duration.ofMinutes(10).toMillis

        closingMillis - timestamp <= tenMinutesMillis
    }
}

case class SwingPoint(
    level: Level,
    direction: Direction,
    timestamp: Long
)

sealed trait PlanZoneType
object PlanZoneType {
    case object Supply extends PlanZoneType
    case object Demand extends PlanZoneType
}

case class PlanZone(planZoneType: PlanZoneType, low: Level, high: Level, startTime: Long, endTime: Option[Long] = None) {
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
        val newLow = Seq(this.low.value, other.low.value).min
        val newHigh = Seq(this.high.value, other.high.value).max

        val laterStart = Seq(this.startTime, other.startTime).max
        val merged = PlanZone(planZoneType, Level(newLow), Level(newHigh), laterStart)

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
            val thisLow = this.low.value
            val thisHigh = this.high.value
            val otherLow = other.low.value
            val otherHigh = other.high.value
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

case class DaytimeExtreme(level: Level, extremeType: ExtremeType, timestamp: Long, endTime: Option[Long], description: String)
