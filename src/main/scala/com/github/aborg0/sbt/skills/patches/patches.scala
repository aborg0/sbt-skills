package com.github.aborg0.sbt.skills.patches

import com.github.aborg0.sbt.skills.config.SkillReference
import sbt.util.Logger

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, StandardOpenOption}
import scala.util.{Failure, Success, Try}

object SkillPatchManager {
  def materialize(
      skills: Seq[SkillReference],
      sourceRoot: File,
      patchesDir: File,
      renderDir: File,
      log: Logger
  ): Try[(Map[String, File], Set[String])] = Try {
    val customized = scala.collection.mutable.Set.empty[String]
    val files = scala.collection.mutable.Map.empty[String, File]
    skills.foreach { skill =>
      val sourceFile = new File(sourceRoot, s"${skill.sourceId}/${skill.path}")
      val patchFile = new File(patchesDir, s"${skill.sourceId}/${skill.category}/${skill.id}.patch")
      val destination = new File(renderDir, s"${skill.sourceId}/${skill.path}")
      destination.getParentFile.mkdirs()
      val content =
        if (patchFile.isFile) {
          val skillKey = s"${skill.sourceId}:${skill.path}"
          customized += skillKey
          log.info(s"[PATCH] Applying override for $skillKey")
          val original = new String(Files.readAllBytes(sourceFile.toPath), UTF_8)
          applyPatch(original, new String(Files.readAllBytes(patchFile.toPath), UTF_8), skillKey)
        } else {
          new String(Files.readAllBytes(sourceFile.toPath), UTF_8)
        }
      Files.write(
        destination.toPath,
        content.getBytes(UTF_8),
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING
      )
      files += s"${skill.sourceId}:${skill.path}" -> destination
    }
    (files.toMap, customized.toSet)
  }

  def patchFile(skillsPatchesDir: File, skill: SkillReference): File =
    new File(skillsPatchesDir, s"${skill.sourceId}/${skill.category}/${skill.id}.patch")

  private def applyPatch(original: String, patch: String, skillKey: String): String = {
    val originalLines = original.split("\\n", -1).toVector
    val patchLines = patch.split("\\n", -1).toVector
    val hunkStart = patchLines.indexWhere(_.startsWith("@@"))
    if (hunkStart < 0) {
      throw new IllegalArgumentException(
        s"Patch for '$skillKey' has no unified-diff hunk. Expected a patch containing '@@'."
      )
    }

    val result = scala.collection.mutable.ArrayBuffer.empty[String]
    var sourceIndex = 0
    var index = 0
    while (index < hunkStart) index += 1
    while (index < patchLines.length) {
      val header = patchLines(index)
      if (!header.startsWith("@@")) {
        throw new IllegalArgumentException(s"Unexpected patch content for '$skillKey': $header")
      }
      val matchGroups = "@@ -([0-9]+)(?:,[0-9]+)? \\+([0-9]+)(?:,[0-9]+)? @@.*".r
        .findFirstMatchIn(header)
        .getOrElse(throw new IllegalArgumentException(s"Invalid hunk header for '$skillKey': $header"))
      val hunkStartLine = matchGroups.group(1).toInt - 1
      while (sourceIndex < hunkStartLine) {
        result += originalLines(sourceIndex)
        sourceIndex += 1
      }
      index += 1
      while (index < patchLines.length && !patchLines(index).startsWith("@@")) {
        val line = patchLines(index)
        if (line.isEmpty && index == patchLines.length - 1) {
          index += 1
        } else if (line == "\\ No newline at end of file") {
          index += 1
        } else if (line.startsWith(" ")) {
          requireLine(originalLines, sourceIndex, line.drop(1), skillKey)
          result += originalLines(sourceIndex)
          sourceIndex += 1
          index += 1
        } else if (line.startsWith("-")) {
          requireLine(originalLines, sourceIndex, line.drop(1), skillKey)
          sourceIndex += 1
          index += 1
        } else if (line.startsWith("+")) {
          result += line.drop(1)
          index += 1
        } else {
          throw new IllegalArgumentException(s"Invalid patch line for '$skillKey': $line")
        }
      }
    }
    while (sourceIndex < originalLines.length) {
      result += originalLines(sourceIndex)
      sourceIndex += 1
    }
    result.mkString("\n")
  }

  private def requireLine(lines: Vector[String], index: Int, expected: String, skillKey: String): Unit = {
    if (index >= lines.length || lines(index) != expected) {
      val actual = if (index < lines.length) lines(index) else "<end of file>"
      throw new IllegalArgumentException(
        s"Patch context mismatch for '$skillKey': expected '$expected', found '$actual'"
      )
    }
  }
}