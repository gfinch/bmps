package bmps.core.brokers

import org.scalatest.funsuite.AnyFunSuite
import bmps.core.models._
import bmps.core.models.OrderStatus._
import bmps.core.models.OrderType._
import java.time.{LocalDate, LocalTime, ZoneId, ZonedDateTime}

class SimulatedAccountBrokerSpec extends AnyFunSuite {

  private def lvl(v: Float) = Level(v)
  
  val eastern = ZoneId.of("America/New_York")
  
  private def tsFor(date: LocalDate, hour: Int, minute: Int): Long =
    ZonedDateTime.of(date, LocalTime.of(hour, minute), eastern).toInstant.toEpochMilli
  
  private def testCandle(low: Float, high: Float, open: Float, close: Float, timestamp: Long) =
    Candle(lvl(open), lvl(high), lvl(low), lvl(close), timestamp, CandleDuration.OneMinute)
  
  private def testOrder(orderType: OrderType, low: Float, high: Float, timestamp: Long) =
    Order(lvl(low), lvl(high), timestamp, orderType, EntryType.EngulfingOrderBlock)
  
  val broker = new SimulatedAccountBroker("TestAccount")
  val baseDate = LocalDate.of(2024, 1, 2)
  val baseTime = tsFor(baseDate, 10, 0)

  // ================= BASIC STATE TRANSITIONS =================
  
