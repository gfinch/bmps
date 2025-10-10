name := "bmps-console"

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect" % "3.5.4",
  "com.squareup.okhttp3" % "okhttp" % "4.12.0",
  "io.circe" %% "circe-core" % "0.14.6",
  "io.circe" %% "circe-generic" % "0.14.6",
  "io.circe" %% "circe-parser" % "0.14.6",
  "ch.qos.logback" % "logback-classic" % "1.4.11"
)

// Set main class for running
Compile / mainClass := Some("bmps.console.TradovateBrokerTest")
