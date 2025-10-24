package bmps.console

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.effect.Ref
import cats.effect.std.Semaphore
import java.time.LocalDate
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import scala.jdk.CollectionConverters._
import fs2.Stream

// Import core library components
import bmps.core.models.{SystemState, SystemStatePhase, Candle, CandleDuration}
import bmps.core.api.run.{PhaseController, PhaseRunner}
import bmps.core.api.storage.EventStore
import bmps.core.phases.{PlanningPhaseBuilder, PreparingPhaseBuilder, TradingPhaseBuilder}
import bmps.core.brokers.{AccountBrokerFactory, BrokerType, LeadAccountBroker}
import bmps.core.io.{DataSource, DatabentoSource}
import bmps.core.services.MarketFeaturesService
import bmps.core.utils.{MarketCalendar, TimestampUtils}

object TrainingDatasetApp extends IOApp {

  case class CliOptions(
    startDate: LocalDate,
    endDate: LocalDate,
    outputDir: String,
    lagMinutes: Int = 20
  )

  def parseArgs(args: List[String]): Either[String, CliOptions] = {
    val argMap = args.foldLeft(Map.empty[String, String]) { (acc, arg) =>
      if (arg.startsWith("--")) {
        val parts = arg.drop(2).split("=", 2)
        if (parts.length == 2) acc + (parts(0) -> parts(1))
        else acc
      } else acc
    }

    for {
      startStr <- argMap.get("start").toRight("Missing --start=YYYY-MM-DD")
      endStr <- argMap.get("end").toRight("Missing --end=YYYY-MM-DD") 
      outputDir <- argMap.get("out").toRight("Missing --out=/path/to/output/dir")
      startDate <- try { Right(LocalDate.parse(startStr)) } catch { case _: Exception => Left(s"Invalid start date: $startStr") }
      endDate <- try { Right(LocalDate.parse(endStr)) } catch { case _: Exception => Left(s"Invalid end date: $endStr") }
      lagMinutes = argMap.get("lag").map(_.toInt).getOrElse(20)
    } yield CliOptions(startDate, endDate, outputDir, lagMinutes)
  }

  lazy val config = ConfigFactory.load()
  