  test("placeOrder transitions from Planned to Placed") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Planned)
    val candle = testCandle(95f, 115f, 105f, 108f, baseTime + 60000)
    
    val placedOrder = broker.placeOrder(order, candle)
    
    assert(placedOrder.status == Placed)
    assert(placedOrder.placedTimestamp.contains(candle.timestamp))
  }
  
  test("fillOrder transitions from Placed to Filled") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Placed)
    val candle = testCandle(95f, 115f, 105f, 108f, baseTime + 60000)
    
    val filledOrder = broker.fillOrder(order, candle)
    
    assert(filledOrder.status == Filled)
    assert(filledOrder.filledTimestamp.contains(candle.timestamp))
  }
  
  test("takeProfit transitions order to Profit") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Filled)
    val candle = testCandle(95f, 115f, 105f, 108f, baseTime + 60000)
    
    val profitOrder = broker.takeProfit(order, candle)
    
    assert(profitOrder.status == Profit)
    assert(profitOrder.closeTimestamp.contains(candle.timestamp))
  }
  
  test("takeLoss transitions order to Loss") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Filled)
    val candle = testCandle(95f, 115f, 105f, 108f, baseTime + 60000)
    
    val lossOrder = broker.takeLoss(order, candle)
    
    assert(lossOrder.status == Loss)
    assert(lossOrder.closeTimestamp.contains(candle.timestamp))
  }
  
  test("cancelOrder transitions order to Cancelled with reason") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Placed)
    val candle = testCandle(95f, 115f, 105f, 108f, baseTime + 60000)
    val reason = CancelReason.FullCandleOutside
    
    val cancelledOrder = broker.cancelOrder(order, candle, reason)
    
    assert(cancelledOrder.status == Cancelled)
    assert(cancelledOrder.closeTimestamp.contains(candle.timestamp))
    assert(cancelledOrder.cancelReason.contains(reason))
  }

  // ================= LONG ORDER SCENARIOS =================

  test("Long order fill when candle crosses entry point") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Placed)
    // Entry point for Long is 110 (high), candle low goes down to touch it exactly
    val candle = testCandle(110f, 115f, 112f, 111f, baseTime + 60000)
    
    println(s"TEST CANDLE PARAMS: low=110f, high=115f, open=112f, close=111f")
    println(s"ACTUAL candle low: ${candle.low.value}, high: ${candle.high.value}")
    println(s"Order entryPoint: ${order.entryPoint}")
    println(s"Should fill: ${candle.low.value <= order.entryPoint}")
    
    val result = broker.updateOrderStatus(order, candle)
    
    println(s"Result: ${result.status}")
    
    assert(result.status == Filled)
    assert(result.filledTimestamp.contains(candle.timestamp))
  }
  
  test("Long order doesn't fill when candle doesn't reach entry point") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Placed)
    // Candle stays above entry point
    val candle = testCandle(111f, 115f, 112f, 114f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Placed)
    assert(result.filledTimestamp.isEmpty)
  }
  
  test("Long order takes profit when filled and reaches take profit") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Filled)
    // Take profit for this order should be at 130 (110 + (110-100)*2)
    val candle = testCandle(125f, 135f, 128f, 132f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Profit)
    assert(result.closeTimestamp.contains(candle.timestamp))
  }
  
  test("Long order takes loss when filled and hits stop loss") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Filled)
    // Stop loss for this order is at 100, candle low hits it
    val candle = testCandle(100f, 105f, 102f, 98f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Loss)
    assert(result.closeTimestamp.contains(candle.timestamp))
  }

  // ================= SHORT ORDER SCENARIOS =================

  test("Short order fill when candle crosses entry point") {
    val order = testOrder(Short, 100f, 110f, baseTime).copy(status = Placed)
    // Entry point for short is 100, candle touches it from below
    val candle = testCandle(95f, 105f, 98f, 102f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Filled)
    assert(result.filledTimestamp.contains(candle.timestamp))
  }
  
  test("Short order takes profit when filled and reaches take profit") {
    val order = testOrder(Short, 100f, 110f, baseTime).copy(status = Filled)
    // Take profit for short should be at 80 (100 - (110-100)*2), candle low hits it
    val candle = testCandle(80f, 85f, 82f, 81f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Profit)
    assert(result.closeTimestamp.contains(candle.timestamp))
  }
  
  test("Short order takes loss when filled and hits stop loss") {
    val order = testOrder(Short, 100f, 110f, baseTime).copy(status = Filled)
    // Stop loss for short is at 110
    val candle = testCandle(105f, 115f, 108f, 112f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Loss)
    assert(result.closeTimestamp.contains(candle.timestamp))
  }

  // ================= COMPLEX STATE TRANSITIONS =================

  test("Long order fills and takes profit in same candle") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Placed)
    // Candle goes low enough to fill (touches 110) and high enough for profit (130+)
    val candle = testCandle(110f, 135f, 112f, 132f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Profit)
    assert(result.filledTimestamp.contains(candle.timestamp))
    assert(result.closeTimestamp.contains(candle.timestamp))
  }
  
  test("Long order fills and takes loss in same candle") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Placed)
    // Candle touches entry (110) but also hits stop loss (100)
    val candle = testCandle(100f, 112f, 111f, 98f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Loss)
    assert(result.filledTimestamp.contains(candle.timestamp))
    assert(result.closeTimestamp.contains(candle.timestamp))
  }
  
  test("Short order fills and takes profit in same candle") {
    val order = testOrder(Short, 100f, 110f, baseTime).copy(status = Placed)
    // Candle goes high enough to fill (touches 100) and low enough for profit (80)
    val candle = testCandle(80f, 100f, 98f, 81f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Profit)
    assert(result.filledTimestamp.contains(candle.timestamp))
    assert(result.closeTimestamp.contains(candle.timestamp))
  }
  
  test("Short order fills and takes loss in same candle") {
    val order = testOrder(Short, 100f, 110f, baseTime).copy(status = Placed)
    // Candle touches entry (100) but also hits stop loss (110)
    val candle = testCandle(98f, 115f, 102f, 112f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Loss)
    assert(result.filledTimestamp.contains(candle.timestamp))
    assert(result.closeTimestamp.contains(candle.timestamp))
  }

  // ================= TALL CANDLE SCENARIOS =================

  test("Long order - tall bearish candle takes profit first") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Filled)
    // Bearish candle that hits both profit (130) and loss (100) - should take profit per rule
    val candle = testCandle(95f, 135f, 132f, 98f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Profit)
    assert(result.closeTimestamp.contains(candle.timestamp))
  }
  
  test("Long order - tall bullish candle takes loss first") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Filled)
    // Bullish candle that hits both profit (130) and loss (100) - should take loss per rule
    val candle = testCandle(95f, 135f, 98f, 132f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Loss)
    assert(result.closeTimestamp.contains(candle.timestamp))
  }
  
  test("Short order - tall bearish candle takes loss first") {
    val order = testOrder(Short, 100f, 110f, baseTime).copy(status = Filled)
    // Bearish candle that hits both profit (80) and loss (110) - should take loss per rule
    val candle = testCandle(75f, 115f, 112f, 78f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Loss)
    assert(result.closeTimestamp.contains(candle.timestamp))
  }
  
  test("Short order - tall bullish candle takes profit first") {
    val order = testOrder(Short, 100f, 110f, baseTime).copy(status = Filled)
    // Bullish candle that hits both profit (80) and loss (110) - should take profit per rule
    val candle = testCandle(75f, 115f, 78f, 112f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Profit)
    assert(result.closeTimestamp.contains(candle.timestamp))
  }

  // ================= CANCELLATION SCENARIOS =================

  test("Long order cancelled when full candle above take profit") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Placed)
    // Entire candle above take profit (130+)
    val candle = testCandle(135f, 140f, 137f, 138f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Cancelled)
    assert(result.cancelReason.contains(CancelReason.FullCandleOutside))
  }
  
  // ================= TIME-BASED CANCELLATION =================

  test("Long order cancelled after 10 minutes with wick outside") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Planned)
    // 11 minutes later, candle wicks to take profit area
    val laterTime = baseTime + (11 * 60 * 1000)
    val candle = testCandle(125f, 135f, 128f, 127f, laterTime)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Cancelled)
    assert(result.cancelReason.contains(CancelReason.TenMinuteWickOutside))
  }
  
  test("Short order cancelled after 10 minutes with wick outside") {
    val order = testOrder(Short, 100f, 110f, baseTime).copy(status = Planned)
    // 11 minutes later, candle wicks to take profit area (80) but doesn't fill (high < 100)
    val laterTime = baseTime + (11 * 60 * 1000)
    val candle = testCandle(80f, 85f, 82f, 83f, laterTime)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Cancelled)
    assert(result.cancelReason.contains(CancelReason.TenMinuteWickOutside))
  }
  
  test("Order not cancelled if less than 10 minutes have passed") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Planned)
    // Only 5 minutes later, candle wicks to take profit area but shouldn't cancel yet
    val laterTime = baseTime + (5 * 60 * 1000)
    val candle = testCandle(125f, 135f, 128f, 127f, laterTime)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Planned)
    assert(result.cancelReason.isEmpty)
  }

  test("Placed Long order cancelled after 10 minutes with profitable wick") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Placed, placedTimestamp = Some(baseTime))
    // 11 minutes later, candle wicks above take profit (130+) but doesn't fill (low > entry 110)
    val laterTime = baseTime + (11 * 60 * 1000)
    val candle = testCandle(112f, 135f, 115f, 114f, laterTime)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Cancelled)
    assert(result.cancelReason.contains(CancelReason.TenMinuteWickOutside))
  }

  test("Placed Long order filled and takes loss when wicking below stop loss after 10 minutes") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Placed, placedTimestamp = Some(baseTime))
    // 11 minutes later, candle wicks below stop loss (100) and touches entry (110)
    val laterTime = baseTime + (11 * 60 * 1000)
    val candle = testCandle(95f, 112f, 111f, 96f, laterTime)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Loss)
    assert(result.cancelReason.isEmpty)
  }

  test("Placed Short order cancelled after 10 minutes with profitable wick") {
    val order = testOrder(Short, 100f, 110f, baseTime).copy(status = Placed, placedTimestamp = Some(baseTime))
    // 11 minutes later, candle wicks below take profit (80) but doesn't fill (high < entry 100)
    val laterTime = baseTime + (11 * 60 * 1000)
    val candle = testCandle(75f, 98f, 97f, 76f, laterTime)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Cancelled)
    assert(result.cancelReason.contains(CancelReason.TenMinuteWickOutside))
  }

  test("Placed Short order filled and takes loss when wicking above stop loss after 10 minutes") {
    val order = testOrder(Short, 100f, 110f, baseTime).copy(status = Placed, placedTimestamp = Some(baseTime))
    // 11 minutes later, candle wicks above stop loss (110) and touches entry (100)
    val laterTime = baseTime + (11 * 60 * 1000)
    val candle = testCandle(98f, 115f, 99f, 114f, laterTime)
    
    val result = broker.updateOrderStatus(order, candle)
    
    assert(result.status == Loss)
    assert(result.cancelReason.isEmpty)
  }

  // ================= EDGE CASES =================

  test("Order operations are applied in correct sequence") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Placed)
    // This candle should fill first (touches 110), then profit (reaches 130)
    val candle = testCandle(110f, 135f, 112f, 132f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    // Should end up as Profit, not just Filled
    assert(result.status == Profit)
    assert(result.filledTimestamp.contains(candle.timestamp))
    assert(result.closeTimestamp.contains(candle.timestamp))
  }
  
  test("Already closed order is not modified") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(
      status = Profit,
      closeTimestamp = Some(baseTime + 30000)
    )
    val candle = testCandle(90f, 95f, 92f, 93f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    // Order should remain unchanged
    assert(result.status == Profit)
    assert(result.closeTimestamp.contains(baseTime + 30000))
  }
  
  test("Cancellation rules don't apply to filled orders") {
    val order = testOrder(Long, 100f, 110f, baseTime).copy(status = Filled)
    // Candle that would normally trigger cancellation
    val candle = testCandle(135f, 140f, 137f, 138f, baseTime + 60000)
    
    val result = broker.updateOrderStatus(order, candle)
    
    // Should take profit, not cancel
    assert(result.status == Profit)
    assert(result.cancelReason.isEmpty)
  }
  
  test("Multiple operations in sequence work correctly") {
    var order = testOrder(Long, 100f, 110f, baseTime).copy(status = Planned)
    
    // First candle: should remain planned
    val candle1 = testCandle(105f, 115f, 108f, 112f, baseTime + 60000)
    order = broker.updateOrderStatus(order, candle1)
    assert(order.status == Planned)
    
    // Place the order manually
    order = broker.placeOrder(order, candle1)
    assert(order.status == Placed)
    
    // Second candle: should fill (low touches entry point 110)
    val candle2 = testCandle(110f, 115f, 112f, 113f, baseTime + 120000)
    order = broker.updateOrderStatus(order, candle2)
    assert(order.status == Filled)
    
    // Third candle: should take profit
    val candle3 = testCandle(125f, 135f, 128f, 132f, baseTime + 180000)
    order = broker.updateOrderStatus(order, candle3)
    assert(order.status == Profit)
  }
}