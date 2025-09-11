package bmps.core.services

import org.scalatest.funsuite.AnyFunSuite
import bmps.core.models._
import java.time.LocalDate

class PlanZoneServiceSpec extends AnyFunSuite {

  private def lvl(v: Float) = Level(v)

  test("Supply PlanZone should close when price moves below low") {
    val zone = PlanZone(PlanZoneType.Supply, lvl(10f), lvl(20f), 100L)
    val candle = Candle(lvl(0f), lvl(0f), lvl(0f), lvl(9.5f), 200L, CandleDuration.OneMinute)
    val closed = zone.closedOut(candle)
    assert(closed.endTime.contains(200L))
  }

  test("Demand PlanZone should close when price moves above high") {
    val zone = PlanZone(PlanZoneType.Demand, lvl(10f), lvl(20f), 100L)
    val candle = Candle(lvl(0f), lvl(21f), lvl(0f), lvl(21.1f), 200L, CandleDuration.OneMinute)
    val closed = zone.closedOut(candle)
    assert(closed.endTime.contains(200L))
  }

  test("mergeWith should use later startTime and expand range, and return closed older zone") {
    val a = PlanZone(PlanZoneType.Supply, lvl(10f), lvl(15f), 100L)
    val b = PlanZone(PlanZoneType.Supply, lvl(12f), lvl(20f), 50L)
    val (m, closed) = a.mergeWith(b)
    // later startTime should be 100 (from a)
    assert(m.startTime == 100L)
    assert(m.low.value == 10f)
    assert(m.high.value == 20f)
    // closed older should have endTime set to 100
    assert(closed.endTime.contains(100L))
  }

  test("merge should iteratively merge the last zone into earlier same-type zones") {
    val z1 = PlanZone(PlanZoneType.Supply, lvl(10f), lvl(15f), 10L)
    val z2 = PlanZone(PlanZoneType.Supply, lvl(14f), lvl(18f), 20L)
    // z3 overlaps z2 and should trigger merging into z1 as well via cascade
    val z3 = PlanZone(PlanZoneType.Supply, lvl(17f), lvl(25f), 30L)

    val state = SystemState(
      tradingDay = LocalDate.of(2020,1,1),
      candles = List(Candle(lvl(0f), lvl(100f), lvl(0f), lvl(50f), 100L, CandleDuration.OneMinute)),
      direction = Direction.Up,
      swingPoints = List.empty,
      planZones = List(z1, z2, z3)
    )

    val merged = PlanZoneService.processPlanZones(state)._1
    // expect a single merged supply covering from 10 to 25
    assert(merged.planZones.exists(z => z.low == Level(10f) && z.high == Level(25f)))
  }

  test("engulfs and overlaps edge cases") {
    val a = PlanZone(PlanZoneType.Demand, lvl(10f), lvl(20f), 10L)
    val b = PlanZone(PlanZoneType.Demand, lvl(12f), lvl(18f), 20L)
    val c = PlanZone(PlanZoneType.Demand, lvl(20f), lvl(25f), 30L)

    assert(a.engulfs(b))
    assert(!b.engulfs(a))
  assert(a.overlaps(c) == false) // touching upper boundary should not count as overlap by current semantics
  }
}

