package bmps.core.models
import bmps.core.models.OrderStatus.Planned
import bmps.core.models.OrderStatus.Placed
import java.security.KeyStore.Entry

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

sealed trait OrderStatus
object OrderStatus {
    case object Planned extends OrderStatus
    case object Placed extends OrderStatus
    case object Filled extends OrderStatus
    case object Profit extends OrderStatus
    case object Loss extends OrderStatus
    case object Cancelled extends OrderStatus
}

sealed trait OrderType
object OrderType {
    case object Long extends OrderType
    case object Short extends OrderType
}

sealed trait EntryType
object EntryType {
    case object EngulfingOrderBlock extends EntryType
}

case class Order(low: Level, 
                     high: Level, 
                     timestamp: Long, 
                     orderType: OrderType,
                     entryType: EntryType, 
                     status: OrderStatus = Planned,
                     profitMultiplier: Double = 2.0,
                     riskDollars: Double = 550.0,
                     placedTimestamp: Option[Long] = None,
                     filledTimestamp: Option[Long] = None,
                     closeTimestamp: Option[Long] = None) {

    import OrderType._
    import OrderStatus._

    require(high > low, "High and Low of an order zone cannot be equal or inverted.")

    final val DollarsPerMicro = 5.0 //Five dollars per point on micros (MES)
    final val MaxContracts = 50

    lazy val entryPoint = orderType match {
        case Long => high.value
        case Short => low.value
    }

    lazy val stopLoss = orderType match {
        case Long => low.value
        case Short => high.value
    }

    lazy val atRiskPoints = (high.value - low.value)
    lazy val profitPoints = atRiskPoints * profitMultiplier

    lazy val atRiskPerContract = atRiskPoints * DollarsPerMicro 
    lazy val potentialPerContract = profitPoints * DollarsPerMicro

    lazy val takeProfit = orderType match {
        case Long => high.value + profitPoints
        case Short => low.value - profitPoints
    }

    lazy val contracts: Int = Math.floor(riskDollars / atRiskPerContract).toInt
    lazy val atRisk = atRiskPerContract * contracts
    lazy val potential = potentialPerContract * contracts
    lazy val isViable = contracts <= MaxContracts
    lazy val isActive = (status == OrderStatus.Placed || status == OrderStatus.Filled)
    lazy val direction: Direction = if (orderType == OrderType.Long) Direction.Up else Direction.Down

    def adjustState(candle: Candle): Order = {
        (status, orderType)  match {
            case (Planned, Long) if candle.high.value >= takeProfit || candle.low.value <= stopLoss => 
                this.copy(status = Cancelled, closeTimestamp = Some(candle.timestamp))
            case (Planned, Short) if candle.low.value <= takeProfit || candle.high.value >= stopLoss =>
                this.copy(status = Cancelled, closeTimestamp = Some(candle.timestamp))
            case (Planned, _) => this

            case (Placed, Long) if candle.low.value <= stopLoss && candle.high.value >= takeProfit && candle.isBullish =>
                this.copy(status = Loss, filledTimestamp = Some(candle.timestamp), closeTimestamp = Some(candle.timestamp))
            case (Placed, Long) if candle.low.value <= stopLoss && candle.high.value >= takeProfit && candle.isBearish =>
                this.copy(status = Profit, filledTimestamp = Some(candle.timestamp), closeTimestamp = Some(candle.timestamp))
            case (Placed, Long) if candle.low.value <= entryPoint && candle.high.value >= takeProfit => 
                this.copy(status = Profit, filledTimestamp = Some(candle.timestamp), closeTimestamp = Some(candle.timestamp))
            case (Placed, Long) if candle.high.value >= takeProfit =>
                this.copy(status = Cancelled, closeTimestamp = Some(candle.timestamp))
            case (Placed, Long) if candle.low.value <= stopLoss => 
                this.copy(status = Loss, filledTimestamp = Some(candle.timestamp), closeTimestamp = Some(candle.timestamp))
            case (Placed, Long) if candle.low.value <= entryPoint => 
                this.copy(status = Filled, filledTimestamp = Some(candle.timestamp))

            case (Placed, Short) if candle.high.value >= stopLoss && candle.low.value <= takeProfit && candle.isBearish =>
                println(timestamp + "..." + 1)
                this.copy(status = Loss, filledTimestamp = Some(candle.timestamp), closeTimestamp = Some(candle.timestamp))
            case (Placed, Short) if candle.high.value >= stopLoss && candle.low.value <= takeProfit && candle.isBullish =>
                println(timestamp + "..." + 2)
                this.copy(status = Profit, filledTimestamp = Some(candle.timestamp), closeTimestamp = Some(candle.timestamp))
            case (Placed, Short) if candle.high.value >= entryPoint && candle.low.value <= takeProfit =>
                println(timestamp + "..." + 3)
                println(this)
                println(candle)
                this.copy(status = Profit, filledTimestamp = Some(candle.timestamp), closeTimestamp = Some(candle.timestamp))
            case (Placed, Short) if candle.low.value <= takeProfit =>
                println(timestamp + "..." + 4)
                println(this)
                println(candle)
                this.copy(status = Cancelled, closeTimestamp = Some(candle.timestamp))
            case (Placed, Short) if candle.high.value >= stopLoss =>
                println(timestamp + "..." + 5)
                this.copy(status = Loss, filledTimestamp = Some(candle.timestamp), closeTimestamp = Some(candle.timestamp))
            case (Placed, Short) if candle.high.value >= entryPoint =>
                println(timestamp + "..." + 6)
                this.copy(status = Filled, filledTimestamp = Some(candle.timestamp))

            case (Filled, Long) if candle.high.value >= takeProfit && candle.low.value <= stopLoss && candle.isBullish =>
                this.copy(status = Loss, closeTimestamp = Some(candle.timestamp))
            case (Filled, Long) if candle.high.value >= takeProfit && candle.low.value <= stopLoss && candle.isBearish =>
                this.copy(status = Profit, closeTimestamp = Some(candle.timestamp))
            case (Filled, Long) if candle.high.value >= takeProfit =>
                this.copy(status = Profit, closeTimestamp = Some(candle.timestamp))
            case (Filled, Long) if candle.low.value <= stopLoss =>
                this.copy(status = Loss, closeTimestamp = Some(candle.timestamp))

            case (Filled, Short) if candle.low.value <= takeProfit && candle.high.value >= stopLoss && candle.isBearish =>
                this.copy(status = Loss, closeTimestamp = Some(candle.timestamp))
            case (Filled, Short) if candle.low.value <= takeProfit && candle.high.value >= stopLoss && candle.isBullish =>
                this.copy(status = Profit, closeTimestamp = Some(candle.timestamp))
            case (Filled, Short) if candle.low.value <= takeProfit =>
                this.copy(status = Profit, closeTimestamp = Some(candle.timestamp))
            case (Filled, Short) if candle.high.value >= stopLoss =>
                this.copy(status = Loss, closeTimestamp = Some(candle.timestamp))

            case _ => this
        }
    }

    def cancelOrSell(candle: Candle): Order = {
        import java.time.{Instant, LocalTime, ZoneId, Duration}

        val zone = ZoneId.of("America/New_York")
        val instant = Instant.ofEpochMilli(candle.timestamp)

        // Determine the local date in Eastern time for the candle's instant,
        // then build the 4:00 PM Eastern ZonedDateTime for that date.
        val localDate = instant.atZone(zone).toLocalDate
        val closingZdt = localDate.atTime(LocalTime.of(16, 0)).atZone(zone)
        val closingMillis = closingZdt.toInstant.toEpochMilli

        val tenMinutesMillis = Duration.ofMinutes(10).toMillis

        if (closeTimestamp.isEmpty && (closingMillis - candle.timestamp) <= tenMinutesMillis) {
          (status, orderType) match {
            case (Filled, Long) if candle.close.value >= entryPoint =>
                this.copy(status = Profit, closeTimestamp = Some(candle.timestamp))
            case (Filled, Long) if candle.close.value <= entryPoint =>
                this.copy(status = Loss, closeTimestamp = Some(candle.timestamp))
            case (Filled, Short) if candle.close.value <= entryPoint =>
                this.copy(status = Profit, closeTimestamp = Some(candle.timestamp))
            case (Filled, Short) if candle.close.value >= entryPoint =>
                this.copy(status = Loss, closeTimestamp = Some(candle.timestamp))
            case _ => 
                println(s"Closing: $closingMillis / ${candle.timestamp} / $tenMinutesMillis / ${closingMillis - candle.timestamp}")
                this.copy(status = Cancelled, closeTimestamp = Some(candle.timestamp))
          }
        } else this
    }
}

