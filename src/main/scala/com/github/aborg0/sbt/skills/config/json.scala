package com.github.aborg0.sbt.skills.config

import com.github.aborg0.sbt.skills.registry.SkillRegistry.Data
import io.circe._
import io.circe.generic.semiauto._
import io.circe.syntax._
import java.time.Instant
import java.io.File

object JsonCodecs {

  implicit val fileEncoder: Encoder[File] = Encoder.encodeString.contramap[File](_.getAbsolutePath)
  implicit val fileDecoder: Decoder[File] = Decoder.decodeString.map(new File(_))

  implicit val instantEncoder: Encoder[Instant] = Encoder.encodeString.contramap[Instant](_.toString)
  implicit val instantDecoder: Decoder[Instant] = Decoder.decodeString.map(Instant.parse)

  implicit val skillSourceEncoder: Encoder[SkillSource] = deriveEncoder
  implicit val skillSourceDecoder: Decoder[SkillSource] = deriveDecoder

  implicit val skillReferenceEncoder: Encoder[SkillReference] = deriveEncoder
  implicit val skillReferenceDecoder: Decoder[SkillReference] = deriveDecoder

  implicit val sourceRegistryEntryEncoder: Encoder[SourceRegistryEntry] = deriveEncoder
  implicit val sourceRegistryEntryDecoder: Decoder[SourceRegistryEntry] = deriveDecoder

  implicit val skillRegistryEncoder: Encoder[Data] = deriveEncoder[Data]
  implicit val skillRegistryDecoder: Decoder[Data] = deriveDecoder[Data]

}
