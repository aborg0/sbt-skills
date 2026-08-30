package com.github.aborg0.sbt.skills

import com.github.aborg0.sbt.skills.config._
import com.github.aborg0.sbt.skills.metrics.MetricsCollectorFactory
import com.github.aborg0.sbt.skills.registry.{SkillRegistry => SkillReg, _}
import com.github.aborg0.sbt.skills.repo.SkillRepoFetcher
import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin

import java.io.File
import java.time.Instant
import scala.collection.mutable.{Map => MutableMap}
import scala.util.{Failure, Success}

object SbtSkillsPlugin extends AutoPlugin {

  private[skills] def resolveEffectiveHarnesses(
      repositoryHarnesses: Seq[String],
      globalHarnesses: Seq[String],
      sourceOverride: Option[Seq[String]]
  ): Seq[String] = {
    val supportedHarnesses = Harnesses.normalizeAll(repositoryHarnesses).toSet
    Harnesses.normalizeAll(sourceOverride.getOrElse(globalHarnesses))
      .filter(supportedHarnesses.contains)
  }

  override def trigger  = allRequirements
  override def requires = JvmPlugin

  object autoImport {
    // Configuration keys

    val skillsSources = settingKey[Seq[SkillSource]](
      "List of skill repositories to fetch from"
    )

    val skillsToAdd = settingKey[Seq[String]](
      "Which skills to include (format: sourceId:category/skill-name)"
    )

    val skillsHarnesses = settingKey[Seq[String]](
      "Global default harnesses for all sources (e.g., copilot, claude)"
    )

    val skillsSourceHarnessOverrides = settingKey[Map[String, Seq[String]]](
      "Per-source harness restrictions (e.g., internal -> claude only)"
    )

    val skillsSourceExclude = settingKey[Seq[String]](
      "Source IDs to exclude from syncing"
    )

    val skillsSourceCacheDir = settingKey[File](
      "Base cache directory for cloned repositories"
    )

    val skillsHarnessMode = settingKey[String](
      "Output format: instructions-file, registry-file, or both"
    )

    val skillsOutputDir = settingKey[File](
      "Directory where harness files are written"
    )

    val skillsAutoGenerate = settingKey[Boolean](
      "Whether to auto-generate harness files on skillsSync"
    )

    val skillsMetricsBackend = settingKey[String](
      "Metrics backend: file or git (Phase 3)"
    )

    val skillsMetricsFile = settingKey[File](
      "Local metrics file for file-based backend"
    )

    val skillsAutoInitRegistry = settingKey[Boolean](
      "Whether to initialize registry on first sync"
    )

    // Task keys

    val skillsSync = taskKey[Unit](
      "Sync all configured skill repositories"
    )

    val skillsAdd = inputKey[Unit](
      "Add a skill to the registry (usage: skillsAdd skillId)"
    )

    val skillsList = taskKey[Unit](
      "List all available skills"
    )

    val skillsRemove = inputKey[Unit](
      "Remove a skill from the registry (usage: skillsRemove skillId)"
    )

    val skillsInfo = inputKey[Unit](
      "Show detailed information about a skill (usage: skillsInfo sourceId:category/skill)"
    )

    val skillsListSources = taskKey[Unit](
      "List all configured skill sources and their sync status"
    )
  }

  import autoImport._

  override lazy val projectSettings = Seq(
    // Default configuration
    skillsSources                := Seq(),
    skillsToAdd                  := Seq(),
    skillsHarnesses              := Seq("copilot"),
    skillsSourceHarnessOverrides := Map(),
    skillsSourceExclude          := Seq(),
    skillsSourceCacheDir         := baseDirectory.value / ".sbt-skills" / "sources",
    skillsHarnessMode            := "instructions-file",
    skillsOutputDir              := baseDirectory.value,
    skillsAutoGenerate           := true,
    skillsMetricsBackend         := "file",
    skillsMetricsFile            := baseDirectory.value / ".sbt-skills" / "metrics.jsonl",
    skillsAutoInitRegistry       := true,

    // Tasks
    skillsSync        := skillsSyncTask.value,
    skillsList        := skillsListTask.value,
    skillsListSources := skillsListSourcesTask.value
  )

