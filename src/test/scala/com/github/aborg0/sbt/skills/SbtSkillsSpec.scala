package com.github.aborg0.sbt.skills

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SbtSkillsSpec extends AnyFlatSpec with Matchers {

  "resolveEffectiveHarnesses" should "limit global harnesses to repository support" in {
    val result = SbtSkillsPlugin.resolveEffectiveHarnesses(
      repositoryHarnesses = Seq("copilot"),
      globalHarnesses = Seq("copilot", "claude"),
      sourceOverride = None
    )

    result shouldBe Seq("copilot")
  }

  it should "apply a source override before repository filtering" in {
    val result = SbtSkillsPlugin.resolveEffectiveHarnesses(
      repositoryHarnesses = Seq("copilot", "claude"),
      globalHarnesses = Seq("copilot"),
      sourceOverride = Some(Seq("claude"))
    )

    result shouldBe Seq("claude")
  }

  it should "normalize and deduplicate harness names" in {
    val result = SbtSkillsPlugin.resolveEffectiveHarnesses(
      repositoryHarnesses = Seq("copilot", "claude"),
      globalHarnesses = Seq(" Copilot ", "COPILOT", "unsupported"),
      sourceOverride = None
    )

    result shouldBe Seq("copilot")
  }

  it should "return no harnesses when configuration and metadata do not overlap" in {
    val result = SbtSkillsPlugin.resolveEffectiveHarnesses(
      repositoryHarnesses = Seq("claude"),
      globalHarnesses = Seq("copilot"),
      sourceOverride = None
    )

    result shouldBe empty
  }
}
