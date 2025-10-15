package bmps.core.api.io

import cats.effect.{IO, Resource}
import cats.syntax.all._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.http4s.server.middleware.CORS
import org.http4s.circe._
import org.http4s.circe.CirceEntityCodec._
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import com.comcast.ip4s.{Host, Port}
import java.time.LocalDate
import java.time.format.DateTimeFormatter

import bmps.core.api.run.PhaseController
import bmps.core.api.storage.EventStore
import bmps.core.models.{SystemStatePhase, Event, EventType, Order}
import bmps.core.brokers.{LeadAccountBroker, OrderReport}
import bmps.core.services.ReportService
import bmps.core.utils.TimestampUtils
import bmps.core.utils.MarketCalendar

object RestServer {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  // Request/Response models for REST API
  case class StartPhaseRequest(
    phase: String,
    tradingDate: String,
    options: Option[Map[String, String]] = None
  ) { 
    lazy val isToday = {
      val todayNY = TimestampUtils.today()
      val reqDate = TimestampUtils.toNewYorkLocalDate(tradingDate)
      todayNY.isEqual(reqDate)
    }
  }

  case class PhaseEventsResponse(
    events: List[Event],
    isComplete: Boolean,
    newYorkOffset: Long
  )

  case class ErrorResponse(
    error: String
  )

  case class AvailableDateInfo(
    date: String,
    profitable: Option[Boolean]  // Some(true)=profit, Some(false)=loss, None=neutral
  )

  case class AvailableDatesResponse(
    dates: List[AvailableDateInfo]
  )

