package com.github.aborg0.sbt.skills.metrics

import io.circe.parser.parse
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
}
