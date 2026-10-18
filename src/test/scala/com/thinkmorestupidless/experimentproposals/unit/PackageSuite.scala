package com.thinkmorestupidless.experimentproposals.unit

import com.thinkmorestupidless.experimentproposals.application.entities.PackageEntity
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.design.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ref
import com.thinkmorestupidless.experimentproposals.domain.request.Facet
import com.thinkmorestupidless.experimentproposals.domain.sources.*
import com.thinkmorestupidless.experimentproposals.rules.PackageRules
import com.thinkmorestupidless.ankka.testkit.EventSourcedTestKit

import java.time.Instant

class PackageSuite extends munit.FunSuite:

  private val q = Ref(RecordKind.question, "rq-1:q4", 1)
  private def source(id: String, eligible: Boolean, doi: String) =
    SourceDocument(
      id,
      registered = true,
      identifiers = Map("doi" -> doi),
      eligibility = Some(Eligibility(eligible, Vector.empty, Instant.now(), "t", "eligibility@v1"))
    )
  private def finding(id: String, sourceId: String, status: RecordStatus, species: Option[String]) =
    EvidenceRecord(
      id,
      registered = true,
      kind = "finding",
      sourceRef = Some(Ref(RecordKind.source, sourceId, 1)),
      content = Some(
        RecordContent.OfFinding(
          FindingContent(
            species,
            None,
            None,
            Vector.empty,
            "lipid",
            "rose",
            OutcomeDirection.increase,
            Vector.empty,
            Vector.empty,
            None,
            "low",
            Map.empty
          )
        )
      ),
      spans = Vector(SourceSpan(sourceId, 1, "sec-1", 0, 4, "Cell")),
      status = status
    )
  private def assessment(id: String, recordId: String, rel: Relationship) =
    EvidenceAssessment(
      id,
      registered = true,
      questionRef = Some(q),
      recordRef = Some(Ref(RecordKind.finding, recordId, 1)),
      relationship = rel,
      applicability = Applicability.direct
    )
  private val facets = Vector(
    Facet(
      "rq-1:f1",
      registered = true,
      name = "product",
      importance = Importance.mandatory,
      status = FacetStatus.confirmed
    )
  )
  private val b  = finding("src:b:v1:fd1", "src:b", RecordStatus.verified, Some("human"))
  private val c  = finding("src:c:v1:fd1", "src:c", RecordStatus.verified, Some("goose"))
  private val aB = assessment("rq-1:q4:src:b:v1:fd1", b.id, Relationship.supports)
  private val aC = assessment("rq-1:q4:src:c:v1:fd1", c.id, Relationship.contradicts)
  private def input(
      included: Vector[EvidenceAssessment],
      candidates: Vector[EvidenceAssessment],
      trims: Vector[Trim] = Vector.empty,
      resolves: Boolean = true
  ) =
    PackageRules.Input(
      included,
      candidates,
      Map(b.id    -> b, c.id                                  -> c),
      Map("src:b" -> source("src:b", true, "10.1/b"), "src:c" -> source("src:c", true, "10.1/c")),
      facets,
      included
        .flatMap(_.recordRef)
        .flatMap(r => Map(b.id -> b, c.id -> c).get(r.id))
        .flatMap(_.spans),
      _ => resolves,
      trims
    )

  test(
    "every check passes for verified, eligible, qualified records with resolving spans and the contradiction included"
  ) {
    val checks = PackageRules.check(input(Vector(aB, aC), Vector(aB, aC)))
    assertEquals(checks.size, 8)
    assert(checks.forall(_.passed), checks.filterNot(_.passed))
    assertEquals(EvidencePackage.quality(checks, Vector.empty), PackageQuality.valid)
  }

  test(
    "a package that leaves out a contradiction, an unverified record, an unresolved span or a critical trim is invalid"
  ) {
    val dropped = PackageRules.check(input(Vector(aB), Vector(aB, aC)))
    assert(
      dropped.exists(ch => ch.name == PackageRules.contradictionsIncluded.key && !ch.passed),
      dropped
    )
    assertEquals(EvidencePackage.quality(dropped, Vector.empty), PackageQuality.invalid)
    val unverified = PackageRules.check(
      input(Vector(aB, aC), Vector(aB, aC))
        .copy(records = Map(b.id -> b.copy(status = RecordStatus.proposed), c.id -> c))
    )
    assert(unverified.exists(ch => ch.name == PackageRules.verificationEnforced.key && !ch.passed))
    val unresolved = PackageRules.check(input(Vector(aB, aC), Vector(aB, aC), resolves = false))
    assert(unresolved.exists(ch => ch.name == PackageRules.spansResolve.key && !ch.passed))
    val critical = PackageRules.check(
      input(
        Vector(aB, aC),
        Vector(aB, aC),
        trims = Vector(
          Trim(Ref(RecordKind.assessment, "x", 1), "dropped a qualification", critical = true)
        )
      )
    )
    assert(critical.exists(ch => ch.name == PackageRules.noCriticalTrim.key && !ch.passed))
    val shared = PackageRules.check(
      input(Vector(aB, aC), Vector(aB, aC)).copy(sources =
        Map(
          "src:b" -> source("src:b", true, "10.1/same"),
          "src:c" -> source("src:c", true, "10.1/same")
        )
      )
    )
    assert(shared.exists(ch => ch.name == PackageRules.sameStudyDeduplicated.key && !ch.passed))
  }

  test(
    "the entity builds once, and is valid only when every check passed and no trim was critical"
  ) {
    val k = EventSourcedTestKit.of(PackageEntity, "rq-1:q4:pk1")
    val build = BuildPackage(
      Vector(q),
      Ref(RecordKind.request, "rq-1", 1),
      Vector.empty,
      Vector(Ref(RecordKind.assessment, aB.id, 1)),
      Vector(Ref(RecordKind.finding, b.id, 1)),
      b.spans,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector("rq-1:q4"),
      Vector.empty,
      Vector.empty,
      Vector(Trim(Ref(RecordKind.assessment, "rq-1:q4:x", 1), "background beyond the budget")),
      "system:package@v1",
      Vector.empty
    )
    assertEquals(k.call(PackageEntity.build)(build).replyValue.version, 1)
    assertEquals(k.call(PackageEntity.build)(build).replyValue.version, 1, "built once")
    val passing = PackageRules.check(input(Vector(aB, aC), Vector(aB, aC)))
    assertEquals(
      k.call(PackageEntity.validate)(ValidatePackage(passing, "package@v1"))
        .replyValue
        .qualityStatus,
      PackageQuality.valid
    )
    val k2 = EventSourcedTestKit.of(PackageEntity, "rq-1:q4:pk2")
    k2.call(PackageEntity.build)(
      build.copy(trims =
        Vector(
          Trim(
            Ref(RecordKind.assessment, "rq-1:q4:x", 1),
            "dropped the contradiction",
            critical = true
          )
        )
      )
    )
    assertEquals(
      k2.call(PackageEntity.validate)(ValidatePackage(passing, "package@v1"))
        .replyValue
        .qualityStatus,
      PackageQuality.invalid
    )
    assert(k2.call(PackageEntity.validate)(ValidatePackage(Vector.empty, "package@v1")).isError)
  }
