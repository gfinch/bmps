package bmps.core.io

import bmps.core.models._
import bmps.core.strategies.exit.SimpleExitStrategy
import java.time.LocalDate
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.net.URI
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{GetObjectRequest, PutObjectRequest, NoSuchKeyException}
import software.amazon.awssdk.core.sync.RequestBody
import scala.jdk.CollectionConverters._
import java.nio.charset.StandardCharsets
import scala.util.Try
import cats.effect.IO
import cats.effect.unsafe.implicits.global

trait OrderSink {
    def saveOrders(state: SystemState): Unit
    def loadAllPastOrders(state: SystemState): SystemState
    def loadPastOrders(tradingDate: LocalDate, state: SystemState): SystemState

    def loadPastOrders(state: SystemState, daysPast: Int): SystemState = {
        val today = state.tradingDay
        val daysToLoad = (0 to daysPast).map(i => today.minusDays(i)).toList.sorted
        daysToLoad.foldLeft(state) { (currentState, day) =>
            loadPastOrders(day, currentState)
        }
    }
    
    protected def appendOrders(orders: List[Order], state: SystemState): SystemState = {
        state.copy(recentOrders = (state.recentOrders ++ orders).sortBy(_.timestamp))
    }
}

class CSVFileOrderSink(filePath: String) extends OrderSink {
    //NOTE: filePath might be an s3 path (prefixed with s3://) or a local file path (prefixed with file://)
    
    private val Header = "tradingDay,timestamp,orderType,status,contractType,contract,contracts,entryStrategy,exitStrategy,entryPrice,stopLoss,trailStop,takeProfit,exitPrice,placedTimestamp,filledTimestamp,closeTimestamp"

    private lazy val s3Client = S3Client.create()

    override def saveOrders(state: SystemState): Unit = {
        IO.blocking {
            val existingLines = readLines()
            val tradingDayStr = state.tradingDay.toString
            val otherDayLines = existingLines.filterNot(line => line.startsWith(tradingDayStr) || line == Header)
            val newOrderLines = state.orders.filter(_.isProfitOrLoss).map(order => serializeOrder(order, state.tradingDay))

            val allLines = (Header :: otherDayLines ::: newOrderLines)
            writeLines(allLines)
        }.handleErrorWith { error =>
            IO.println(s"[OrderSink] Error saving orders: ${error.getMessage}") >>
            IO(error.printStackTrace())
        }.unsafeRunSync()
    }

    override def loadPastOrders(tradingDate: LocalDate, state: SystemState): SystemState = {
        IO.blocking {
            val lines = readLines()
            val tradingDayStr = tradingDate.toString
            
            val orders = lines
                .filter(_.startsWith(tradingDayStr))
                .flatMap(deserializeOrder)
                
            super.appendOrders(orders, state)
        }.handleErrorWith { error =>
            IO.println(s"[OrderSink] Error loading past orders for $tradingDate: ${error.getMessage}") >>
            IO(error.printStackTrace()) >>
            IO.pure(state)
        }.unsafeRunSync()
    }

    override def loadAllPastOrders(state: SystemState): SystemState = {
        IO.blocking {
            val lines = readLines()
            val orders = lines.flatMap(deserializeOrder)
            super.appendOrders(orders, state)
        }.handleErrorWith { error =>
            IO.println(s"[OrderSink] Error loading all past orders.") >>
            IO(error.printStackTrace()) >>
            IO.pure(state)
        }.unsafeRunSync()
    }

    private def readLines(): List[String] = {
        IO.blocking {
            if (filePath.startsWith("s3://")) {
                val uri = new URI(filePath)
                val bucket = uri.getHost
                val key = uri.getPath.stripPrefix("/")
                try {
                    val request = GetObjectRequest.builder().bucket(bucket).key(key).build()
                    val response = s3Client.getObjectAsBytes(request)
                    new String(response.asByteArray(), StandardCharsets.UTF_8).linesIterator.toList
                } catch {
                    case _: NoSuchKeyException => List.empty
                    case e: Exception => 
                        println(s"Error reading from S3: ${e.getMessage}")
                        List.empty
                }
            } else {
                val path = if (filePath.startsWith("file://")) Paths.get(new URI(filePath)) else Paths.get(filePath)
                if (Files.exists(path)) {
                    Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toList
                } else {
                    List.empty
                }
            }
        }.unsafeRunSync()
    }