  // Task implementations
  private lazy val skillsSyncTask = Def.task {
    val log    = streams.value.log
    val config = buildConfig(state.value)

    log.info("═" * 60)
    log.info("[SYNC] Starting skills synchronization...")
    log.info("═" * 60)

    if (config.skillsSources.isEmpty) {
      log.error("[ERROR] No skill sources configured. Add skillsSources to build.sbt")
      throw new RuntimeException("skillsSources not configured")
    }

    log.info(s"[INFO] Configured sources: ${config.skillsSources.map(_.id).mkString(", ")}")
    if (config.skillsSourceExclude.nonEmpty) {
      log.info(s"[INFO] Excluded sources: ${config.skillsSourceExclude.mkString(", ")}")
    }

    val fetcher          = new SkillRepoFetcher(config.skillsSourceCacheDir, log)
    val registryFile     = new File(config.skillsSourceCacheDir.getParentFile, "registry.json")
    val registry         = new SkillReg(registryFile, log)
    val metricsCollector = MetricsCollectorFactory.create(
      config.skillsMetricsBackend,
      config.skillsMetricsFile,
      log
    )

    // Load current registry
    val currentData = registry.load() match {
      case Success(data) =>
        log.info(s"[INFO] Loaded existing registry with ${data.skills.length} skill(s)")
        data
      case Failure(e) =>
        log.warn(s"[WARN] Failed to load existing registry: ${e.getMessage}")
        SkillReg.Data(Seq(), Seq())
    }

    var updatedData = currentData
    var syncedCount = 0
    var errorCount  = 0

    // Sync each source (excluding those in skillsSourceExclude)
    for (source <- config.skillsSources if !config.skillsSourceExclude.contains(source.id)) {
      log.info(s"\n[SOURCE] Syncing '${source.id}'...")

      fetcher.fetchRepository(source) match {
        case Success(repoDir) =>
          log.info(s"[FETCH] ✓ Repository ready at ${repoDir.getAbsolutePath}")
          syncedCount += 1

          // Discover skills in repository
          fetcher.discoverSkills(repoDir) match {
            case Success(discoveredSkills) =>
              log.info(s"[DISCOVER] Found ${discoveredSkills.length} skill(s)")

              // Get commit hash for versioning
              val versionResult = fetcher.getCurrentCommitHash(repoDir)
              val version       = versionResult match {
                case Success(hash) =>
                  log.debug(s"[VERSION] Commit: $hash")
                  hash
                case Failure(e) =>
                  log.warn(s"[WARN] Failed to get commit hash: ${e.getMessage}. Using 'unknown'")
                  "unknown"
              }

              // Parse skill metadata and create references
              val skillRefs = discoveredSkills.flatMap { discoveredSkill =>
                fetcher.parseSkillMetadata(discoveredSkill.file, version) match {
                  case Success(metadata) =>
                    // Determine effective harnesses
                    val harnessOverride    = config.skillsSourceHarnessOverrides.get(source.id)
                    val effectiveHarnesses = resolveEffectiveHarnesses(
                      metadata.harnesses,
                      config.skillsHarnesses,
                      harnessOverride
                    )
                    val skillPath =
                      Seq("skills", discoveredSkill.category, discoveredSkill.name, "SKILL.md")
                        .filter(_.nonEmpty)
                        .mkString("/")

                    if (effectiveHarnesses.isEmpty) {
                      log.warn(
                        s"[WARN] No compatible harnesses for $skillPath. " +
                          s"Repository supports: ${metadata.harnesses.mkString(", ")}"
                      )
                    }

                    log.debug(
                      s"  ✓ $skillPath (harnesses: ${effectiveHarnesses.mkString(", ")})"
                    )

                    Some(SkillReference(
                      id = discoveredSkill.name,
                      sourceId = source.id,
                      category = discoveredSkill.category,
                      path = skillPath,
                      version = version,
                      harnessesInRepo = metadata.harnesses,
                      effectiveHarnesses = effectiveHarnesses,
                      lastFetched = Instant.now(),
                      customized = false
                    ))
                  case Failure(e) =>
                    log.warn(
                      s"  ✗ Failed to parse ${discoveredSkill.file.getPath}: ${e.getMessage}"
                    )
                    errorCount += 1
                    None
                }
              }

              // Update registry with source and skills
              val harnessOverride = config.skillsSourceHarnessOverrides.get(source.id)
              updatedData = registry.addOrUpdateSource(updatedData, source, harnessOverride)
              updatedData = registry.addOrUpdateSkills(updatedData, skillRefs)

              // Record metrics
              metricsCollector.recordSkillSync(
                source.id,
                discoveredSkills.length,
                source.ref
              ) match {
                case Success(_) => ()
                case Failure(e) => log.warn(s"[WARN] Failed to record metrics: ${e.getMessage}")
              }

            case Failure(e) =>
              log.error(s"[ERROR] Failed to discover skills in '${source.id}': ${e.getMessage}")
              errorCount += 1
          }

        case Failure(e) =>
          log.error(s"[ERROR] Failed to sync source '${source.id}': ${e.getMessage}")
          errorCount += 1
      }
    }

    // Save updated registry
    registry.save(updatedData) match {
      case Success(_) =>
        log.info(s"\n[REGISTRY] Saved registry to ${registryFile.getAbsolutePath}")
        log.info(s"[REGISTRY] Total skills: ${updatedData.skills.length}")
      case Failure(e) =>
        log.error(s"[ERROR] Failed to save registry: ${e.getMessage}")
        throw e
    }

    // Generate harness output if auto-generate is enabled
    if (config.skillsAutoGenerate) {
      val skillsToGenerate = updatedData.skills.filter(_.effectiveHarnesses.nonEmpty)
      if (skillsToGenerate.nonEmpty) {
        log.info(s"\n[GENERATE] Generating harness output (mode: ${config.skillsHarnessMode})...")
        // Build map of skill files for content reading
        val skillsFileMap = MutableMap[String, File]()
        for (skill <- skillsToGenerate) {
          val skillDir  = new File(config.skillsSourceCacheDir, skill.sourceId)
          val skillFile = new File(skillDir, skill.path)
          if (skillFile.exists()) {
            skillsFileMap(s"${skill.sourceId}:${skill.path}") = skillFile
          } else {
            log.warn(s"[WARN] Skill file not found: ${skillFile.getAbsolutePath}")
          }
        }

        HarnessOutputWriter.write(
          config.skillsHarnessMode,
          skillsToGenerate,
          skillsFileMap.toMap,
          config.skillsOutputDir,
          log
        ) match {
          case Success(_) =>
            log.info(s"[GENERATE] ✓ Harness files generated successfully")
          case Failure(e) =>
            log.warn(s"[WARN] Failed to generate harness files: ${e.getMessage}")
        }
      } else {
        log.warn("[WARN] No skills with compatible harnesses to generate output for")
      }
    }

    metricsCollector.flush()

    log.info("\n" + "═" * 60)
    if (errorCount > 0) {
      log.warn(
        s"[SUMMARY] Sync completed with ${syncedCount} source(s) synced, ${errorCount} error(s)"
      )
    } else {
      log.info(s"[SUMMARY] ✓ Sync completed successfully! ${syncedCount} source(s) synced")
    }
    log.info("═" * 60)
  }

