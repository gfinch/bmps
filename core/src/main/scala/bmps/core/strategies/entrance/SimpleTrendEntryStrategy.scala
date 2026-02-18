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
import bmps.core.strategies.exit
import bmps.core.strategies.zones.{Zones, ZoneId}

trait SimpleTrendEntryStrategy {
    private final val exitStrategy = new SimpleExitStrategy()

    def isSimpleTrendTriggered(state: SystemState): Option[(OrderType, EntryStrategy, (SystemState, OrderType, EntryStrategy) => Order)] = {
        val lastThreeMinutes = state.recentTrendAnalysis.takeRight(3)
        val shortTerms = lastThreeMinutes.map(_.shortTermMA)
        val longTerms = lastThreeMinutes.map(_.longTermMA)

        val threshold = 0.0 //TODO - compute threshold based on ATR

        val (direction, _) = shortTerms.zip(longTerms)
            .foldLeft((Option.empty[Direction], 0.0)) { case ((last, mag), (short, long)) =>
                val diff = short - long
                last match {
                    case None if diff > threshold => (Some(Direction.Up), diff)
                    case None if diff < threshold => (Some(Direction.Down), diff)
                    case Some(Direction.Up) if diff > threshold && diff > mag => (Some(Direction.Up), diff)
                    case Some(Direction.Down) if short - long < threshold && diff < mag => (Some(Direction.Down), diff)
                    case _ => (Some(Direction.Doji), mag)
                }
        }

        // Apply zone bounds: don't go long at new highs, don't go short at new lows
        val zones = Zones.fromState(state)
        val currentZone = zones.zoneId(state.tradingCandles.last.close)
        val entryStrategy = EntryStrategy(s"SimpleTrendEntryStrategy:${ZoneId.fromId(currentZone)}")

        direction match {
            case Some(Direction.Up) if currentZone != ZoneId.NewHigh && currentZone != ZoneId.Short => 
                Some((OrderType.Long, entryStrategy, balancedATRSetup))
            case Some(Direction.Down) if currentZone != ZoneId.NewLow && currentZone != ZoneId.Long => 
                Some((OrderType.Short, entryStrategy, balancedATRSetup))
            case _ => None
        }
    }

    def balancedATRSetup(state: SystemState, orderType: OrderType, entryStrategy: EntryStrategy) = {
        val twoAtrs = state.recentVolatilityAnalysis.last.trueRange.atr * 2.0
        val lastCandle = state.tradingCandles.last
        val entry = lastCandle.close
        val (stop, profit) = orderType match {
            case OrderType.Long => 
                (entry - twoAtrs, entry + twoAtrs)
            case OrderType.Short => 
                (entry + twoAtrs, entry - twoAtrs)
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
            trailStop = None,
            takeProfit = profit,
        )
    }
}
