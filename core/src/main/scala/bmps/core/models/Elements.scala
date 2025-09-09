package bmps.core.models

case class Level(value: Float)

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

case class Candle(
    open: Level,
    high: Level,
    low: Level,
    close: Level,
    timestamp: Long,
    duration: CandleDuration
)

sealed trait Direction
object Direction {
    case object Up extends Direction
    case object Down extends Direction
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

case class PlanZone(low: Level, high: Level, startTime: Long, endTime: Option[Long], planZoneType: PlanZoneType)

sealed trait MaxType
object MaxType {
    case object High extends MaxType
    case object Low extends MaxType
}

case class DaytimeMax(level: Level, maxType: MaxType, description: String)

sealed trait OrderStatus
object OrderStatus {
    case object Planned extends OrderStatus
    case object Placed extends OrderStatus
    case object Filled extends OrderStatus
    case object Profit extends OrderStatus
    case object Loss extends OrderStatus
    case object Cancelled extends OrderStatus
}

sealed trait OrderZoneType

case class OrderZone(low: Double, high: Double, 
                     timestamp: Long, 
                     zoneType: OrderZoneType, 
                     status: OrderStatus,
                     placedTimestamp: Option[Long] = None,
                     closeTimestamp: Option[Long] = None)