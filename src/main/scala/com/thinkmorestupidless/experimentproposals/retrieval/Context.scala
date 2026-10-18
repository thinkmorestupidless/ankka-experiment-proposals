package com.thinkmorestupidless.experimentproposals.retrieval

import com.thinkmorestupidless.experimentproposals.application.entities.SourceEntity
import com.thinkmorestupidless.experimentproposals.domain.sources.{ChunkContext, ChunkRef}
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.sdk.ComponentClient

/** A retrieved chunk with its section, neighbours, related sections and metadata (FR-018). */
object Context:
  /** `<source>:v<n>:<section>:<ordinal>`: the chunk id names where it lives. */
  def parse(chunkId: String): Option[(String, Int, String)] =
    val m = """^(.+):v(\d+):([^:]+):\d+$""".r
    chunkId match
      case m(source, version, section) => Some((source, version.toInt, section))
      case _                           => None

  def resolve(client: ComponentClient, chunkId: String): Option[ChunkContext] =
    parse(chunkId).map { (source, version, _) =>
      client
        .forEventSourcedEntity(EntityId(source))
        .call(SourceEntity.context)
        .invoke(ChunkRef(version, chunkId))
    }
