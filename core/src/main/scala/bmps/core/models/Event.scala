package bmps.core.models

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
    case object Order extends EventType
    case object TradingDirection extends EventType
    case object PhaseComplete extends EventType
    case object PhaseErrored extends EventType
    case object Reset extends EventType
    case object ModelPrediction extends EventType
    case object TechnicalAnalysis extends EventType
}

case class Event(
    eventType: EventType, 
    timestamp: Long,
    candle: Option[Candle] = None,
    swingPoint: Option[SwingPoint] = None,
    planZone: Option[PlanZone] = None,
    daytimeExtreme: Option[DaytimeExtreme] = None,
    order: Option[Order] = None,
    tradingDirection: Option[Direction] = None,
    techncialAnalysis: Option[TechnicalAnalysis] = None
)

object Event {
    def fromCandle(candle: Candle): Event = Event(EventType.Candle, candle.timestamp, candle = Some(candle))
    def fromSwingPoint(swingPoint: SwingPoint): Event = Event(EventType.SwingPoint, swingPoint.timestamp, swingPoint = Some(swingPoint))
    def fromPlanZone(planZone: PlanZone): Event = Event(EventType.PlanZone, planZone.startTime, planZone = Some(planZone))
    def fromDaytimeExtreme(daytimeExtreme: DaytimeExtreme): Event = Event(EventType.DaytimeExtreme, daytimeExtreme.timestamp, daytimeExtreme = Some(daytimeExtreme))
    def fromOrder(order: Order): Event = Event(EventType.Order, order.timestamp, order = Some(order))
    def fromTradeDirection(candle: Candle, tradingDirection: Option[Direction]) = Event(EventType.TradingDirection, candle.timestamp, tradingDirection = tradingDirection)
    def fromTechnicalAnalysis(technicalAnalysis: TechnicalAnalysis) = Event(EventType.TechnicalAnalysis, technicalAnalysis.timestamp, techncialAnalysis = Some(technicalAnalysis))
    def phaseComplete(timestamp: Long): Event = Event(EventType.PhaseComplete, timestamp)
    def phaseErrored(timestamp: Long): Event = Event(EventType.PhaseErrored, timestamp)
    def reset(timestamp: Long): Event = Event(EventType.Reset, timestamp)
}
