import com.github.aborg0.sbt.skills.config.SkillSource

name         := "skill-source-nonempty"
version      := "0.1.0"
organization := "com.example"

// Non-empty skillsSources exercising the SkillSource case class directly in build.sbt,
// pointing at a local git repository created by setup.sh (no network access needed).
skillsSources := Seq(
  SkillSource(
    id = "local",
    url = (baseDirectory.value / "upstream-repo").getAbsolutePath,
    ref = "main"
  )
)
skillsToAdd      := Seq("local:engineering/code-review")
skillsHarnesses  := Seq("copilot")
skillsOutputDir  := baseDirectory.value
