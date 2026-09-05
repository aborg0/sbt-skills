package com.github.aborg0.sbt.skills.metrics

import io.circe.parser.parse
import org.eclipse.jgit.api.Git
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sbt.util.Logger

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

class FileMetricsCollectorSpec extends AnyFlatSpec with Matchers {

  "FileMetricsCollector" should "write metrics as UTF-8" in {
    val directory   = Files.createTempDirectory("metrics")
    val metricsFile = directory.resolve("metrics.jsonl")

    try {
      val collector = new FileMetricsCollector(metricsFile.toFile, Logger.Null)
      collector.recordSkillSync("sourcé-日本語", 1, "maïn").get

      val json = parse(new String(Files.readAllBytes(metricsFile), UTF_8)).toOption.get
      json.hcursor.get[String]("sourceId") shouldBe Right("sourcé-日本語")
      json.hcursor.get[String]("repoRef") shouldBe Right("maïn")
    } finally {
      Files.deleteIfExists(metricsFile)
      Files.deleteIfExists(directory)
    }
  }

  it should "commit events with the Git backend" in {
    val directory = Files.createTempDirectory("git-metrics")
    val repository = Git.init().setDirectory(directory.toFile).call()
    val metricsFile = directory.resolve(".sbt-skills/metrics.jsonl")
    try {
      val collector = new GitMetricsCollector(metricsFile.toFile, "metrics", "origin", false, None, directory.toFile, ".sbt-skills/metrics.jsonl", Logger.Null)
      collector.recordSkillSync("source", 2, "main").get

      Files.exists(metricsFile) shouldBe true
      repository.getRepository.getBranch shouldBe "metrics"
      repository.log().call().iterator().next().getFullMessage should include("Record sbt-skills metrics")
    } finally {
      repository.close()
    }
  }

  it should "clone a managed repository and use its configured metrics path" in {
    val upstream = Files.createTempDirectory("metrics-upstream")
    val checkout = Files.createTempDirectory("metrics-checkout").resolve("repo")
    val upstreamGit = Git.init().setDirectory(upstream.toFile).call()
    try {
      Files.write(upstream.resolve("README.md"), "upstream\n".getBytes(UTF_8))
      upstreamGit.add().addFilepattern("README.md").call()
      upstreamGit.commit().setMessage("initial").call()
      val collector = new GitMetricsCollector(
        Files.createTempFile("unused", ".jsonl").toFile,
        "metrics", "origin", false, Some(upstream.toString), checkout.toFile,
        "data/metrics.jsonl", Logger.Null
      )
      collector.recordFeedback("review", "source", 5, "useful skill").get

      val metricsFile = checkout.resolve("data/metrics.jsonl")
      Files.exists(metricsFile) shouldBe true
      val json = parse(new String(Files.readAllBytes(metricsFile), UTF_8).trim).toOption.get
      json.hcursor.get[String]("event") shouldBe Right("feedback")
      json.hcursor.get[String]("schema") shouldBe Right("feedback-v1")
    } finally {
      upstreamGit.close()
    }
  }
}
