package bmps.core.api.model

import java.time.LocalDate

sealed trait TradingMode
object TradingMode {
  case object Simulation extends TradingMode
  case object Live extends TradingMode
}

case class InitParams(
  mode: TradingMode,
  tradingDate: LocalDate = LocalDate.now(),
  playbackSpeed: Double = 1.0
)


