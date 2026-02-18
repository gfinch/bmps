package bmps.core.brokers.rest

import bmps.core.models.{OrderStatus, ContractType}
import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import scala.util.{Try, Success, Failure}
import scala.concurrent.duration._
import java.time.Duration
import bmps.core.models.OrderType
import cats.instances.order
import bmps.core.models.Order

case class PlaceOsoResponse(orderId: Option[Long], oso1Id: Option[Long], oso2Id: Option[Long], 
                           failureReason: Option[String], failureText: Option[String])

case class CommandResult(commandId: Option[Long], failureReason: Option[String], failureText: Option[String])

case class OrderState(orderId: Long, orderAction: OrderType, contracts: Int, contract: String,
                       contractId: Long, orderType: String, limit: Option[Double], stop: Option[Double], 
                       fill: Option[Double], status: OrderStatus, fillTimestamp: Option[Long])

// API Response Models

case class ApiOrder(id: Long, accountId: Long, contractId: Long, timestamp: String, 
                   action: String, ordStatus: String)
case class ApiPosition(id: Long, accountId: Long, contractId: Long, netPos: Int, 
                      bought: Int, boughtValue: Double, sold: Int, soldValue: Double, 
                      timestamp: String)
case class ApiFill(id: Long, orderId: Long, contractId: Long, timestamp: String, 
                  action: String, qty: Int, price: Double, active: Boolean)
case class ApiOrderVersion(id: Long, orderId: Long, orderQty: Int, orderType: String,
                          price: Option[Double], stopPrice: Option[Double])
case class ApiContract(id: Long, name: String, contractMaturityId: Long)

// OCO Order response
case class PlaceOcoResponse(orderId: Option[Long], ocoId: Option[Long], 
                           failureReason: Option[String], failureText: Option[String])

// Authentication response models
case class AccessTokenResponse(accessToken: String, userId: Long, expirationTime: String)

// Cash balance models
case class TradeDate(year: Int, month: Int, day: Int)
case class CashBalance(
    id: Option[Long],
    accountId: Long,
    timestamp: String,
    tradeDate: TradeDate,
    currencyId: Long,
    amount: Double,
    realizedPnL: Option[Double],
    weekRealizedPnL: Option[Double],
    amountSOD: Option[Double]
)

// Order details with price information
case class OrderDetails(
    limitPrice: Option[Float],
    stopPrice: Option[Float],
    avgFillPrice: Option[Float],
    qty: Int
)

