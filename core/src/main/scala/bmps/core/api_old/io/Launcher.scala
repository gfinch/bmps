
/* DISABLED api_old
package bmps.core.api.io

import cats.effect.{IO, IOApp}
import bmps.core.api.model.InitParams
import bmps.core.api.model.TradingMode
import bmps.core.api.impl.CoreServiceImpl
import bmps.core.api.impl.CoreServiceRefactor
import bmps.core.api.run.PhaseRunners

/**
 * Small launcher IOApp that starts the CoreService implementation and the
 * ServerModule. The original `bmps.core.CoreService` main is left untouched.
 */
object Launcher extends IOApp.Simple {
  def run: IO[Unit] = {
    val cfg = com.typesafe.config.ConfigFactory.load()
    val webPort = try { cfg.getInt("bmps.core.web-port") } catch { case _: Throwable => 9001 }
    val httpPort = try { cfg.getInt("bmps.core.http-port") } catch { case _: Throwable => 5173 }
    val parquetPath = try { cfg.getString("bmps.core.parquet-path") } catch { case _: Throwable => "core/src/main/resources/samples/es_futures_1h_60days.parquet" }

  val params = InitParams(TradingMode.Simulation)
    // Wire in the refactored CoreService using the Parquet planning runner
    val (src, proc) = PhaseRunners.parquetPlanning(parquetPath)
    val core = new CoreServiceRefactor(src, proc, params.tradingDate)

  // Note: CoreServiceRefactor does not currently expose a stateHolder like the
  // original CoreServiceImpl. ServerModule expects a state atomic ref; the
  // simplest approach for now is to keep using CoreServiceImpl where a
  // snapshot is required, or extend CoreServiceRefactor to accept a
  // StateHolder. For this exercise we'll run the server module with the
  // original snapshot from a CoreServiceImpl but stream events from the
  // refactored core for testing.

  val snapshotProvider = new CoreServiceImpl(params, parquetPath).stateHolder().getAtomicRef()
  ServerModule.start(webPort, httpPort, "web", snapshotProvider, (s: String) => (), core).compile.drain
  }
}

*/


