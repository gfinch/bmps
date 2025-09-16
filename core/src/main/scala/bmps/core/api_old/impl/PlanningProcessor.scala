
/* DISABLED api_old
package bmps.core.api.impl

import bmps.core.api.intf.CandleProcessor
import bmps.core.models.{SystemState, Candle}
import bmps.core.services.{SwingService, PlanZoneService, LiquidityZoneService}
import bmps.core.Event

/**
 * Processor used for the Planning phase. Reproduces the existing pipeline
 * behaviour: compute swings, plan zones and liquidity zones, and return the
 * updated state with any events produced by those services.
 */
class PlanningProcessor extends CandleProcessor {
  private val swingService = new SwingService()

  override def process(state: SystemState, candle: Candle): (SystemState, List[Event]) = {
    val updatedCandles = state.planningCandles :+ candle
    val withSwings = swingService.computeSwings(state.copy(planningCandles = updatedCandles))
    val (withZones, zoneEvents) = PlanZoneService.processPlanZones(withSwings)
    val (updatedState, liquidEvents) = LiquidityZoneService.processLiquidityZones(withZones)

    val newSwingPoints = updatedState.planningSwingPoints.drop(state.planningSwingPoints.length)
    val swingEvents = newSwingPoints.map(sp => Event.fromSwingPoint(sp))

    // Combine produced events (swing, zone, liquidity). Note: the PhaseRunner
    // always emits a Candle event first, so here we only return non-candle events.
    val producedEvents = swingEvents ++ zoneEvents ++ liquidEvents
    (updatedState, producedEvents)
  }
}
*/


