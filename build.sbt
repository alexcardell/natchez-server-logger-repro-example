val repo = "natchez-server-logger-repro-example"

Global / onChangedBuildSource := ReloadOnSourceChanges
ThisBuild / Compile / run / fork := true
ThisBuild / scalaVersion := "2.13.12"
ThisBuild / organization := "io.cardell"
ThisBuild / organizationName := "alexcardell"

lazy val root = (project in file("."))
  .settings(commonSettings: _*)
  .settings(
    name := s"$repo",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-client" % "0.23.23",
      "org.http4s" %% "http4s-ember-server" % "0.23.23",
      "org.http4s" %% "http4s-dsl" % "0.23.23",
      "org.http4s" %% "http4s-otel4s-middleware" % "0.6.0",
      "org.typelevel" %% "otel4s-sdk" % "0.7.0",
      "org.typelevel" %% "log4cats-slf4j" % "2.6.0",
      "ch.qos.logback" % "logback-classic" % "1.4.14",
      "net.logstash.logback" % "logstash-logback-encoder" % "7.0.1",
      "ch.qos.logback.contrib" % "logback-jackson" % "0.1.5",
      "ch.qos.logback.contrib" % "logback-json-classic" % "0.1.5"
    )
  )

lazy val commonSettings = Seq(
  scalacOptions ++= List("-Wunused", "-Ymacro-annotations"),
  testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
  addCompilerPlugin(
    "org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full
  ),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
  publishArtifact := false,
  publish / skip := true
)
