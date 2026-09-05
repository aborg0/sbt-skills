package com.github.aborg0.sbt.skills.metrics

import io.circe.syntax._
import io.circe.{Json, JsonObject}
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.api.RebaseCommand
import org.eclipse.jgit.api.RebaseResult
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
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
  def recordSkillAdded(
      skillId: String,
      sourceId: String,
      category: String,
      effectiveHarnesses: Seq[String]
  ): Try[Unit]
  def recordSkillRemoved(skillId: String, sourceId: String): Try[Unit]
  def recordFeedback(
      skillId: String,
      sourceId: String,
      rating: Int,
      comment: String
  ): Try[Unit]
  def flush(): Try[Unit]
}

/**
 * File-based metrics collector. Writes append-only JSON lines to a file. Format: one JSON object
 * per line, each with: event, timestamp, and event-specific fields
 */
class FileMetricsCollector(metricsFile: File, log: Logger) extends MetricsCollector {

  override def recordSkillSync(sourceId: String, skillCount: Int, repoRef: String): Try[Unit] = {
    val event = JsonObject(
      "event"      -> "sync".asJson,
      "timestamp"  -> Instant.now().toString.asJson,
      "sourceId"   -> sourceId.asJson,
      "skillCount" -> skillCount.asJson,
      "repoRef"    -> repoRef.asJson
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
      "event"              -> "skill-added".asJson,
      "timestamp"          -> Instant.now().toString.asJson,
      "skillId"            -> skillId.asJson,
      "sourceId"           -> sourceId.asJson,
      "category"           -> category.asJson,
      "effectiveHarnesses" -> effectiveHarnesses.asJson
    )
    writeEvent(event.asJson)
  }

  override def recordSkillRemoved(skillId: String, sourceId: String): Try[Unit] = {
    val event = JsonObject(
      "event"     -> "skill-removed".asJson,
      "timestamp" -> Instant.now().toString.asJson,
      "skillId"   -> skillId.asJson,
      "sourceId"  -> sourceId.asJson
    )
    writeEvent(event.asJson)
  }

  override def recordFeedback(
      skillId: String,
      sourceId: String,
      rating: Int,
      comment: String
  ): Try[Unit] = {
    val event = JsonObject(
      "event"      -> "feedback".asJson,
      "schema"     -> "feedback-v1".asJson,
      "timestamp"  -> Instant.now().toString.asJson,
      "skillId"    -> skillId.asJson,
      "sourceId"   -> sourceId.asJson,
      "rating"     -> rating.asJson,
      "comment"    -> comment.asJson
    )
    writeEvent(event.asJson)
  }

