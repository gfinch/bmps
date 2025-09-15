package bmps.core.api.impl

import cats.effect.{IO, Ref}
import fs2.Stream
import java.time.ZoneId
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import bmps.core.api.intf.CoreService
import bmps.core.api.intf.StateHolder
import bmps.core.models._
import bmps.core.api.model.InitParams
import bmps.core.services.SwingService
import bmps.core.services.PlanZoneService
import bmps.core.services.LiquidityZoneService
import bmps.core.Event
import bmps.core.io.ParquetSource
import cats.effect.std.Queue

/**
 * Extracted implementation of the core orchestration. This class only moves
 * code that was originally defined inside `bmps.core.CoreService` into the
 * new package. It deliberately leaves domain types (Candle, Event, SystemState)
 * in their original packages.
 */
class CoreServiceImpl(params: InitParams, parquetPath: String = "core/src/main/resources/samples/es_futures_1h_60days.parquet") extends CoreService {

  private val swingService = new SwingService()
  private val zoneId = ZoneId.systemDefault()

  // Keep a snapshot of the latest SystemState so other threads can access it.
  private val initialState = SystemState(tradingDay = params.tradingDate)
  private val latestStateRef: AtomicReference[SystemState] = new AtomicReference[SystemState](initialState)
  private val outboundOfferRef: AtomicReference[Option[Event => IO[Unit]]] = new AtomicReference[Option[Event => IO[Unit]]](None)

  // Parquet reading lives in ParquetSource (unchanged)

  def events: Stream[IO, Event] = {
    val candleStream: Stream[IO, Candle] = ParquetSource.readParquetAsCandlesInRangeStream(parquetPath, 0L, Long.MaxValue, zoneId)
    processCandlesStream(candleStream, params.tradingDate)
  }

  private def processCandlesStream(candles: Stream[IO, Candle], tradingDay: LocalDate): Stream[IO, Event] = {
  val streamInitial = SystemState(tradingDay = tradingDay)
    for {
      stateRef <- Stream.eval(Ref.of[IO, SystemState](streamInitial))
      _ <- Stream.eval(IO { try { latestStateRef.set(streamInitial) } catch { case _: Throwable => () } })
      event <- candles.evalMap { candle =>
        stateRef.modify { state =>
          val updatedCandles = state.planningCandles :+ candle
          val withSwings = swingService.computeSwings(state.copy(planningCandles = updatedCandles))
          val (withZones, zoneEvents) = PlanZoneService.processPlanZones(withSwings)
          val (updatedState, liquidEvents) = LiquidityZoneService.processLiquidityZones(withZones)
          try { latestStateRef.set(updatedState) } catch { case _: Throwable => () }

          val newSwingPoints = updatedState.planningSwingPoints.drop(state.planningSwingPoints.length)
          (updatedState, (candle, newSwingPoints, zoneEvents, liquidEvents))
        }
      }.flatMap { case (candle, newSwings, zoneEvents, liquidEvents) =>
        val candleEvent = Event.fromCandle(candle)
        val swingEvents = newSwings.map(sp => Event.fromSwingPoint(sp))

  // liquidity events may be emitted; no debug printing here
        Stream.emit(candleEvent) ++ Stream.emits(swingEvents) ++ Stream.emits(zoneEvents) ++ Stream.emits(liquidEvents)
      }
    } yield event
  }

  // Minimal start stub. Full server lifecycle will be implemented in the io package.
  def start: Stream[IO, Unit] = Stream.eval(IO.unit)

  // Minimal broadcaster stub — IO wiring belongs in io package.
  def broadcasterIO(globalQueue: Queue[IO, Event]): IO[Unit] = IO.unit

  // Small StateHolder implementation so other modules can access the snapshot if needed.
  class StateHolderImpl extends StateHolder {
    override def get(): SystemState = latestStateRef.get()
    override def set(s: SystemState): Unit = try { latestStateRef.set(s) } catch { case _: Throwable => () }
    override def getAtomicRef(): AtomicReference[SystemState] = latestStateRef
  }

  def stateHolder(): StateHolder = new StateHolderImpl

  /**
   * Stream a parquet time range into the provided queue by running the same
   * processing pipeline used by `events`.
   */
  def streamParquetRangeToQueue(startMs: Long, endMs: Long, tradingDay: LocalDate, q: Queue[IO, Event]): IO[Unit] = {
    val candleStream: Stream[IO, Candle] = ParquetSource.readParquetAsCandlesInRangeStream(parquetPath, startMs, endMs, zoneId)
    processCandlesStream(candleStream, tradingDay).evalMap(e => q.offer(e)).compile.drain
  }
}


