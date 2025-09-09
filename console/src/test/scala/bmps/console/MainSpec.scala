package bmps.console

import cats.effect.IO
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

class MainSpec extends AsyncFlatSpec with Matchers {
  "Main" should "print events" in {
    IO.pure(succeed).unsafeToFuture()
  }
}
