package bmps.console

import bmps.core.brokers.rest.{TradovateBroker, PlannedOrder}
import bmps.core.models.OrderType
import scala.io.StdIn
import scala.util.{Try, Success, Failure}

/**
 * Simple console app to test TradovateBroker functions.
 * 
 * Environment variables required:
 * - TRADOVATE_USER: Your Tradovate username
 * - TRADOVATE_PASS: Your Tradovate password
 * - TRADOVATE_KEY: Your Tradovate API key (client ID)
 * - TRADOVATE_CID: Your Tradovate client secret
 * - TRADOVATE_DEVICE: Your permanent device ID
 * 
 * Usage: sbt "console/run"
 * Then select TradovateBrokerTest from the menu
 */
object TradovateBrokerTest extends App {
  
  // Get environment variables
  private val username = sys.env.getOrElse("TRADOVATE_USER", 
    throw new RuntimeException("TRADOVATE_USER environment variable not set"))
  private val password = sys.env.getOrElse("TRADOVATE_PASS", 
    throw new RuntimeException("TRADOVATE_PASS environment variable not set"))
  private val clientId = sys.env.getOrElse("TRADOVATE_CID", 
    throw new RuntimeException("TRADOVATE_CID environment variable not set"))
  private val clientSecret = sys.env.getOrElse("TRADOVATE_KEY", 
    throw new RuntimeException("TRADOVATE_KEY environment variable not set"))
  private val deviceId = sys.env.getOrElse("TRADOVATE_DEVICE", 
    throw new RuntimeException("TRADOVATE_DEVICE environment variable not set"))
  
  // Hard-coded values for testing
  private val accountId = 28281753L
  private val accountSpec = "DEMO5364424"
  private val contractSymbol = "ESZ5" // ES December 2025 contract
  
  println("\n" + "="*60)
  println("Tradovate Broker Test Console")
  println("="*60)
  println(s"Account: $accountSpec ($accountId)")
  println(s"Contract: $contractSymbol (ES)")
  println(s"User: $username")
  println("="*60 + "\n")
  
  // Initialize broker
  println("Initializing broker and authenticating...")
  val broker = Try {
    new TradovateBroker(
      username = username,
      password = password,
      clientId = clientId,
      clientSecret = clientSecret,
      deviceId = deviceId,
      accountId = accountId,
      accountSpec = accountSpec,
      baseUrl = "https://demo.tradovateapi.com/v1"
    )
  }
  
  broker match {
    case Success(b) =>
      println("✓ Broker initialized successfully!\n")
      println(b.accessToken)
      runInteractiveMenu(b)
    case Failure(e) =>
      println(s"✗ Failed to initialize broker: ${e.getMessage}")
      println(s"  ${e.getClass.getSimpleName}")
      e.printStackTrace()
      sys.exit(1)
  }
  
  /**
   * Run the interactive menu
   */
  def runInteractiveMenu(broker: TradovateBroker): Unit = {
    var running = true
    var lastOrderId: Option[Long] = None
    
    while (running) {
      println("\n" + "-"*60)
      println("Available Commands:")
      println("  1. Place OSO Order (entry, take profit, stop loss)")
      println("  2. Cancel Order")
      println("  3. Get Order Status")
      println("  4. Quick Test (place and check order)")
      println("  0. Exit")
      if (lastOrderId.isDefined) {
        println(s"\nLast Order ID: ${lastOrderId.get}")
      }
      println("-"*60)
      print("Enter command: ")
      
      val choice = StdIn.readLine().trim
      
      choice match {
        case "1" => placeOrderCommand(broker, lastOrderId = id => lastOrderId = Some(id))
        case "2" => cancelOrderCommand(broker, lastOrderId)
        case "3" => orderStatusCommand(broker, lastOrderId)
        case "4" => quickTestCommand(broker, lastOrderId = id => lastOrderId = Some(id))
        case "0" => 
          println("\nExiting...")
          running = false
        case _ => 
          println(s"\n✗ Invalid command: $choice")
      }
    }
  }
  
