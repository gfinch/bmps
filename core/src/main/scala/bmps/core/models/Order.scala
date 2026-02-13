package bmps.core.models

import java.security.KeyStore.Entry
import bmps.core.models.ContractType.ES
import spire.syntax.truncatedDivision
import breeze.numerics.round
import bmps.core.models.OrderStatus._
import cats.instances.order
import io.circe.{Encoder, Decoder, Json, HCursor}

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

case class EntryStrategy(description: String)

trait ExitStrategy {
    def adjustOrder(state: SystemState, order: Order): Seq[Order] = Seq(order)
}

object ExitStrategy {
    implicit val encoder: Encoder[ExitStrategy] = Encoder.instance(_ => Json.Null)
}

object Prices {
    final val MicroDollarsPerPoint = 5.0
    final val MiniDollarsPerPoint = 50.0
    final val FeePerESContract = 2.88 * 2
    final val FeePerMESContract = 0.95 * 2
}

case class Order (
    timestamp: Long,
    orderType: OrderType,
    status: OrderStatus,
    contractType: ContractType,
    contract: String,
    contracts: Int,
    entryStrategy: EntryStrategy,
    exitStrategy: ExitStrategy,
    entryPrice: Double,
    stopLoss: Double,
    trailStop: Boolean = false,
    takeProfit: Double,
    exitPrice: Option[Double] = None,
    placedTimestamp: Option[Long] = None,
    filledTimestamp: Option[Long] = None,
    closeTimestamp: Option[Long] = None
) {
    lazy val isActive = closeTimestamp.isEmpty
    lazy val isProfitOrLoss = status match {
        case OrderStatus.Profit => true
        case OrderStatus.Loss => true
        case _ => false
    }

    lazy val closedProfit: Option[Double] = {
        if (!isProfitOrLoss) None
        else {
            val exit = exitPrice.getOrElse {
                status match {
                    case OrderStatus.Profit => takeProfit
                    case OrderStatus.Loss => stopLoss
                    case _ => entryPrice
                }
            }
            Some(calcProfit(exit))
        }
    }
    lazy val cancelProfit = 0.0
    lazy val contractId = contractType match {
        case ContractType.ES => contract
        case ContractType.MES => "M" + contract
    }

    def openProfit(candle: Candle): Option[Double] = {
        if (isActive && status == OrderStatus.Filled) {
            Some(calcProfit(candle.close)) 
        } else None
    }

    def profit(candle: Candle): Double = closedProfit
        .orElse(openProfit(candle))
        .getOrElse(cancelProfit)

    lazy val fees: Double = contractType match {
        case ContractType.ES => Prices.FeePerESContract * contracts
        case ContractType.MES => Prices.FeePerMESContract * contracts
    }

    lazy val tickAligned: Order = this.copy(
        entryPrice = roundToTickSize(entryPrice),
        stopLoss = roundToTickSize(stopLoss),
        takeProfit = roundToTickSize(takeProfit)
    )

    lazy val timeAligned: Order = this.copy(
        timestamp = snapToOneMinute(timestamp),
        placedTimestamp = this.placedTimestamp.map(snapToOneMinute),
        filledTimestamp = this.filledTimestamp.map(snapToOneMinute),
        closeTimestamp = this.closeTimestamp.map(snapToOneMinute)
    )

    def log(candle: Candle): String = {
        val currentProfit = profit(candle)
        val profitIndicator = if (currentProfit == 0) "🟡"
            else if (currentProfit > 0) "🟢"
            else "🔴"

        val profitIndication = s"$profitIndicator: $currentProfit"
        val basic = s"$status $orderType - ➡️: $entryPrice"
        val open = s"$basic  🟥: $stopLoss 🟩: $takeProfit $profitIndication"
        val closed = s"$basic $profitIndication"
        status match {
            case Planned => basic
            case PlaceNow => basic
            case Placed => basic
            case Filled => open
            case Cancelled => closed
            case Profit => closed
            case Loss => closed
        }
    }

    private def snapToOneMinute(ts: Long): Long = {
        val oneMinuteMillis = 60000L
        val remainder = ts % oneMinuteMillis
        ts - remainder
    }

    private def roundToTickSize(price: Double, tickSize: Double = 0.25f): Double = {
        // Use integer arithmetic to avoid floating point precision errors
        // For 0.25 tick: multiply by 4, round, then divide by 4
        val multiplier = (1.0 / tickSize).toInt
        (math.round(price * multiplier) / multiplier)
    }

    private def calcProfit(exitPrice: Double) = {
        val movement = orderType match {
            case OrderType.Long => (exitPrice - entryPrice)
            case OrderType.Short => (entryPrice - exitPrice)
        }

        val pricePerPoint = contractType match {
            case ContractType.ES => Prices.MiniDollarsPerPoint
            case ContractType.MES => Prices.MicroDollarsPerPoint
        }

        val gain = movement * pricePerPoint * contracts
        gain - fees
    }
}
