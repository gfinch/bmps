// package bmps.core.phases

// import java.time.LocalDate
// import java.time.format.DateTimeFormatter
// import fs2.Stream
// import cats.effect.IO
// import bmps.core.services.{SwingService, PlanZoneService, LiquidityZoneService}
// import bmps.core.io.ParquetSource
// import bmps.core.models.{SystemState, Event, Candle}
// import bmps.core.api.intf.{EventGenerator, CandleSource}
// import bmps.core.api.run.PhaseRunner
// import bmps.core.models.Order
// import bmps.core.services.OrderService
// import cats.instances.order
// import bmps.core.models.SystemStatePhase
// import bmps.core.models.CandleDuration
// import bmps.core.brokers.LeadAccountBroker
// import bmps.core.io.DatabentoSource
// import bmps.core.io.DataSource
// import bmps.core.utils.TimestampUtils
// import bmps.core.brokers.rest.OrderState
// import bmps.core.models.OrderStatus

// class TradingEventGenerator(leadAccount: LeadAccountBroker, orderService: OrderService, swingService: SwingService = new SwingService(5, 0.5f)) extends EventGenerator {
//     require(leadAccount.brokerCount >= 1, "Must have at least one account broker defined.")

//     def initialize(state: SystemState, options: Map[String, String] = Map.empty): SystemState = {
//         val tradingDirection = orderService.determineTradingDirection(state)
//         state.copy(systemStatePhase = SystemStatePhase.Trading, tradingDirection = Some(tradingDirection))
//     }

//     def process(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
//         candle.duration match {
//             case CandleDuration.OneSecond => processOneSecond(state, candle)
//             case CandleDuration.OneMinute => processOneMinute(state, candle)
//             case _ => (state, List.empty[Event])
//         }
//     }

//     def processOneSecond(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
//         //Order processing every second
//         val newState = state.copy(recentOneSecondCandles = 
//             if (state.recentOneSecondCandles.size > 10) {
//                 state.recentOneSecondCandles.tail :+ candle
//             } else state.recentOneSecondCandles :+ candle
//         )
        
//         val withNewOrders = orderService.buildOrders(newState, candle)

//         //Event processing
//         // val changedOrders = withNewOrders.orders.filterNot(state.orders.contains)
        
//         val withOrders = { //if (changedOrders.nonEmpty) {
//             // val lastOneMinuteCandle = state.tradingCandles.last
//             adjustOrderState(withNewOrders, candle) //force market orders to be placed
//         } //else withNewOrders
        
//         // val orderEvents = changedOrders.map(Event.fromOrder(_, leadAccount.riskPerTrade))
//         (withNewOrders, List.empty)
//     }

//     def processOneMinute(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
//         //Candle and swing processing every minute
//         val updatedCandles = state.tradingCandles :+ candle
//         val (swings, directionOption) = swingService.computeSwings(updatedCandles)
//         val newDirection = directionOption.getOrElse(state.swingDirection)
//         val withSwings = state.copy(tradingCandles = updatedCandles, tradingSwingPoints = swings, swingDirection = newDirection)

//         // //Order processing every minute
//         val withNewOrders = orderService.buildOrders(withSwings, candle)
//         val withOrders = adjustOrderState(withNewOrders, candle)
//         val withPlacedOrders = placeOrders(withOrders, candle)
        
//         //Event processing
//         val newSwingPoints = withPlacedOrders.tradingSwingPoints.drop(state.tradingSwingPoints.length)
//         val swingEvents = newSwingPoints.map(Event.fromSwingPoint)
//         val changedOrders = withPlacedOrders.orders.filterNot(state.orders.contains)
//         val orderEvents = changedOrders.map(Event.fromOrder(_, leadAccount.riskPerTrade))

//         val allEvents = swingEvents ++ orderEvents
//         (withPlacedOrders, allEvents)
//     }

//     private def adjustOrderState(state: SystemState, candle: Candle): SystemState = {
//         val adjustedOrders = state.orders.map(o => leadAccount.updateOrderStatus(o, candle))
//         state.copy(orders = adjustedOrders)
//     }
    
//     private def placeOrders(state: SystemState, candle: Candle): SystemState = {
//         val placedOrders = orderService.findOrderToPlace(state, candle: Candle).map(leadAccount.placeOrder(_, candle)) match {
//             case Some(placedOrder) =>
//                 state.orders.map(order => if (order.timestamp == placedOrder.timestamp) placedOrder else order)
//             case None =>
//                 state.orders
//         }
//         state.copy(orders = placedOrders)
//     }
// }

// class TradingSource(dataSource: DataSource) extends CandleSource {
    
//     def candles(state: SystemState): Stream[IO, Candle] = {
//         val (plannedStart, endMs) = computeTradingWindow(state)
//         val startMs = state.tradingCandles.lastOption.map(_.timestamp + 1).getOrElse(plannedStart)

//         dataSource.candlesInRangeStream(startMs, endMs)
//     }

//     private def computeTradingWindow(state: SystemState): (Long, Long) = {
//         val startMs = TimestampUtils.newYorkOpen(state.tradingDay)
//         val endMs = TimestampUtils.newYorkClose(state.tradingDay)
        
//         (startMs, endMs)
//     }
// }

// object TradingPhaseBuilder {
//     def build(leadAccount: LeadAccountBroker, dataSource: DataSource, orderService: OrderService) = {
//         new PhaseRunner(new TradingSource(dataSource), new TradingEventGenerator(leadAccount, orderService))
//     }
// }
