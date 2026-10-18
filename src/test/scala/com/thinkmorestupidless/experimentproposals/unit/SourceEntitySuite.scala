package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.entities.SourceEntity
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.Reviewers
import com.thinkmorestupidless.experimentproposals.domain.sources.*
import com.thinkmorestupidless.experimentproposals.rules.EligibilityRules
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

import java.time.Instant

class SourceEntitySuite extends munit.FunSuite:

  override def beforeAll(): Unit =
    Configured.reviewers = Reviewers.fromInline("ada:reviewer").toOption.get

  private val register = RegisterSource(
    Map("doi" -> "10.9999/x"),
    "A title",
    Vector("A. Author"),
    Some("2024"),
    None,
    None,
    Some("CC BY 4.0"),
    SourceKind.literature,
    None,
    Some("An abstract."),
    SourceStatus.active
  )
  private def content(hash: String, text: String) =
    FetchedContent(
      hash,
      Instant.parse("2026-10-16T10:00:00Z"),
      None,
      RetentionRestriction.none,
      Some(text)
    )
  private val sections = Vector(
    Section(
      "sec-1",
      None,
      1,
      SectionKind.methods,
      "Methods",
      "Cells were cultured for 96 hours.",
      Vector("sec-2")
    ),
    Section(
      "sec-2",
      None,
      2,
      SectionKind.results,
      "Results",
      "Cell number increased 3.1-fold. Viability stayed above 90 percent.",
      Vector("sec-1")
    )
  )
  private def kit =
    val k = EventSourcedTestKit.of(SourceEntity, "src:doi:10.9999/x")
    k.call(SourceEntity.register)(register)
    k

  test("registering twice persists once, and the same content hash is a duplicate with no event") {
    val k = kit
    k.call(SourceEntity.register)(register)
    assertEquals(k.allEvents.size, 1)
    assertEquals(
      k.call(SourceEntity.recordContent)(content("h1", "text")).replyValue,
      ContentOutcome.Recorded(1)
    )
    assertEquals(
      k.call(SourceEntity.recordContent)(content("h1", "text")).replyValue,
      ContentOutcome.Duplicate(1)
    )
    assertEquals(k.allEvents.size, 2)
  }

  test("a second version keeps the first version's sections resolvable") {
    val k = kit
    k.call(SourceEntity.recordContent)(content("h1", "one"))
    k.call(SourceEntity.recordParse)(
      RecordParse(1, "jats@v1", sections, Vector.empty, ParseCompleteness.complete)
    )
    assertEquals(
      k.call(SourceEntity.recordContent)(content("h2", "two")).replyValue,
      ContentOutcome.Recorded(2)
    )
    val span = SourceSpan("src:doi:10.9999/x", 1, "sec-2", 0, 31, "Cell number increased 3.1-fold.")
    assertEquals(
      k.call(SourceEntity.resolveSpan)(span).replyValue,
      SpanResolution.Resolved("Cell number increased 3.1-fold.")
    )
    val wrong = span.copy(quotation = "Cell number decreased")
    assert(
      k.call(SourceEntity.resolveSpan)(wrong).replyValue.isInstanceOf[SpanResolution.Unresolved]
    )
    assert(
      k.call(SourceEntity.resolveSpan)(span.copy(sourceVersion = 2))
        .replyValue
        .isInstanceOf[SpanResolution.Unresolved]
    )
  }

  test(
    "a status change by an unlisted name is refused and persists nothing; a listed one is recorded"
  ) {
    val k = kit
    val refused = k.call(SourceEntity.changeStatus)(
      ChangeStatus(SourceStatus.retracted, "image duplication", "mallory")
    )
    assert(refused.isError)
    assertEquals(k.allEvents.size, 1)
    k.call(SourceEntity.changeStatus)(
      ChangeStatus(SourceStatus.retracted, "image duplication", "ada")
    )
    assertEquals(k.currentState.status, SourceStatus.retracted)
    k.call(SourceEntity.changeStatus)(ChangeStatus(SourceStatus.retracted, "again", "operator"))
    assertEquals(k.allEvents.size, 2, "the same status again is not an event")
  }

  test(
    "chunks are recorded once per batch, context gives neighbours and related sections, quarantine is kept"
  ) {
    val k = kit
    k.call(SourceEntity.recordContent)(content("h1", "one"))
    k.call(SourceEntity.recordParse)(
      RecordParse(1, "jats@v1", sections, Vector.empty, ParseCompleteness.complete)
    )
    val chunks = Vector(
      Chunk("c1", "sec-2", 1, 0, 31, "Cell number increased 3.1-fold.", 4, Vector(1f)),
      Chunk("c2", "sec-2", 2, 32, 66, "Viability stayed above 90 percent.", 5, Vector(1f))
    )
    val rc = RecordChunks(1, "hash", "1", chunks, 1, 1)
    k.call(SourceEntity.recordChunks)(rc)
    k.call(SourceEntity.recordChunks)(rc)
    assertEquals(k.currentState.version(1).get.chunks.size, 2)
    val ctx = k.call(SourceEntity.context)(ChunkRef(1, "c1")).replyValue
    assertEquals(ctx.adjacent, Vector("Viability stayed above 90 percent."))
    assertEquals(ctx.related.map(_.id), Vector("sec-1"))
    k.call(SourceEntity.quarantine)(QuarantineSource(1, "corrupt table"))
    assert(k.currentState.version(1).get.quarantined)
    assert(
      k.call(SourceEntity.recordParse)(
        RecordParse(9, "jats@v1", sections, Vector.empty, ParseCompleteness.complete)
      ).isError
    )
  }

  test(
    "a status change re-assesses eligibility in the same command, by a reviewer or the operator"
  ) {
    val k = kit
    k.call(SourceEntity.recordContent)(content("h1", "text"))
    k.call(SourceEntity.recordParse)(
      RecordParse(1, "jats@v1", sections, Vector.empty, ParseCompleteness.complete)
    )
    k.call(SourceEntity.changeStatus)(ChangeStatus(SourceStatus.retracted, "notice", "ada"))
    val assessed = k.allEvents.collect { case SourceEvent.EligibilityAssessed(e) => e }
    assertEquals(assessed.size, 1)
    assert(!assessed.head.eligible)
    assert(
      assessed.head.reasons.exists(_.ruleId == EligibilityRules.retracted.key),
      assessed.head.reasons
    )
    assertEquals(assessed.head.assessedBy, "status-change:ada")
    assert(
      k.call(SourceEntity.changeStatus)(ChangeStatus(SourceStatus.active, "x", "nobody")).isError
    )
    k.call(SourceEntity.changeStatus)(
      ChangeStatus(SourceStatus.corrected, "erratum applied", "operator")
    )
    val again = k.allEvents.collect { case SourceEvent.EligibilityAssessed(e) => e }
    assert(
      again.last.eligible && again.last.reasons.exists(_.ruleId == EligibilityRules.corrected.key),
      again.last
    )
  }
