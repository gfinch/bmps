package bmps.core.models

import java.time.LocalDate
import bmps.core.models.SystemStatePhase.Planning

sealed trait SystemStatePhase
object SystemStatePhase {
    case object Planning extends SystemStatePhase
    case object Preparing extends SystemStatePhase
    case object Trading extends SystemStatePhase
}

case class SystemState(
    tradingDay: LocalDate = LocalDate.now(),
    swingDirection: Direction = Direction.Up,
    systemStatePhase: SystemStatePhase = Planning,
    tradingDirection: Option[Direction] = None,
    planningCandles: List[Candle] = List.empty,
    planningSwingPoints: List[SwingPoint] = List.empty,
    tradingCandles: List[Candle] = List.empty,
    tradingSwingPoints: List[SwingPoint] = List.empty,
    planZones: List[PlanZone] = List.empty,
    daytimeExtremes: List[DaytimeExtreme] = List.empty,
    orders: List[Order] = List.empty
)