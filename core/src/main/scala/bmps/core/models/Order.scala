package bmps.core.models

sealed trait OrderStatus
object OrderStatus {
    case object Planned extends OrderStatus
    case object Placed extends OrderStatus
    case object Filled extends OrderStatus
    case object Profit extends OrderStatus
    case object Loss extends OrderStatus
    case object Cancelled extends OrderStatus
}

sealed trait OrderType
object OrderType {
    case object Long extends OrderType
    case object Short extends OrderType
}

sealed trait EntryType
object EntryType {
    case object EngulfingOrderBlock extends EntryType
    case object FairValueGapOrderBlock extends EntryType
    case object InvertedFairValueGapOrderBlock extends EntryType
    case object BreakerBlockOrderBlock extends EntryType
    case object MarketStructureShiftOrderBlock extends EntryType
    case object SupermanOrderBlock extends EntryType
    case object JediOrderBlock extends EntryType
}

object CancelReason {
    final val FullCandleOutside: String = "A full candle fell outside the range of the order."
    final val TenMinuteWickOutside: String = "More than ten minutes have passed and the candle wicked outside the order."
    final val EndOfDay: String = "Ten minutes until closing. All orders cancelled."
}

case class Order(low: Level, 
                    high: Level, 
                    timestamp: Long, 
                    orderType: OrderType,
                    entryType: EntryType, 
                    status: OrderStatus = OrderStatus.Planned,
                    profitMultiplier: Double = 2.0,
                    placedTimestamp: Option[Long] = None,
                    filledTimestamp: Option[Long] = None,
                    closeTimestamp: Option[Long] = None,
                    cancelReason: Option[String] = None,
                    accountId: Option[String] = None) {

    import OrderType._
    import OrderStatus._

    require(high > low, "High and Low of an order zone cannot be equal or inverted.")

    final val DollarsPerMicro = 5.0 //Five dollars per point on micros (MES)
    final val MaxContracts = 50
    final val TenMinutes = 10 * 60 * 1000

    lazy val entryPoint = orderType match {
        case Long => high.value
        case Short => low.value
    }

    lazy val stopLoss = orderType match {
        case Long => low.value
        case Short => high.value
    }

    lazy val atRiskPoints = (high.value - low.value)
    lazy val profitPoints = atRiskPoints * profitMultiplier

    lazy val atRiskPerContract = atRiskPoints * DollarsPerMicro 
    lazy val potentialPerContract = profitPoints * DollarsPerMicro

    lazy val takeProfit = orderType match {
        case Long => high.value + profitPoints
        case Short => low.value - profitPoints
    }

    lazy val isActive = (status == OrderStatus.Placed || status == OrderStatus.Filled)
    lazy val direction: Direction = if (orderType == OrderType.Long) Direction.Up else Direction.Down
}

case class SerializableOrder(low: Level, 
                             high: Level, 
                             timestamp: Long, 
                             orderType: OrderType,
                             entryType: EntryType, 
                             status: OrderStatus,
                             profitMultiplier: Double,
                             riskDollars: Double,
                             placedTimestamp: Option[Long],
                             filledTimestamp: Option[Long],
                             closeTimestamp: Option[Long],
                             entryPoint: Double,
                             stopLoss: Double,
                             takeProfit: Double,
                             contracts: Int,
                             atRisk: Double,
                             potential: Double,
                             cancelReason: Option[String]
                     )

object Order {
    def fromCandle(candle: Candle, orderType: OrderType, entryType: EntryType, timestamp: Long) = {
        Order(candle.low, candle.high, timestamp, orderType, entryType)
    }

    def fromGapCandles(firstCandle: Candle, secondCandle: Candle, orderType: OrderType, entryType: EntryType, timestamp: Long) = {
        orderType match {
            case OrderType.Short => Order(firstCandle.high, secondCandle.high, timestamp, orderType, entryType)
            case OrderType.Long => Order(secondCandle.low, firstCandle.low, timestamp, orderType, entryType)
        }
        
    }
}

object SerializableOrder {
    def fromOrder(order: Order, riskPerTrade: Double): SerializableOrder = {

        //For the purposes of simulation, we'll assume $500 at risk per order
        val riskDollars = riskPerTrade
        val contracts: Int = Math.floor(riskDollars / order.atRiskPerContract).toInt
        val atRisk = order.atRiskPerContract * contracts
        val potential = order.potentialPerContract * contracts

        SerializableOrder(
            low = order.low,
            high = order.high,
            timestamp = order.timestamp,
            orderType = order.orderType,
            entryType = order.entryType,
            status = order.status,
            profitMultiplier = order.profitMultiplier,
            riskDollars = riskDollars,
            placedTimestamp = order.placedTimestamp,
            filledTimestamp = order.filledTimestamp,
            closeTimestamp = order.closeTimestamp,
            entryPoint = order.entryPoint.toDouble,
            stopLoss = order.stopLoss.toDouble,
            takeProfit = order.takeProfit.toDouble,
            contracts = contracts,
            atRisk = atRisk,
            potential = potential,
            cancelReason = order.cancelReason
        )
    } 
}
