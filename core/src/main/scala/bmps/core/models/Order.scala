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
}

case class Order(low: Level, 
                     high: Level, 
                     timestamp: Long, 
                     orderType: OrderType,
                     entryType: EntryType, 
                     status: OrderStatus = OrderStatus.Planned,
                     profitMultiplier: Double = 2.0,
                     riskDollars: Double = 550.0,
                     placedTimestamp: Option[Long] = None,
                     filledTimestamp: Option[Long] = None,
                     closeTimestamp: Option[Long] = None) {

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

    lazy val contracts: Int = Math.floor(riskDollars / atRiskPerContract).toInt
    lazy val atRisk = atRiskPerContract * contracts
    lazy val potential = potentialPerContract * contracts
    lazy val isViable = contracts <= MaxContracts
    lazy val isActive = (status == OrderStatus.Placed || status == OrderStatus.Filled)
    lazy val direction: Direction = if (orderType == OrderType.Long) Direction.Up else Direction.Down

    def adjustState(candle: Candle): Order = {
        (status, orderType)  match {
            case (Planned, Long) if (candle.timestamp - timestamp > TenMinutes) && (candle.close.value >= takeProfit || candle.close.value <= stopLoss) => 
                this.copy(status = Cancelled, closeTimestamp = Some(candle.timestamp))
            case (Planned, Short) if (candle.timestamp - timestamp > TenMinutes) && (candle.close.value <= takeProfit || candle.close.value >= stopLoss) =>
                this.copy(status = Cancelled, closeTimestamp = Some(candle.timestamp))
            case (Planned, _) => this

            case (Placed, Long) if candle.low.value <= stopLoss && candle.high.value >= takeProfit && candle.isBullish =>
                this.copy(status = Loss, filledTimestamp = Some(candle.timestamp), closeTimestamp = Some(candle.timestamp))
            case (Placed, Long) if candle.low.value <= stopLoss && candle.high.value >= takeProfit && candle.isBearish =>
                this.copy(status = Profit, filledTimestamp = Some(candle.timestamp), closeTimestamp = Some(candle.timestamp))
            case (Placed, Long) if candle.low.value <= entryPoint && candle.high.value >= takeProfit => 
                this.copy(status = Profit, filledTimestamp = Some(candle.timestamp), closeTimestamp = Some(candle.timestamp))
            case (Placed, Long) if candle.high.value >= takeProfit =>
                this.copy(status = Cancelled, closeTimestamp = Some(candle.timestamp))
            // case (Placed, Long) if candle.low.value <= stopLoss => 
            //     this.copy(status = Loss, filledTimestamp = Some(candle.timestamp), closeTimestamp = Some(candle.timestamp))
            case (Placed, Long) if candle.low.value >= entryPoint => 
                this.copy(status = Filled, filledTimestamp = Some(candle.timestamp))

            case (Placed, Short) if candle.high.value >= stopLoss && candle.low.value <= takeProfit && candle.isBearish =>
                this.copy(status = Loss, filledTimestamp = Some(candle.timestamp), closeTimestamp = Some(candle.timestamp))
            case (Placed, Short) if candle.high.value >= stopLoss && candle.low.value <= takeProfit && candle.isBullish =>
                this.copy(status = Profit, filledTimestamp = Some(candle.timestamp), closeTimestamp = Some(candle.timestamp))
            case (Placed, Short) if candle.high.value >= entryPoint && candle.low.value <= takeProfit =>
                this.copy(status = Profit, filledTimestamp = Some(candle.timestamp), closeTimestamp = Some(candle.timestamp))
            case (Placed, Short) if candle.low.value <= takeProfit =>
                this.copy(status = Cancelled, closeTimestamp = Some(candle.timestamp))
            // case (Placed, Short) if candle.high.value >= stopLoss =>
            //     this.copy(status = Loss, filledTimestamp = Some(candle.timestamp), closeTimestamp = Some(candle.timestamp))
            case (Placed, Short) if candle.low.value <= entryPoint =>
                this.copy(status = Filled, filledTimestamp = Some(candle.timestamp))

            case (Filled, Long) if candle.high.value >= takeProfit && candle.low.value <= stopLoss && candle.isBullish =>
                this.copy(status = Loss, closeTimestamp = Some(candle.timestamp))
            case (Filled, Long) if candle.high.value >= takeProfit && candle.low.value <= stopLoss && candle.isBearish =>
                this.copy(status = Profit, closeTimestamp = Some(candle.timestamp))
            case (Filled, Long) if candle.high.value >= takeProfit =>
                this.copy(status = Profit, closeTimestamp = Some(candle.timestamp))
            case (Filled, Long) if candle.low.value <= stopLoss =>
                this.copy(status = Loss, closeTimestamp = Some(candle.timestamp))

            case (Filled, Short) if candle.low.value <= takeProfit && candle.high.value >= stopLoss && candle.isBearish =>
                this.copy(status = Loss, closeTimestamp = Some(candle.timestamp))
            case (Filled, Short) if candle.low.value <= takeProfit && candle.high.value >= stopLoss && candle.isBullish =>
                this.copy(status = Profit, closeTimestamp = Some(candle.timestamp))
            case (Filled, Short) if candle.low.value <= takeProfit =>
                this.copy(status = Profit, closeTimestamp = Some(candle.timestamp))
            case (Filled, Short) if candle.high.value >= stopLoss =>
                this.copy(status = Loss, closeTimestamp = Some(candle.timestamp))

            case _ => this
        }
    }

    def cancelOrSell(candle: Candle): Order = {
        if (closeTimestamp.isEmpty) {
            (status, orderType) match {
                case (Filled, Long) if candle.close.value >= entryPoint =>
                    this.copy(status = Profit, closeTimestamp = Some(candle.timestamp))
                case (Filled, Long) if candle.close.value <= entryPoint =>
                    this.copy(status = Loss, closeTimestamp = Some(candle.timestamp))
                case (Filled, Short) if candle.close.value <= entryPoint =>
                    this.copy(status = Profit, closeTimestamp = Some(candle.timestamp))
                case (Filled, Short) if candle.close.value >= entryPoint =>
                    this.copy(status = Loss, closeTimestamp = Some(candle.timestamp))
                case _ => 
                    this.copy(status = Cancelled, closeTimestamp = Some(candle.timestamp))
            }
        } else this
    }
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
                             potential: Double
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
    def fromOrder(order: Order): SerializableOrder =
      SerializableOrder(
        low = order.low,
        high = order.high,
        timestamp = order.timestamp,
        orderType = order.orderType,
        entryType = order.entryType,
        status = order.status,
        profitMultiplier = order.profitMultiplier,
        riskDollars = order.riskDollars,
        placedTimestamp = order.placedTimestamp,
        filledTimestamp = order.filledTimestamp,
        closeTimestamp = order.closeTimestamp,
        entryPoint = order.entryPoint.toDouble,
        stopLoss = order.stopLoss.toDouble,
        takeProfit = order.takeProfit.toDouble,
        contracts = order.contracts,
        atRisk = order.atRisk,
        potential = order.potential
      )
}
