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

object AppLauncher extends IOApp.Simple {

  lazy val config = ConfigFactory.load()
  
  /** Load AccountBrokers from configuration */
  private def loadAccountBrokers(): LeadAccountBroker = {
    val riskPerTradeUI = config.getDouble("bmps.core.risk-per-trade-ui")
    val brokerConfigs = config.getConfigList("bmps.core.account-brokers").asScala.toList
    
    val brokers = brokerConfigs.map { brokerConfig =>
      val accountId = brokerConfig.getString("account-id")
      val riskPerTrade = brokerConfig.getDouble("risk-per-trade")
      val brokerTypeString = brokerConfig.getString("broker-type")
      
      val brokerType = brokerTypeString match {
        case "SimulatedAccountBroker" => BrokerType.SimulatedAccountBroker
        case "TradeovateBroker" => BrokerType.TradovateAccountBroker
        case _ => throw new IllegalArgumentException(s"Unknown broker type: $brokerTypeString")
      }
      
      AccountBrokerFactory.buildAccountBroker(accountId, riskPerTrade, brokerType)
    }

    new LeadAccountBroker(brokers, riskPerTradeUI)
  }

  private def loadDataSources(): (DataSource, DataSource) = {
    val dataSource = config.getString("bmps.core.candle-datasource")
     dataSource match {
      case "DatabentoSource" =>
        (new DatabentoSource(CandleDuration.OneHour), new DatabentoSource(CandleDuration.OneMinute))
      case "ParquetSource" => 
        (new ParquetSource(CandleDuration.OneHour), new ParquetSource(CandleDuration.OneMinute))
      case _ => 
        throw new IllegalArgumentException(s"$dataSource not supported.")
    }
  }

  /** Create the shared pieces and return resources that include a running
    * REST API server on port 8081 for phase control and event retrieval.
    */
  def createResource(restPort: Int = 8081): Resource[IO, (Ref[IO, SystemState], PhaseController, org.http4s.server.Server)] = for {
    stateRef <- Resource.eval(Ref.of[IO, SystemState](SystemState()))
    eventStore <- Resource.eval(EventStore.create())
    sem <- Resource.eval(Semaphore[IO](1))

    // Load account brokers and data sources from configuration
    leadAccount = loadAccountBrokers()
    (oneHourSource, oneMinuteSource) = loadDataSources()

    // populate with PhaseRunner instances, using configured AccountBrokers for TradingPhase
    runners: Map[SystemStatePhase, PhaseRunner] = Map(
      SystemStatePhase.Planning -> PlanningPhaseBuilder.build(oneHourSource),
      SystemStatePhase.Preparing -> PreparingPhaseBuilder.build(oneMinuteSource),
      SystemStatePhase.Trading -> TradingPhaseBuilder.build(leadAccount, oneMinuteSource)
    )
    controller = new PhaseController(stateRef, eventStore, runners, sem)
    // Create report service
    reportService = new ReportService(eventStore, leadAccount)
    // Start REST API server on port 8081
    restServer <- RestServer.resource(controller, eventStore, leadAccount, reportService, restPort)
  } yield (stateRef, controller, restServer)

  val run: IO[Unit] = createResource().use { case (stateRef, controller, restServer) =>
    IO.println("REST API server started on port 8081") *>
    IO.never
  }

}

