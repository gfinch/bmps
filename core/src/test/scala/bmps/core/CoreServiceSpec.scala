package bmps.core

import cats.effect.IO
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

class CoreServiceSpec extends AsyncFlatSpec with Matchers {
  "CoreService" should "process events" in {
    // Placeholder test
    IO.pure(succeed).unsafeToFuture()
  }
}
