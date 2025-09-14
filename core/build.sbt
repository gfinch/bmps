name := "bmps-core"

libraryDependencies ++= Seq(
  "co.fs2" %% "fs2-core" % "3.9.4",
  "org.typelevel" %% "cats-effect" % "3.5.4",
  "com.typesafe" % "config" % "1.4.3",
  "io.circe" %% "circe-core" % "0.14.6",
  "io.circe" %% "circe-generic" % "0.14.6",
  "org.scalatest" %% "scalatest" % "3.2.17" % Test,
  "org.typelevel" %% "cats-effect-testing-scalatest" % "1.5.0" % Test,
  // For Parquet reading with DuckDB
  "org.duckdb" % "duckdb_jdbc" % "1.1.0",
  // For plotting
  "org.scalanlp" %% "breeze" % "2.1.0",
  "org.scalanlp" %% "breeze-viz" % "2.1.0"
)

// Embedded websocket server for single-process frontend + core
libraryDependencies += "org.java-websocket" % "Java-WebSocket" % "1.5.3"

// Logging implementation for SLF4J (enable our logger.info/info output)
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.4.11"

// Note: running in-process (non-forked) is preferred for interactive debugging.
// Removed the forked-run JVM settings to keep sbt in-process.
