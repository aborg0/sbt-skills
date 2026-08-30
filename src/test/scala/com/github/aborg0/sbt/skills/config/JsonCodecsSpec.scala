package com.github.aborg0.sbt.skills.config

import io.circe.parser._
import io.circe.syntax._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.Files
import java.time.Instant

class JsonCodecsSpec extends AnyFlatSpec with Matchers {

  "FileEncoder" should "encode File to absolute path string" in {
    import JsonCodecs._
    val file = Files.createTempFile("json-codecs", ".txt").toFile
    try {
      val json = file.asJson
      json.as[String].toOption should contain(file.getAbsolutePath)
    } finally {
      Files.deleteIfExists(file.toPath)
    }
  }

  "FileDecoder" should "decode string to File" in {
    import JsonCodecs._
    val file = Files.createTempFile("json-codecs", ".txt").toFile
    try {
      val json   = file.getAbsolutePath.asJson
      val result = json.as[File].toOption.map(_.getAbsolutePath)
      result should contain(file.getAbsolutePath)
    } finally {
      Files.deleteIfExists(file.toPath)
    }
  }

  "SkillSourceEncoder/Decoder" should "round-trip correctly" in {
    import JsonCodecs._
    val source  = SkillSource("test", "https://github.com/test/repo.git", "main")
    val json    = source.asJson
    val decoded = json.as[SkillSource].toOption
    decoded should contain(source)
  }

  "SkillReferenceEncoder/Decoder" should "round-trip correctly" in {
    import JsonCodecs._
    val now   = Instant.now()
    val skill = SkillReference(
      id = "test-skill",
      sourceId = "test-source",
      category = "testing",
      path = "skills/testing/test-skill/SKILL.md",
      version = "abc123def",
      harnessesInRepo = Seq("copilot", "claude"),
      effectiveHarnesses = Seq("copilot"),
      lastFetched = now,
      customized = false
    )
    val json    = skill.asJson
    val decoded = json.as[SkillReference].toOption
    decoded should contain(skill)
  }

}
