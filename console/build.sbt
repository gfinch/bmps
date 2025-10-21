name := "bmps-console"

// Depend on the core project
dependsOn(LocalProject("core"))

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
)

// Set main class for running
Compile / mainClass := Some("bmps.console.TrainingDatasetApp")
