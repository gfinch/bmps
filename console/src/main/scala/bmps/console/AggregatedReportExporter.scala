package bmps.console

import cats.effect.{IO, IOApp, ExitCode}
import okhttp3._
import io.circe.parser._
import io.circe.generic.auto._
import java.io.{File, PrintWriter}
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter

/**
 * Console client for exporting aggregated order report to CSV.
 * 
 * Usage: AggregatedReportExporter [output-file] [accountId]
 * Example: AggregatedReportExporter orders.csv
 * Example: AggregatedReportExporter orders.csv MyAccount123
 * 
 * The client will:
 * 1. Fetch the aggregated order report from the REST API
 * 2. Extract all SerializableOrders from the report
 * 3. Export all orders to a CSV file with all order features
 * 
 * Default output file: aggregated_orders.csv
 * Default accountId: (uses lead account)
 */
object AggregatedReportExporter extends IOApp {

  private val API_URL = "http://localhost:8081"
  private val client = new OkHttpClient()
  private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("America/New_York"))

  // Response models matching the REST API
  case class Level(value: Float)
  
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

  case class SerializableOrder(
    low: Level, 
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

  case class OrderReport(
    orders: List[SerializableOrder],
    winning: Int,
    losing: Int,
    averageWinDollars: Double,
    averageLossDollars: Double,
    maxDrawdownDollars: Double,
    totalPnL: Double
  )

  case class ErrorResponse(error: String)

  override def run(args: List[String]): IO[ExitCode] = {
    val outputFile = if (args.nonEmpty) args(0) else "aggregated_orders.csv"
    val accountId = if (args.length > 1) Some(args(1)) else None
    
    exportAggregatedReport(outputFile, accountId).as(ExitCode.Success).handleErrorWith { err =>
      IO.println(s"Error: ${err.getMessage}") *>
      IO.pure(ExitCode.Error)
    }
  }

  /**
   * Fetch aggregated report and export to CSV
   */
  private def exportAggregatedReport(outputFile: String, accountId: Option[String]): IO[Unit] = {
    for {
      _ <- IO.println("=" * 80)
      _ <- IO.println("Aggregated Order Report Exporter")
      _ <- IO.println("=" * 80)
      _ <- IO.println(s"Output file: $outputFile")
      _ <- accountId.map(id => IO.println(s"Account ID: $id")).getOrElse(IO.println("Account ID: (lead account)"))
      _ <- IO.println("")
      
      report <- fetchAggregatedReport(accountId)
      _ <- IO.println(s"✓ Fetched ${report.orders.length} orders from server")
      _ <- IO.println("")
      
      _ <- exportOrdersToCSV(report, outputFile)
      _ <- IO.println(s"✓ Exported ${report.orders.length} orders to $outputFile")
      _ <- IO.println("")
      
      _ <- printReportSummary(report)
      _ <- IO.println("")
      _ <- IO.println("=" * 80)
      _ <- IO.println("Export completed successfully!")
      _ <- IO.println("=" * 80)
    } yield ()
  }

  /**
   * Fetch aggregated order report from REST API
   */
  private def fetchAggregatedReport(accountId: Option[String]): IO[OrderReport] = {
    IO.blocking {
      val url = accountId match {
        case Some(id) => s"$API_URL/aggregateOrderReport?accountId=$id"
        case None => s"$API_URL/aggregateOrderReport"
      }
      
      val request = new Request.Builder()
        .url(url)
        .get()
        .build()

      val response = client.newCall(request).execute()
      try {
        if (!response.isSuccessful) {
          val errorBody = response.body().string()
          val errorMsg = parse(errorBody).flatMap(_.as[ErrorResponse]) match {
            case Right(err) => err.error
            case Left(_) => s"HTTP ${response.code()}: ${response.message()}"
          }
          throw new RuntimeException(s"Failed to fetch aggregated report: $errorMsg")
        }
        
        val body = response.body().string()
        val json = parse(body).getOrElse(throw new RuntimeException(s"Failed to parse response: $body"))
        json.as[OrderReport].getOrElse(
          throw new RuntimeException(s"Failed to decode response.")
        )
      } finally {
        response.close()
      }
    }
  }

  /**
   * Export orders to CSV file
   */
  private def exportOrdersToCSV(report: OrderReport, filename: String): IO[Unit] = {
    IO.blocking {
      val file = new File(filename)
      val writer = new PrintWriter(file)
      
      try {
        // Write CSV header
        writer.println(
          "Timestamp,Timestamp_ET,Order_Type,Entry_Type,Status," +
          "Low,High,Entry_Point,Stop_Loss,Take_Profit," +
          "Profit_Multiplier,Risk_Dollars,Contracts,At_Risk,Potential," +
          "Placed_Timestamp,Placed_Timestamp_ET," +
          "Filled_Timestamp,Filled_Timestamp_ET," +
          "Close_Timestamp,Close_Timestamp_ET," +
          "Cancel_Reason"
        )
        
        // Write each order as a CSV row
        report.orders.sortBy(_.timestamp).foreach { order =>
          writer.println(
            s"${order.timestamp}," +
            s"${formatTimestamp(order.timestamp)}," +
            s"${orderTypeToString(order.orderType)}," +
            s"${entryTypeToString(order.entryType)}," +
            s"${orderStatusToString(order.status)}," +
            s"${order.low.value}," +
            s"${order.high.value}," +
            s"${order.entryPoint}," +
            s"${order.stopLoss}," +
            s"${order.takeProfit}," +
            s"${order.profitMultiplier}," +
            s"${order.riskDollars}," +
            s"${order.contracts}," +
            s"${order.atRisk}," +
            s"${order.potential}," +
            s"${order.placedTimestamp.getOrElse("")}," +
            s"${order.placedTimestamp.map(formatTimestamp).getOrElse("")}," +
            s"${order.filledTimestamp.getOrElse("")}," +
            s"${order.filledTimestamp.map(formatTimestamp).getOrElse("")}," +
            s"${order.closeTimestamp.getOrElse("")}," +
            s"${order.closeTimestamp.map(formatTimestamp).getOrElse("")}," +
            s"${escapeCsvField(order.cancelReason.getOrElse(""))}"
          )
        }
      } finally {
        writer.close()
      }
    }
  }

  /**
   * Print report summary
   */
  private def printReportSummary(report: OrderReport): IO[Unit] = {
    IO.println("Report Summary:") *>
    IO.println("-" * 80) *>
    IO.println(f"Total Orders: ${report.orders.length}") *>
    IO.println(f"Winning Trades: ${report.winning}") *>
    IO.println(f"Losing Trades: ${report.losing}") *>
    IO.println(f"Win Rate: ${if (report.winning + report.losing > 0) (report.winning.toDouble / (report.winning + report.losing) * 100) else 0.0}%.2f%%") *>
    IO.println(f"Average Win: $$${report.averageWinDollars}%.2f") *>
    IO.println(f"Average Loss: $$${report.averageLossDollars}%.2f") *>
    IO.println(f"Total P&L: $$${report.totalPnL}%.2f") *>
    IO.println(f"Max Drawdown: $$${report.maxDrawdownDollars}%.2f") *>
    IO.println("-" * 80)
  }

  /**
   * Format timestamp to human-readable string in ET timezone
   */
  private def formatTimestamp(timestamp: Long): String = {
    timestampFormatter.format(Instant.ofEpochMilli(timestamp))
  }

  /**
   * Convert OrderType to string
   */
  private def orderTypeToString(orderType: OrderType): String = orderType match {
    case OrderType.Long => "Long"
    case OrderType.Short => "Short"
  }

  /**
   * Convert EntryType to string
   */
  private def entryTypeToString(entryType: EntryType): String = entryType match {
    case EntryType.EngulfingOrderBlock => "EngulfingOrderBlock"
    case EntryType.FairValueGapOrderBlock => "FairValueGapOrderBlock"
    case EntryType.InvertedFairValueGapOrderBlock => "InvertedFairValueGapOrderBlock"
    case EntryType.BreakerBlockOrderBlock => "BreakerBlockOrderBlock"
    case EntryType.MarketStructureShiftOrderBlock => "MarketStructureShiftOrderBlock"
    case EntryType.SupermanOrderBlock => "SupermanOrderBlock"
    case EntryType.JediOrderBlock => "JediOrderBlock"
  }

  /**
   * Convert OrderStatus to string
   */
  private def orderStatusToString(status: OrderStatus): String = status match {
    case OrderStatus.Planned => "Planned"
    case OrderStatus.Placed => "Placed"
    case OrderStatus.Filled => "Filled"
    case OrderStatus.Profit => "Profit"
    case OrderStatus.Loss => "Loss"
    case OrderStatus.Cancelled => "Cancelled"
  }

  /**
   * Escape CSV field (handle commas, quotes, newlines)
   */
  private def escapeCsvField(field: String): String = {
    if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
      "\"" + field.replace("\"", "\"\"") + "\""
    } else {
      field
    }
  }
}
