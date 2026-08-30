package com.github.aborg0.sbt.skills.metrics

import io.circe.syntax._
import io.circe.{Json, JsonObject}
import sbt.util.Logger
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.StandardOpenOption.{APPEND, CREATE}
import java.nio.file.Files
import java.time.Instant
import scala.util.Try

/**
 * Trait for metrics collection. Extensible for different backends.
 */
trait MetricsCollector {
  def recordSkillSync(sourceId: String, skillCount: Int, repoRef: String): Try[Unit]
  def recordSkillAdded(skillId: String, sourceId: String, category: String, effectiveHarnesses: Seq[String]): Try[Unit]
  def recordSkillRemoved(skillId: String, sourceId: String): Try[Unit]
  def flush(): Try[Unit]
}

/**
 * File-based metrics collector. Writes append-only JSON lines to a file.
 * Format: one JSON object per line, each with: event, timestamp, and event-specific fields
 */
class FileMetricsCollector(metricsFile: File, log: Logger) extends MetricsCollector {

  override def recordSkillSync(sourceId: String, skillCount: Int, repoRef: String): Try[Unit] = {
    val event = JsonObject(
      "event" -> "sync".asJson,
      "timestamp" -> Instant.now().toString.asJson,
      "sourceId" -> sourceId.asJson,
      "skillCount" -> skillCount.asJson,
      "repoRef" -> repoRef.asJson
    )
    writeEvent(event.asJson)
  }

  override def recordSkillAdded(
    skillId: String,
    sourceId: String,
    category: String,
    effectiveHarnesses: Seq[String]
  ): Try[Unit] = {
    val event = JsonObject(
      "event" -> "skill-added".asJson,
      "timestamp" -> Instant.now().toString.asJson,
      "skillId" -> skillId.asJson,
      "sourceId" -> sourceId.asJson,
      "category" -> category.asJson,
      "effectiveHarnesses" -> effectiveHarnesses.asJson
    )
    writeEvent(event.asJson)
  }

  override def recordSkillRemoved(skillId: String, sourceId: String): Try[Unit] = {
    val event = JsonObject(
      "event" -> "skill-removed".asJson,
      "timestamp" -> Instant.now().toString.asJson,
      "skillId" -> skillId.asJson,
      "sourceId" -> sourceId.asJson
    )
    writeEvent(event.asJson)
  }

  override def flush(): Try[Unit] = Try(())  // File backend doesn't need explicit flushing

  private def writeEvent(event: Json): Try[Unit] = {
    Try {
      metricsFile.getParentFile.mkdirs()
      val writer = Files.newBufferedWriter(metricsFile.toPath, UTF_8, CREATE, APPEND)
      try {
        writer.write(event.noSpaces)
        writer.write("\n")
        log.debug(s"Recorded metrics event: ${event.noSpaces}")
      } finally {
        writer.close()
      }
    }
  }
}

/**
 * No-op metrics collector for testing or when metrics are disabled.
 */
class NoOpMetricsCollector extends MetricsCollector {
  override def recordSkillSync(sourceId: String, skillCount: Int, repoRef: String): Try[Unit] = Try(())
  override def recordSkillAdded(skillId: String, sourceId: String, category: String, effectiveHarnesses: Seq[String]): Try[Unit] = Try(())
  override def recordSkillRemoved(skillId: String, sourceId: String): Try[Unit] = Try(())
  override def flush(): Try[Unit] = Try(())
}

/**
 * Factory for creating metrics collectors.
 */
object MetricsCollectorFactory {
  def create(backend: String, metricsFile: File, log: Logger): MetricsCollector = {
    backend.toLowerCase match {
      case "file" =>
        log.info(s"Using file-based metrics backend: ${metricsFile.getAbsolutePath}")
        new FileMetricsCollector(metricsFile, log)
      case "git" =>
        log.warn("Git-based metrics backend not yet implemented (Phase 3). Using file backend.")
        new FileMetricsCollector(metricsFile, log)
      case "none" | "noop" =>
        log.info("Metrics collection disabled")
        new NoOpMetricsCollector()
      case other =>
        log.warn(s"Unknown metrics backend: $other. Using file backend.")
        new FileMetricsCollector(metricsFile, log)
    }
  }
}
