package bmps.console

import cats.effect.{IO, IOApp}
import fs2.Stream
import bmps.core.api.impl.CoreServiceImpl
import bmps.core.api.impl.CoreServiceRefactor
import bmps.core.api.run.PhaseRunners
import bmps.core.api.model.{InitParams, TradingMode}

object Main extends IOApp.Simple {
  def run: IO[Unit] = {
    // Hard-coded for live trading
    val params = InitParams(TradingMode.Live)
  val parquetPath = "core/src/main/resources/samples/es_futures_1h_60days.parquet"
  val (src, proc) = PhaseRunners.parquetPlanning(parquetPath)
  val core = new CoreServiceRefactor(src, proc, params.tradingDate)
  // Run the refactored core's event stream for a quick smoke test.
  core.events.compile.drain
  }
}
