package com.github.aborg0.sbt.skills.config

import java.time.Instant
import java.io.File
import java.util.Locale

object Harnesses {
  def normalize(harness: String): String = harness.trim.toLowerCase(Locale.ROOT)

  def normalizeAll(harnesses: Seq[String]): Seq[String] = {
    harnesses.map(normalize).filter(_.nonEmpty).distinct
  }
}

/**
 * Represents a skill repository source.
 *
 * @param id
 *   Unique identifier for this source (e.g., "mattpocock", "internal")
 * @param url
 *   Git repository URL
 * @param ref
 *   Branch, tag, or commit hash to fetch
 * @param cacheDir
 *   Optional override for cache directory (defaults to .sbt-skills/sources/{id})
 */
case class SkillSource(
    id: String,
    url: String,
    ref: String,
    cacheDir: Option[File] = None
)

/**
 * Represents a single skill in the registry.
 *
 * @param id
 *   Unique skill identifier (e.g., "code-review")
 * @param sourceId
 *   ID of the source this skill comes from
 * @param category
 *   Category path (e.g., "engineering", "productivity/advanced")
 * @param path
 *   Relative path to SKILL.md in the repository
 * @param version
 *   Git commit hash when fetched
 * @param harnessesInRepo
 *   Harnesses declared in SKILL.md frontmatter
 * @param effectiveHarnesses
 *   Harnesses after applying source-level overrides
 * @param lastFetched
 *   Timestamp when skill was last synced
 * @param customized
 *   Whether this skill has been customized (for Phase 2)
 */
case class SkillReference(
    id: String,
    sourceId: String,
    category: String,
    path: String,
    version: String,
    harnessesInRepo: Seq[String],
    effectiveHarnesses: Seq[String],
    lastFetched: Instant,
    customized: Boolean = false
)

/**
 * Represents a source entry in the registry file.
 */
case class SourceRegistryEntry(
    id: String,
    url: String,
    ref: String,
    harnessOverride: Option[Seq[String]],
    lastSynced: Instant
)

/**
 * Complete registry structure for JSON serialization.
 */
case class SkillRegistry(
    sources: Seq[SourceRegistryEntry],
    skills: Seq[SkillReference]
)

/**
 * Configuration for metrics collection.
 *
 * @param backend
 *   "file", "git", or "none"
 * @param metricsFile
 *   Local file path for the metrics JSONL stream
 * @param metricsGitRepo
 *   Reserved for a future managed Git checkout
 * @param metricsGitBranch
 *   Branch in metrics repo
 */
case class MetricsConfig(
    backend: String = "file",
    metricsFile: File = new File(".sbt-skills/metrics.jsonl"),
    metricsGitRepo: Option[String] = None,
    metricsGitBranch: Option[String] = None
)

/**
 * Complete plugin configuration.
 */
case class SkillsConfig(
    skillsSources: Seq[SkillSource],
    skillsToAdd: Seq[String],
    skillsHarnesses: Seq[String],
    skillsSourceHarnessOverrides: Map[String, Seq[String]],
    skillsSourceExclude: Seq[String],
    skillsSourceCacheDir: File,
    skillsHarnessMode: String = "instructions-file",
    skillsOutputDir: File,
    skillsAutoGenerate: Boolean = true,
    skillsMetricsBackend: String = "file",
    skillsMetricsFile: File = new File(".sbt-skills/metrics.jsonl"),
    skillsMetricsGitBranch: String = "metrics",
    skillsMetricsGitRemote: String = "origin",
    skillsMetricsGitPush: Boolean = false,
    skillsMetricsGitRepository: Option[String] = None,
    skillsMetricsGitDirectory: File = new File(".sbt-skills/metrics-repository"),
    skillsMetricsGitPath: String = ".sbt-skills/metrics.jsonl",
    skillsAutoInitRegistry: Boolean = true,
    skillsPatchesDir: File = new File(".sbt-skills/patches"),
    skillsVersionOverrides: Map[String, String] = Map()
)
