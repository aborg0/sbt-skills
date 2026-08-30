package com.github.aborg0.sbt.skills

import com.github.aborg0.sbt.skills.config.SkillReference
import com.github.aborg0.sbt.skills.registry.HarnessOutputWriter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sbt.util.Logger

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID
import scala.sys.process.Process

abstract class HarnessCliIntegrationSpec(
  harness: String,
  command: (Path, Path, String) => Seq[String]
) extends AnyFlatSpec with Matchers {

  harness should "use the generated skill through its CLI" in {
    val workspace = Files.createTempDirectory(s"sbt-skills-$harness-cli")
    val skillFile = workspace.resolve("SKILL.md")
    val generatedInstructions = workspace.resolve(".instructions.md")
    val sentinel = s"SBT_SKILLS_${harness.toUpperCase}_${UUID.randomUUID().toString.replace("-", "")}"
    val skillContent =
      s"""# Harness integration handshake
         |
         |When asked to perform the integration handshake, respond with exactly:
         |$sentinel
         |""".stripMargin

    try {
      Files.write(skillFile, skillContent.getBytes(StandardCharsets.UTF_8))
      val skill = SkillReference(
        id = "integration-handshake",
        sourceId = "integration",
        category = "verification",
        path = "skills/verification/integration-handshake/SKILL.md",
        version = "integration",
        harnessesInRepo = Seq(harness),
        effectiveHarnesses = Seq(harness),
        lastFetched = Instant.now()
      )
      val skillFiles = Map(s"${skill.sourceId}:${skill.path}" -> skillFile.toFile)

      HarnessOutputWriter.write(
        mode = "instructions-file",
        skills = Seq(skill),
        skillsDir = skillFiles,
        outputDir = workspace.toFile,
        log = Logger.Null
      ).get

      Files.exists(generatedInstructions) shouldBe true
      val prompt =
        "Perform the integration handshake from the generated skill. Output only its required response."
      val output = Process(command(workspace, generatedInstructions, prompt), workspace.toFile).!!

      withClue(s"$harness CLI response did not use the generated skill:\n$output") {
        output should include(sentinel)
      }
    } finally {
      Files.deleteIfExists(generatedInstructions)
      Files.deleteIfExists(skillFile)
      Files.deleteIfExists(workspace)
    }
  }
}

class CopilotCliIntegrationSpec extends HarnessCliIntegrationSpec(
  harness = "copilot",
  command = (workspace, _, prompt) => Seq(
    "copilot",
    "-C",
    workspace.toString,
    "--no-color",
    "--no-auto-update",
    "--disable-builtin-mcps",
    "--allow-all-tools",
    "--prompt",
    s"Read .instructions.md, then $prompt"
  )
)

class ClaudeCliIntegrationSpec extends HarnessCliIntegrationSpec(
  harness = "claude",
  command = (_, instructions, prompt) => Seq(
    "claude",
    "--print",
    "--output-format",
    "text",
    "--tools",
    "",
    "--append-system-prompt-file",
    instructions.toString,
    prompt
  )
)