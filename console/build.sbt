name := "bmps-console"

Compile / run / fork := true

javaOptions ++= Seq(
  "-Djava.security.auth.login.config=/dev/null",
  "-DHADOOP_USER_NAME=user",
  "-Dhadoop.security.authentication=simple",
  "-Dhadoop.security.authorization=false"
)
