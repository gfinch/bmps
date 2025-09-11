package bmps.core

import bmps.core.models.Candle
import bmps.core.models.SwingPoint
import bmps.core.models.PlanZone
import bmps.core.models.DaytimeExtreme

sealed trait EventType
object EventType {
    case object Candle extends EventType
    case object SwingPoint extends EventType
    case object PlanZone extends EventType
    case object DaytimeExtreme extends EventType
}

case class Event(
    eventType: EventType, 
    timestamp: Long, 
    candle: Option[Candle] = None,
    swingPoint: Option[SwingPoint] = None,
    planZone: Option[PlanZone] = None,
    daytimeExtreme: Option[DaytimeExtreme] = None
)

object Event {
    def fromCandle(candle: Candle): Event = Event(EventType.Candle, candle.timestamp, candle = Some(candle))
    def fromSwingPoint(swingPoint: SwingPoint): Event = Event(EventType.SwingPoint, swingPoint.timestamp, swingPoint = Some(swingPoint))
    def fromPlanZone(planZone: PlanZone): Event = Event(EventType.PlanZone, planZone.startTime, planZone = Some(planZone))
    def fromDaytimeExtreme(daytimeExtreme: DaytimeExtreme): Event = Event(EventType.DaytimeExtreme, daytimeExtreme.timestamp, daytimeExtreme = Some(daytimeExtreme))
}
