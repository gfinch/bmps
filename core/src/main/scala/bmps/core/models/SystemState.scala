package bmps.core.models

import java.time.LocalDate

case class SystemState(
    tradingDay: LocalDate,
    candles: List[Candle],
    direction: Direction,
    swingPoints: List[SwingPoint],
    planZones: List[PlanZone] = List.empty,
    daytimeExtremes: List[DaytimeExtreme] = List.empty
)