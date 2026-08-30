package com.github.aborg0.sbt.skills.registry

import com.github.aborg0.sbt.skills.config.{SkillReference, SkillSource}
import com.github.aborg0.sbt.skills.registry.SkillRegistry.Data
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sbt.util.Logger

import java.nio.file.Files
import java.time.Instant

class SkillRegistrySpec extends AnyFlatSpec with Matchers {

  private def withTemporaryRegistry[A](test: SkillRegistry => A): A = {
    val directory = Files.createTempDirectory("skill-registry")
    val registryFile = directory.resolve("registry.json")
    try {
      test(new SkillRegistry(registryFile.toFile, Logger.Null))
    } finally {
      Files.deleteIfExists(registryFile)
      Files.deleteIfExists(directory)
    }
  }

  "SkillRegistry.load" should "return empty registry if file doesn't exist" in {
    withTemporaryRegistry { registry =>
      val result = registry.load()
      result.isSuccess shouldBe true
      result.toOption.map(_.skills) should contain(Seq())
    }
  }

  "SkillRegistry.addOrUpdateSource" should "add new source to registry" in {
    val data = Data(Seq(), Seq())
    val source = SkillSource("test", "https://github.com/test/repo.git", "main")

    withTemporaryRegistry { registry =>
      val updated = registry.addOrUpdateSource(data, source, None)
      updated.sources should have length 1
      updated.sources(0).id should be("test")
    }
  }

  "SkillRegistry.addOrUpdateSkills" should "add skills to registry" in {
    val now = Instant.now()
    val skill = SkillReference(
      id = "test-skill",
      sourceId = "test-source",
      category = "testing",
      path = "skills/testing/test-skill/SKILL.md",
      version = "abc123",
      harnessesInRepo = Seq("copilot"),
      effectiveHarnesses = Seq("copilot"),
      lastFetched = now,
      customized = false
    )
    
    val data = Data(Seq(), Seq())

    withTemporaryRegistry { registry =>
      val updated = registry.addOrUpdateSkills(data, Seq(skill))
      updated.skills should have length 1
      updated.skills(0).id should be("test-skill")
    }
  }

  it should "replace an existing skill when its effective harnesses change" in {
    val now = Instant.now()
    val existingSkill = SkillReference(
      id = "test-skill",
      sourceId = "test-source",
      category = "testing",
      path = "skills/testing/test-skill/SKILL.md",
      version = "abc123",
      harnessesInRepo = Seq("copilot", "claude"),
      effectiveHarnesses = Seq("copilot"),
      lastFetched = now
    )
    val updatedSkill = existingSkill.copy(
      version = "def456",
      effectiveHarnesses = Seq("claude")
    )
    val data = Data(Seq(), Seq(existingSkill))

    withTemporaryRegistry { registry =>
      val updated = registry.addOrUpdateSkills(data, Seq(updatedSkill))
      updated.skills should contain only updatedSkill
    }
  }

  "SkillRegistry.getSkillsForHarness" should "filter skills by harness" in {
    val now = Instant.now()
    val skill1 = SkillReference(
      id = "skill1",
      sourceId = "source1",
      category = "test",
      path = "skills/test/skill1/SKILL.md",
      version = "abc",
      harnessesInRepo = Seq("copilot", "claude"),
      effectiveHarnesses = Seq("copilot", "claude"),
      lastFetched = now
    )
    val skill2 = SkillReference(
      id = "skill2",
      sourceId = "source1",
      category = "test",
      path = "skills/test/skill2/SKILL.md",
      version = "abc",
      harnessesInRepo = Seq("claude"),
      effectiveHarnesses = Seq("claude"),
      lastFetched = now
    )
    
    val data = Data(Seq(), Seq(skill1, skill2))

    withTemporaryRegistry { registry =>
      val copilotSkills = registry.getSkillsForHarness(data, " CopILot ")
      copilotSkills should have length 1
      copilotSkills(0).id should be("skill1")

      val claudeSkills = registry.getSkillsForHarness(data, "claude")
      claudeSkills should have length 2
    }
  }

}
