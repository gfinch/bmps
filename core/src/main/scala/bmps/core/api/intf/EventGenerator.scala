package bmps.core.api.intf

import bmps.core.models.SystemState
import bmps.core.models.Candle
import bmps.core.models.Event

trait EventGenerator {
  
  def initialize(state: SystemState, options: Map[String, String] = Map.empty): SystemState

  def process(state: SystemState, candle: Candle): (SystemState, List[Event])

  def finalize(state: SystemState): (SystemState, List[Event]) = (state, List.empty)

}


