package bmps.core.services

import org.scalatest.funsuite.AnyFunSuite
import bmps.core.models._
import java.time.{LocalDate, LocalTime, ZoneId, ZonedDateTime}

class LiquidityZoneServiceSpec extends AnyFunSuite {

  private def lvl(v: Float) = Level(v)

  val eastern = ZoneId.of("America/New_York")

  private def tsFor(date: LocalDate, hour: Int, minute: Int): Long =
    ZonedDateTime.of(date, LocalTime.of(hour, minute), eastern).toInstant.toEpochMilli

  test("NY previous session candle creates New York extremes lazily") {
    val tradingDay = LocalDate.of(2020, 1, 2)
    // a candle during NY previous day 9:30 (prev day)
    val cts = tsFor(tradingDay.minusDays(1), 9, 45)
    val candle = Candle(lvl(100f), lvl(110f), lvl(90f), lvl(105f), cts, CandleDuration.OneMinute)

  val state = SystemState(tradingDay = tradingDay, planningCandles = List(candle), swingDirection = Direction.Up, planningSwingPoints = List.empty)

  val (newState, events) = LiquidityZoneService.processLiquidityZones(state)

  // Should have New York Low/High created
  assert(newState.daytimeExtremes.exists(_.description.startsWith("New York - Low")))
  assert(newState.daytimeExtremes.exists(_.description.startsWith("New York - High")))
  // And events should be emitted for the created extremes
  assert(events.nonEmpty)
  assert(events.forall(_.daytimeExtreme.isDefined))
  }

  test("Asia and London create and update extremes only when open") {
    val tradingDay = LocalDate.of(2020, 1, 2)

    // Asia open prev day 18:00 -> candle at 18:30 prev day
    val asiaTs = tsFor(tradingDay.minusDays(1), 18, 30)
    val asiaC1 = Candle(lvl(200f), lvl(205f), lvl(195f), lvl(200f), asiaTs, CandleDuration.OneMinute)

  val state1 = SystemState(tradingDay = tradingDay, planningCandles = List(asiaC1), swingDirection = Direction.Up, planningSwingPoints = List.empty)
  val (sAfterAsia, events1) = LiquidityZoneService.processLiquidityZones(state1)

  assert(sAfterAsia.daytimeExtremes.exists(_.description.startsWith("Asia - Low")))
  assert(sAfterAsia.daytimeExtremes.exists(_.description.startsWith("Asia - High")))
  assert(events1.nonEmpty)

  // Now a later Asia candle sets a new low
    val asiaTs2 = tsFor(tradingDay.minusDays(1), 19, 0)
    val asiaC2 = Candle(lvl(199f), lvl(204f), lvl(190f), lvl(199f), asiaTs2, CandleDuration.OneMinute)
    val state2 = sAfterAsia.copy(planningCandles = sAfterAsia.planningCandles :+ asiaC2)

  val (sAfterAsia2, events2) = LiquidityZoneService.processLiquidityZones(state2)

  val asiaLow = sAfterAsia2.daytimeExtremes.find(_.description.startsWith("Asia - Low")).get.level.value
  assert(asiaLow <= 190f)
  // updated extremes should emit an event for the changed low
  assert(events2.exists(_.daytimeExtreme.exists(_.description.startsWith("Asia - Low"))))

    // A candle outside London hours should not create London extremes
    val outsideLondonTs = tsFor(tradingDay, 0, 30) // before London open (03:00)
    val outsideLondonC = Candle(lvl(50f), lvl(55f), lvl(45f), lvl(52f), outsideLondonTs, CandleDuration.OneMinute)
    val state3 = sAfterAsia2.copy(planningCandles = sAfterAsia2.planningCandles :+ outsideLondonC)
    val (sAfterOut, _) = LiquidityZoneService.processLiquidityZones(state3)

    assert(!sAfterOut.daytimeExtremes.exists(_.description.startsWith("London -")))

  }

  test("ending a DaytimeExtreme High when another High surpasses it") {
    val tradingDay = LocalDate.of(2020, 1, 2)

    // Create a New York high first (previous day 10:00)
    val nyTs = tsFor(tradingDay.minusDays(1), 10, 0)
    val nyC = Candle(lvl(100f), lvl(110f), lvl(90f), lvl(105f), nyTs, CandleDuration.OneMinute)
  val state1 = SystemState(tradingDay = tradingDay, planningCandles = List(nyC), swingDirection = Direction.Up, planningSwingPoints = List.empty)
    val (sAfterNy, eventsNy) = LiquidityZoneService.processLiquidityZones(state1)

    val nyHigh = sAfterNy.daytimeExtremes.find(_.description.startsWith("New York - High")).get
    assert(nyHigh.endTime.isEmpty)

    // Now an Asia candle with a higher high should cause the New York high to end
    val asiaTs = tsFor(tradingDay.minusDays(1), 19, 0) // Asia session
    val asiaC = Candle(lvl(200f), lvl(120f), lvl(195f), lvl(200f), asiaTs, CandleDuration.OneMinute)
    val state2 = sAfterNy.copy(planningCandles = sAfterNy.planningCandles :+ asiaC)
    val (sAfterAsia, eventsAsia) = LiquidityZoneService.processLiquidityZones(state2)

    val nyHighAfter = sAfterAsia.daytimeExtremes.find(_.description.startsWith("New York - High")).get
    assert(nyHighAfter.endTime.isDefined, "NY high should be end-dated when Asia high surpasses it")
    // events should include the end update for NY high
    assert(eventsAsia.exists(_.daytimeExtreme.exists(de => de.description.startsWith("New York - High") && de.endTime.isDefined)))
  }

  test("ending a DaytimeExtreme Low when another Low surpasses it (lower)") {
    val tradingDay = LocalDate.of(2020, 1, 2)

    // Create a New York low first (previous day 10:00)
    val nyTs = tsFor(tradingDay.minusDays(1), 10, 0)
    val nyC = Candle(lvl(100f), lvl(110f), lvl(90f), lvl(105f), nyTs, CandleDuration.OneMinute)
  val state1 = SystemState(tradingDay = tradingDay, planningCandles = List(nyC), swingDirection = Direction.Up, planningSwingPoints = List.empty)
    val (sAfterNy, _) = LiquidityZoneService.processLiquidityZones(state1)

    val nyLow = sAfterNy.daytimeExtremes.find(_.description.startsWith("New York - Low")).get
    assert(nyLow.endTime.isEmpty)

    // Now an Asia candle with a lower low should cause the New York low to end
    val asiaTs = tsFor(tradingDay.minusDays(1), 19, 0) // Asia session
    val asiaC = Candle(lvl(50f), lvl(55f), lvl(80f), lvl(52f), asiaTs, CandleDuration.OneMinute)
    val state2 = sAfterNy.copy(planningCandles = sAfterNy.planningCandles :+ asiaC)
    val (sAfterAsia, eventsAsia) = LiquidityZoneService.processLiquidityZones(state2)

    val nyLowAfter = sAfterAsia.daytimeExtremes.find(_.description.startsWith("New York - Low")).get
    assert(nyLowAfter.endTime.isDefined, "NY low should be end-dated when Asia low goes lower")
    assert(eventsAsia.exists(_.daytimeExtreme.exists(de => de.description.startsWith("New York - Low") && de.endTime.isDefined)))
  }
}


