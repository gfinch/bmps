package bmps.console

import bmps.core.brokers.rest.TradovateBroker

/**
 * Console client to fetch and display cash balance information from Tradovate.
 * 
 * Required environment variables:
 * - TRADOVATE_USER: Your Tradovate username
 * - TRADOVATE_PASS: Your Tradovate password
 * - TRADOVATE_CID: Your API client ID
 * - TRADOVATE_KEY: Your API client secret
 * - TRADOVATE_DEVICE: A unique device identifier
 * - TRADOVATE_ACCOUNT_ID: Your numeric account ID
 * - TRADOVATE_ACCOUNT_NAME: Your account name/spec
 * - TRADOVATE_BASE_URL: (Optional) API base URL, defaults to demo
 * 
 * Usage:
 *   sbt "console/runMain bmps.console.CashBalanceClient"
 */
object CashBalanceClient extends App {
  
  private val username = "Google:101549601152299221078"
  private val clientId = "8115"
  private val accountId = 28281753L
  private val accountSpec = "DEMO5364424"

  // Read configuration from environment variables
  
  
  
  private val password = sys.env.getOrElse("TRADOVATE_PASS", 
    throw new RuntimeException("TRADOVATE_PASS environment variable not set"))
  private val clientSecret = sys.env.getOrElse("TRADOVATE_KEY", 
    throw new RuntimeException("TRADOVATE_KEY environment variable not set"))
  private val deviceId = sys.env.getOrElse("TRADOVATE_DEVICE", 
    throw new RuntimeException("TRADOVATE_DEVICE environment variable not set"))
//   private val baseUrl = sys.env.getOrElse("TRADOVATE_BASE_URL", 
//     "https://demo.tradovateapi.com/v1")
  private val baseUrl = sys.env.getOrElse("TRADOVATE_BASE_URL", 
    "https://live.tradovateapi.com/v1")
  
  println("=" * 80)
  println("Cash Balance Client")
  println("=" * 80)
  println(s"Base URL: $baseUrl")
  println(s"Account: $accountSpec (ID: $accountId)")
  println("-" * 80)
  
  try {
    // Initialize broker
    val broker = new TradovateBroker(
      username = username,
      password = password,
      clientId = clientId,
      clientSecret = clientSecret,
      deviceId = deviceId,
      accountId = accountId,
      accountSpec = accountSpec,
      baseUrl = baseUrl
    )
    
    // Fetch cash balances
    println("Fetching cash balances...")
    val balances = broker.getCashBalances()
    
    println(s"\nFound ${balances.length} cash balance record(s):\n")
    
    // Print each balance
    balances.foreach { balance =>
      println("─" * 80)
      println(s"Balance ID:       ${balance.id.getOrElse("N/A")}")
      println(s"Account ID:       ${balance.accountId}")
      println(s"Timestamp:        ${balance.timestamp}")
      println(s"Trade Date:       ${balance.tradeDate.year}-${"%02d".format(balance.tradeDate.month)}-${"%02d".format(balance.tradeDate.day)}")
      println(s"Currency ID:      ${balance.currencyId}")
      println(s"Amount:           ${balance.amount}")
      balance.realizedPnL.foreach(pnl => println(s"Realized PnL:     $$$pnl"))
      balance.weekRealizedPnL.foreach(pnl => println(s"Week Realized:    $$$pnl"))
      balance.amountSOD.foreach(amount => println(s"Amount SOD:       $$$amount"))
    }
    
    println("─" * 80)
    println("\n✓ Successfully retrieved cash balances")
    
  } catch {
    case e: Exception =>
      println(s"\n✗ Error: ${e.getMessage}")
      e.printStackTrace()
      sys.exit(1)
  }
}