  override def flush(): Try[Unit] = Try(()) // File backend doesn't need explicit flushing

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
 * Git-backed metrics collector. Events are appended to the configured JSONL file and committed
 * to the Git repository containing that file. The repository must already exist; this collector
 * never creates or pushes a remote repository.
 */
class GitMetricsCollector(
  metricsFile: File,
  branch: String,
  remote: String,
  push: Boolean,
  repositoryUrl: Option[String],
  repositoryDirectory: File,
  repositoryMetricsPath: String,
  log: Logger
) extends MetricsCollector {
  private def delegate = new FileMetricsCollector(managedTarget(), log)

  override def recordSkillSync(sourceId: String, skillCount: Int, repoRef: String): Try[Unit] =
    record(s"sync $sourceId", delegate.recordSkillSync(sourceId, skillCount, repoRef))

  override def recordSkillAdded(
      skillId: String,
      sourceId: String,
      category: String,
      effectiveHarnesses: Seq[String]
  ): Try[Unit] =
    record(s"add $sourceId:$category/$skillId", delegate.recordSkillAdded(skillId, sourceId, category, effectiveHarnesses))

  override def recordSkillRemoved(skillId: String, sourceId: String): Try[Unit] =
    record(s"remove $sourceId:$skillId", delegate.recordSkillRemoved(skillId, sourceId))

  override def recordFeedback(skillId: String, sourceId: String, rating: Int, comment: String): Try[Unit] =
    record(s"feedback $sourceId:$skillId", delegate.recordFeedback(skillId, sourceId, rating, comment))

  override def flush(): Try[Unit] = Try(())

  private def record(message: String, write: Try[Unit]): Try[Unit] = write.flatMap { _ =>
    Try {
      val target = managedTarget()
      val repository = new FileRepositoryBuilder().findGitDir(target).build()
      try {
        val git = Git.wrap(repository)
        val hasHead = repository.resolve("HEAD") != null
        if (push) fetchRemote(git)
        val branchExists = repository.getRefDatabase.findRef(branch) != null
        val remoteBranchExists = repository.getRefDatabase.findRef(s"refs/remotes/$remote/$branch") != null
        if (hasHead && repository.getBranch != branch) {
          val checkout = git.checkout()
            .setName(branch)
            .setCreateBranch(!branchExists)
          if (!branchExists && remoteBranchExists) checkout.setStartPoint(s"$remote/$branch")
          checkout.call()
        }
        if (push && hasHead && remoteBranchExists) {
          rebaseAndResolve(git, repository, target)
        }
        val workTree = repository.getWorkTree.toPath.toAbsolutePath.normalize
        val path = workTree.relativize(target.toPath.toAbsolutePath.normalize).toString.replace(File.separatorChar, '/')
        git.add()
          .addFilepattern(path)
          .call()
        git.commit()
          .setMessage(s"Record sbt-skills metrics: $message")
          .setAllowEmpty(false)
          .call()
        if (!hasHead && repository.getBranch != branch) {
          git.branchRename()
            .setNewName(branch)
            .call()
        }
        if (push) {
          val pushCommand = git.push().setRemote(remote)
          sys.env.get("GITHUB_TOKEN").foreach(token =>
            pushCommand.setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token))
          )
          pushCommand.call()
          log.info(s"[METRICS] Pushed metrics commit to '$remote/$branch'")
        }
      } finally {
        repository.close()
      }
    }
  }

  private def managedTarget(): File = repositoryUrl match {
    case Some(url) =>
      if (!repositoryDirectory.exists()) {
        val clone = Git.cloneRepository().setURI(url).setDirectory(repositoryDirectory)
        sys.env.get("GITHUB_TOKEN").foreach(token =>
          clone.setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token))
        )
        val git = clone.call()
        git.close()
      }
      new File(repositoryDirectory, repositoryMetricsPath)
    case None => metricsFile
  }

  private def fetchRemote(git: Git): Unit = {
    val fetch = git.fetch().setRemote(remote)
    sys.env.get("GITHUB_TOKEN").foreach(token =>
      fetch.setCredentialsProvider(new UsernamePasswordCredentialsProvider("x-access-token", token))
    )
    fetch.call()
  }

  private def rebaseAndResolve(git: Git, repository: org.eclipse.jgit.lib.Repository, target: File): Unit = {
    val path = repository.getWorkTree.toPath.toAbsolutePath.normalize
      .relativize(target.toPath.toAbsolutePath.normalize).toString.replace(File.separatorChar, '/')
    val localLines = readLines(target)
    var result = git.rebase().setUpstream(s"$remote/$branch").call()
    while (result.getStatus == RebaseResult.Status.CONFLICTS) {
      git.checkout().setStage(CheckoutCommand.Stage.THEIRS).addPath(path).call()
      val remoteLines = readLines(target)
      writeLines(target, remoteLines ++ localLines.filterNot(remoteLines.contains).filter(_.nonEmpty))
      git.add().addFilepattern(path).call()
      result = git.rebase().setOperation(RebaseCommand.Operation.CONTINUE).call()
    }
    if (result.getStatus != RebaseResult.Status.OK && result.getStatus != RebaseResult.Status.UP_TO_DATE) {
      throw new IllegalStateException(s"Metrics rebase failed: ${result.getStatus}")
    }
  }

  private def readLines(file: File): Vector[String] =
    if (file.isFile) new String(Files.readAllBytes(file.toPath), UTF_8).split("\\n", -1).toVector else Vector.empty

  private def writeLines(file: File, lines: Seq[String]): Unit = {
    file.getParentFile.mkdirs()
    Files.write(file.toPath, (lines.mkString("\n") + "\n").getBytes(UTF_8), CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
  }
}

/**
 * No-op metrics collector for testing or when metrics are disabled.
 */
class NoOpMetricsCollector extends MetricsCollector {
  override def recordSkillSync(sourceId: String, skillCount: Int, repoRef: String): Try[Unit] =
    Try(())
  override def recordSkillAdded(
      skillId: String,
      sourceId: String,
      category: String,
      effectiveHarnesses: Seq[String]
  ): Try[Unit]                                                                  = Try(())
  override def recordSkillRemoved(skillId: String, sourceId: String): Try[Unit] = Try(())
  override def recordFeedback(skillId: String, sourceId: String, rating: Int, comment: String): Try[Unit] = Try(())
  override def flush(): Try[Unit]                                               = Try(())
}

/**
 * Factory for creating metrics collectors.
 */
object MetricsCollectorFactory {
  def create(
      backend: String,
      metricsFile: File,
      gitBranch: String,
      gitRemote: String,
      gitPush: Boolean,
      gitRepository: Option[String],
      gitDirectory: File,
      gitMetricsPath: String,
      log: Logger
  ): MetricsCollector = {
    backend.toLowerCase match {
      case "file" =>
        log.info(s"Using file-based metrics backend: ${metricsFile.getAbsolutePath}")
        new FileMetricsCollector(metricsFile, log)
      case "git" =>
        log.info(s"Using Git-based metrics backend: ${metricsFile.getAbsolutePath}")
        new GitMetricsCollector(metricsFile, gitBranch, gitRemote, gitPush, gitRepository, gitDirectory, gitMetricsPath, log)
      case "none" | "noop" =>
        log.info("Metrics collection disabled")
        new NoOpMetricsCollector()
      case other =>
        log.warn(s"Unknown metrics backend: $other. Using file backend.")
        new FileMetricsCollector(metricsFile, log)
    }
  }
}
