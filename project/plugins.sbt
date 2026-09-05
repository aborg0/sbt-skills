libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

addSbtPlugin("ch.epfl.scala" %% "sbt-bloop" % "2.1.2")

resolvers += "GitHub Packages" at "https://maven.pkg.github.com/aborg0/sbt-skills"
credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  sys.env("GITHUB_ACTOR"),
  sys.env("GITHUB_TOKEN")
)

addSbtPlugin("com.github.aborg0" %% "sbt-skills" % "0.1.5-1-71c8515-SNAPSHOT")
