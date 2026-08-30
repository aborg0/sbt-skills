libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

if (scala.util.Properties.versionNumberString.startsWith("2.12")) {
	addSbtPlugin("com.geirsson" % "sbt-ci-release" % "1.5.7")
} else {
	resolvers ++= Seq.empty
}