class TradovateBroker(
    username: String,
    password: String,
    clientId: String,
    clientSecret: String,
    deviceId: String,
    accountId: Long,
    accountSpec: String,
    baseUrl: String = "https://demo.tradovateapi.com/v1", // Use demo by default
    maxRetries: Int = 5,
    initialRetryDelayMs: Long = 1000
) {

    private val httpClient = HttpClient.newHttpClient()
    
    // Thread-safe token container supporting lazy initialization and refresh
    private val tokenContainer: AtomicReference[Option[String]] = new AtomicReference(None)
    private val tokenExpirationTime: AtomicReference[Long] = new AtomicReference(0L)
    
    /**
     * Get current access token, authenticating lazily if needed
     */
    private def accessToken: String = {
        tokenContainer.get() match {
            case Some(token) => token
            case None =>
                // Authenticate and try to set atomically
                val token = authenticate()
                if (tokenContainer.compareAndSet(None, Some(token))) {
                    // We successfully set it
                    token
                } else {
                    // Another thread beat us to it, use their token
                    tokenContainer.get().getOrElse(token)
                }
        }
    }

    /**
     * Place an OCO (Order Cancels Order) order strategy
     * If one order is filled, the other is cancelled
     * Details: https://api.tradovate.com/#tag/Orders/operation/placeOCO
     */
    def placeOCOOrder(order: Order): PlaceOcoResponse = {
        val action = if (order.orderType == OrderType.Long) "Buy" else "Sell"
        val exitAction = if (action == "Buy") "Sell" else "Buy"
        
        // First order (e.g., take profit)
        val orderType = if (order.status == OrderStatus.PlaceNow) "Market" else "Limit"
        val price = if (order.status == OrderStatus.PlaceNow) None else Some(order.entryPrice)
        
        // Other order (e.g., stop loss)
        val otherOrderType = if (order.trailStop.isDefined) "TrailingStop" else "Stop"
        val other = Json.obj(
            "action" -> Json.fromString(exitAction),
            "orderType" -> Json.fromString(otherOrderType),
            "stopPrice" -> Json.fromDoubleOrNull(order.stopLoss)
        )
        
        val basePayload = Json.obj(
            "accountSpec" -> Json.fromString(accountSpec),
            "accountId" -> Json.fromLong(accountId),
            "action" -> Json.fromString(action),
            "symbol" -> Json.fromString(order.contractId),
            "orderQty" -> Json.fromInt(order.contracts),
            "orderType" -> Json.fromString(orderType),
            "isAutomated" -> Json.fromBoolean(true),
            "other" -> other
        )
        
        val payloadJson = price match {
            case Some(p) => basePayload.deepMerge(Json.obj("price" -> Json.fromDoubleOrNull(p)))
            case None => basePayload
        }
        
        val payload = payloadJson.noSpaces

        val request = buildRequest("POST", "/order/placeoco", Some(payload))
        executeWithRetry(request, body => decode[PlaceOcoResponse](body).toTry)
    }

    /**
     * Modify an existing order's parameters
     * Details: https://api.tradovate.com/#tag/Orders/operation/modifyOrder
     */
    def modifyOrder(order: Order, orderId: Long): CommandResult = {
        val orderType = order.status match {
            case OrderStatus.PlaceNow => "Market"
            case _ => if (order.entryPrice > 0) "Limit" else "Stop"
        }
        
        val basePayload = Json.obj(
            "orderId" -> Json.fromLong(orderId),
            "orderQty" -> Json.fromInt(order.contracts),
            "orderType" -> Json.fromString(orderType),
            "isAutomated" -> Json.fromBoolean(true)
        )
        
        // Add price fields based on order type
        val withPrice = if (order.entryPrice > 0) {
            basePayload.deepMerge(Json.obj("price" -> Json.fromDoubleOrNull(order.entryPrice)))
        } else basePayload
        
        val withStopPrice = if (order.stopLoss > 0) {
            withPrice.deepMerge(Json.obj("stopPrice" -> Json.fromDoubleOrNull(order.stopLoss)))
        } else withPrice
        
        val payload = withStopPrice.noSpaces

        val request = buildRequest("POST", "/order/modifyorder", Some(payload))
        executeWithRetry(request, body => decode[CommandResult](body).toTry)
    }

    /**
     * List all positions for the authenticated user
     * Details: https://api.tradovate.com/#tag/Positions/operation/positionList
     */
    def listPositions(): List[ApiPosition] = {
        val request = buildRequest("GET", "/position/list")
        executeWithRetry(request, body => decode[List[ApiPosition]](body).toTry)
    }

    /**
     * List all orders for the authenticated user
     * Details: https://api.tradovate.com/#tag/Orders/operation/orderList
     */
    def listOrders(): List[ApiOrder] = {
        val request = buildRequest("GET", "/order/list")
        executeWithRetry(request, body => decode[List[ApiOrder]](body).toTry)
    }

    /**
     * Place an OSO (Order Sends Order) bracket order
     * Details: https://api.tradovate.com/#tag/Orders/operation/placeOSO
     */
    def placeOSOOrder(order: Order): PlaceOsoResponse = {
        val action = if (order.orderType == OrderType.Long) "Buy" else "Sell"
        
        // Determine opposite action for exit orders
        val exitAction = if (action == "Buy") "Sell" else "Buy"
        
        val stopLossBracket = {
            val orderType = if (order.trailStop.isDefined) "TrailingStop" else "Stop"
            Json.obj(
                "action" -> Json.fromString(exitAction),
                "orderType" -> Json.fromString(orderType),
                "stopPrice" -> Json.fromDoubleOrNull(order.stopLoss)
            )
        }

        val orderType = if (order.status == OrderStatus.PlaceNow) "Market" else "Limit"
        val price = if (order.status == OrderStatus.PlaceNow) None else Some(order.entryPrice)
        
        val basePayload = Json.obj(
            "accountSpec" -> Json.fromString(accountSpec),
            "accountId" -> Json.fromLong(accountId),
            "action" -> Json.fromString(action),
            "symbol" -> Json.fromString(order.contractId),
            "orderQty" -> Json.fromInt(order.contracts),
            "orderType" -> Json.fromString(orderType),
            "isAutomated" -> Json.fromBoolean(true), // Required for algorithmic trading
            "bracket1" -> stopLossBracket
        )
        
        val payloadJson = price match {
            case Some(p) => basePayload.deepMerge(Json.obj("price" -> Json.fromDoubleOrNull(p)))
            case None => basePayload
        }
        
        val payload = payloadJson.noSpaces

        val request = buildRequest("POST", "/order/placeoso", Some(payload))
        executeWithRetry(request, body => decode[PlaceOsoResponse](body).toTry)
    }

    /**
     * Cancel an order
     * Details: https://api.tradovate.com/#tag/Orders/operation/cancelOrder
     */
    def cancelOrder(orderId: Long): CommandResult = {
        val payload = Json.obj(
            "orderId" -> Json.fromLong(orderId),
            "isAutomated" -> Json.fromBoolean(true)
        ).noSpaces

        val request = buildRequest("POST", "/order/cancelorder", Some(payload))
        executeWithRetry(request, body => decode[CommandResult](body).toTry)
    }

    /**
      * Liquidate all positions for a specific contract
      *
      * @param contractId
      * @return
      */
    def liquidatePosition(contractId: Long): CommandResult = {
        val payload = Json.obj(
            "accountSpec" -> Json.fromString(accountSpec),
            "accountId" -> Json.fromLong(accountId),
            "contractId" -> Json.fromLong(contractId),
            "admin" -> Json.fromBoolean(false),
            "isAutomated" -> Json.fromBoolean(true)
        ).noSpaces

        val request = buildRequest("POST", "/order/liquidateposition", Some(payload))
        executeWithRetry(request, body => decode[CommandResult](body).toTry)
    }

    def cancelOrLiquidate(orderId: Long): Unit = {
        val orderState = orderStatus(orderId)
        
        orderState.status match {
            case OrderStatus.Filled =>
                // Position is open, need to liquidate
                println(s"[TradovateAccountBroker] Liquidating position for order $orderId")
                liquidatePosition(orderState.contractId)
                
            case OrderStatus.Placed =>
                // Order is still working, cancel it
                println(s"[TradovateAccountBroker] Cancelling working order $orderId")
                cancelOrder(orderId)
                
            case OrderStatus.Cancelled =>
                println(s"[TradovateAccountBroker] Order $orderId already cancelled")
                
            case _ =>
                println(s"[TradovateAccountBroker] Unknown order state: ${orderState.status}")
        }
    }

    /**
      * Get order status with detailed price information
      * Makes 3 API calls: /order/item (status), /orderVersion/deps (prices), /fill/deps (fills)
      */
    def orderStatus(orderId: Long): OrderState = {
        // 1. Get basic order information
        val orderRequest = buildRequest("GET", s"/order/item?id=$orderId")
        val apiOrder = executeWithRetry(orderRequest, body => decode[ApiOrder](body).toTry)
        
        // 2. Get order version details (prices and quantity)
        val orderVersionRequest = buildRequest("GET", s"/orderVersion/deps?masterid=$orderId")
        val orderVersions = executeWithRetry(orderVersionRequest, body => decode[List[ApiOrderVersion]](body).toTry)
        val orderVersion = orderVersions.headOption
        
        // 3. Get fill information
        val fillRequest = buildRequest("GET", s"/fill/deps?masterid=$orderId")
        val fills = executeWithRetry(fillRequest, body => decode[List[ApiFill]](body).toTry)
        
        // Calculate average fill price if there are fills
        val (avgFillPrice, fillTimestamp) = if (fills.nonEmpty) {
            val activeFills = fills.filter(_.active)
            if (activeFills.nonEmpty) {
                val totalQty = activeFills.map(_.qty).sum
                val weightedSum = activeFills.map(f => f.price * f.qty).sum
                val avgPrice = if (totalQty > 0) weightedSum / totalQty else 0.0
                // Get the latest fill timestamp
                val latestFill = activeFills.maxBy(f => java.time.Instant.parse(f.timestamp).toEpochMilli)
                (Some(avgPrice), Some(java.time.Instant.parse(latestFill.timestamp).toEpochMilli))
            } else {
                (None, None)
            }
        } else {
            (None, None)
        }
        
        // Get contract symbol - need to fetch contract details
        val contractRequest = buildRequest("GET", s"/contract/item?id=${apiOrder.contractId}")
        val apiContract = executeWithRetry(contractRequest, body => decode[ApiContract](body).toTry)
        val contract = apiContract.name
        
        // Map Tradovate action to OrderType
        val orderType = apiOrder.action match {
            case "Buy" => OrderType.Long
            case "Sell" => OrderType.Short
            case _ => throw new RuntimeException(s"Unknown action: ${apiOrder.action}")
        }
        
        // Map Tradovate ordStatus to OrderStatus
        val status = apiOrder.ordStatus match {
            case "Filled" => OrderStatus.Filled
            case "Canceled" | "Cancelled" => OrderStatus.Cancelled
            case "Rejected" => OrderStatus.Cancelled // Map rejected to cancelled
            case "Working" | "Pending" => OrderStatus.Placed
            case _ => OrderStatus.Placed // Default to placed for unknown statuses
        }
        
        // Build the OrderState
        OrderState(
            orderId = apiOrder.id,
            orderAction = orderType,
            contracts = orderVersion.map(_.orderQty).getOrElse(1),
            contract = contract,
            contractId = apiOrder.contractId,
            orderType = orderVersion.map(_.orderType).getOrElse("Unknown"),
            limit = orderVersion.flatMap(_.price),
            stop = orderVersion.flatMap(_.stopPrice),
            fill = avgFillPrice,
            status = status,
            fillTimestamp = fillTimestamp
        )
    }

    /**
     * Get cash balance list
     * Details: https://api.tradovate.com/#tag/Cash-Balance/operation/cashBalanceList
     */
    def getCashBalances(): List[CashBalance] = {
        val request = buildRequest("GET", "/cashBalance/list")
        executeWithRetry(request, body => decode[List[CashBalance]](body).toTry)
    }

    //~~~~~~~~~~~~~~
    
    /**
     * Authenticate with Tradovate and get access token
     * https://api.tradovate.com/#tag/Access/Get-An-Access-Token-Using-Client-Credentials
     */
    private def authenticate(): String = {
        println(s"Authenticating with Tradovate (device: ${deviceId.take(8)}...)")
        
        val authPayload = Json.obj(
            "name" -> Json.fromString(username),
            "password" -> Json.fromString(password),
            "appId" -> Json.fromString(clientId),
            "appVersion" -> Json.fromString("1.0"),
            "cid" -> Json.fromString(clientId),
            "sec" -> Json.fromString(clientSecret),
            "deviceId" -> Json.fromString(deviceId)
        ).noSpaces
        
        val request = HttpRequest.newBuilder()
            .uri(URI.create(s"$baseUrl/auth/accesstokenrequest"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(authPayload))
            .build()
        
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() == 200) {
            decode[AccessTokenResponse](response.body()) match {
                case Right(tokenResp) =>
                    println("✓ Authentication successful")
                    // Parse and store expiration time
                    Try {
                        java.time.Instant.parse(tokenResp.expirationTime).toEpochMilli
                    }.foreach { expiryMs =>
                        tokenExpirationTime.set(expiryMs)
                        val expiresIn = (expiryMs - System.currentTimeMillis()) / 1000 / 60
                        println(s"  Token expires in ~$expiresIn minutes")
                    }
                    tokenResp.accessToken
                case Left(error) =>
                    throw new RuntimeException(s"Failed to parse auth response: ${error.getMessage}\nBody: ${response.body()}")
            }
        } else {
            throw new RuntimeException(
                s"Authentication failed with status ${response.statusCode()}: ${response.body()}"
            )
        }
    }
    
    /**
     * Re-authenticate and update the access token
     */
    private def clearToken(): Unit = {
        println("[TradovateBroker] Access token expired, refreshing...")
        val oldToken = tokenContainer.get()
        tokenContainer.compareAndSet(oldToken, None)
    }
    
    /**
     * Execute HTTP request with exponential backoff retry logic for rate limits and token expiration
     */
    private def executeWithRetry[T](request: HttpRequest, parseResponse: String => Try[T], 
                                    retryCount: Int = 0): T = {
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        response.statusCode() match {
            case 200 | 201 =>
                parseResponse(response.body()) match {
                    case Success(result) => result
                    case Failure(error) =>
                        throw new RuntimeException(s"Failed to parse response: ${error.getMessage}\nBody: ${response.body()}")
                }
            
            case 401 if retryCount == 0 => // Expired access token - refresh and retry once
                println("[TradovateBroker] Received 401 Unauthorized, refreshing access token...")
                clearToken()
                // Rebuild the request with the new token
                val newRequest = rebuildRequestWithNewToken(request)
                executeWithRetry(newRequest, parseResponse, retryCount + 1)
            
            case 429 | 503 if retryCount < maxRetries => // Rate limit or service unavailable
                val delayMs = initialRetryDelayMs * Math.pow(2, retryCount).toLong
                println(s"Rate limit hit, retrying in ${delayMs}ms (attempt ${retryCount + 1}/$maxRetries)")
                Thread.sleep(delayMs)
                executeWithRetry(request, parseResponse, retryCount + 1)
            
            case statusCode =>
                throw new RuntimeException(
                    s"HTTP $statusCode: ${response.body()}"
                )
        }
    }
    
    /**
     * Rebuild an HTTP request with a new access token
     */
    private def rebuildRequestWithNewToken(oldRequest: HttpRequest): HttpRequest = {
        val builder = HttpRequest.newBuilder()
            .uri(oldRequest.uri())
            .header("Authorization", s"Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(oldRequest.timeout().orElse(Duration.ofSeconds(30)))
        
        oldRequest.method() match {
            case "GET" => builder.GET()
            case "POST" => 
                // Re-send the same body
                oldRequest.bodyPublisher().ifPresent(publisher => 
                    builder.POST(publisher)
                )
                builder
            case _ => builder
        }
        
        builder.build()
    }
    
    private def buildRequest(method: String, endpoint: String, body: Option[String] = None): HttpRequest = {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(s"$baseUrl$endpoint"))
            .header("Authorization", s"Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(30))
        
        method match {
            case "GET" => builder.GET()
            case "POST" => builder.POST(HttpRequest.BodyPublishers.ofString(body.getOrElse("{}")))
            case _ => throw new IllegalArgumentException(s"Unsupported HTTP method: $method")
        }
        
        builder.build()
    }

    /**
     * Get order version details for a specific order to retrieve price and quantity info
     * https://api.tradovate.com/#tag/Orders/operation/orderVersionDependents
     */
    private def getOrderVersion(orderId: Long): Option[ApiOrderVersion] = {
        val request = buildRequest("GET", s"/orderVersion/deps?masterid=$orderId")
        Try {
            executeWithRetry(request, body => decode[List[ApiOrderVersion]](body).toTry)
        }.toOption.flatMap(_.headOption)
    }
}
