ThisBuild / scalaVersion := "2.13.12"

lazy val root = (project in file("."))
  .aggregate(core, console)
  .settings(
    name := "bmps"
  )

lazy val core = (project in file("core"))
  .settings(
    name := "bmps-core",
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
      // Embedded websocket server for single-process frontend + core
      "org.java-websocket" % "Java-WebSocket" % "1.5.3",
      // Logging implementation for SLF4J
      "ch.qos.logback" % "logback-classic" % "1.4.11",
      // HTTP4S dependencies
      "org.http4s" %% "http4s-ember-server" % "0.23.26",
      "org.http4s" %% "http4s-dsl" % "0.23.26",
      "org.http4s" %% "http4s-circe" % "0.23.26",
      "com.comcast" %% "ip4s-core" % "3.2.0"
    )
  )

lazy val console = (project in file("console"))
  .dependsOn(core)
  .settings(
    name := "bmps-console",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.5.4",
      "co.fs2" %% "fs2-core" % "3.9.4",
      "com.squareup.okhttp3" % "okhttp" % "4.12.0",
      "io.circe" %% "circe-core" % "0.14.6",
      "io.circe" %% "circe-generic" % "0.14.6",
      "io.circe" %% "circe-parser" % "0.14.6",
      "ch.qos.logback" % "logback-classic" % "1.4.11",
      "com.typesafe" % "config" % "1.4.3",
      "org.duckdb" % "duckdb_jdbc" % "1.1.0"
    ),
    Compile / mainClass := Some("bmps.console.TrainingDatasetApp")
  )
