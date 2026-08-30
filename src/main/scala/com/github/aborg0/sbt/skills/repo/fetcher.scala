package com.github.aborg0.sbt.skills.repo

import com.github.aborg0.sbt.skills.config.{Harnesses, SkillSource}
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode
import org.eclipse.jgit.api.ResetCommand.ResetType
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryBuilder
import sbt.util.Logger

import java.io.File
import scala.collection.mutable.{ListBuffer, Map => MutableMap}
import scala.io.Source
import scala.util.{Failure, Try}

/**
 * Handles fetching and updating skill repositories from remote sources.
 */
class SkillRepoFetcher(baseDir: File, log: Logger) {

  private[repo] def childDirectories(dir: File): Seq[File] = {
    Option(dir.listFiles()).toSeq.flatten.filter(_.isDirectory)
  }

  def ensureDir(dir: File): Try[File] = {
    Try {
      if (!dir.exists()) {
        if (!dir.mkdirs()) {
          throw new RuntimeException(
            s"[ERROR] Failed to create directory: ${dir.getAbsolutePath}. " +
              s"Check permissions and disk space."
          )
        }
        log.info(s"Created directory: ${dir.getAbsolutePath}")
      }
      dir
    }
  }

  /**
   * Fetches or updates a skill repository from a remote source. Returns the local directory path
   * containing the repository.
   */
  def fetchRepository(source: SkillSource): Try[File] = {
    val localDir = source.cacheDir.getOrElse(new File(baseDir, source.id))

    Try {
      if (localDir.exists()) {
        // Repository exists, update it
        log.info(s"[FETCH] Updating repository '${source.id}' at ${localDir.getAbsolutePath}")
        log.debug(s"  URL: ${source.url}, Ref: ${source.ref}")
        updateRepository(localDir, source.ref)
        log.info(s"[FETCH] ✓ Successfully updated repository '${source.id}'")
      } else {
        // Clone new repository
        log.info(s"[FETCH] Cloning repository '${source.id}' from ${source.url}")
        log.debug(s"  Target: ${localDir.getAbsolutePath}, Ref: ${source.ref}")
        cloneRepository(source.url, localDir, source.ref)
        log.info(s"[FETCH] ✓ Successfully cloned repository '${source.id}'")
      }
      localDir
    }.recoverWith { case e =>
      log.error(s"[ERROR] Failed to fetch repository '${source.id}': ${e.getMessage}")
      log.debug(s"  URL: ${source.url}, Ref: ${source.ref}")
      log.debug(s"  Exception: ${e.getClass.getSimpleName}")
      Failure(e)
    }
  }

  /**
   * Clone a repository and checkout the specified ref.
   */
  private def cloneRepository(url: String, targetDir: File, ref: String): Unit = {
    val git = Git.cloneRepository()
      .setURI(url)
      .setDirectory(targetDir)
      .call()

    try {
      checkoutRef(git, ref)
    } finally {
      git.close()
    }
  }

  /**
   * Update an existing repository and checkout the specified ref.
   */
  private def updateRepository(localDir: File, ref: String): Unit = {
    val repo = new RepositoryBuilder()
      .setGitDir(new File(localDir, ".git"))
      .build()

    val git = new Git(repo)
    try {
      git.fetch().call()
      checkoutRef(git, ref)
    } finally {
      git.close()
      repo.close()
    }
  }

  /**
   * Checkout a specific branch, tag, or commit hash.
   */
  private def checkoutRef(git: Git, ref: String): Unit = {
    val repository = git.getRepository
    val branchName = ref
      .stripPrefix("refs/heads/")
      .stripPrefix("refs/remotes/origin/")
      .stripPrefix("origin/")
    val localBranch  = repository.exactRef(s"refs/heads/$branchName")
    val remoteBranch = repository.exactRef(s"refs/remotes/origin/$branchName")

    if (remoteBranch != null) {
      if (localBranch == null) {
        git.checkout()
          .setCreateBranch(true)
          .setName(branchName)
          .setStartPoint(remoteBranch.getName)
          .setUpstreamMode(SetupUpstreamMode.TRACK)
          .call()
      } else {
        git.checkout()
          .setName(branchName)
          .call()
      }

      git.reset()
        .setMode(ResetType.HARD)
        .setRef(remoteBranch.getName)
        .call()
    } else if (repository.resolve(ref) != null) {
      git.checkout()
        .setName(ref)
        .call()
    } else {
      throw new IllegalArgumentException(s"Git ref '$ref' was not found")
    }

    log.info(s"Checked out ref: $ref")
  }

