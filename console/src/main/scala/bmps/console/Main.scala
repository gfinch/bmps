package bmps.console

import cats.effect.{IO, IOApp}
import fs2.Stream
import bmps.core.api.impl.CoreServiceImpl
import bmps.core.api.model.{InitParams, TradingMode}

object Main extends IOApp.Simple {
  def run: IO[Unit] = {
    // Hard-coded for live trading
    val params = InitParams(TradingMode.Live)
  val core = new CoreServiceImpl(params)
  core.start.compile.drain
  }
}
