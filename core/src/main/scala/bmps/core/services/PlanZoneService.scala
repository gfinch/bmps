package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.models.Direction
import bmps.core.models.SwingPoint
import bmps.core.models.PlanZoneType
import bmps.core.models.PlanZone
import scala.collection.mutable
import bmps.core.Event

object PlanZoneService {

    def processPlanZones(state: SystemState): (SystemState, List[Event]) = {
        val constructed = buildPlanZone(state)
        val newState = reconcile(constructed)
        val events = changeEvents(newState, state)
        (newState, events)
    }

    def buildPlanZone(state: SystemState): SystemState = {
        val swingPoints = state.swingPoints
        val lastCandle = state.candles.last
        ((
            swingPoints.reverse.find(_.direction == Direction.Down),
            swingPoints.reverse.find(_.direction == Direction.Up)
        ) match {
            case (Some(swingDown), Some(swingUp)) if swingUp.timestamp < swingDown.timestamp && 
                                                    swingUp.level < swingDown.level &&
                                                    swingUp.level > lastCandle.close =>
                Some(PlanZone.apply(swingUp, swingDown, PlanZoneType.Supply))
            case (Some(swingDown), Some(swingUp)) if swingUp.level < swingDown.level &&
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
        val oldZones = priorState.planZones.filterNot(z => state.planZones.exists(_.startTime == z.startTime))
        val newZones = state.planZones.filterNot(z => priorState.planZones.exists(_.startTime == z.startTime))
        val changedZones = state.planZones.filter(z => priorState.planZones.find(_.startTime == z.startTime).map(_ != z).getOrElse(false))

        val lastTimestamp = state.candles.last.timestamp
        val closedZones = oldZones.map(_.copy(endTime = Some(lastTimestamp)))

        (closedZones ++ newZones ++ changedZones).map(Event.fromPlanZone)
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
        val lastCandle = state.candles.last
        val newPlanZones = state.planZones.map(_.closedOut(lastCandle))
        state.copy(planZones = newPlanZones)
    }

    private def merge(state: SystemState): SystemState = {
        if (state.planZones.size == 1) state else {
            val pairs = for {
                (zoneA, idxA) <- state.planZones.zipWithIndex
                (zoneB, idxB) <- state.planZones.zipWithIndex
                if idxA < idxB
            } yield (zoneA, zoneB)

            val mergedPlanZones = pairs.flatMap { 
                case (left, right) => 
                    if (left.engulfs(right)) Seq(left) 
                    else if (right.engulfs(left)) Seq(right)
                    else if (left.overlaps(right)) Seq(left.mergeWith(right))
                    else if (right.overlaps(left)) Seq(right.mergeWith(left))
                    else Seq(left, right)
            }

            val sorted = mergedPlanZones.sortBy(_.startTime).filter(_.endTime.isEmpty)
            state.copy(planZones = sorted)
        }
    }
}
