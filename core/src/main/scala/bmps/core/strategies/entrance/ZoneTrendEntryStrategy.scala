package bmps.core.strategies.entrance

import bmps.core.models.SystemState
import bmps.core.models.OrderType
import bmps.core.models.Direction
import bmps.core.models.Order
import bmps.core.models.OrderStatus
import bmps.core.models.ContractType
import bmps.core.models.EntryStrategy
import bmps.core.models.ExitStrategy
import bmps.core.strategies.exit.SimpleExitStrategy
import bmps.core.strategies.zones.{Zones, ZoneId}

trait ZoneTrendEntryStrategy {
    private final val entryStrategy = EntryStrategy("ZoneTrendEntryStrategy")
    private final val exitStrategy = new SimpleExitStrategy()

    // Configurable parameters
    private val bufferTicks = 2.0
    private val trendStrengthThreshold = 15.0
    private val movingMinutes = 3

    def isZoneTrendTriggered(state: SystemState): Option[(OrderType, EntryStrategy, (SystemState, OrderType, EntryStrategy) => Order)] = {
        if (state.recentTrendAnalysis.size < 5) return None
        
        val currentCandle = state.tradingCandles.last
        val zones = Zones.fromState(state)
        val zoneId = zones.zoneId(currentCandle.close)

        zoneId match {
            case ZoneId.NewHigh =>
                if (Zones.movingForMinutes(state, Direction.Up, movingMinutes) && 
                    !Zones.lastOrderFromZone(state, ZoneId.NewHigh)) {
                    Some((OrderType.Long, entryStrategy.copy(description = s"ZoneTrendEntryStrategy:ZoneId:${ZoneId.NewHigh}"), zoneTrendSetup))
                } else None

            case ZoneId.Short if Zones.isDeathCrossMinutesAgo(state, 0) && 
                                 Zones.movingForMinutes(state, Direction.Down, movingMinutes) =>
                Some((OrderType.Short, entryStrategy.copy(description = s"ZoneTrendEntryStrategy:ZoneId:${ZoneId.Short}"), zoneTrendSetup))

            case ZoneId.MidHigh if Zones.isGoldenCrossMinutesAgo(state, 0) && 
                                   Zones.trendStrengthNMinutesAgo(state, movingMinutes) > trendStrengthThreshold =>
                Some((OrderType.Long, entryStrategy.copy(description = s"ZoneTrendEntryStrategy:ZoneId:${ZoneId.MidHigh}"), zoneTrendSetup))

            case ZoneId.MidLow if Zones.isDeathCrossMinutesAgo(state, 0) && 
                                  Zones.trendStrengthNMinutesAgo(state, movingMinutes) > trendStrengthThreshold =>
                Some((OrderType.Short, entryStrategy.copy(description = s"ZoneTrendEntryStrategy:ZoneId:${ZoneId.MidLow}"), zoneTrendSetup))

            case ZoneId.Long if Zones.isGoldenCrossMinutesAgo(state, 0) && 
                                Zones.movingForMinutes(state, Direction.Up, movingMinutes) =>
                Some((OrderType.Long, entryStrategy.copy(description = s"ZoneTrendEntryStrategy:ZoneId:${ZoneId.Long}"), zoneTrendSetup))

            case ZoneId.NewLow =>
                if (Zones.movingForMinutes(state, Direction.Down, movingMinutes) && 
                    !Zones.lastOrderFromZone(state, ZoneId.NewLow)) {
                    Some((OrderType.Short, entryStrategy.copy(description = s"ZoneTrendEntryStrategy:ZoneId:${ZoneId.NewLow}"), zoneTrendSetup))
                } else None

            case _ => None
        }
    }

    def zoneTrendSetup(state: SystemState, orderType: OrderType, entryStrategy: EntryStrategy): Order = {
        val lastCandle = state.tradingCandles.last
        val zones = Zones.fromState(state)
        val zoneId = zones.zoneId(lastCandle.close)
        val atr = state.recentVolatilityAnalysis.last.trueRange.atr

        val (entry, stop, profit) = orderType match {
            case OrderType.Long =>
                val longEntry = lastCandle.close //- bufferTicks
                val longStop = longEntry - (atr * 3.0)
                val longTP = longEntry + (atr * 2.0)
                (longEntry, longStop, longTP)
            case OrderType.Short =>
                val shortEntry = lastCandle.close //+ bufferTicks
                val shortStop = shortEntry + (atr * 3.0)
                val shortTP = shortEntry - (atr * 2.0)
                (shortEntry, shortStop, shortTP)
        }

        Order(
            timestamp = lastCandle.endTime,
            orderType = orderType,
            status = OrderStatus.PlaceNow,
            contractType = ContractType.ES,
            contract = state.contractSymbol.get,
            contracts = 1,
            entryStrategy = entryStrategy,
            exitStrategy = exitStrategy,
            entryPrice = entry,
            stopLoss = stop,
            trailStop = Some(math.abs(entry - stop)),
            takeProfit = profit
        )
    }
}