case class SerializableOrder(low: Level, 
                             high: Level, 
                             timestamp: Long, 
                             orderType: OrderType,
                             entryType: EntryType, 
                             status: OrderStatus,
                             profitMultiplier: Double,
                             riskDollars: Double,
                             placedTimestamp: Option[Long],
                             filledTimestamp: Option[Long],
                             closeTimestamp: Option[Long],
                             entryPoint: Double,
                             stopLoss: Double,
                             takeProfit: Double,
                             contracts: Int,
                             atRisk: Double,
                             potential: Double
                     )

object Order {
    def fromCandle(candle: Candle, orderType: OrderType, entryType: EntryType, timestamp: Long) = {
        Order(candle.low, candle.high, timestamp, orderType, entryType)
    }
}

object SerializableOrder {
    def fromOrder(order: Order): SerializableOrder =
      SerializableOrder(
        low = order.low,
        high = order.high,
        timestamp = order.timestamp,
        orderType = order.orderType,
        entryType = order.entryType,
        status = order.status,
        profitMultiplier = order.profitMultiplier,
        riskDollars = order.riskDollars,
        placedTimestamp = order.placedTimestamp,
        filledTimestamp = order.filledTimestamp,
        closeTimestamp = order.closeTimestamp,
        entryPoint = order.entryPoint.toDouble,
        stopLoss = order.stopLoss.toDouble,
        takeProfit = order.takeProfit.toDouble,
        contracts = order.contracts,
        atRisk = order.atRisk,
        potential = order.potential
      )
}