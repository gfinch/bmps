name := "bmps-core"

libraryDependencies ++= Seq(
  "co.fs2" %% "fs2-core" % "3.9.4",
  "org.typelevel" %% "cats-effect" % "3.5.4",
  "com.typesafe" % "config" % "1.4.3",
  "io.circe" %% "circe-core" % "0.14.6",
  "io.circe" %% "circe-generic" % "0.14.6",
  "io.circe" %% "circe-parser" % "0.14.6",
  "org.scalatest" %% "scalatest" % "3.2.17" % Test,
  "org.typelevel" %% "cats-effect-testing-scalatest" % "1.5.0" % Test,
  // For Parquet reading with DuckDB
  "org.duckdb" % "duckdb_jdbc" % "1.1.0",
  // For plotting
  "org.scalanlp" %% "breeze" % "2.1.0",
  "org.scalanlp" %% "breeze-viz" % "2.1.0",
  // AWS SDK for S3 access
  "software.amazon.awssdk" % "s3" % "2.27.21",
  "software.amazon.awssdk" % "auth" % "2.27.21",
  // HTTP client for Polygon.io REST API calls
  "com.squareup.okhttp3" % "okhttp" % "4.12.0",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.15.2",
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.15.2",
  "org.ta4j" % "ta4j-core" % "0.18"
)

// Embedded websocket server for single-process frontend + core
libraryDependencies += "org.java-websocket" % "Java-WebSocket" % "1.5.3"

// Logging implementation for SLF4J (enable our logger.info/info output)
libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.4.11"

// Note: running in-process (non-forked) is preferred for interactive debugging.
// Removed the forked-run JVM settings to keep sbt in-process.

// Forward environment variables to the running application
run / envVars := Map(
  "DATABENTO_KEY" -> sys.env.getOrElse("DATABENTO_KEY", ""),
  "TRADOVATE_PASS" -> sys.env.getOrElse("TRADOVATE_PASS", ""),
  "TRADOVATE_KEY" -> sys.env.getOrElse("TRADOVATE_KEY", ""),
  "TRADOVATE_DEVICE" -> sys.env.getOrElse("TRADOVATE_DEVICE", "")
)

// Assembly settings for creating fat JAR
assembly / assemblyMergeStrategy := {
  case PathList("META-INF", xs @ _*) => xs match {
    case "MANIFEST.MF" :: Nil => MergeStrategy.discard
    case "services" :: _ => MergeStrategy.concat
    case _ => MergeStrategy.discard
  }
  case "reference.conf" => MergeStrategy.concat
  case "application.conf" => MergeStrategy.concat
  case x if x.endsWith(".proto") => MergeStrategy.first
  case x if x.contains("module-info") => MergeStrategy.discard
  case _ => MergeStrategy.first
}

assembly / mainClass := Some("bmps.core.AppLauncher")
assembly / assemblyJarName := s"${name.value}-assembly-${version.value}.jar"