  private lazy val skillsListTask = Def.task {
    val log    = streams.value.log
    val config = buildConfig(state.value)

    val registryFile = new File(config.skillsSourceCacheDir.getParentFile, "registry.json")
    val registry     = new SkillReg(registryFile, log)

    log.info("═" * 60)
    log.info("[LIST] Skills Registry")
    log.info("═" * 60)

    registry.load() match {
      case Success(data) =>
        if (data.skills.isEmpty) {
          log.info("[INFO] No skills registered. Run 'skillsSync' first.")
        } else {
          log.info(s"[INFO] Total skills: ${data.skills.length}\n")
          val grouped = data.skills.groupBy(_.sourceId)
          for ((sourceId, skills) <- grouped.toList.sortBy(_._1)) {
            val override_note = config.skillsSourceHarnessOverrides.get(sourceId) match {
              case Some(harnesses) => s" [Harnesses: ${harnesses.mkString(", ")}]"
              case None            => ""
            }
            log.info(s"[SOURCE] $sourceId$override_note")
            for (skill <- skills.sortBy(s => (s.category, s.id))) {
              val harnesses = skill.effectiveHarnesses.sorted.mkString(", ")
              val custom    = if (skill.customized) " (customized)" else ""
              log.info(s"  ├─ ${skill.category}/${skill.id}")
              log.info(s"  │  Harnesses: $harnesses")
              log.info(s"  │  Version: ${skill.version}$custom")
            }
            log.info("")
          }
        }

      case Failure(e) =>
        log.error(s"[ERROR] Failed to load registry: ${e.getMessage}")
    }
    log.info("═" * 60)
  }

