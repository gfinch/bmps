package bmps.core

import cats.effect.{IO, Resource}
import cats.effect.Ref
import cats.effect.std.Semaphore
import bmps.core.models.SystemState
import bmps.core.models.SystemStatePhase
import bmps.core.api.run.{PhaseController, PhaseRunner}
import bmps.core.api.io.RestServer
import bmps.core.api.storage.EventStore
import bmps.core.phases.PlanningPhaseBuilder
import bmps.core.phases.PreparingPhaseBuilder
import bmps.core.phases.TradingPhaseBuilder
import cats.effect.{IO, IOApp}
import java.time.LocalDate
import scala.concurrent.duration._
import bmps.core.brokers.{AccountBroker, AccountBrokerFactory, BrokerType}
import com.typesafe.config.ConfigFactory
import scala.jdk.CollectionConverters._
import bmps.core.brokers.LeadAccountBroker
import bmps.core.services.ReportService
import bmps.core.models.CandleDuration
import bmps.core.io.DatabentoSource
import bmps.core.models.Candle
import bmps.core.io.DataSource
import bmps.core.io.ParquetSource
import bmps.core.io.DualSource
import bmps.core.utils.MarketCalendar
import com.typesafe.config.Config
import bmps.core.utils.MarketCalendar
import bmps.core.services.OrderService
import bmps.core.services.TechnicalAnalysisService
import bmps.core.services.MLStrategyService
import bmps.core.brokers.rest.InferenceBroker
import okhttp3.OkHttpClient
import bmps.core.io.CSVFileOrderSink
import bmps.core.io.OrderSink
import bmps.core.services.TechnicalAnalysisOrderService

object AppLauncher extends IOApp.Simple {

  lazy val config = ConfigFactory.load()
  
  /** Load AccountBrokers from configuration */
  private def loadAccountBrokers(): LeadAccountBroker = {
    val riskPerTradeUI = config.getDouble("bmps.core.risk-per-trade-ui")
    val brokerConfigs: List[Config] = config.getConfigList("bmps.core.account-brokers").asScala.toList
    
    val brokers = brokerConfigs.map { brokerConfig =>
      val brokerTypeString = brokerConfig.getString("broker-type")
      
      val brokerType = brokerTypeString match {
        case "SimulatedAccountBroker" => BrokerType.SimulatedAccountBroker
        case "TradovateBroker" => BrokerType.TradovateAccountBroker
        case _ => throw new IllegalArgumentException(s"Unknown broker type: $brokerTypeString")
      }

      val brokerDetails = brokerConfig.getConfig("broker-config")
      
      AccountBrokerFactory.buildAccountBroker(brokerType, brokerDetails)
    }

    new LeadAccountBroker(brokers, riskPerTradeUI)
  }

  private def loadDataSources(): (DataSource, DataSource, DataSource) = {
    val dataSource = config.getString("bmps.core.candle-datasource")
     dataSource match {
      case "DatabentoSource" =>
        (
          new DatabentoSource(Set(CandleDuration.OneHour)), 
          new DatabentoSource(Set(CandleDuration.OneMinute)),
          new DatabentoSource(Set(CandleDuration.OneSecond, CandleDuration.OneMinute))
        )
      case "ParquetSource" => 
        (
          new ParquetSource(Set[CandleDuration](CandleDuration.OneHour)), 
          new ParquetSource(Set[CandleDuration](CandleDuration.OneMinute)),
          new ParquetSource(Set[CandleDuration](CandleDuration.OneSecond, CandleDuration.OneMinute)),
        )
      case "DualSource" =>
        (
          new DualSource(Set[CandleDuration](CandleDuration.OneHour)),
          new DualSource(Set[CandleDuration](CandleDuration.OneMinute)),
          new DualSource(Set[CandleDuration](CandleDuration.OneSecond, CandleDuration.OneMinute))
        )
      case _ => 
        throw new IllegalArgumentException(s"$dataSource not supported.")
    }
  }

  private lazy val orderSink: OrderSink = {
    val sinkPath = config.getString("bmps.core.order-sink")
    new CSVFileOrderSink(sinkPath)
  }

