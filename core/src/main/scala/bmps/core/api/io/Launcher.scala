package bmps.core.api.io

import cats.effect.{IO, IOApp}
import bmps.core.api.model.InitParams
import bmps.core.api.model.TradingMode
import bmps.core.api.impl.CoreServiceImpl

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
    val core = new CoreServiceImpl(params, parquetPath)

  // Run the ServerModule stream for the CoreService implementation.
  ServerModule.start(webPort, httpPort, "web", core.stateHolder().getAtomicRef(), (s: String) => (), core).compile.drain
  }
}


