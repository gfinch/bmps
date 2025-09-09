package bmps.core

import cats.effect.{IO, IOApp, Ref}
import fs2.Stream
import _root_.io.circe.syntax._
import _root_.io.circe.generic.auto._
import java.time.{Instant, ZoneId}
import java.sql.DriverManager
import bmps.core.models._
import bmps.core.services.SwingService
import scala.jdk.CollectionConverters._

/**
 * CoreService reads market data (currently from a Parquet file via DuckDB),
 * publishes each candle as an Event, runs the SwingService to detect swing
 * points and publishes any new swing point events.
 *
 * Future services (order manager, zone detector, risk manager, etc.) should
 * be hooked into the event publishing pipeline where indicated below.
 */
class CoreService(params: InitParams, parquetPath: String = "core/src/main/resources/samples/es_futures_1h_60days.parquet") {

  private val swingService = new SwingService()

  private val zoneId = ZoneId.systemDefault()

  // Parquet reading lives in ParquetSource to keep CoreService data-source agnostic.
  import bmps.core.io.ParquetSource

  /**
   * Start the core stream: read candles, publish candle events, run SwingService,
   * and publish new swing events. Currently events are printed as JSON to stdout.
   */
  def start: Stream[IO, Unit] = {
    val initialState = SystemState(Nil, Direction.Up, Nil)

    for {
  // read all candles (delegated to ParquetSource)
  candles <- Stream.eval(ParquetSource.readParquetAsCandles(parquetPath))

      // keep mutable state in Ref so each candle can be processed sequentially
      stateRef <- Stream.eval(Ref.of[IO, SystemState](initialState))

      // publish each candle and any newly discovered swing points
      event <- Stream.emits(candles).covary[IO].evalMap { candle =>
        // Atomically update state and compute new swings
        stateRef.modify { state =>
          val updatedCandles = state.candles :+ candle
          val updatedState = swingService.computeSwings(state.copy(candles = updatedCandles))
          val newSwingPoints = updatedState.swingPoints.drop(state.swingPoints.length)
          (updatedState, (candle, newSwingPoints))
        }
      }.flatMap { case (candle, newSwings) =>
        val candleEvent = Event(EventType.Candle, candle.timestamp, Some(candle), None)
        val swingEvents = newSwings.map(sp => Event(EventType.SwingPoint, sp.timestamp, None, Some(sp)))
        // Here: future services would subscribe to `candleEvent` and `swingEvents`.
        // e.g. zoneService.process(candleEvent), orderManager.enqueue(candleEvent), etc.
        Stream.emit(candleEvent) ++ Stream.emits(swingEvents)
      }

      // publish all events (for now, print JSON string). In future this will write to an outbound websocket or message bus.
      _ <- Stream.eval(IO(println(event.asJson.noSpaces)))

    } yield ()
  }
}

object CoreService extends IOApp.Simple {
  def run: IO[Unit] = {
    val params = InitParams(TradingMode.Simulation)
    new CoreService(params).start.compile.drain
  }
}