  /**
   * Discover all skills in a repository following the nested structure:
   * skills/category/skillname/SKILL.md
   *
   * Returns the skills found beneath the repository's skills directory.
   */
  def discoverSkills(repoDir: File): Try[Seq[DiscoveredSkill]] = {
    Try {
      val skillsDir = new File(repoDir, "skills")
      if (!skillsDir.isDirectory) {
        log.warn(
          s"[DISCOVER] No 'skills' directory found in ${repoDir.getAbsolutePath}. " +
            s"Expected: skills/category/skillname/SKILL.md"
        )
        Seq.empty
      } else {
        log.debug(s"[DISCOVER] Scanning skills directory: ${skillsDir.getAbsolutePath}")

        val results = ListBuffer[DiscoveredSkill]()

        def walkDir(dir: File, categoryPath: String = ""): Unit = {
          if (dir.isDirectory) {
            for (file <- childDirectories(dir)) {
              val skillMdFile = new File(file, "SKILL.md")
              if (skillMdFile.exists()) {
                // Found a skill: current directory is the skill name
                val skillName = file.getName
                results += DiscoveredSkill(categoryPath, skillName, skillMdFile)
              } else {
                // Not a skill directory, recurse into subdirectories
                val newCategory =
                  if (categoryPath.isEmpty) file.getName else s"$categoryPath/${file.getName}"
                walkDir(file, newCategory)
              }
            }
          }
        }

        walkDir(skillsDir)
        results.toSeq
      }
    }.map { skills =>
      log.info(s"[DISCOVER] Found ${skills.length} skill(s) in ${repoDir.getAbsolutePath}")
      skills.foreach { skill =>
        val skillPath = Seq(skill.category, skill.name).filter(_.nonEmpty).mkString("/")
        log.debug(s"  - $skillPath")
      }
      skills
    }
  }

  /**
   * Read SKILL.md file and parse metadata from frontmatter. Returns the normalized harness
   * identifiers and supplied repository version.
   */
  def parseSkillMetadata(skillMdFile: File, version: String): Try[SkillMetadata] = {
    Try {
      if (!skillMdFile.exists()) {
        throw new RuntimeException(s"SKILL.md file not found at ${skillMdFile.getAbsolutePath}")
      }
      val source = Source.fromFile(skillMdFile)
      try {
        val lines     = source.getLines().toList
        val metadata  = parseYamlFrontmatter(lines)
        val harnesses = Harnesses.normalizeAll(
          metadata.getOrElse("harnesses", "copilot,claude").split(",").toSeq
        )
        log.debug(s"[PARSE] Parsed metadata from ${skillMdFile.getName}: harnesses=$harnesses")
        SkillMetadata(harnesses = harnesses, version = version)
      } finally {
        source.close()
      }
    }.recoverWith { case e =>
      log.warn(
        s"[WARN] Failed to parse SKILL.md at ${skillMdFile.getAbsolutePath}: ${e.getMessage}"
      )
      Failure(e)
    }
  }

  /**
   * Parse YAML frontmatter from markdown file. Expects format: --- key: value key2: value2 ---
   */
  private def parseYamlFrontmatter(lines: List[String]): Map[String, String] = {
    if (lines.isEmpty || lines.head != "---") {
      Map.empty
    } else {
      val result        = MutableMap[String, String]()
      var inFrontmatter = true
      var i             = 1

      while (i < lines.length && inFrontmatter) {
        val line = lines(i)
        if (line == "---") {
          inFrontmatter = false
        } else {
          val parts = line.split(":", 2)
          if (parts.length == 2) {
            val key   = parts(0).trim.toLowerCase
            val value = parts(1).trim
            result(key) = value
          }
        }
        i += 1
      }

      result.toMap
    }
  }

  /**
   * Get the current commit hash of a repository.
   */
  def getCurrentCommitHash(repoDir: File): Try[String] = {
    Try {
      val repo = new RepositoryBuilder()
        .setGitDir(new File(repoDir, ".git"))
        .build()
      try {
        repo.resolve("HEAD").abbreviate(7).name()
      } finally {
        repo.close()
      }
    }
  }
}

case class DiscoveredSkill(
    category: String,
    name: String,
    file: File
)

/**
 * Metadata extracted from SKILL.md frontmatter.
 */
case class SkillMetadata(
    harnesses: Seq[String],
    version: String
)
