package bmps.console

import cats.effect.{IO, IOApp}
import fs2.Stream
import bmps.core.models._
import bmps.core.services.SwingService
import java.sql.{Connection, DriverManager, ResultSet}
import java.time.Instant
import java.time.Duration
import java.nio.file.Paths
import breeze.plot._
import breeze.linalg._
import breeze.plot.Figure

object PlotSwings extends IOApp.Simple {

  case class RawCandle(symbol: String, timestamp: Instant, timeframe: String, open: Double, high: Double, low: Double, close: Double, volume: Long)

  def run: IO[Unit] = {
    val rawCandlesIO = IO {
      val connection: Connection = DriverManager.getConnection("jdbc:duckdb:")
      val statement = connection.createStatement()
      val filePath = "../core/src/main/resources/samples/es_futures_1h_60days.parquet"
      val resultSet = statement.executeQuery(s"SELECT * FROM '$filePath'")
      val rawCandles = scala.collection.mutable.ListBuffer[RawCandle]()
      while (resultSet.next()) {
        val rawCandle = RawCandle(
          symbol = resultSet.getString("symbol"),
          timestamp = resultSet.getTimestamp("timestamp").toInstant,
          timeframe = resultSet.getString("timeframe"),
          open = resultSet.getDouble("open"),
          high = resultSet.getDouble("high"),
          low = resultSet.getDouble("low"),
          close = resultSet.getDouble("close"),
          volume = resultSet.getLong("volume")
        )
        rawCandles += rawCandle
      }
      resultSet.close()
      statement.close()
      connection.close()
      rawCandles.toList
    }

    for {
      rawCandles <- rawCandlesIO
      _ <- IO.println(s"Loaded ${rawCandles.length} candles")
      // Filter to last 10 days
      latestTimestamp = rawCandles.map(_.timestamp).max
      tenDaysAgo = latestTimestamp.minus(Duration.ofDays(10))
      filteredRawCandles = rawCandles.filter(_.timestamp.isAfter(tenDaysAgo))
      _ <- IO.println(s"Filtered to ${filteredRawCandles.length} candles from last 10 days")
      candles = filteredRawCandles.map(rc => Candle(
        open = Level(rc.open.toFloat),
        high = Level(rc.high.toFloat),
        low = Level(rc.low.toFloat),
        close = Level(rc.close.toFloat),
        timestamp = rc.timestamp.toEpochMilli,
        duration = CandleDuration.OneHour
      ))
      swingService = new SwingService(minConfirmations = 3)
      initialState = SystemState(candles = candles, direction = Direction.Up, swingPoints = List.empty)
      finalState = swingService.computeSwings(initialState)
      _ <- IO {
        println(s"Computed ${finalState.swingPoints.length} swing points")
        finalState.swingPoints.foreach(sp => println(s"Swing at level ${sp.level.value}, direction ${sp.direction}"))

        // Plotting
        val timestamps = candles.map(_.timestamp.toDouble).toArray
        val closes = candles.map(_.close.value.toDouble).toArray
        val swingTimestamps = finalState.swingPoints.map(_.timestamp.toDouble).toArray
        val swingLevels = finalState.swingPoints.map(_.level.value.toDouble).toArray

        val f = Figure()
        val p = f.subplot(0)
        p += plot(timestamps, closes, name = "Close Prices")
        p += plot(swingTimestamps, swingLevels, '.', name = "Swing Points")
        p.xlabel = "Time"
        p.ylabel = "Price"
        p.title = "Candles and Swings"
        p.legend = true
        f.saveas("swings_plot.png")
        println("Plot saved as swings_plot.png")
      }
    } yield ()
  }
}
