package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.models.Direction
import bmps.core.models.SwingPoint
import bmps.core.models.PlanZoneType
import bmps.core.models.PlanZone
import scala.collection.mutable
import bmps.core.models.Event
import bmps.core.models.Level

object PlanZoneService {

    def processPlanZones(state: SystemState): (SystemState, List[Event]) = {
        val constructed = buildPlanZone(state)
        val newState = reconcile(constructed)
        val events = changeEvents(newState, state)
        (newState, events)
    }

    def buildPlanZone(state: SystemState): SystemState = {
    val swingPoints = state.planningSwingPoints
    val lastCandle = state.planningCandles.last
        ((
            swingPoints.reverse.find(_.direction == Direction.Down),
            swingPoints.reverse.find(_.direction == Direction.Up)
        ) match {
            case (Some(swingDown), Some(swingUp)) if swingUp.timestamp < swingDown.timestamp && 
                                                    swingUp.level < swingDown.level &&
                                                    swingUp.level > lastCandle.close =>
                Some(PlanZone.apply(swingUp, swingDown, PlanZoneType.Supply))
            case (Some(swingDown), Some(swingUp)) if swingUp.timestamp > swingDown.timestamp &&
                                                    swingUp.level < swingDown.level &&
                                                    swingDown.level < lastCandle.close =>
                Some(PlanZone.apply(swingUp, swingDown, PlanZoneType.Demand))
            case _ => None
        }).map { newPlanZone =>
            state.copy(planZones = (state.planZones :+ newPlanZone))
        } getOrElse(state)
    }

    def reconcile(state: SystemState): SystemState = {
        val deduplicated = deduplicate(state)
        val closed = close(deduplicated)
        merge(closed)
    }

    def changeEvents(state: SystemState, priorState: SystemState): List[Event] = {
        val newZones = state.planZones.filterNot(z => priorState.planZones.exists(_.startTime == z.startTime))
        val changedZones = state.planZones.filter(z => priorState.planZones.find(_.startTime == z.startTime).map(_ != z).getOrElse(false))

        val lastTimestamp = state.planningCandles.last.timestamp

        (newZones ++ changedZones).map(Event.fromPlanZone)
    }

    private def deduplicate(state: SystemState): SystemState = {
        val seen = mutable.HashSet[Long]()
        val dedupedZones = state.planZones.filter { zone =>
            val ts = zone.startTime
            if (seen(ts)) false
            else {
                seen += ts
                true
            }
        }
        state.copy(planZones = dedupedZones)
    }

    private def close(state: SystemState): SystemState = {
        val lastCandle = state.planningCandles.last
        val newPlanZones = state.planZones.map(_.closedOut(lastCandle))
        state.copy(planZones = newPlanZones)
    }

    private def merge(state: SystemState): SystemState = {
        if (state.planZones.size <= 1) state
        else {
            // Keep only active zones for merging
            val active = state.planZones.filter(_.endTime.isEmpty).sortBy(_.startTime)
            val inactive = state.planZones.filter(_.endTime.nonEmpty)

            if (active.isEmpty) state
            else {
                // We'll iteratively merge the last (newest) zone into earlier same-type zones until stable
                import scala.collection.mutable.ArrayBuffer

                val buf = ArrayBuffer(active: _*)
                var last = buf.remove(buf.length - 1)
                // accumulate closed (end-dated) zones produced during merging so they
                // can be preserved in the final output (but not considered for further merging)
                val closedAccum = ArrayBuffer.empty[PlanZone]

                var changed = true
                while (changed && buf.nonEmpty) {
                    changed = false
                    // iterate from newest to oldest (right-to-left)
                    var i = buf.length - 1
                    while (i >= 0) {
                        val candidate = buf(i)
                        // if (candidate.planZoneType == last.planZoneType && (candidate.engulfs(last) || candidate.overlaps(last))) {
                        if (candidate.planZoneType == last.planZoneType && last.engulfs(candidate)) {
                            // Use PlanZone.mergeWith with later-start semantics so we retain a closed older zone
                            val (merged, closedOlder) = candidate.mergeWith(last)

                            // remove the candidate from the active buffer and keep the closed older
                            // separately (closed zones should not participate in further merges)
                            buf.remove(i)
                            closedAccum += closedOlder

                            // Keep merged newer zone for further merging
                            last = merged
                            changed = true
                            // continue scanning earlier candidates - do not advance i
                        }
                        i -= 1
                    }
                }

                val mergedSorted = (inactive ++ buf.toList ++ closedAccum.toList :+ last).sortBy(_.startTime)
                state.copy(planZones = mergedSorted)
            }
        }
    }
}
