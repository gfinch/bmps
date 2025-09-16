
/* DISABLED api_old
package bmps.core.api.impl

import bmps.core.api.intf.CandleProcessor
import bmps.core.models.{SystemState, Candle}
import bmps.core.Event

/**
 * PreparingProcessor: for now, simply appends incoming candles to the
 * tradingCandles list on the SystemState and emits no additional events.
 * This mirrors the 'play candles' behavior used by the replay processor.
 */
class PreparingProcessor extends CandleProcessor {
  override def process(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
    val updated = state.copy(tradingCandles = state.tradingCandles :+ candle)
    (updated, List.empty)
  }
}
*/


