package bmps.core.services

import bmps.core.models.SystemState
import bmps.core.Event
import bmps.core.models.DaytimeExtreme
import bmps.core.models.ExtremeType
import bmps.core.models.Level
import bmps.core.models.Candle

import java.time.{LocalDate, LocalTime, ZoneId, ZonedDateTime, Instant}

object LiquidityZoneService {

    private val eastern = ZoneId.of("America/New_York")

    // Extracted helper: build/update daytime extremes based on the last candle and market windows.
    private def updateDaytimeExtremes(state: SystemState): List[DaytimeExtreme] = {
        val tradingDay = state.tradingDay
        val lastCandle = state.candles.last

        // helper to build epoch millis for a given date and local time in eastern
        def toMillis(date: LocalDate, time: LocalTime): Long = {
            ZonedDateTime.of(date, time, eastern).toInstant.toEpochMilli
        }

        // Market windows (as specified). Note: New York windows are on the day before the trading day.
        val nyOpenPrev = toMillis(tradingDay.minusDays(1), LocalTime.of(9, 30))
        val nyClosePrev = toMillis(tradingDay.minusDays(1), LocalTime.of(16, 0))

        val asiaOpenPrev = toMillis(tradingDay.minusDays(1), LocalTime.of(18, 0))
        val asiaClose = toMillis(tradingDay, LocalTime.of(2, 0))

        val londonOpen = toMillis(tradingDay, LocalTime.of(3, 0))
        val londonClose = toMillis(tradingDay, LocalTime.of(9, 30))

        // description helpers
        def desc(exchange: String, typ: String) = s"$exchange - $typ"

        // We'll lazily create extremes only when we see a candle during the market window.
        val ts = lastCandle.timestamp

        def within(open: Long, close: Long, t: Long): Boolean = t >= open && t <= close

        // Use a mutable map keyed by description so we can create/update entries easily
        import scala.collection.mutable
        val map = mutable.LinkedHashMap[String, DaytimeExtreme]()
        // seed map with any existing extremes (preserve previously created values)
        state.daytimeExtremes.foreach(de => map.put(de.description, de))

        def ensureAndUpdate(exchange: String, open: Long, close: Long): Unit = {
            if (within(open, close, ts)) {
                println(s"[LiquidityZoneService] candle ts=$ts within window for $exchange open=$open close=$close")
                val lowDesc = desc(exchange, "Low")
                val highDesc = desc(exchange, "High")

                // create if missing
                if (!map.contains(lowDesc)) {
                    val de = DaytimeExtreme(Level(lastCandle.low.value), ExtremeType.Low, lastCandle.timestamp, None, lowDesc)
                    println(s"[LiquidityZoneService] creating extreme $lowDesc -> ${de.level}")
                    map.put(lowDesc, de)
                } else {
                    val cur = map(lowDesc)
                    if (lastCandle.low.value < cur.level.value) map.put(lowDesc, cur.copy(level = Level(lastCandle.low.value)))
                }

                if (!map.contains(highDesc)) {
                    val de = DaytimeExtreme(Level(lastCandle.high.value), ExtremeType.High, lastCandle.timestamp, None, highDesc)
                    println(s"[LiquidityZoneService] creating extreme $highDesc -> ${de.level}")
                    map.put(highDesc, de)
                } else {
                    val cur = map(highDesc)
                    if (lastCandle.high.value > cur.level.value) {
                        println(s"[LiquidityZoneService] updating extreme $highDesc from ${cur.level} to ${lastCandle.high}")
                        map.put(highDesc, cur.copy(level = Level(lastCandle.high.value)))
                    }
                }
            }
        }

        // New York uses previous trading day window
        ensureAndUpdate("New York", nyOpenPrev, nyClosePrev)
        ensureAndUpdate("Asia", asiaOpenPrev, asiaClose)
        ensureAndUpdate("London", londonOpen, londonClose)

        // After creating/updating extremes for the current candle, end-date any existing extremes
        // that are now surpassed by another daytime extreme of the same type.
        val origMap = state.daytimeExtremes.map(de => de.description -> de).toMap

        // For every original extreme that is still open, if any other updated extreme of the
        // same ExtremeType has moved past it (higher for High, lower for Low), then close it
        // at the current candle timestamp.
        for ((desc, orig) <- origMap if orig.endTime.isEmpty) {
            // find any other updated extreme that surpasses the original
            val others = map.values.filter(de => de.description != desc && de.extremeType == orig.extremeType)
            val shouldClose = orig.extremeType match {
                // only consider 'other' extremes that were created/updated after the original extreme's start
                case ExtremeType.High => others.exists(o => o.timestamp > orig.timestamp && o.level.value > orig.level.value)
                case ExtremeType.Low  => others.exists(o => o.timestamp > orig.timestamp && o.level.value < orig.level.value)
            }

            if (shouldClose) {
                println(s"[LiquidityZoneService] ending extreme $desc because another extreme surpassed it at ts=$ts")
                // update the map entry to include endTime if not already set
                map.get(desc).foreach { cur =>
                    if (cur.endTime.isEmpty) map.put(desc, cur.copy(endTime = Some(ts)))
                }
            }
        }

        map.values.toList
    }

    // Generate events for any new or changed DaytimeExtremes by comparing original and updated lists.
    private def generateDaytimeExtremeEvents(original: List[DaytimeExtreme], updated: List[DaytimeExtreme]): List[Event] = {
        // index by description for quick lookup
        val origMap = original.map(de => de.description -> de).toMap
        val updMap = updated.map(de => de.description -> de).toMap


        // For every updated extreme, emit an event when it's new, its level changed, or its endTime changed.
        val createdOrChanged = updated.filter { de =>
            origMap.get(de.description) match {
                case None =>
                    println(s"[LiquidityZoneService] detected new daytime extreme: ${de.description} = ${de.level}")
                    true
                case Some(orig) =>
                    val levelChanged = orig.level.value != de.level.value
                    val endTimeChanged = orig.endTime != de.endTime
                    if (levelChanged) {
                        println(s"[LiquidityZoneService] detected changed daytime extreme: ${de.description} ${orig.level} -> ${de.level}")
                        true
                    } else if (endTimeChanged) {
                        println(s"[LiquidityZoneService] detected ended daytime extreme: ${de.description} endTime ${orig.endTime} -> ${de.endTime}")
                        true
                    } else false
            }
        }

        val events = createdOrChanged.map { de =>
            val ev = Event.fromDaytimeExtreme(de)
            println(s"[LiquidityZoneService] emitting event for daytime extreme: ${de.description}")
            ev
        }

        events
    }

    def processLiquidityZones(state: SystemState): (SystemState, List[Event]) = {
        val updated = updateDaytimeExtremes(state)
        val events = generateDaytimeExtremeEvents(state.daytimeExtremes, updated)
        (state.copy(daytimeExtremes = updated), events)
    }
}