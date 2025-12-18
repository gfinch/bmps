package bmps.core.models

import java.time.LocalDate
import bmps.core.models.SystemStatePhase.Planning
import bmps.core.services.analysis.TrendAnalysis
import bmps.core.services.analysis.MomentumAnalysis
import bmps.core.services.analysis.VolumeAnalysis
import bmps.core.services.analysis.VolatilityAnalysis

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
    planningCandles: List[Candle] = List.empty,
    planningSwingPoints: List[SwingPoint] = List.empty,
    planZones: List[PlanZone] = List.empty,
    daytimeExtremes: List[DaytimeExtreme] = List.empty,
    
    tradingReplayStartTime: Option[Long] = None,
    tradingDirection: Option[Direction] = None,
    tradingCandles: List[Candle] = List.empty,
    tradingSwingPoints: List[SwingPoint] = List.empty,
    recentOneSecondCandles: List[Candle] = List.empty,

    recentTrendAnalysis: List[TrendAnalysis] = List.empty,
    recentMomentumAnalysis: List[MomentumAnalysis] = List.empty,
    recentVolumeAnalysis: List[VolumeAnalysis] = List.empty,
    recentVolatilityAnalysis: List[VolatilityAnalysis] = List.empty,
    
    orders: List[Order] = List.empty,
    contractSymbol: Option[String] = None,
    recentOrders: List[Order] = List.empty
)