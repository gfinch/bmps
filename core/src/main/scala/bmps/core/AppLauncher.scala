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

object AppLauncher extends IOApp.Simple {
  
  /** Load AccountBrokers from configuration */
  private def loadAccountBrokers(): LeadAccountBroker = {
    val config = ConfigFactory.load()
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

  /** Create the shared pieces and return resources that include a running
    * REST API server on port 8081 for phase control and event retrieval.
    */
  def createResource(restPort: Int = 8081): Resource[IO, (Ref[IO, SystemState], PhaseController, org.http4s.server.Server)] = for {
    stateRef <- Resource.eval(Ref.of[IO, SystemState](SystemState()))
    eventStore <- Resource.eval(EventStore.create())
    sem <- Resource.eval(Semaphore[IO](1))
    // Load account brokers from configuration
    leadAccount = loadAccountBrokers()
    // populate with PhaseRunner instances, using configured AccountBrokers for TradingPhase
    runners: Map[SystemStatePhase, PhaseRunner] = Map(
      SystemStatePhase.Planning -> PlanningPhaseBuilder.build(),
      SystemStatePhase.Preparing -> PreparingPhaseBuilder.build(),
      SystemStatePhase.Trading -> TradingPhaseBuilder.build(leadAccount)
    )
    controller = new PhaseController(stateRef, eventStore, runners, sem)
    // Create report service
    reportService = new ReportService(eventStore, leadAccount)
    // Start REST API server on port 8081
    restServer <- RestServer.resource(controller, eventStore, leadAccount, reportService, restPort)
  } yield (stateRef, controller, restServer)

  val run: IO[Unit] = createResource().use { case (stateRef, controller, restServer) =>
    IO.println("REST API server started on port 8081") *>
    IO.println("Use PUT /phase/start to start a phase") *>
    IO.println("Use GET /phase/events?tradingDate=YYYY-MM-DD&phase=<phase> to get events") *>
    IO.never
  }

}

