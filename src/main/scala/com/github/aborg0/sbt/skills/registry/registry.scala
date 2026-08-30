package com.github.aborg0.sbt.skills.registry

import com.github.aborg0.sbt.skills.config._
import com.github.aborg0.sbt.skills.config.JsonCodecs._
import io.circe._
import io.circe.parser._
import io.circe.syntax._
import io.circe.generic.semiauto._
import sbt.util.Logger
import java.io.{File, FileWriter}
import java.time.Instant
import scala.io.Source
import scala.util.{Failure, Success, Try}

/**
 * Manages the skill registry: stores and retrieves skills from registry.json.
 */
class SkillRegistry(registryFile: File, log: Logger) {

  /**
   * Load the registry from disk. Returns empty registry if file doesn't exist.
   */
  def load(): Try[SkillRegistry.Data] = {
    Try {
      if (!registryFile.exists()) {
        log.info(s"Registry file not found at ${registryFile.getAbsolutePath}, creating empty registry")
        SkillRegistry.Data(Seq(), Seq())
      } else {
        val source = Source.fromFile(registryFile)
        try {
          val content = source.mkString
          parse(content)
            .flatMap(_.as[SkillRegistry.Data](SkillRegistryCodecs.dataDecoder))
            .fold(
              err => throw new RuntimeException(s"Failed to parse registry: $err"),
              data => data
            )
        } finally {
          source.close()
        }
      }
    }
  }

  /**
   * Save the registry to disk.
   */
  def save(data: SkillRegistry.Data): Try[Unit] = {
    Try {
      val json = data.asJson(SkillRegistryCodecs.dataEncoder).spaces2
      registryFile.getParentFile.mkdirs()
      val writer = new FileWriter(registryFile)
      try {
        writer.write(json)
      } finally {
        writer.close()
      }
      log.info(s"Saved registry to ${registryFile.getAbsolutePath}")
    }
  }

  /**
   * Add or update a source in the registry.
   */
  def addOrUpdateSource(
    data: SkillRegistry.Data,
    source: SkillSource,
    override_harnesses: Option[Seq[String]]
  ): SkillRegistry.Data = {
    val entry = SourceRegistryEntry(
      id = source.id,
      url = source.url,
      ref = source.ref,
      harnessOverride = override_harnesses,
      lastSynced = Instant.now()
    )

    val updatedSources = data.sources.filterNot(_.id == source.id) :+ entry
    data.copy(sources = updatedSources)
  }

  /**
   * Add or update skills in the registry.
   */
  def addOrUpdateSkills(
    data: SkillRegistry.Data,
    skills: Seq[SkillReference]
  ): SkillRegistry.Data = {
    val updatedSkillIds = skills.map(skill => (skill.sourceId, skill.path)).toSet
    val unchangedSkills = data.skills.filterNot { skill =>
      updatedSkillIds.contains((skill.sourceId, skill.path))
    }
    val result = unchangedSkills ++ skills
    data.copy(skills = result)
  }

  /**
   * Get all skills for a specific harness using normalized harness identifiers.
   */
  def getSkillsForHarness(data: SkillRegistry.Data, harness: String): Seq[SkillReference] = {
    val normalizedHarness = Harnesses.normalize(harness)
    data.skills.filter(_.effectiveHarnesses.exists(Harnesses.normalize(_) == normalizedHarness))
  }

  /**
   * Get all skills, optionally filtered by source.
   */
  def getSkills(data: SkillRegistry.Data, sourceId: Option[String] = None): Seq[SkillReference] = {
    sourceId match {
      case Some(id) => data.skills.filter(_.sourceId == id)
      case None => data.skills
    }
  }

  /**
   * Remove skills by source ID.
   */
  def removeSkillsBySource(data: SkillRegistry.Data, sourceId: String): SkillRegistry.Data = {
    data.copy(skills = data.skills.filterNot(_.sourceId == sourceId))
  }
}

object SkillRegistry {
  case class Data(
    sources: Seq[SourceRegistryEntry],
    skills: Seq[SkillReference]
  )
}

// JSON codecs
object SkillRegistryCodecs {
  import JsonCodecs._
  import io.circe.generic.semiauto.{deriveEncoder, deriveDecoder}

  implicit val dataEncoder: Encoder[SkillRegistry.Data] = deriveEncoder
  implicit val dataDecoder: Decoder[SkillRegistry.Data] = deriveDecoder
}