  /**
   * Place an OSO order
   */
  def placeOrderCommand(broker: TradovateBroker, lastOrderId: Long => Unit): Unit = {
    println("\n" + "="*60)
    println("Place OSO Order")
    println("="*60)
    
    try {
      // Get order type
      print("Order type (1=Long/Buy, 2=Short/Sell): ")
      val orderType = StdIn.readLine().trim match {
        case "1" => OrderType.Long
        case "2" => OrderType.Short
        case other => 
          println(s"Invalid order type: $other")
          return
      }
      
      // Get entry price
      print("Entry price (e.g., 5800.00): ")
      val entry = StdIn.readLine().trim.toFloat
      
      // Get stop loss
      print("Stop loss price: ")
      val stopLoss = StdIn.readLine().trim.toFloat
      
      // Get take profit
      print("Take profit price: ")
      val takeProfit = StdIn.readLine().trim.toFloat
      
      // Get number of contracts
      print("Number of contracts (default=1): ")
      val contractsInput = StdIn.readLine().trim
      val contracts = if (contractsInput.isEmpty) 1 else contractsInput.toInt
      
      // Validate prices
      if (orderType == OrderType.Long) {
        if (stopLoss >= entry) {
          println(s"\n✗ Error: Stop loss ($stopLoss) must be below entry ($entry) for a long order")
          return
        }
        if (takeProfit <= entry) {
          println(s"\n✗ Error: Take profit ($takeProfit) must be above entry ($entry) for a long order")
          return
        }
      } else {
        if (stopLoss <= entry) {
          println(s"\n✗ Error: Stop loss ($stopLoss) must be above entry ($entry) for a short order")
          return
        }
        if (takeProfit >= entry) {
          println(s"\n✗ Error: Take profit ($takeProfit) must be below entry ($entry) for a short order")
          return
        }
      }
      
      println(s"\nPlacing ${orderType} order:")
      println(s"  Entry: $entry")
      println(s"  Stop Loss: $stopLoss")
      println(s"  Take Profit: $takeProfit")
      println(s"  Contracts: $contracts")
      println(s"  Symbol: $contractSymbol")
      
      val plannedOrder = PlannedOrder(
        entry = entry,
        stopLoss = stopLoss,
        takeProfit = takeProfit,
        orderType = orderType,
        contractId = contractSymbol,
        contracts = contracts
      )
      
      val response = broker.placeOrder(plannedOrder)
      
      response.orderId match {
        case Some(orderId) =>
          println(s"\n✓ Order placed successfully!")
          println(s"  Order ID: $orderId")
          response.oso1Id.foreach(id => println(s"  Take Profit Order ID: $id"))
          response.oso2Id.foreach(id => println(s"  Stop Loss Order ID: $id"))
          lastOrderId(orderId)
        case None =>
          println(s"\n✗ Order placement failed")
          response.failureReason.foreach(r => println(s"  Reason: $r"))
          response.failureText.foreach(t => println(s"  Details: $t"))
      }
    } catch {
      case e: NumberFormatException =>
        println(s"\n✗ Invalid number format: ${e.getMessage}")
      case e: Exception =>
        println(s"\n✗ Error placing order: ${e.getMessage}")
        e.printStackTrace()
    }
  }
  
  /**
   * Cancel an order
   */
  def cancelOrderCommand(broker: TradovateBroker, lastOrderId: Option[Long]): Unit = {
    println("\n" + "="*60)
    println("Cancel Order")
    println("="*60)
    
    try {
      print(s"Order ID to cancel${lastOrderId.map(id => s" (press Enter for $id)").getOrElse("")}: ")
      val input = StdIn.readLine().trim
      
      val orderId = if (input.isEmpty && lastOrderId.isDefined) {
        lastOrderId.get
      } else if (input.nonEmpty) {
        input.toLong
      } else {
        println("\n✗ No order ID provided")
        return
      }
      
      println(s"\nCancelling order $orderId...")
      val response = broker.cancelOrder(orderId)
      
      response.commandId match {
        case Some(commandId) =>
          println(s"\n✓ Cancel command sent successfully!")
          println(s"  Command ID: $commandId")
        case None =>
          println(s"\n✗ Cancel failed")
          response.failureReason.foreach(r => println(s"  Reason: $r"))
          response.failureText.foreach(t => println(s"  Details: $t"))
      }
    } catch {
      case e: NumberFormatException =>
        println(s"\n✗ Invalid order ID: ${e.getMessage}")
      case e: Exception =>
        println(s"\n✗ Error cancelling order: ${e.getMessage}")
        e.printStackTrace()
    }
  }
  
