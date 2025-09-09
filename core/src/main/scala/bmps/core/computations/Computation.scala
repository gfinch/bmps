package bmps.core

import cats.effect.IO
import fs2.Stream

object Computation {
  def process(input: Stream[IO, Event]): Stream[IO, Event] = {
    // Placeholder: Add your computation logic here. For now, pass events through unchanged.
    input
  }
}
