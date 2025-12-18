package bmps.core.models

import java.security.KeyStore.Entry

sealed trait OrderStatus
object OrderStatus {
    case object Planned extends OrderStatus
    case object PlaceNow extends OrderStatus
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

sealed trait ContractType
object ContractType {
    case object ES extends ContractType
    case object MES extends ContractType
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
    case object BouncingOrderBlock extends EntryType
    case object MomentumOrderBlock extends EntryType
    case object OverOrderBlock extends EntryType
    case object TrendOrderBlock extends EntryType
    case class Trendy(description: String) extends EntryType {
        override def toString(): String = description
    }
}

object CancelReason {
    final val FullCandleOutside: String = "A full candle fell outside the range of the order."
    final val TenMinuteWickOutside: String = "More than ten minutes have passed and the candle wicked outside the order."
    final val TenMinutesUnfilled: String = "The order has been unfilled for more than ten minutes."
    final val EndOfDay: String = "Ten minutes until closing. All orders cancelled."
}

case class Order(low: Float, 
                    high: Float, 
                    timestamp: Long, 
                    orderType: OrderType,
                    entryType: EntryType,
                    contract: String, 
                    profitCap: Option[Float] = None,
                    status: OrderStatus = OrderStatus.Planned,
                    profitMultiplier: Float = 2.0f,
                    placedTimestamp: Option[Long] = None,
                    filledTimestamp: Option[Long] = None,
                    closeTimestamp: Option[Long] = None,
                    cancelReason: Option[String] = None,
                    accountId: Option[String] = None,
                    closedAt: Option[Float] = None,
                    riskMultiplier: Option[Float] = None) {

    import OrderType._
    import OrderStatus._

    require(high > low, "High and Low of an order zone cannot be equal or inverted.")

    final val DollarsPerMicro = 5.0 //Five dollars per point on micros (MES)

    lazy val entryPoint = orderType match {
        case Long => high
        case Short => low
    }

    lazy val stopLoss = orderType match {
        case Long => low
        case Short => high
    }

    lazy val atRiskPoints = (high - low)
    lazy val profitPoints = atRiskPoints * profitMultiplier

    lazy val atRiskPerContract = atRiskPoints * DollarsPerMicro 
    lazy val potentialPerContract = profitPoints * DollarsPerMicro

    lazy val takeProfit = profitCap.getOrElse(orderType match {
        case Long => high + profitPoints
        case Short => low - profitPoints
    })

    lazy val isActive = (status == OrderStatus.Placed || status == OrderStatus.PlaceNow || status == OrderStatus.Filled)
    lazy val direction: Direction = if (orderType == OrderType.Long) Direction.Up else Direction.Down

    lazy val isProfitOrLoss = (status == OrderStatus.Profit || status == OrderStatus.Loss)
    lazy val exitPrice = closedAt.getOrElse {
        status match {
            case OrderStatus.Profit => takeProfit
            case OrderStatus.Loss   => stopLoss
            case _ => throw new IllegalStateException("Order not closed")
        }
    }

    lazy val realizedR: Float = {
        val unclamped = orderType match {
            case OrderType.Long  => (exitPrice - entryPoint) / atRiskPoints
            case OrderType.Short => (entryPoint - exitPrice) / atRiskPoints
        }

        math.max(-1.2f, math.min(unclamped, profitMultiplier + 0.2f))
    }
}

case class SerializableOrder(low: Float, 
                             high: Float, 
                             timestamp: Long, 
                             orderType: OrderType,
                             entryType: EntryType, 
                             status: OrderStatus,
                             profitMultiplier: Double,
                             placedTimestamp: Option[Long],
                             filledTimestamp: Option[Long],
                             closeTimestamp: Option[Long],
                             entryPoint: Double,
                             stopLoss: Double,
                             takeProfit: Double,
                             contract: String,
                             contracts: Int,
                             atRisk: Double,
                             potential: Double,
                             cancelReason: Option[String]
                     )

object Order {
    def fromCandle(candle: Candle, orderType: OrderType, entryType: EntryType, timestamp: Long, contract: String) = {
        Order(candle.low, candle.high, timestamp, orderType, entryType, contract)
    }

    def fromGapCandles(firstCandle: Candle, secondCandle: Candle, orderType: OrderType, entryType: EntryType, timestamp: Long, contract: String) = {
        orderType match {
            case OrderType.Short => Order(firstCandle.high, secondCandle.high, timestamp, orderType, entryType, contract)
            case OrderType.Long => Order(secondCandle.low, firstCandle.low, timestamp, orderType, entryType, contract)
        }        
    }
}

trait DetermineContracts{
    final val mesContractCutoff = 10

    protected def determineContracts(order: Order, riskPerTrade: Double, riskMultiplier: Float): (String, Int) = {
        require(order.atRiskPerContract > 0, "atRiskPerContract must be positive")
        require(riskPerTrade > 0, "riskPerTrade must be positive")
        require(riskMultiplier > 0, "riskMultiplier must be positive")
        val finalRiskPerTrade = riskPerTrade * riskMultiplier
        val mesContracts: Int = Math.floor(finalRiskPerTrade / order.atRiskPerContract).toInt

        val (contractType, contracts) = if (mesContracts >= mesContractCutoff) {
            (ContractType.ES, Math.round(mesContracts.toDouble / 10.0).toInt)
        } else (ContractType.MES, mesContracts)

        val contract = contractType match {
            case ContractType.ES => order.contract
            case ContractType.MES => "M" + order.contract
        }

        (contract, contracts)
    }
}

object SerializableOrder extends DetermineContracts {
    final val MicroDollarsPerPoint = 5.0
    final val MiniDollarsPerPoint = 50.0
    final val FeePerESContract = 2.88 * 2
    final val FeePerMESContract = 0.95 * 2

    def fromOrder(order: Order, riskPerTrade: Double): SerializableOrder = {
        val riskMultiplier = order.riskMultiplier.getOrElse(1.0f)
        val (contract, contracts) = determineContracts(order, riskPerTrade, riskMultiplier)
        val (atRisk, potential) = {
            val takeProfit = order.closedAt.getOrElse(order.takeProfit)
            val stopLoss = order.closedAt.getOrElse(order.stopLoss)
            val profitPoints = math.abs(takeProfit - order.entryPoint)
            val lossPoints = math.abs(stopLoss - order.entryPoint)
            if (contract.startsWith("M")) {
                val rawLoss = lossPoints * MicroDollarsPerPoint * contracts
                val rawProfit = profitPoints * MicroDollarsPerPoint * contracts
                val fees = FeePerMESContract * contracts
                (rawLoss + fees, rawProfit - fees)
            } else {
                val rawLoss = lossPoints * MiniDollarsPerPoint * contracts
                val rawProfit = profitPoints * MiniDollarsPerPoint * contracts
                val fees = FeePerMESContract * contracts
                (rawLoss + fees, rawProfit - fees)
            }
        }

        SerializableOrder(
            low = order.low,
            high = order.high,
            timestamp = order.timestamp,
            orderType = order.orderType,
            entryType = order.entryType,
            status = order.status,
            profitMultiplier = order.profitMultiplier,
            placedTimestamp = order.placedTimestamp,
            filledTimestamp = order.filledTimestamp,
            closeTimestamp = order.closeTimestamp,
            entryPoint = order.entryPoint.toDouble,
            stopLoss = order.stopLoss.toDouble,
            takeProfit = order.takeProfit.toDouble,
            contract = contract,
            contracts = contracts,
            atRisk = atRisk,
            potential = potential,
            cancelReason = order.cancelReason
        )
    }
}