  private lazy val skillsListSourcesTask = Def.task {
    val log    = streams.value.log
    val config = buildConfig(state.value)

    log.info("═" * 60)
    log.info("[SOURCES] Configured Skill Repositories")
    log.info("═" * 60)

    if (config.skillsSources.isEmpty) {
      log.info("[INFO] No sources configured. Add skillsSources to build.sbt")
    } else {
      log.info(s"[INFO] Total sources: ${config.skillsSources.length}\n")
      for (source <- config.skillsSources) {
        val excluded = if (config.skillsSourceExclude.contains(source.id)) " [EXCLUDED]" else ""
        val override_note = config.skillsSourceHarnessOverrides.get(source.id) match {
          case Some(harnesses) => s" [Harnesses: ${harnesses.mkString(", ")}]"
          case None            => ""
        }
        val cacheDir = source.cacheDir.getOrElse(new File(config.skillsSourceCacheDir, source.id))
        log.info(s"[ID] ${source.id}$excluded$override_note")
        log.info(s"  URL: ${source.url}")
        log.info(s"  Ref: ${source.ref}")
        log.info(s"  Cache: ${cacheDir.getAbsolutePath}")
        log.info("")
      }
    }
    log.info("═" * 60)
  }

  private def buildConfig(state: State): SkillsConfig = {
    val extracted = Project.extract(state)
    SkillsConfig(
      skillsSources = extracted.get(skillsSources),
      skillsToAdd = extracted.get(skillsToAdd),
      skillsHarnesses = extracted.get(skillsHarnesses),
      skillsSourceHarnessOverrides = extracted.get(skillsSourceHarnessOverrides),
      skillsSourceExclude = extracted.get(skillsSourceExclude),
      skillsSourceCacheDir = extracted.get(skillsSourceCacheDir),
      skillsHarnessMode = extracted.get(skillsHarnessMode),
      skillsOutputDir = extracted.get(skillsOutputDir),
      skillsAutoGenerate = extracted.get(skillsAutoGenerate),
      skillsMetricsBackend = extracted.get(skillsMetricsBackend),
      skillsMetricsFile = extracted.get(skillsMetricsFile),
      skillsAutoInitRegistry = extracted.get(skillsAutoInitRegistry)
    )
  }

  override lazy val buildSettings = Seq()

  override lazy val globalSettings = Seq()
}
