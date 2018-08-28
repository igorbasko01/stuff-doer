import Dependencies._

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.baskorp",
      scalaVersion := "2.11.8",
      version      := "1.0-SNAPSHOT"
    )),
    name := "stuff-doer",
    libraryDependencies += logbackClassic,
    libraryDependencies += akkaSlf4j,
    libraryDependencies += akkaActor,
    libraryDependencies += akkaHttp,
    libraryDependencies += akkaStream,
    libraryDependencies += typesafeConfig,
    libraryDependencies += h2,
    libraryDependencies += jodaTime,
    libraryDependencies += jodaConvert,
    libraryDependencies += akkaHttpSprayJson,
    libraryDependencies += akkaHttpTestkit,
    libraryDependencies += scalaTest % Test,
    libraryDependencies += akkaTestkit % Test
  )

// assembly configuration
assemblyJarName in assembly := "stuff-doer.jar"
