package com.github.aborg0.sbt.skills.repo

import com.github.aborg0.sbt.skills.config.SkillSource
import org.eclipse.jgit.api.Git
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sbt.util.Logger

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._

class SkillRepoFetcherSpec extends AnyFlatSpec with Matchers {

  "SkillRepoFetcher.discoverSkills" should "separate category paths from skill names" in {
    val repoDir           = Files.createTempDirectory("skill-repo")
    val skillsDir         = Files.createDirectory(repoDir.resolve("skills"))
    val engineeringDir    = Files.createDirectory(skillsDir.resolve("engineering"))
    val backendDir        = Files.createDirectory(engineeringDir.resolve("backend"))
    val nestedSkillDir    = Files.createDirectory(backendDir.resolve("code-review"))
    val nestedSkillFile   = Files.createFile(nestedSkillDir.resolve("SKILL.md"))
    val topLevelSkillDir  = Files.createDirectory(skillsDir.resolve("standalone"))
    val topLevelSkillFile = Files.createFile(topLevelSkillDir.resolve("SKILL.md"))

    val pathsToDelete: Seq[Path] = Seq(
      nestedSkillFile,
      nestedSkillDir,
      backendDir,
      engineeringDir,
      topLevelSkillFile,
      topLevelSkillDir,
      skillsDir,
      repoDir
    )

    try {
      val fetcher    = new SkillRepoFetcher(repoDir.toFile, Logger.Null)
      val discovered = fetcher.discoverSkills(repoDir.toFile)

      discovered.isSuccess shouldBe true
      discovered.get.map { skill =>
        (skill.category, skill.name, skill.file.toPath)
      }.toSet shouldBe Set(
        ("engineering/backend", "code-review", nestedSkillFile),
        ("", "standalone", topLevelSkillFile)
      )
    } finally {
      pathsToDelete.foreach(Files.deleteIfExists)
    }
  }

  it should "treat a failed directory listing as empty" in {
    val unreadableDirectory = new File("unreadable") {
      override def listFiles(): Array[File] = null
    }
    val fetcher = new SkillRepoFetcher(new File("."), Logger.Null)

    fetcher.childDirectories(unreadableDirectory) shouldBe empty
  }

  "SkillRepoFetcher.fetchRepository" should "track and update a non-default remote branch" in {
    val sourceDir   = Files.createTempDirectory("skill-source")
    val cacheDir    = Files.createTempDirectory("skill-cache")
    val sourceGit   = Git.init().setDirectory(sourceDir.toFile).call()
    val contentFile = sourceDir.resolve("content.txt")

    try {
      Files.write(contentFile, "default".getBytes(StandardCharsets.UTF_8))
      commit(sourceGit, contentFile, "initial")
      val defaultBranch = sourceGit.getRepository.getBranch

      sourceGit.checkout().setCreateBranch(true).setName("feature").call()
      Files.write(contentFile, "feature-one".getBytes(StandardCharsets.UTF_8))
      commit(sourceGit, contentFile, "feature one")
      sourceGit.checkout().setName(defaultBranch).call()

      val fetcher    = new SkillRepoFetcher(cacheDir.toFile, Logger.Null)
      val source     = SkillSource("remote", sourceDir.toUri.toString, "feature")
      val firstFetch = fetcher.fetchRepository(source).get

      val firstCachedGit = Git.open(firstFetch)
      try {
        firstCachedGit.getRepository.getBranch shouldBe "feature"
        firstCachedGit.getRepository.getConfig.getString("branch", "feature", "remote") shouldBe
          "origin"
        firstCachedGit.getRepository.getConfig.getString("branch", "feature", "merge") shouldBe
          "refs/heads/feature"
      } finally {
        firstCachedGit.close()
      }
      read(contentFile = firstFetch.toPath.resolve("content.txt")) shouldBe "feature-one"

      sourceGit.checkout().setName("feature").call()
      Files.write(contentFile, "feature-two".getBytes(StandardCharsets.UTF_8))
      val latestCommit = commit(sourceGit, contentFile, "feature two")
      sourceGit.checkout().setName(defaultBranch).call()

      val secondFetch = fetcher.fetchRepository(source).get
      val cachedGit   = Git.open(secondFetch)
      try {
        cachedGit.getRepository.resolve("HEAD").name() shouldBe latestCommit
        read(secondFetch.toPath.resolve("content.txt")) shouldBe "feature-two"
      } finally {
        cachedGit.close()
      }
    } finally {
      sourceGit.close()
      deleteRecursively(cacheDir)
      deleteRecursively(sourceDir)
    }
  }

  private def commit(git: Git, file: Path, message: String): String = {
    git.add().addFilepattern(file.getFileName.toString).call()
    git.commit()
      .setMessage(message)
      .setAuthor("Test", "test@example.com")
      .setCommitter("Test", "test@example.com")
      .call()
      .getName
  }

  private def read(contentFile: Path): String = {
    new String(Files.readAllBytes(contentFile), StandardCharsets.UTF_8)
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.exists(path)) {
      val paths = Files.walk(path)
      try {
        paths.iterator().asScala.toSeq.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      } finally {
        paths.close()
      }
    }
  }
}
