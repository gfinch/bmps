package bmps.core.strategies.lifecycle

import bmps.core.strategies.entrance.SimpleTrendEntryStrategy
import bmps.core.strategies.entrance.ConsolidationFadeEntryStrategy
import bmps.core.strategies.entrance.ZoneTrendEntryStrategy
import bmps.core.strategies.entrance.MomentumCrossoverEntryStrategy
import bmps.core.strategies.entrance.SqueezeEntryStrategy
import bmps.core.strategies.entrance.KeltnerEntryStrategy
import bmps.core.strategies.entrance.IceInVeinsEntryStrategy
import bmps.core.strategies.exit.SimpleExitStrategy
import bmps.core.models.Order
import bmps.core.models.SystemState
import bmps.core.strategies.rules.OrderEntryRules
import bmps.core.strategies.rules.TimeEntryRules

trait OrderBuilder extends SimpleTrendEntryStrategy with ConsolidationFadeEntryStrategy
    with ZoneTrendEntryStrategy with MomentumCrossoverEntryStrategy 
    with SqueezeEntryStrategy with KeltnerEntryStrategy with IceInVeinsEntryStrategy
    with OrderEntryRules with TimeEntryRules {

    lazy val entryRules = Seq(
        // noActiveOrder(_, _),
        notLateInTheDay(_, _)
    )

    lazy val entries = Seq(
        // isIceInVeinsTriggered(_),
        isKeltnerTriggered(_),
        // isSqueezeTriggered(_),
        // isMomentumCrossoverTriggered(_),
        // isZoneTrendTriggered(_),
        isConsolidationFadeTriggered(_),
        // isSimpleTrendTriggered(_),
    )

    def buildOrders(state: SystemState): Option[Order] = {
        val strategies = activeEntryStrategies(state)
        val candle = state.tradingCandles.last
        if (entryRules.forall(fn => fn(state, candle))) {
            entries.foldLeft(Option.empty[Order]) { case (orderOption, entryStrategy) =>
                orderOption match {
                    case None => entryStrategy(state) match {
                        case None => orderOption
                        case Some((orderType, strategy, orderFn)) if !strategies.contains(strategy) => 
                            Some(orderFn(state, orderType, strategy))
                        case _ => None
                    }
                    case _ => orderOption
                }
            }
        } else None
    }

}
