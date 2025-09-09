package bmps.console

import cats.effect.{IO, IOApp}
import fs2.Stream
import bmps.core.{CoreService, InitParams, TradingMode}

object Main extends IOApp.Simple {
  def run: IO[Unit] = {
    // Hard-coded for live trading
    val params = InitParams(TradingMode.Live)
    val core = new CoreService(params)
    core.start.compile.drain
  }
}
