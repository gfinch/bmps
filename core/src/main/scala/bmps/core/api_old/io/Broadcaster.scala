
/* DISABLED api_old
package bmps.core.api.io

import cats.effect.IO
import cats.effect.std.Queue
import bmps.core.Event

object Broadcaster {
  /**
   * Consume events from the provided queue and forward them using the supplied send function.
   * The sendFn handles the raw JSON send to clients or buffering.
   */
  def run(q: Queue[IO, Event], sendFn: Event => IO[Unit]): IO[Unit] = {
    def loop: IO[Unit] = q.take.flatMap(ev => sendFn(ev).handleError(_ => ()) >> loop)
    loop
  }
}

*/


