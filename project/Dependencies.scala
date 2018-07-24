import sbt._

object Dependencies {
  lazy val logbackClassic = "ch.qos.logback" % "logback-classic" % "2.22"
  lazy val akkaSlf4j = "com.typesafe.akka" %% "akka-slf4j" % "2.5.2"
  lazy val akkaActor = "com.typesafe.akka" %% "akka-actor" % "2.5.2"
  lazy val akkaHttp = "com.typesafe.akka" %% "akka-http" % "10.0.5"
  lazy val akkaStream = "com.typesafe.akka" %% "akka-stream" % "2.5.2"
  lazy val typesafeConfig = "com.typesafe" % "config" % "1.3.1"
  lazy val h2 = "com.h2database" % "h2" % "1.4.196"
  lazy val jodaTime = "joda-time" % "joda-time" % "2.9.9"
  lazy val jodaConvert = "org.joda" % "joda-convert" % "1.9.2"
  lazy val akkaHttpSprayJson = "com.typesafe.akka" %% "akka-http-spray-json" % "10.0.11"
  lazy val akkaHttpTestkit = "com.typesafe.akka" %% "akka-http-testkit" % "10.1.1"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.5"
  lazy val akkaTestkit = "com.typesafe.akka" %% "akka-testkit" % "2.5.12"
}
