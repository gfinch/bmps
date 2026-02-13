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

trait SimpleTrendEntryStrategy {
    final val entryStrategy = EntryStrategy("SimpleTrendEntryStrategy")
    final val exitStrategy = new SimpleExitStrategy()

    def isSimpleTrendTriggered(state: SystemState): Option[(OrderType, EntryStrategy)] = {
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

        direction match {
            case Some(Direction.Up) => Some((OrderType.Long, entryStrategy))
            case Some(Direction.Down) => Some((OrderType.Short, entryStrategy))
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
            timestamp = lastCandle.timestamp,
            orderType = orderType,
            status = OrderStatus.PlaceNow,
            contractType = ContractType.ES,
            contract = state.contractSymbol.get,
            contracts = 1, 
            entryStrategy = entryStrategy,
            exitStrategy = exitStrategy,
            entryPrice = entry,
            stopLoss = stop,
            trailStop = false,
            takeProfit = profit,
        )
    }
}