  /** Create the shared pieces and return resources that include a running
    * REST API server on port 8081 for phase control and event retrieval.
    */
  def createResource(restPort: Int, readOnly: Boolean): Resource[IO, (Ref[IO, SystemState], PhaseController, EventStore, org.http4s.server.Server)] = for {
    stateRef <- Resource.eval(Ref.of[IO, SystemState](SystemState()))
    eventStore <- Resource.eval(EventStore.create())
    sem <- Resource.eval(Semaphore[IO](1))

    // Load account brokers and data sources from configuration
    leadAccount = loadAccountBrokers()
    (planningSource, preparingSource, tradingSource) = loadDataSources()

    technicalAnalysisService = new TechnicalAnalysisService()
    techAnalysisOrderService = new TechnicalAnalysisOrderService()
    orderService = new OrderService(technicalAnalysisService, techAnalysisOrderService)

    // populate with PhaseRunner instances, using configured AccountBrokers for TradingPhase
    runners: Map[SystemStatePhase, PhaseRunner] = Map(
      SystemStatePhase.Planning -> PlanningPhaseBuilder.build(planningSource),
      SystemStatePhase.Preparing -> PreparingPhaseBuilder.build(preparingSource),
      SystemStatePhase.Trading -> TradingPhaseBuilder.build(leadAccount, tradingSource, orderService, orderSink)
    )

    controller = new PhaseController(stateRef, eventStore, runners, sem)
    // Create report service
    reportService = new ReportService(eventStore, leadAccount)
    // Start REST API server on port 8081
    restServer <- RestServer.resource(controller, eventStore, leadAccount, reportService, restPort, readOnly)
  } yield (stateRef, controller, eventStore, restServer)

  /** Poll until a phase completes for a given trading date */
  private def pollUntilComplete(phase: SystemStatePhase, tradingDate: LocalDate, eventStore: EventStore): IO[Unit] = {
    (IO.sleep(1.second) *> eventStore.isComplete(tradingDate, phase))
      .iterateUntil(_ == true)
      .void
  }
  
  /** Process a single trading day through all three phases */
  private def processTradingDay(tradingDate: LocalDate, controller: PhaseController, eventStore: EventStore): IO[Unit] = {
    val dateStr = tradingDate.toString
    val options = Some(Map("tradingDate" -> dateStr))
    
    for {
      _ <- IO.println(s"Processing trading day: $dateStr")
      _ <- IO.println("-" * 60)
      
      // Planning phase
      _ <- IO.println("  Starting planning phase...")
      _ <- controller.startPhase(SystemStatePhase.Planning, options)
      _ <- pollUntilComplete(SystemStatePhase.Planning, tradingDate, eventStore)
      _ <- IO.println("  ✓ Planning phase completed")
      
      // Preparing phase
      _ <- IO.println("  Starting preparing phase...")
      _ <- controller.startPhase(SystemStatePhase.Preparing, options)
      _ <- pollUntilComplete(SystemStatePhase.Preparing, tradingDate, eventStore)
      _ <- IO.println("  ✓ Preparing phase completed")
      
      // Trading phase
      _ <- IO.println("  Starting trading phase...")
      _ <- controller.startPhase(SystemStatePhase.Trading, options)
      _ <- pollUntilComplete(SystemStatePhase.Trading, tradingDate, eventStore)
      _ <- IO.println("  ✓ Trading phase completed")
      
      _ <- IO.println(s"✓ Completed trading day: $dateStr")
    } yield ()
  }
  
  /** Logic to run after server starts in read-only mode */
  private def readOnlyModeLogic(stateRef: Ref[IO, SystemState], controller: PhaseController, eventStore: EventStore): IO[Unit] = {
    for {
      _ <- IO.println("Running in read-only mode...")
      _ <- IO.println("")
      
      today <- IO.pure(LocalDate.now())
      _ <- IO.println(s"Today's date: $today")
      
      _ <- if (MarketCalendar.isTradingDay(today)) {
        for {
          _ <- IO.println("Today is a trading day. Processing...")
          _ <- processTradingDay(today, controller, eventStore)
          _ <- IO.println("")
          _ <- IO.println("Trading day complete. Shutting down...")
        } yield ()
      } else {
        IO.println(s"Today is not a trading day. Shutting down...")
        IO.sleep(30.seconds) //This allows time for step functions to learn that today is not a trading day.
      }
    } yield ()
  }

  val run: IO[Unit] = {
    val port = sys.env.get("BMPS_PORT").map(_.toInt).getOrElse(8081)
    val readOnly = sys.env.get("BMPS_READ_ONLY_MODE").map(_.toBoolean).getOrElse(false)
    
    createResource(port, readOnly).use { case (stateRef, controller, eventStore, restServer) =>
      val mode = if (readOnly) "read only" else "read write"
      IO.println(s"REST API server started in $mode mode on port $port") *>
      (if (readOnly) readOnlyModeLogic(stateRef, controller, eventStore) else IO.never)
    }
  }

}

