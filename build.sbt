ThisBuild / scalaVersion        := "3.1.3"
ThisBuild / organization        := "com.fiatjaf"
ThisBuild / homepage            := Some(url("https://github.com/fiatjaf/immortan"))
ThisBuild / licenses            += License.Apache2
ThisBuild / developers          := List(
  tlGitHubDev("fiatjaf", "fiatjaf"),
  tlGitHubDev("btcontract", "anton kumaigorodskiy"),
)

ThisBuild / version             := "0.8.0-SNAPSHOT"
ThisBuild / tlSonatypeUseLegacyHost := false

lazy val root = tlCrossRootProject.aggregate(immortan)

lazy val immortan = crossProject(JVMPlatform, JSPlatform)
  .in(file("."))
  .settings(
    name := "immortan",
    description := "A lightweight client-side Lightning and Hosted Channels implementation.",
    libraryDependencies ++= Seq(
      // must be replaced
      "com.google.guava" % "guava" % "31.1-jre",
      "io.reactivex" % "rxscala_2.13" % "0.27.0",
      "io.spray" % "spray-json_2.13" % "1.3.5",

      // good
      "com.fiatjaf" %% "scoin" % "0.3.1-SNAPSHOT",
      "org.scala-lang.modules" %% "scala-parser-combinators" % "2.1.1",
      "com.softwaremill.quicklens" %% "quicklens" % "1.8.4",
    )
  )
  .jvmSettings(
    scalaVersion := "2.13.8",
    libraryDependencies ++= Seq(
      // electrum client
      "org.json4s" % "json4s-native_2.13" % "3.6.7",
      "io.netty" % "netty-all" % "4.1.42.Final",
      "com.lihaoyi" % "castor_2.13" % "0.2.1",

      // test
      "com.lihaoyi" % "utest_2.13" % "0.7.11" % Test,
      "com.lihaoyi" % "requests_2.13" % "0.7.0" % Test,
      "org.xerial" % "sqlite-jdbc" % "3.27.2.1" % Test,
    )
  )
  .jsConfigure { _.enablePlugins(NpmDependenciesPlugin) }
  .jsSettings(
    scalaVersion := "3.1.3",
    Compile / npmDependencies ++= Seq(
      // electrum client
      "@keep-network/electrum-client-js" -> "0.1.1",
    )
  )

ThisBuild / scalacOptions        ++= Seq("-deprecation", "-feature")
ThisBuild / testFrameworks        += new TestFramework("utest.runner.Framework")

// maven magic, see https://github.com/makingthematrix/scala-suffix/tree/56270a6b4abbb1cd1008febbd2de6eea29a23b52#but-wait-thats-not-all
Compile / packageBin / packageOptions += Package.ManifestAttributes("Automatic-Module-Name" -> "immortan")
