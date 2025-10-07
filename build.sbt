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
  "org.http4s" %% "http4s-ember-server" % "0.23.26",
  "org.http4s" %% "http4s-dsl" % "0.23.26",
  "org.http4s" %% "http4s-circe" % "0.23.26",
    "io.circe" %% "circe-parser" % "0.14.6",
      "com.typesafe" % "config" % "1.4.3",
      "io.circe" %% "circe-core" % "0.14.6",
      "io.circe" %% "circe-generic" % "0.14.6",
      "org.scalatest" %% "scalatest" % "3.2.17" % Test,
      "org.typelevel" %% "cats-effect-testing-scalatest" % "1.5.0" % Test
    )
  )

lazy val console = (project in file("console"))
  .settings(
    name := "bmps-console",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.5.4",
      "com.squareup.okhttp3" % "okhttp" % "4.12.0",
      "io.circe" %% "circe-core" % "0.14.6",
      "io.circe" %% "circe-generic" % "0.14.6",
      "io.circe" %% "circe-parser" % "0.14.6",
      "ch.qos.logback" % "logback-classic" % "1.4.11"
    ),
    Compile / mainClass := Some("bmps.console.ConsoleClient")
  )
