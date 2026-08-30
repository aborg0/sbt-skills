package com.github.aborg0.sbt.skills.registry

import com.github.aborg0.sbt.skills.config.SkillReference
import sbt.util.Logger
import java.io.{File, FileWriter}
import java.nio.file.Files
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.StandardOpenOption.{CREATE, TRUNCATE_EXISTING}
import scala.collection.immutable.{SortedMap, SortedSet}
import scala.io.Source
import scala.util.Try

/**
 * Generates harness-specific output files (e.g., .instructions.md, SKILLS.md).
 */
trait HarnessGenerator {
  def generate(skills: Seq[SkillReference], skillsDir: Map[String, File]): String
}

/**
 * Generates .instructions.md file by concatenating skill contents, organized by harness type.
 */
class InstructionsFileGenerator(log: Logger) extends HarnessGenerator {

  def generate(skills: Seq[SkillReference], skillsDir: Map[String, File]): String = {
    implicit val harnessOrdering: Ordering[SortedSet[String]] =
      Ordering.by(_.mkString("\u0000"))
    val grouped = skills.foldLeft(SortedMap.empty[SortedSet[String], Seq[SkillReference]]) {
      case (groups, skill) =>
        val harnesses          = SortedSet.empty[String] ++ skill.effectiveHarnesses
        val skillsForHarnesses = groups.getOrElse(harnesses, Seq.empty)
        groups.updated(harnesses, skill +: skillsForHarnesses)
    }

    val sections = grouped.map { case (harnesses, skillsForHarnesses) =>
      val harnessLabel = if (harnesses.size == 1) {
        harnesses.head.capitalize
      } else {
        harnesses.map(_.capitalize).mkString(" & ")
      }

      val skillSections = skillsForHarnesses
        .sortBy(s => (s.sourceId, s.category, s.id, s.path))
        .map { skill =>
          val skillId = Seq(skill.sourceId, skill.category, skill.id)
            .filter(_.nonEmpty)
            .mkString("/")
          val skillKey     = s"${skill.sourceId}:${skill.path}"
          val skillContent = readSkillFile(skillsDir.get(skillKey), skillKey)
          s"## $skillId\n\n$skillContent"
        }
        .mkString("\n\n")

      s"## $harnessLabel\n\n$skillSections"
    }

    val header = """# LLM Skills for This Project

This project uses skills from multiple sources.

"""

    header + sections.mkString("\n\n")
  }

  private def readSkillFile(fileOpt: Option[File], skillKey: String): String = {
    fileOpt match {
      case Some(file) if file.exists() =>
        try {
          val source = Source.fromFile(file)
          try {
            source.mkString
          } finally {
            source.close()
          }
        } catch {
          case e: Exception =>
            log.warn(s"Failed to read skill file ${file.getAbsolutePath}: ${e.getMessage}")
            "[Content unavailable]"
        }
      case Some(file) =>
        log.warn(s"Skill file for '$skillKey' not found at ${file.getAbsolutePath}")
        "[Content unavailable]"
      case None =>
        log.warn(s"No skill file registered for '$skillKey'")
        "[Content unavailable]"
    }
  }
}

/**
 * Generates SKILLS.md registry file with skill metadata in a machine-readable format.
 */
class RegistryFileGenerator extends HarnessGenerator {

  def generate(skills: Seq[SkillReference], skillsDir: Map[String, File]): String = {
    val header = """# Skills Registry

This file lists all available skills for this project.

| Source | Category | Skill | Harnesses | Version |
|--------|----------|-------|-----------|---------|
"""

    val rows = skills
      .sortBy(s => (s.sourceId, s.category, s.id))
      .map { skill =>
        val harnessesStr = skill.effectiveHarnesses.sorted.mkString(", ")
        val versionShort = skill.version.take(7)
        s"| ${skill.sourceId} | ${skill.category} | ${skill.id} | $harnessesStr | $versionShort |"
      }
      .mkString("\n")

    header + rows
  }
}

/**
 * Factory for creating harness generators.
 */
object HarnessGeneratorFactory {
  def create(mode: String, log: Logger): Seq[HarnessGenerator] = {
    mode.toLowerCase match {
      case "instructions-file" => Seq(new InstructionsFileGenerator(log))
      case "registry-file"     => Seq(new RegistryFileGenerator())
      case "both" => Seq(new InstructionsFileGenerator(log), new RegistryFileGenerator())
      case other  =>
        throw new IllegalArgumentException(s"Unknown harness mode: $other")
    }
  }
}

/**
 * Harness output writer.
 */
object HarnessOutputWriter {

  def write(
      mode: String,
      skills: Seq[SkillReference],
      skillsDir: Map[String, File],
      outputDir: File,
      log: Logger
  ): Try[Unit] = {
    Try {
      val generators = HarnessGeneratorFactory.create(mode, log)

      for (generator <- generators) {
        val content    = generator.generate(skills, skillsDir)
        val outputFile = generator match {
          case _: InstructionsFileGenerator =>
            new File(outputDir, ".instructions.md")
          case _: RegistryFileGenerator =>
            new File(outputDir, "SKILLS.md")
          case _ =>
            throw new RuntimeException(s"Unknown generator type")
        }

        outputDir.mkdirs()
        val writer = Files.newBufferedWriter(outputFile.toPath, UTF_8, CREATE, TRUNCATE_EXISTING)
        try {
          writer.write(content)
          log.info(s"Wrote harness output to ${outputFile.getAbsolutePath}")
        } finally {
          writer.close()
        }
      }
    }
  }
}
