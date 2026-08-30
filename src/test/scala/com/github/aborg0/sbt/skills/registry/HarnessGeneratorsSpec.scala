package com.github.aborg0.sbt.skills.registry

import com.github.aborg0.sbt.skills.config.SkillReference
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sbt.util.Logger

import java.time.Instant

class HarnessGeneratorsSpec extends AnyFlatSpec with Matchers {

  "InstructionsFileGenerator" should "produce deterministic output for reordered skills and harnesses" in {
    val now = Instant.now()
    val copilotSkill = skill("copilot-skill", Seq("copilot"), now)
    val sharedSkill = skill("shared-skill", Seq("copilot", "claude"), now)
    val reorderedSharedSkill = sharedSkill.copy(effectiveHarnesses = Seq("claude", "copilot"))
    val generator = new InstructionsFileGenerator(Logger.Null)

    val first = generator.generate(Seq(copilotSkill, sharedSkill), Map.empty)
    val second = generator.generate(Seq(reorderedSharedSkill, copilotSkill), Map.empty)

    second shouldBe first
    first.indexOf("## Claude & Copilot") should be < first.indexOf("## Copilot\n")
  }

  private def skill(id: String, harnesses: Seq[String], now: Instant): SkillReference = {
    SkillReference(
      id = id,
      sourceId = "source",
      category = "category",
      path = s"skills/category/$id/SKILL.md",
      version = "abcdef0",
      harnessesInRepo = harnesses,
      effectiveHarnesses = harnesses,
      lastFetched = now
    )
  }
}