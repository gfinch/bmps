package bmps.core.models

import java.time.LocalDate

case class SystemState(
    tradingDay: LocalDate,
    candles: List[Candle],
    direction: Direction,
    swingPoints: List[SwingPoint],
    planZones: List[PlanZone] = List.empty,
    daytimeExtremes: List[DaytimeExtreme] = List.empty,
    tradingDirection: Option[Direction] = None,
    orders: List[Order] = List.empty,
    // five-minute historical playback state (populated during TRADE)
    fiveMinCandles: List[Candle] = List.empty,
    fiveMinSwingPoints: List[SwingPoint] = List.empty
)