package bmps.core.api.intf

import bmps.core.models.SystemState
import bmps.core.models.Candle
import bmps.core.Event

trait EventGenerator {
  /**
   * Process a single candle, returning the updated state and any events that
   * should be emitted as a result of processing this candle.
   */
  def process(state: SystemState, candle: Candle): (SystemState, List[Event])
}