  /**
   * Create a REST server resource that starts on the given port.
   * The server provides endpoints for starting phases and retrieving events.
   */
  def resource(
    controller: PhaseController,
    eventStore: EventStore,
    leadAccountBroker: LeadAccountBroker,
    reportService: ReportService,
    port: Int,
    readOnly: Boolean
  ): Resource[IO, org.http4s.server.Server] = {

    val routes = HttpRoutes.of[IO] {
      
      // PUT /phase/start - Start a phase with specified trading date and options
      case req @ PUT -> Root / "phase" / "start" if !readOnly =>
        req.as[StartPhaseRequest].flatMap { request =>
          logger.info(s"Received start phase request: $request")
          
          // Parse phase
          val phaseOpt = request.phase.toLowerCase match {
            case "planning" if !request.isToday => Some(SystemStatePhase.Planning)
            case "preparing" => Some(SystemStatePhase.Preparing)
            case "trading" => Some(SystemStatePhase.Trading)
            case _ => None
          }

          phaseOpt match {
            case None if request.isToday =>
              BadRequest(ErrorResponse(s"Not allowed to backtest today's trade. (${request.tradingDate})").asJson)

            case None =>
              BadRequest(ErrorResponse(s"Invalid phase: ${request.phase}").asJson)
            
            case Some(phase) =>
              // Build options map with tradingDate
              val optionsMap = Map(
                "tradingDate" -> request.tradingDate
              ) ++ request.options.getOrElse(Map.empty)

              // Start the phase asynchronously
              controller.startPhase(phase, Some(optionsMap)).start.flatMap { fiber =>
                // Return immediately while phase runs in background
                Accepted(Map("message" -> s"Phase $phase started", "phase" -> request.phase).asJson)
              }.handleErrorWith { err =>
                logger.error(s"Failed to start phase $phase", err)
                InternalServerError(ErrorResponse(err.getMessage).asJson)
              }
          }
        }.handleErrorWith { err =>
          logger.error("Failed to parse start phase request", err)
          BadRequest(ErrorResponse(s"Invalid request: ${err.getMessage}").asJson)
        }

      case PUT -> Root / "phase" / "start" if readOnly =>
          Forbidden(ErrorResponse("Server is running in read-only mode").asJson)


      // GET /phase/events?tradingDate=YYYY-MM-DD&phase=planning - Get events for a phase
      case GET -> Root / "phase" / "events" :? TradingDateQueryParam(tradingDateStr) +& PhaseQueryParam(phaseStr) =>
        // logger.info(s"Received get events request: tradingDate=$tradingDateStr, phase=$phaseStr")
        
        // Parse trading date
        val tradingDateResult = scala.util.Try {
          TimestampUtils.toNewYorkLocalDate(tradingDateStr)
        }.toEither.left.map(err => s"Invalid trading date format: ${err.getMessage}")

        // Parse phase
        val phaseResult = phaseStr.toLowerCase match {
          case "planning" => Right(SystemStatePhase.Planning)
          case "preparing" => Right(SystemStatePhase.Preparing)
          case "trading" => Right(SystemStatePhase.Trading)
          case _ => Left(s"Invalid phase: $phaseStr")
        }

        (tradingDateResult, phaseResult) match {
          case (Right(tradingDate), Right(phase)) =>
            eventStore.getEvents(tradingDate, phase).flatMap { case (events, isComplete) =>
              val newYorkOffset = TimestampUtils.newYorkOffset(tradingDate)
              Ok(PhaseEventsResponse(events, isComplete, newYorkOffset).asJson)
            }.handleErrorWith { err =>
              logger.error(s"Failed to retrieve events for $tradingDate/$phase", err)
              InternalServerError(ErrorResponse(err.getMessage).asJson)
            }
          
          case (Left(err), _) =>
            BadRequest(ErrorResponse(err).asJson)
          
          case (_, Left(err)) =>
            BadRequest(ErrorResponse(err).asJson)
        }

      // GET /orderReport?tradingDate=YYYY-MM-DD&accountId=optional - Get order report
      case GET -> Root / "orderReport" :? TradingDateQueryParam(tradingDateStr) +& OptionalAccountIdQueryParam(accountIdOpt) =>
        // logger.info(s"Received order report request: tradingDate=$tradingDateStr, accountId=$accountIdOpt")
        
        // Parse trading date
        val tradingDateResult = scala.util.Try {
          TimestampUtils.toNewYorkLocalDate(tradingDateStr)
        }.toEither.left.map(err => s"Invalid trading date format: ${err.getMessage}")

        tradingDateResult match {
          case Right(tradingDate) =>
            reportService.getOrderReport(tradingDate, accountIdOpt).flatMap {
              case Some(report) =>
                Ok(report.asJson)
              case None =>
                NotFound(ErrorResponse(s"Broker not found with accountId: ${accountIdOpt.getOrElse("unknown")}").asJson)
            }.handleErrorWith { err =>
              logger.error(s"Failed to generate order report for $tradingDate", err)
              InternalServerError(ErrorResponse(err.getMessage).asJson)
            }
          
          case Left(err) =>
            BadRequest(ErrorResponse(err).asJson)
        }

      // GET /availableDates?accountId=optional - Get all trading dates with profitability status
      case GET -> Root / "availableDates" :? OptionalAccountIdQueryParam(accountIdOpt) =>
        logger.info(s"Received available dates request: accountId=$accountIdOpt")
        reportService.getAvailableDatesWithProfitability(accountIdOpt).flatMap { dateProfitability =>
          // Convert to response format
          val dateInfos = dateProfitability.map { dp =>
            AvailableDateInfo(
              date = dp.date.format(DateTimeFormatter.ISO_LOCAL_DATE),
              profitable = dp.profitable
            )
          }
          Ok(AvailableDatesResponse(dateInfos).asJson)
        }.handleErrorWith { err =>
          logger.error("Failed to retrieve available dates with profitability", err)
          InternalServerError(ErrorResponse(err.getMessage).asJson)
        }

      // GET /aggregateOrderReport?accountId=optional - Get aggregate order report across all dates
      case GET -> Root / "aggregateOrderReport" :? OptionalAccountIdQueryParam(accountIdOpt) =>
        logger.info(s"Received aggregate order report request: accountId=$accountIdOpt")
        reportService.getAggregateOrderReport(accountIdOpt).flatMap {
          case Some(report) =>
            Ok(report.asJson)
          case None =>
            NotFound(ErrorResponse(s"Broker not found with accountId: ${accountIdOpt.getOrElse("unknown")}").asJson)
        }.handleErrorWith { err =>
          logger.error("Failed to generate aggregate order report", err)
          InternalServerError(ErrorResponse(err.getMessage).asJson)
        }

      // GET /health - Health check endpoint
      case GET -> Root / "health" =>
        Ok(Map("status" -> "ok").asJson)

      // GET /todayIsMarketDay - Check if today is a market day
      case GET -> Root / "isTodayATradingDay" =>
        if (MarketCalendar.isTodayTradingDay()) {
          Ok(Map("todayIsATradingDay" -> true).asJson)
        } else {
          Ok(Map("todayIsATradingDay" -> false).asJson)
        }
    }

    // Apply CORS middleware to allow requests from frontend
    val corsRoutes = CORS.policy.withAllowOriginAll.apply(routes)
    val app = Router("/" -> corsRoutes).orNotFound

    val portVal = Port.fromInt(port).getOrElse(Port.fromInt(8081).get)
    val host = Host.fromString("0.0.0.0").getOrElse(Host.fromString("localhost").get)
    
    EmberServerBuilder.default[IO]
      .withHost(host)
      .withPort(portVal)
      .withHttpApp(app)
      .build
  }

  // Query parameter matchers
  object TradingDateQueryParam extends QueryParamDecoderMatcher[String]("tradingDate")
  object PhaseQueryParam extends QueryParamDecoderMatcher[String]("phase")
  object OptionalAccountIdQueryParam extends OptionalQueryParamDecoderMatcher[String]("accountId")
}
