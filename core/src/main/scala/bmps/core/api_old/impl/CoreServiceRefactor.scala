/* DISABLED api_old
package bmps.core.api.impl

import cats.effect.IO
import fs2.Stream
import java.time.LocalDate
import cats.effect.std.Queue
import bmps.core.api.intf.CoreService
import bmps.core.api.intf.CandleSource
import bmps.core.api.intf.CandleProcessor
import bmps.core.Event
import bmps.core.models.SystemState

/**
 * A refactored CoreService that composes a CandleSource and CandleProcessor via
 * PhaseRunner. It intentionally does not modify any existing domain types and
 * is safe to substitute where a CoreService is expected.
 */
class CoreServiceRefactor(source: CandleSource, processor: CandleProcessor, tradingDay: LocalDate) extends CoreService {

  private val runner = new PhaseRunner(source, processor)

  override def events: Stream[IO, Event] = runner.run(SystemState(tradingDay = tradingDay))

  override def start: Stream[IO, Unit] = Stream.eval(IO.unit)

  override def broadcasterIO(globalQueue: Queue[IO, Event]): IO[Unit] = IO.unit

  override def streamParquetRangeToQueue(startMs: Long, endMs: Long, tradingDay: LocalDate, q: Queue[IO, Event]): IO[Unit] = {
    // If the provided source is a ParquetCandleSource we can create a new
    // instance scoped to the requested range. To keep this method generic we
    // fall back to running the existing runner and offering events into the
    // queue. Callers who need efficient range reads can construct their own
    // ParquetCandleSource and PhaseRunner.
    runner.run(SystemState(tradingDay = tradingDay)).evalMap(e => q.offer(e)).compile.drain
  }
}
*/


