package bmps.core.models

case class SystemState(
    candles: List[Candle],
    direction: Direction,
    swingPoints: List[SwingPoint],
    planZones: List[PlanZone] = List.empty
)