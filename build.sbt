
name := """sbt-skills"""
ThisBuild / version := "0.1-SNAPSHOT"
ThisBuild / versionScheme := Some("early-semver")

sbtPlugin := true

// Cross-build the plugin for both sbt 1.x (Scala 2.12) and sbt 2.x (Scala 3),
// following the pattern from https://github.com/sbt/sbt2-compat
def scala212 = "2.12.20"
def scala3 = "3.8.4"
scalaVersion := {
  if (sbtVersion.value.startsWith("2.")) scala3 else scala212
}
crossScalaVersions := Seq(scala212, scala3)

(pluginCrossBuild / sbtVersion) := {
  scalaBinaryVersion.value match {
    case "2.12" => sbtVersion.value
    case _      => "2.0.0"
  }
}

Compile / scalacOptions ++= {
  scalaBinaryVersion.value match {
    case "2.12" => Seq("-Xsource:3", "-feature", "-unchecked")
    case _      => Seq("-feature", "-unchecked")
  }
}

lazy val HarnessIntegration = config("harnessIntegration") extend Test

configs(HarnessIntegration)
inConfig(HarnessIntegration)(Defaults.testSettings)
// The harness-integration tests exercise the sbt CLI directly and are only
// relevant to the sbt 1.x / Scala 2.12 build of this plugin.
HarnessIntegration / unmanagedSourceDirectories := {
  if (scalaBinaryVersion.value == "2.12")
    Seq(baseDirectory.value / "src" / "harness-integration" / "scala")
  else Seq.empty
}
HarnessIntegration / parallelExecution := false
Global / excludeLintKeys += HarnessIntegration / semanticdbTargetRoot
Global / excludeLintKeys += HarnessIntegration / javaSource
Global / excludeLintKeys += HarnessIntegration / scalaSource

// Dependencies
libraryDependencies ++= Seq(
  // Git operations
  "org.eclipse.jgit" % "org.eclipse.jgit" % "6.7.0.202309050840-r",
  
  // JSON parsing and serialization
  "io.circe" %% "circe-core" % "0.14.6",
  "io.circe" %% "circe-parser" % "0.14.6",
  "io.circe" %% "circe-generic" % "0.14.6",
  
  // Testing
  "org.scalatest" %% "scalatest" % "3.2.17" % Test
)

libraryDependencies ++= {
  if (scalaBinaryVersion.value == "2.12") Seq(sbtDependency.value % HarnessIntegration.name)
  else Seq.empty
}

// Compat layer providing a unified API for plugins cross-building against sbt 1.x and sbt 2.x
addSbtPlugin("com.github.sbt" % "sbt2-compat" % "0.2.0")

inThisBuild(List(
  organization := "com.github.aborg0",
  homepage := Some(url("https://github.com/aborg0/sbt-skills")),
  licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  developers := List(
    Developer(
      "aborg0",
      "Gábor Bakos",
      "aborg0@users.noreply.github.com",
      url("https://github.com/aborg0")
    )
  )
))

ThisBuild / publishMavenStyle := true
ThisBuild / publishTo := Some(
  "GitHub Packages" at
    s"https://maven.pkg.github.com/${sys.env.getOrElse("GITHUB_REPOSITORY", "aborg0/sbt-skills")}"
)
ThisBuild / credentials ++=
  (for {
    actor <- sys.env.get("GITHUB_ACTOR")
    token <- sys.env.get("GITHUB_TOKEN")
  } yield Credentials("GitHub Package Registry", "maven.pkg.github.com", actor, token)).toSeq

initialCommands / console := """import com.github.aborg0.sbt.skills._"""

enablePlugins(ScriptedPlugin)
// set up 'scripted; sbt plugin for testing sbt plugins
scriptedLaunchOpts ++=
  Seq("-Xmx1024M", "-Dplugin.version=" + version.value)