  /**
   * Get order status
   */
  def orderStatusCommand(broker: TradovateBroker, lastOrderId: Option[Long]): Unit = {
    println("\n" + "="*60)
    println("Get Order Status")
    println("="*60)
    
    try {
      print(s"Order ID to check${lastOrderId.map(id => s" (press Enter for $id)").getOrElse("")}: ")
      val input = StdIn.readLine().trim
      
      val orderId = if (input.isEmpty && lastOrderId.isDefined) {
        lastOrderId.get
      } else if (input.nonEmpty) {
        input.toLong
      } else {
        println("\n✗ No order ID provided")
        return
      }
      
      println(s"\nFetching status for order $orderId...")
      val orderState = broker.orderStatus(orderId)
      
      println(s"\n✓ Order Status:")
      println(s"  Order ID: ${orderState.orderId}")
      println(s"  Action: ${orderState.orderAction}")
      println(s"  Contract: ${orderState.contract}")
      println(s"  Contracts: ${orderState.contracts}")
      println(s"  Order Type: ${orderState.orderType}")
      println(s"  Status: ${orderState.status}")
      orderState.limit.foreach(p => println(f"  Limit Price: $p%.2f"))
      orderState.stop.foreach(p => println(f"  Stop Price: $p%.2f"))
      orderState.fill.foreach(p => println(f"  Avg Fill Price: $p%.2f"))
      orderState.fillTimestamp.foreach { ts =>
        val instant = java.time.Instant.ofEpochMilli(ts)
        println(s"  Fill Time: $instant")
      }
      
    } catch {
      case e: NumberFormatException =>
        println(s"\n✗ Invalid order ID: ${e.getMessage}")
      case e: Exception =>
        println(s"\n✗ Error getting order status: ${e.getMessage}")
        e.printStackTrace()
    }
  }
  
  /**
   * Quick test - place a limit order near current price and check its status
   */
  def quickTestCommand(broker: TradovateBroker, lastOrderId: Long => Unit): Unit = {
    println("\n" + "="*60)
    println("Quick Test")
    println("="*60)
    println("This will place a test order and immediately check its status.")
    println("Note: You'll need to provide realistic prices for ES.")
    
    try {
      print("\nCurrent ES price (approx): ")
      val currentPrice = StdIn.readLine().trim.toFloat
      
      // Place a long order slightly below current price
      val entry = currentPrice - 10.0f
      val stopLoss = entry - 20.0f
      val takeProfit = entry + 30.0f
      
      println(s"\nPlacing test LONG order:")
      println(f"  Entry: $entry%.2f (${currentPrice - entry}%.2f below current)")
      println(f"  Stop Loss: $stopLoss%.2f")
      println(f"  Take Profit: $takeProfit%.2f")
      println(s"  Contracts: 1")
      
      val plannedOrder = PlannedOrder(
        entry = entry,
        stopLoss = stopLoss,
        takeProfit = takeProfit,
        orderType = OrderType.Long,
        contractId = contractSymbol,
        contracts = 1
      )
      
      println("\n1. Placing order...")
      val response = broker.placeOrder(plannedOrder)
      
      response.orderId match {
        case Some(orderId) =>
          println(s"   ✓ Order placed: $orderId")
          lastOrderId(orderId)
          
          // Wait a moment for the order to be processed
          Thread.sleep(1000)
          
          println("\n2. Fetching order status...")
          val orderState = broker.orderStatus(orderId)
          
          println(s"   ✓ Status retrieved:")
          println(s"     Order ID: ${orderState.orderId}")
          println(s"     Action: ${orderState.orderAction}")
          println(s"     Status: ${orderState.status}")
          println(s"     Contract: ${orderState.contract}")
          println(s"     Order Type: ${orderState.orderType}")
          orderState.limit.foreach(p => println(f"     Limit Price: $p%.2f"))
          
          println(s"\n✓ Test completed successfully!")
          println(s"\nYou can now:")
          println(s"  - Use command 3 to check the order status again")
          println(s"  - Use command 2 to cancel the order")
          
        case None =>
          println(s"   ✗ Order placement failed")
          response.failureReason.foreach(r => println(s"     Reason: $r"))
          response.failureText.foreach(t => println(s"     Details: $t"))
      }
      
    } catch {
      case e: NumberFormatException =>
        println(s"\n✗ Invalid number format: ${e.getMessage}")
      case e: Exception =>
        println(s"\n✗ Error in quick test: ${e.getMessage}")
        e.printStackTrace()
    }
  }
}
