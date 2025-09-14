package bmps.core.api.impl

import cats.effect.IO
import cats.syntax.traverse._
import cats.syntax.all._
import bmps.core.models._
import bmps.core.services.SwingService
import bmps.core.io.ParquetSource
import bmps.core.Event
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import cats.effect.std.Queue

object ReplayProcessor {

  /**
   * Replay five-minute parquet between startMs (inclusive) and endMs (exclusive).
   * Updates stateRef with five-minute candles and swings and publishes events via publisher.
   * openPlanZones and openDaytime are sequences captured before replay and used to emit
   * any existing plan/daytime events when their start times are reached.
   */
  def replayFiveMin(
    parquetPath: String,
    startMs: Long,
    endMs: Long,
    zoneId: ZoneId,
    stateRef: AtomicReference[SystemState],
    openPlanZones: Seq[PlanZone],
    openDaytime: Seq[DaytimeExtreme],
    publisher: Event => IO[Unit],
    swingService: SwingService = new SwingService()
  ): IO[Unit] = {

    val candlesStream = ParquetSource.readParquetAsCandlesInRangeStream(parquetPath, startMs, endMs, zoneId)

    // mutable sets to track emitted plan/daytime starts during this replay
    val emittedPlanStarts = scala.collection.mutable.Set.empty[Long]
    val emittedDayStarts = scala.collection.mutable.Set.empty[Long]

    candlesStream.evalMap { candle =>
      // Compose IO actions instead of running them unsafely.
      for {
        prev <- IO.delay(stateRef.get())
        newFiveCandles = prev.fiveMinCandles :+ candle
        tmp = prev.copy(fiveMinCandles = newFiveCandles, fiveMinSwingPoints = List.empty)
        swingsComputed = swingService.computeSwings(tmp)
        newFiveSwings = swingsComputed.swingPoints
        updated = prev.copy(fiveMinCandles = newFiveCandles, fiveMinSwingPoints = newFiveSwings)
        _ <- IO.delay(try { stateRef.set(updated) } catch { case _: Throwable => () })
        candleEvent = Event.fromCandle(candle)
        _ <- publisher(candleEvent).handleError(_ => ())
        prevSwings = prev.fiveMinSwingPoints.map(_.timestamp).toSet
        newSwings = newFiveSwings.filterNot(sp => prevSwings.contains(sp.timestamp))
        _ <- newSwings.toList.traverse_(sp => publisher(Event.fromSwingPoint(sp)).handleError(_ => ()))
        _ <- IO.delay(
          openPlanZones.filter(pz => !emittedPlanStarts.contains(pz.startTime) && candle.timestamp >= pz.startTime)
            .foreach { pz => emittedPlanStarts += pz.startTime }
        )
        _ <- openPlanZones.toList.filter(pz => emittedPlanStarts.contains(pz.startTime) && candle.timestamp >= pz.startTime)
               .traverse_(pz => publisher(Event.fromPlanZone(pz)).handleError(_ => ()))
        _ <- IO.delay(
          openDaytime.filter(de => !emittedDayStarts.contains(de.timestamp) && candle.timestamp >= de.timestamp)
            .foreach { de => emittedDayStarts += de.timestamp }
        )
        _ <- openDaytime.toList.filter(de => emittedDayStarts.contains(de.timestamp) && candle.timestamp >= de.timestamp)
               .traverse_(de => publisher(Event.fromDaytimeExtreme(de)).handleError(_ => ()))
      } yield ()
    }.compile.drain
  }
}