  /** Load AccountBrokers from configuration (same as AppLauncher) */
  private def loadAccountBrokers(): LeadAccountBroker = {
    val riskPerTradeUI = config.getDouble("bmps.core.risk-per-trade-ui")
    val brokerConfigs = config.getConfigList("bmps.core.account-brokers").asScala.toList
    
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

  /** Load DataSources (same as AppLauncher, but force Databento) */
  private def loadDataSources(): (DataSource, DataSource, DataSource) = {
    // Force Databento for training dataset generation
    (
      new DatabentoSource(Set(CandleDuration.OneHour)), 
      new DatabentoSource(Set(CandleDuration.OneMinute)),
      new DatabentoSource(Set(CandleDuration.OneSecond, CandleDuration.OneMinute))
    )
  }

  /** Poll until a phase completes for a given trading date */
  private def pollUntilComplete(phase: SystemStatePhase, tradingDate: LocalDate, eventStore: EventStore): IO[Unit] = {
    (IO.sleep(1.second) *> eventStore.isComplete(tradingDate, phase))
      .iterateUntil(_ == true)
      .void
  }

  /** Run planning and preparing phases to populate tradingCandles with pre-market data */
  private def runPreparationPhases(
    tradingDate: LocalDate, 
    controller: PhaseController, 
    eventStore: EventStore
  ): IO[Unit] = {
    val dateStr = tradingDate.toString
    val options = Some(Map("tradingDate" -> dateStr))
    
    for {
      _ <- IO.println(s"  Running planning phase for $dateStr...")
      _ <- controller.startPhase(SystemStatePhase.Planning, options)
      _ <- pollUntilComplete(SystemStatePhase.Planning, tradingDate, eventStore)
      _ <- IO.println(s"  ✓ Planning phase completed")
      
      _ <- IO.println(s"  Running preparing phase for $dateStr...")
      _ <- controller.startPhase(SystemStatePhase.Preparing, options)
      _ <- pollUntilComplete(SystemStatePhase.Preparing, tradingDate, eventStore)
      _ <- IO.println(s"  ✓ Preparing phase completed")
    } yield ()
  }

  /** Process a single trading day for training dataset generation */
  private def processTrainingDay(
    tradingDate: LocalDate,
    controller: PhaseController, 
    eventStore: EventStore,
    leadAccount: LeadAccountBroker,
    tradingSource: DataSource,
    parquetSink: ParquetSink,
    lagMinutes: Int
  ): IO[Unit] = {
    val dateStr = tradingDate.toString
    
    for {
      _ <- IO.println(s"Processing training day: $dateStr")
      _ <- IO.println("-" * 60)
      
      // Skip if not a trading day
      _ <- if (!MarketCalendar.isTradingDay(tradingDate)) {
        IO.println(s"  Skipping $dateStr (not a trading day)") *> IO.unit
      } else {
        for {
          // Run planning and preparing phases first
          _ <- runPreparationPhases(tradingDate, controller, eventStore)
          
          // Get the prepared state with pre-market candles
          stateRef <- controller.getStateRef()
          initialState <- stateRef.get
          
          _ <- IO.println(s"  Initial tradingCandles count: ${initialState.tradingCandles.length}")
          
          // Run custom training data generation loop
          _ <- generateTrainingData(tradingDate, initialState, leadAccount, tradingSource, parquetSink, lagMinutes)
          
          // Flush data for this day
          _ <- parquetSink.flushDay(tradingDate)
          _ <- IO.println(s"  ✓ Flushed training data for $dateStr")
          
        } yield ()
      }
      
      _ <- IO.println(s"✓ Completed training day: $dateStr")
    } yield ()
  }

  /** Generate training data for a single trading day */
  private def generateTrainingData(
    tradingDate: LocalDate,
    initialState: SystemState, 
    leadAccount: LeadAccountBroker,
    tradingSource: DataSource,
    parquetSink: ParquetSink,
    lagMinutes: Int
  ): IO[Unit] = {
    val marketFeaturesService = new MarketFeaturesService(leadAccount, lagMinutes)
    
    // Compute extended trading window (market close + 10 minutes)
    val startMs = TimestampUtils.newYorkOpen(tradingDate)
    val regularEndMs = TimestampUtils.newYorkClose(tradingDate)
    val extendedEndMs = regularEndMs + (10 * 60 * 1000) // Add 10 minutes
    
    // Stop producing examples 20 minutes before regular close (3:40 PM ET)
    val stopExamplesMs = regularEndMs - (20 * 60 * 1000)
    
    for {
      _ <- IO.println(s"  Generating training data from ${java.time.Instant.ofEpochMilli(startMs)} to ${java.time.Instant.ofEpochMilli(extendedEndMs)}")
      _ <- IO.println(s"  Stop producing examples at: ${java.time.Instant.ofEpochMilli(stopExamplesMs)}")
      
      // Stream candles and process them
      processedCount <- Ref.of[IO, Int](0)
      currentState <- Ref.of[IO, SystemState](initialState)
      
      _ <- tradingSource.candlesInRangeStream(startMs, extendedEndMs)
        .filter(_.duration == CandleDuration.OneMinute) // Only one-minute candles
        .evalMap { candle =>
          for {
            // Update state with new candle
            _ <- currentState.update(state => state.copy(tradingCandles = state.tradingCandles :+ candle))
            
            // Check if we should produce an example (before stop time and have enough history/future)
            state <- currentState.get
            shouldProduce = candle.timestamp < stopExamplesMs && 
                           state.tradingCandles.length >= lagMinutes + 60 // Require some history
                           
            _ <- if (shouldProduce) {
              for {
                // Compute features and labels
                features <- IO.delay(marketFeaturesService.computeMarketFeatures(state))
                labels <- IO.delay(marketFeaturesService.computeLabels(state))
                
                // Convert to vectors
                featureVector = features.toFeatureVector
                labelVector = labels.toLabelVector.toArray
                
                // Write to sink
                _ <- parquetSink.writeRow(
                  timestamp = candle.timestamp,
                  symbol = state.contractSymbol.getOrElse("UNKNOWN"),
                  featureVector = featureVector,
                  labelVector = labelVector,
                  duration = "1m",
                  lagMinutes = lagMinutes
                )
                
                // Update counter
                _ <- processedCount.update(_ + 1)
              } yield ()
            } else IO.unit
            
          } yield ()
        }
        .compile
        .drain
        
      finalCount <- processedCount.get
      _ <- IO.println(s"  Generated $finalCount training examples")
      
    } yield ()
  }

  def run(args: List[String]): IO[ExitCode] = {
    parseArgs(args) match {
      case Left(error) =>
        IO.println(s"Error: $error") *>
        IO.println("Usage: --start=YYYY-MM-DD --end=YYYY-MM-DD --out=/path/to/output/dir [--lag=20]") *>
        IO.pure(ExitCode.Error)
      
      case Right(options) =>
        for {
          _ <- IO.println(s"Training Dataset Generator")
          _ <- IO.println(s"Start Date: ${options.startDate}")
          _ <- IO.println(s"End Date: ${options.endDate}")
          _ <- IO.println(s"Output Dir: ${options.outputDir}")
          _ <- IO.println(s"Lag Minutes: ${options.lagMinutes}")
          _ <- IO.println()
          
          // Load components (same as AppLauncher)
          leadAccount = loadAccountBrokers()
          (planningSource, preparingSource, tradingSource) = loadDataSources()
          
          // Create resources
          _ <- {
            val resource = for {
              stateRef <- Resource.eval(Ref.of[IO, SystemState](SystemState()))
              eventStore <- Resource.eval(EventStore.create())
              sem <- Resource.eval(Semaphore[IO](1))
              parquetSink <- Resource.eval(ParquetSink.create(options.outputDir))
            } yield {
              // Create phase runners
              val runners = Map[SystemStatePhase, PhaseRunner](
                SystemStatePhase.Planning -> PlanningPhaseBuilder.build(planningSource),
                SystemStatePhase.Preparing -> PreparingPhaseBuilder.build(preparingSource),
                // SystemStatePhase.Trading -> TradingPhaseBuilder.build(leadAccount, tradingSource)
              )
              val controller = new PhaseController(stateRef, eventStore, runners, sem)
              (controller, eventStore, parquetSink)
            }
            
            resource.use { case (controller, eventStore, parquetSink) =>
              // Process each trading day in the range
              val dateRange = Iterator.iterate(options.startDate)(_.plusDays(1))
                .takeWhile(!_.isAfter(options.endDate))
                .toList
                
              dateRange.foldLeft(IO.unit) { (acc, date) =>
                acc *> processTrainingDay(date, controller, eventStore, leadAccount, tradingSource, parquetSink, options.lagMinutes)
              }
            }
          }
          
          _ <- IO.println()
          _ <- IO.println("✓ Training dataset generation complete!")
          
        } yield ExitCode.Success
    }
  }
}