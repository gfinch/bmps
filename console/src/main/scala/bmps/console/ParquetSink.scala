package bmps.console

import cats.effect.IO
import java.time.LocalDate
import java.sql.{Connection, DriverManager, PreparedStatement}
import java.io.File

trait ParquetSink {
  def writeRow(
    timestamp: Long,
    symbol: String, 
    featureVector: Array[Float],
    labelVector: Array[Float],
    duration: String,
    lagMinutes: Int
  ): IO[Unit]
  
  def flushDay(date: LocalDate): IO[Unit]
  def close(): IO[Unit]
}

object ParquetSink {
  def create(outputDir: String): IO[ParquetSink] = {
    IO.delay {
      new DuckDBParquetSink(outputDir)
    }
  }
}

class DuckDBParquetSink(outputDir: String) extends ParquetSink {
  
  // In-memory DuckDB connection
  private lazy val connection: Connection = {
    Class.forName("org.duckdb.DuckDBDriver")
    DriverManager.getConnection("jdbc:duckdb:")
  }
  
  // Create the output directory
  private val outputDirFile = new File(outputDir)
  if (!outputDirFile.exists()) {
    outputDirFile.mkdirs()
  }
  
  // Initialize the table
  private def initializeTable(): Unit = {
    val stmt = connection.createStatement()
    
    // Create table with JSON string columns for vectors (DuckDB LIST support varies)
    stmt.execute("""
      CREATE TABLE IF NOT EXISTS training_data (
        timestamp BIGINT,
        symbol VARCHAR,
        feature_vector VARCHAR,  -- JSON array of floats
        label_vector VARCHAR,    -- JSON array of floats 
        duration VARCHAR,
        lag_minutes INTEGER
      )
    """)
    
    stmt.close()
  }
  
  // Initialize table on first use
  private lazy val insertStmt: PreparedStatement = {
    initializeTable()
    connection.prepareStatement("""
      INSERT INTO training_data 
      (timestamp, symbol, feature_vector, label_vector, duration, lag_minutes)
      VALUES (?, ?, ?, ?, ?, ?)
    """)
  }
  
  // Convert float array to JSON string
  private def floatArrayToJson(arr: Array[Float]): String = {
    "[" + arr.map(_.toString).mkString(",") + "]"
  }
  
  override def writeRow(
    timestamp: Long,
    symbol: String,
    featureVector: Array[Float], 
    labelVector: Array[Float],
    duration: String,
    lagMinutes: Int
  ): IO[Unit] = {
    IO.delay {
      insertStmt.setLong(1, timestamp)
      insertStmt.setString(2, symbol)
      insertStmt.setString(3, floatArrayToJson(featureVector))
      insertStmt.setString(4, floatArrayToJson(labelVector))
      insertStmt.setString(5, duration)
      insertStmt.setInt(6, lagMinutes)
      insertStmt.executeUpdate()
    }
  }
  
  override def flushDay(date: LocalDate): IO[Unit] = {
    IO.delay {
      val filename = s"training_data_${date.toString}.parquet"
      val outputPath = new File(outputDirFile, filename).getAbsolutePath
      
      // Export to Parquet file
      val stmt = connection.createStatement()
      stmt.execute(s"""
        COPY (SELECT * FROM training_data) 
        TO '$outputPath' 
        (FORMAT PARQUET)
      """)
      
      // Clear the table for next day
      stmt.execute("DELETE FROM training_data")
      stmt.close()
      
      println(s"    Exported ${new File(outputPath).length() / 1024}KB to $filename")
    }
  }
  
  override def close(): IO[Unit] = {
    IO.delay {
      if (insertStmt != null) insertStmt.close()
      if (connection != null) connection.close()
    }
  }
}