    private def writeLines(lines: List[String]): Unit = {
        IO.blocking {
            val content = lines.mkString("\n")
            if (filePath.startsWith("s3://")) {
                val uri = new URI(filePath)
                val bucket = uri.getHost
                val key = uri.getPath.stripPrefix("/")
                val request = PutObjectRequest.builder().bucket(bucket).key(key).build()
                s3Client.putObject(request, RequestBody.fromString(content, StandardCharsets.UTF_8))
            } else {
                val path = if (filePath.startsWith("file://")) Paths.get(new URI(filePath)) else Paths.get(filePath)
                Option(path.getParent).foreach(Files.createDirectories(_))
                Files.write(path, lines.asJava, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            }
        }.unsafeRunSync()
    }

    private def serializeOrder(order: Order, tradingDay: LocalDate): String = {
        val fields = List(
            tradingDay.toString,
            order.timestamp.toString,
            order.orderType.toString,
            order.status.toString,
            order.contractType.toString,
            order.contract,
            order.contracts.toString,
            order.entryStrategy.description,
            order.exitStrategy.getClass.getSimpleName.stripSuffix("$"),
            order.entryPrice.toString,
            order.stopLoss.toString,
            order.trailStop.toString,
            order.takeProfit.toString,
            order.exitPrice.map(_.toString).getOrElse(""),
            order.placedTimestamp.map(_.toString).getOrElse(""),
            order.filledTimestamp.map(_.toString).getOrElse(""),
            order.closeTimestamp.map(_.toString).getOrElse("")
        )
        fields.map(escapeCsv).mkString(",")
    }

    private def escapeCsv(field: String): String = {
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
    }

    private def deserializeOrder(line: String): Option[Order] = {
        val parts = parseCsvLine(line)
        if (parts.length < 17) return None

        Try {
            // parts(0) is tradingDay, ignored for Order object itself
            val timestamp = parts(1).toLong
            val orderType = parts(2) match {
                case "Long" => OrderType.Long
                case "Short" => OrderType.Short
                case _ => throw new IllegalArgumentException(s"Unknown order type: ${parts(2)}")
            }
            val status = parseOrderStatus(parts(3))
            val contractType = parseContractType(parts(4))
            val contract = parts(5)
            val contracts = parts(6).toInt
            val entryStrategy = EntryStrategy(parts(7))
            val exitStrategy = createExitStrategy(parts(8))
            val entryPrice = parts(9).toDouble
            val stopLoss = parts(10).toDouble
            val trailStop = if (parts(11).isEmpty) false else parts(11).toBoolean
            val takeProfit = parts(12).toDouble
            val exitPrice = if (parts(13).isEmpty) None else Some(parts(13).toDouble)
            val placedTimestamp = if (parts(14).isEmpty) None else Some(parts(14).toLong)
            val filledTimestamp = if (parts(15).isEmpty) None else Some(parts(15).toLong)
            val closeTimestamp = if (parts(16).isEmpty) None else Some(parts(16).toLong)

            Order(
                timestamp = timestamp,
                orderType = orderType,
                status = status,
                contractType = contractType,
                contract = contract,
                contracts = contracts,
                entryStrategy = entryStrategy,
                exitStrategy = exitStrategy,
                entryPrice = entryPrice,
                stopLoss = stopLoss,
                trailStop = trailStop,
                takeProfit = takeProfit,
                exitPrice = exitPrice,
                placedTimestamp = placedTimestamp,
                filledTimestamp = filledTimestamp,
                closeTimestamp = closeTimestamp
            )
        }.toOption
    }

    private def parseCsvLine(line: String): List[String] = {
        val result = scala.collection.mutable.ListBuffer[String]()
        val currentField = new StringBuilder
        var inQuotes = false
        var i = 0
        
        while (i < line.length) {
            val c = line(i)
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length && line(i + 1) == '"') {
                        currentField.append('"')
                        i += 1
                    } else {
                        inQuotes = false
                    }
                } else {
                    currentField.append(c)
                }
            } else {
                if (c == '"') {
                    inQuotes = true
                } else if (c == ',') {
                    result += currentField.toString
                    currentField.clear()
                } else {
                    currentField.append(c)
                }
            }
            i += 1
        }
        result += currentField.toString
        result.toList
    }

    private def parseOrderStatus(s: String): OrderStatus = {
        s match {
            case "Planned" => OrderStatus.Planned
            case "PlaceNow" => OrderStatus.PlaceNow
            case "Placed" => OrderStatus.Placed
            case "Filled" => OrderStatus.Filled
            case "Profit" => OrderStatus.Profit
            case "Loss" => OrderStatus.Loss
            case "Cancelled" => OrderStatus.Cancelled
            case _ => OrderStatus.Planned
        }
    }

    private def parseContractType(s: String): ContractType = {
        s match {
            case "ES" => ContractType.ES
            case "MES" => ContractType.MES
            case _ => throw new IllegalArgumentException(s"Unknown contract type: $s")
        }
    }

    private def createExitStrategy(className: String): ExitStrategy = {
        className match {
            case "SimpleExitStrategy" => new SimpleExitStrategy()
            case _ => new SimpleExitStrategy() // Default fallback
        }
    }
}