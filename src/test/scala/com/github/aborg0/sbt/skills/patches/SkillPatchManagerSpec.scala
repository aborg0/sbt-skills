package com.github.aborg0.sbt.skills.patches

import com.github.aborg0.sbt.skills.config.SkillReference
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sbt.util.Logger

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.time.Instant

class SkillPatchManagerSpec extends AnyFlatSpec with Matchers {

  "SkillPatchManager" should "apply a unified diff while preserving unchanged content" in {
    val root = Files.createTempDirectory("skill-patch")
    val sourceRoot = root.resolve("sources").toFile
    val patchesDir = root.resolve("patches").toFile
    val renderDir = root.resolve("rendered").toFile
    val sourceFile = root.resolve("sources/source/skills/engineering/review/SKILL.md")
    val patchFile = root.resolve("patches/source/engineering/review.patch")
    Files.createDirectories(sourceFile.getParent)
    Files.createDirectories(patchFile.getParent)
    Files.write(sourceFile, "line one\nline two\nline three\n".getBytes(UTF_8))
    Files.write(
      patchFile,
      "--- a/skills/engineering/review/SKILL.md\n+++ b/skills/engineering/review/SKILL.md\n@@ -1,3 +1,3 @@\n line one\n-line two\n+updated line two\n line three\n".getBytes(UTF_8)
    )

    val skill = SkillReference(
      "review", "source", "engineering", "skills/engineering/review/SKILL.md", "v1",
      Seq("copilot"), Seq("copilot"), Instant.now()
    )

    val result = SkillPatchManager.materialize(Seq(skill), sourceRoot, patchesDir, renderDir, Logger.Null).get
    val rendered = result._1("source:skills/engineering/review/SKILL.md")
    new String(Files.readAllBytes(rendered.toPath), UTF_8) shouldBe "line one\nupdated line two\nline three\n"
    result._2 should contain("source:skills/engineering/review/SKILL.md")
  }

  it should "reject patches whose context does not match" in {
    val root = Files.createTempDirectory("invalid-skill-patch")
    val sourceRoot = root.resolve("sources").toFile
    val patchesDir = root.resolve("patches").toFile
    val renderDir = root.resolve("rendered").toFile
    val sourceFile = root.resolve("sources/source/skills/review/SKILL.md")
    val patchFile = root.resolve("patches/source/review.patch")
    Files.createDirectories(sourceFile.getParent)
    Files.createDirectories(patchFile.getParent)
    Files.write(sourceFile, "actual\n".getBytes(UTF_8))
    Files.write(patchFile, "@@ -1,1 +1,1 @@\n-wrong\n+updated\n".getBytes(UTF_8))
    val skill = SkillReference("review", "source", "", "skills/review/SKILL.md", "v1", Seq(), Seq(), Instant.now())

    val failure = SkillPatchManager.materialize(Seq(skill), sourceRoot, patchesDir, renderDir, Logger.Null).failed.get
    failure.getMessage should include("Patch context mismatch")
  }
}
