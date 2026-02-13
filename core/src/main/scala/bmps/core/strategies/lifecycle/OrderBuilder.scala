package bmps.core.strategies.lifecycle

import bmps.core.strategies.entrance.SimpleTrendEntryStrategy
import bmps.core.strategies.exit.SimpleExitStrategy
import bmps.core.models.Order
import bmps.core.models.SystemState
import bmps.core.strategies.rules.OrderEntryRules
import bmps.core.strategies.rules.TimeEntryRules

trait OrderBuilder extends SimpleTrendEntryStrategy with OrderEntryRules with TimeEntryRules {

    lazy val entryRules = Seq(
        noActiveOrder(_, _),
        notLateInTheDay(_, _)
    )

    lazy val entries = Seq(
        isSimpleTrendTriggered(_)
    )

    def buildOrders(state: SystemState): Option[Order] = {
        val candle = state.tradingCandles.last
        if (entryRules.forall(fn => fn(state, candle))) {
            entries.foldLeft(Option.empty[Order]) { case (orderOption, entryStrategy) =>
                orderOption match {
                    case None => entryStrategy(state) match {
                        case None => orderOption
                        case Some((orderType, entryStrategy)) => 
                            Some(balancedATRSetup(state, orderType, entryStrategy))
                    }
                    case _ => orderOption
                }
            }
        } else None
    }

}
