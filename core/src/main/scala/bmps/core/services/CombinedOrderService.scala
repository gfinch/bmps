// package bmps.core.services

// import bmps.core.services.rules.CalcAtrs
// import bmps.core.services.rules.CrossingRules
// import bmps.core.services.rules.OrderPlacingRules
// import bmps.core.services.rules.TrendRules
// import bmps.core.services.rules.ZoneRules
// import bmps.core.services.rules.ZoneId
// import bmps.core.services.rules.Zones
// import bmps.core.models.Direction
// import bmps.core.models.OrderType
// import bmps.core.models.SystemState
// import bmps.core.models.Candle
// import bmps.core.models.EntryType
// import bmps.core.models.Order
// import bmps.core.models.OrderStatus


// class CombinedOrderService() extends CalcAtrs 
//         with CrossingRules with OrderPlacingRules 
//         with TrendRules with ZoneRules {

//     def processOneMinuteState(state: SystemState): SystemState = {
//         if (noActiveOrderExists(state) && notEndOfDay(state)) {
//             processByZone(state)
//         } else {
//             state
//         }
//     }

//     def processAnalysis(state: SystemState): SystemState = {
        

//         //Besties:
//     /**
//       *       7,11,3: Wins= 15, Losses= 13, WinRate= 53.57%
//              10,12,3: Wins=  7, Losses=  8, WinRate= 46.67%
//               9,11,3: Wins=  5, Losses=  6, WinRate= 45.45%
//               6,11,3: Wins= 14, Losses= 19, WinRate= 42.42%
//              10,12,4: Wins=103, Losses=148, WinRate= 41.04%
//           7,14,21,11: Wins=252, Losses=404, WinRate= 38.41%
//           9,13,21,12: Wins= 83, Losses=134, WinRate= 38.25%
//       */

//     def applyScenario(id: Int, siblings: List[Int], state: SystemState): Boolean = {
//         id match {
//             case 1 => isOverbought(state) || isOversold(state)
//             case 2 => !(isOverbought(state) || isOversold(state))
//         //**    case 3 => nearingSummit(state, 1.0) || nearingFloor(state, 1.0)
//         //**    case 4 => !(nearingSummit(state, 1.0) || nearingFloor(state, 1.0))
//             case 5 => false //wasDeathCrossNMinutesAgo(state, 0) || wasGoldenCrossNMinutesAgo(state, 0) ##This generated a lot of orders that were not winners.
//             case 6 => wasDeathCrossNMinutesAgo(state, 2) || wasGoldenCrossNMinutesAgo(state, 2)
//         //**    case 7 => wasDeathCrossNMinutesAgo(state, 3) || wasGoldenCrossNMinutesAgo(state, 3)
//             case 8 => wasDeathCrossNMinutesAgo(state, 5) || wasGoldenCrossNMinutesAgo(state, 5)
//         //**    case 9 => wasDeathCrossNMinutesAgo(state, 7) || wasGoldenCrossNMinutesAgo(state, 7)
//         //**    case 10 => wasDeathCrossNMinutesAgo(state, 10) || wasGoldenCrossNMinutesAgo(state, 10)
//         //**    case 11 => hasIncreasingADX(state, 3)
//         //**    case 12 => !hasIncreasingADX(state, 3)
//             case 13 => hasSpread(state, siblings, 10)
//         //**    case 14 => hasSpread(state, siblings, 20)
//             case 15 => hasSpread(state, siblings, 30)
//             case 16 => hasSpread(state, siblings, 40)
//             case 17 => hasSpread(state, siblings, 50)
//             case 18 => isIntersecting(state, siblings, 0)
//             case 19 => isIntersecting(state, siblings, 1)
//             case 20 => isIntersecting(state, siblings, 2)
//         //**    case 21 => isIntersecting(state, siblings, 5)
//         }
//         }
//     }

//     def deathAdxSummitOrFloor(state: SystemState): Boolean = {
//         isDeathCrossMinutesAgo(state, 3) 
//     }

//     def processByZone(state: SystemState): SystemState = {
//         val candle = state.tradingCandles.last
//         val zones = zonesFromState(state)
//         val zoneId = zones.zoneId(candle)
//         zoneId match {
//             case ZoneId.NewHigh if 
//                     movingForMinutes(state, Direction.Up, 3) &&
//                     !lastOrderFromZone(state, ZoneId.NewHigh) => //TODO - check if last order was loser
                
//                 val entryType = EntryType.Trendy(s"ZoneId:$zoneId.NewHigh.L.1x.2atr")
//                 addOrder(OrderType.Long, candle, state, 2.0, 1.0, entryType)

//             case ZoneId.Short if 
//                     isDeathCrossMinutesAgo(state, 0) && 
//                     movingForMinutes(state, Direction.Down, 3) =>

//                 val entryType = EntryType.Trendy(s"ZoneId:$zoneId.Short.S.2x.3atr")
//                 addOrder(OrderType.Short, candle, state, 3.0, 2.0, entryType)

//             case ZoneId.MidHigh if 
//                     isGoldenCrossMinutesAgo(state, 0) && 
//                     trendStrengthNMinutesAgo(state, 3) > 15.0 => 

//                 val entryType = EntryType.Trendy(s"ZoneId:$zoneId.MidHigh.L.2x.1atr")
//                 addOrder(OrderType.Long, candle, state, 1.0, 2.0, entryType)

//             case ZoneId.MidLow if 
//                     isDeathCrossMinutesAgo(state, 0) && 
//                     trendStrengthNMinutesAgo(state, 3) > 15.0 =>

//                 val entryType = EntryType.Trendy(s"ZoneId:$zoneId.MidLow.S.2x.1atr")
//                 addOrder(OrderType.Short, candle, state, 1.0, 2.0, entryType)

//             case ZoneId.Long if 
//                 isGoldenCrossMinutesAgo(state, 0) && 
//                 movingForMinutes(state, Direction.Up, 3) =>

//                 val entryType = EntryType.Trendy(s"ZoneId:$zoneId.Long.L.2x.3atr")
//                 addOrder(OrderType.Long, candle, state, 3.0, 2.0, entryType)
                
//             case ZoneId.NewLow => state
//             case _ => state
//         }
//     }

//     def addOrder(orderType: OrderType, lastCandle: Candle, state: SystemState, 
//         atrQty: Double, multQty: Double, entryType: EntryType): SystemState = {

//         val atrs = calcAtrs(state, atrQty).toFloat
//         val (low, high) = orderType match {
//             case OrderType.Long => 
//                 (lastCandle.close - atrs, lastCandle.close)
//             case OrderType.Short => 
//                 (lastCandle.close, lastCandle.close + atrs)
//         }

//         val newOrder = Order(
//             low,
//             high,
//             lastCandle.timestamp,
//             orderType,
//             entryType,
//             state.contractSymbol.get,
//             status = OrderStatus.PlaceNow,
//             profitMultiplier = multQty.toFloat,
//             riskMultiplier = Some(1.0f) //TODO ... compute risk based on earnings
//         )

//         state.copy(orders = state.orders :+ newOrder)
//     }
// }
