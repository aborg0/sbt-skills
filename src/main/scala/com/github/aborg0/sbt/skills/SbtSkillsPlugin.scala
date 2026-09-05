package com.github.aborg0.sbt.skills

import com.github.aborg0.sbt.skills.config._
import com.github.aborg0.sbt.skills.metrics.MetricsCollectorFactory
import com.github.aborg0.sbt.skills.patches.SkillPatchManager
import com.github.aborg0.sbt.skills.registry.{SkillRegistry => SkillReg, _}
import com.github.aborg0.sbt.skills.repo.SkillRepoFetcher
import sbt._
import sbt.Keys._
import sbt.complete.DefaultParsers.spaceDelimited
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
      "Metrics backend: file, git, or none"
    )

    val skillsMetricsFile = settingKey[File](
      "Local metrics file for file-based backend"
    )

    val skillsMetricsGitBranch = settingKey[String](
      "Git branch for metrics commits (for example, user/<name> or metrics)"
    )

    val skillsMetricsGitRemote = settingKey[String](
      "Git remote used when pushing metrics commits"
    )

    val skillsMetricsGitPush = settingKey[Boolean](
      "Whether the Git metrics backend should push commits"
    )

    val skillsMetricsGitRepository = settingKey[Option[String]](
      "Optional Git repository URL or local path managed by the metrics backend"
    )

    val skillsMetricsGitDirectory = settingKey[File](
      "Local checkout directory for the managed metrics repository"
    )

    val skillsMetricsGitPath = settingKey[String](
      "Metrics JSONL path relative to the managed Git repository"
    )

    val skillsAutoInitRegistry = settingKey[Boolean](
      "Whether to initialize registry on first sync"
    )

    val skillsPatchesDir = settingKey[File](
      "Directory containing per-skill .patch override files"
    )

    val skillsVersionOverrides = settingKey[Map[String, String]](
      "Per-skill version overrides keyed by sourceId:category/skill"
    )

    // Task keys

    val skillsSync = taskKey[Unit](
      "Sync all configured skill repositories"
    )

    val skillsUpdate = taskKey[Unit](
      "Show registered skills whose configured source version has changed"
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

    val skillsFeedback = inputKey[Unit](
      "Record feedback (usage: skillsFeedback sourceId:category/skill rating comment...)"
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
    skillsMetricsGitBranch       := "metrics",
    skillsMetricsGitRemote       := "origin",
    skillsMetricsGitPush         := false,
    skillsMetricsGitRepository   := None,
    skillsMetricsGitDirectory    := baseDirectory.value / ".sbt-skills" / "metrics-repository",
    skillsMetricsGitPath         := ".sbt-skills/metrics.jsonl",
    skillsAutoInitRegistry       := true,
    skillsPatchesDir              := baseDirectory.value / ".sbt-skills" / "patches",
    skillsVersionOverrides       := Map(),

    // Tasks
    skillsSync        := skillsSyncTask.value,
    skillsUpdate      := skillsUpdateTask.value,
    skillsAdd         := skillsAddTask.evaluated,
    skillsRemove      := skillsRemoveTask.evaluated,
    skillsInfo        := skillsInfoTask.evaluated,
    skillsFeedback    := skillsFeedbackTask.evaluated,
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
      config.skillsMetricsGitBranch,
      config.skillsMetricsGitRemote,
      config.skillsMetricsGitPush,
      config.skillsMetricsGitRepository,
      config.skillsMetricsGitDirectory,
      config.skillsMetricsGitPath,
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
              val selectedSkills = discoveredSkills.filter { discoveredSkill =>
                config.skillsToAdd.isEmpty || config.skillsToAdd.contains(
                  s"${source.id}:${discoveredSkill.category}/${discoveredSkill.name}"
                ) || config.skillsToAdd.contains(s"${source.id}:skills/${discoveredSkill.category}/${discoveredSkill.name}")
              }
              val skillRefs = selectedSkills.flatMap { discoveredSkill =>
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
                    val skillKey = s"${source.id}:${skillPath}"
                    val effectiveVersion = config.skillsVersionOverrides
                      .get(skillKey)
                      .orElse(config.skillsVersionOverrides.get(s"${source.id}:${discoveredSkill.category}/${discoveredSkill.name}"))
                      .getOrElse(version)

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
                      version = effectiveVersion,
                      harnessesInRepo = metadata.harnesses,
                      effectiveHarnesses = effectiveHarnesses,
                      lastFetched = Instant.now(),
                      customized = SkillPatchManager
                        .patchFile(config.skillsPatchesDir, SkillReference(
                          discoveredSkill.name,
                          source.id,
                          discoveredSkill.category,
                          skillPath,
                          version,
                          metadata.harnesses,
                          effectiveHarnesses,
                          Instant.now()
                        ))
                        .isFile
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

    val oldSkills = currentData.skills.map(skill => s"${skill.sourceId}:${skill.path}" -> skill).toMap
    val newSkills = updatedData.skills.map(skill => s"${skill.sourceId}:${skill.path}" -> skill).toMap
    newSkills.values.filter(skill => !oldSkills.contains(s"${skill.sourceId}:${skill.path}")).foreach { skill =>
      metricsCollector.recordSkillAdded(skill.id, skill.sourceId, skill.category, skill.effectiveHarnesses).failed.foreach { error =>
        log.warn(s"[WARN] Failed to record added-skill metric: ${error.getMessage}")
      }
    }
    oldSkills.values.filter { skill =>
      !newSkills.contains(s"${skill.sourceId}:${skill.path}") &&
        config.skillsSources.exists(_.id == skill.sourceId) &&
        !config.skillsSourceExclude.contains(skill.sourceId)
    }.foreach { skill =>
      metricsCollector.recordSkillRemoved(skill.id, skill.sourceId).failed.foreach { error =>
        log.warn(s"[WARN] Failed to record removed-skill metric: ${error.getMessage}")
      }
    }

    // Generate harness output if auto-generate is enabled
    if (config.skillsAutoGenerate) {
      val skillsToGenerate = updatedData.skills.filter(_.effectiveHarnesses.nonEmpty)
      if (skillsToGenerate.nonEmpty) {
        log.info(s"\n[GENERATE] Generating harness output (mode: ${config.skillsHarnessMode})...")
        // Build map of skill files for content reading
        val materialized = skillsToGenerate.flatMap { skill =>
          val sourceFile = new File(config.skillsSourceCacheDir, s"${skill.sourceId}/${skill.path}")
          if (sourceFile.exists()) Some((skill, sourceFile))
          else {
            log.warn(s"[WARN] Skill file not found: ${sourceFile.getAbsolutePath}")
            None
          }
        }
        SkillPatchManager.materialize(
          materialized.map(_._1),
          config.skillsSourceCacheDir,
          config.skillsPatchesDir,
          new File(config.skillsSourceCacheDir.getParentFile, "rendered"),
          log
        ).flatMap { case (skillsFileMap, customized) =>
          HarnessOutputWriter.write(
            config.skillsHarnessMode,
            skillsToGenerate.map { skill =>
              if (customized.contains(s"${skill.sourceId}:${skill.path}")) skill.copy(customized = true) else skill
            },
            skillsFileMap,
            config.skillsOutputDir,
            log
          )
        } match {
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

  private lazy val skillsUpdateTask = Def.task {
    val log = streams.value.log
    val config = buildConfig(state.value)
    val registry = new SkillReg(
      new File(config.skillsSourceCacheDir.getParentFile, "registry.json"),
      log
    )
    registry.load() match {
      case Success(data) =>
        val configuredRefs = config.skillsSources.map(source => source.id -> source.ref).toMap
        val registeredRefs = data.sources.map(source => source.id -> source.ref).toMap
        val updates = data.skills.filter { skill =>
          configuredRefs.get(skill.sourceId).exists(ref => registeredRefs.get(skill.sourceId).forall(_ != ref))
        }
        if (updates.isEmpty) log.info("[UPDATE] No registered skills require an update")
        else {
          log.info(s"[UPDATE] ${updates.length} skill(s) may have updates:")
          updates.sortBy(skill => (skill.sourceId, skill.category, skill.id)).foreach { skill =>
            log.info(s"[UPDATE] ${skill.sourceId}:${skill.category}/${skill.id} (${skill.version})")
          }
        }
      case Failure(error) =>
        throw new RuntimeException(s"[ERROR] Failed to load registry: ${error.getMessage}", error)
    }
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

  private lazy val skillsAddTask = Def.inputTask {
    val args = spaceDelimited("skill key").parsed
    val skillKey = args.headOption.getOrElse(throw new MessageOnlyException("Usage: skillsAdd sourceId:category/skill"))
    val log = streams.value.log
    val config = buildConfig(state.value)
    val registry = new SkillReg(new File(config.skillsSourceCacheDir.getParentFile, "registry.json"), log)
    registry.load().flatMap { data =>
      val exists = data.skills.exists(skill => s"${skill.sourceId}:${skill.category}/${skill.id}" == skillKey)
      if (!exists) Failure(new MessageOnlyException(s"Skill '$skillKey' is not registered; run skillsSync first"))
      else registry.save(data)
    }.fold(error => throw error, _ => log.info(s"[ADD] Skill already registered: $skillKey"))
  }

  private lazy val skillsRemoveTask = Def.inputTask {
    val args = spaceDelimited("skill key").parsed
    val skillKey = args.headOption.getOrElse(throw new MessageOnlyException("Usage: skillsRemove sourceId:category/skill"))
    val parts = skillKey.split(":", 2)
    if (parts.length != 2) throw new MessageOnlyException("Skill must use sourceId:category/skill format")
    val log = streams.value.log
    val config = buildConfig(state.value)
    val registry = new SkillReg(new File(config.skillsSourceCacheDir.getParentFile, "registry.json"), log)
    registry.load().flatMap(data => registry.save(registry.removeSkill(data, parts(0), parts(1))))
      .fold(error => throw error, _ => log.info(s"[REMOVE] Removed skill: $skillKey"))
  }

  private lazy val skillsInfoTask = Def.inputTask {
    val args = spaceDelimited("skill key").parsed
    val skillKey = args.headOption.getOrElse(throw new MessageOnlyException("Usage: skillsInfo sourceId:category/skill"))
    val log = streams.value.log
    val config = buildConfig(state.value)
    val registry = new SkillReg(new File(config.skillsSourceCacheDir.getParentFile, "registry.json"), log)
    registry.load().fold(
      error => throw error,
      data => data.skills.find(skill => s"${skill.sourceId}:${skill.category}/${skill.id}" == skillKey) match {
        case Some(skill) =>
          log.info(s"[INFO] ${skill.sourceId}:${skill.category}/${skill.id}")
          log.info(s"[INFO] Path: ${skill.path}")
          log.info(s"[INFO] Version: ${skill.version}")
          log.info(s"[INFO] Harnesses: ${skill.effectiveHarnesses.mkString(", ")}")
          log.info(s"[INFO] Customized: ${skill.customized}")
        case None => throw new MessageOnlyException(s"Skill '$skillKey' is not registered")
      }
    )
  }

  private lazy val skillsFeedbackTask = Def.inputTask {
    val args = spaceDelimited("feedback argument").parsed
    if (args.length < 3) {
      throw new MessageOnlyException("Usage: skillsFeedback sourceId:category/skill rating comment...")
    }
    val parts = args.head.split(":", 2)
    val rating = scala.util.Try(args(1).toInt).toOption.filter(value => value >= 1 && value <= 5)
      .getOrElse(throw new MessageOnlyException("Feedback rating must be an integer from 1 to 5"))
    if (parts.length != 2) throw new MessageOnlyException("Skill must use sourceId:category/skill format")
    val log = streams.value.log
    val config = buildConfig(state.value)
    val collector = MetricsCollectorFactory.create(
      config.skillsMetricsBackend,
      config.skillsMetricsFile,
      config.skillsMetricsGitBranch,
      config.skillsMetricsGitRemote,
      config.skillsMetricsGitPush,
      config.skillsMetricsGitRepository,
      config.skillsMetricsGitDirectory,
      config.skillsMetricsGitPath,
      log
    )
    collector.recordFeedback(parts(1), parts(0), rating, args.drop(2).mkString(" ")).fold(
      error => throw new MessageOnlyException(s"Failed to record feedback: ${error.getMessage}"),
      _ => log.info(s"[FEEDBACK] Recorded feedback for ${args.head}")
    )
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
      skillsMetricsGitBranch = extracted.get(skillsMetricsGitBranch),
      skillsMetricsGitRemote = extracted.get(skillsMetricsGitRemote),
      skillsMetricsGitPush = extracted.get(skillsMetricsGitPush),
      skillsMetricsGitRepository = extracted.get(skillsMetricsGitRepository),
      skillsMetricsGitDirectory = extracted.get(skillsMetricsGitDirectory),
      skillsMetricsGitPath = extracted.get(skillsMetricsGitPath),
      skillsAutoInitRegistry = extracted.get(skillsAutoInitRegistry),
      skillsPatchesDir = extracted.get(skillsPatchesDir),
      skillsVersionOverrides = extracted.get(skillsVersionOverrides)
    )
  }

  override lazy val buildSettings = Seq()

  override lazy val globalSettings = Seq()
}
