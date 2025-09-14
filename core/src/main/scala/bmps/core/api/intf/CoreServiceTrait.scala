package bmps.core.api.intf

import cats.effect.IO
import fs2.Stream
import cats.effect.std.Queue
import bmps.core.Event
import bmps.core.models._

/**
 * Thin trait defining the high-level CoreService surface used by the rest of the app.
 * Implementation and wiring live in the new `impl` and `io` packages.
 */
trait CoreService {
  /** Stream of events produced by the core processing pipeline. */
  def events: Stream[IO, Event]

  /** Start the full server lifecycle (embedded WS + HTTP + broadcaster). */
  def start: Stream[IO, Unit]

  /**
   * Provide a broadcaster IO that accepts a global queue and forwards control messages
   * into it. Kept here as a named method so implementations can expose the same surface
   * as the original `CoreService`.
   */
  def broadcasterIO(globalQueue: Queue[IO, Event]): IO[Unit]

  /** Stream a parquet range into the provided queue using the service's pipeline. */
  def streamParquetRangeToQueue(startMs: Long, endMs: Long, tradingDay: java.time.LocalDate, q: Queue[IO, Event]): IO[Unit]
}


