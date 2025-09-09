package bmps.core

import bmps.core.models.Candle
import bmps.core.models.SwingPoint

sealed trait EventType
object EventType {
    case object Candle extends EventType
    case object SwingPoint extends EventType
}

case class Event(
    eventType: EventType, 
    timestamp: Long, 
    candle: Option[Candle],
    swingPoint: Option[SwingPoint]
)
