package com.thinkmorestupidless.experimentproposals.integration

import com.thinkmorestupidless.experimentproposals.application.entities.DesignEntity
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.design.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ref
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceSpan
import com.thinkmorestupidless.ankka.core.EntityId

import scala.concurrent.duration.*

/**
 * US8 through the whole service with the cassettes: a twelve-section design whose references
 * resolve, its rendering, a request for changes answered by a new version, an approval, a
 * rejection, and a design never submitted presented as a proposal; the history survives a restart.
 */
class DesignSuite extends ServiceFixture:

  private val A       = "src:file:paper-a:v1:fd1"
  private val B       = "src:file:paper-b:v1:fd1"
  private val C       = "src:file:paper-c:v1:fd1"
  private val Changes = "State the inconclusive criterion with a numeric threshold"

  override def beforeAll(): Unit =
    super.beforeAll()
    scriptFromCassettes(
      "facets@v1",
      "roundtrip@v1",
      "questions@v1",
      "extract@v1",
      "verify@v1",
      "reextract@v1",
      "assess@v1",
      "sufficiency@v1",
      "plan@v1",
      "candidates@v1",
      "design@v1"
    )

  private def question(id: String): String      = get(api(s"/questions/$id"))._2
  private def investigation(id: String): String = get(api(s"/workflows/investigation/$id"))._2
  private def design(id: String): String        = get(api(s"/designs/$id:design"))._2

  /** The reference request through to the decision; the workflow then designs. */
  private def decidedRequest(): String =
    val (status, body) =
      post(api("/requests"), """{"text":"Can you make foie gras?","submittedBy":"customer"}""")
    assertEquals(status, 201, body)
    val id = """"id":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).get
    eventually(s"$id pauses for facet review")(
      Option.when(investigation(id).contains("awaiting-facet-review"))(id)
    )
    Seq("f1", "f2", "f3", "f8").foreach(f =>
      assertEquals(
        post(
          api(s"/facets/$id:$f/review"),
          """{"action":"confirm","reviewer":"ada","rationale":"ok"}"""
        )._1,
        201
      )
    )
    assertEquals(post(api(s"/requests/$id/facets:confirmed"), """{"reviewer":"ada"}""")._1, 200)
    eventually("the lipid question is retrieved", within = 120.seconds)(
      Option.when(question(s"$id:q4").contains("\"status\":\"retrieved\""))(id)
    )
    assertEquals(
      post(api(s"/questions/$id:q4/assess"), s"""{"recordIds":["$A","$B","$C"]}""")._1,
      202
    )
    eventually("three assessments")(
      Option.when("\"relationship\":".r.findAllIn(question(s"$id:q4")).size == 3)(id)
    )
    assertEquals(post(api(s"/requests/$id/packages"), """{"reviewer":"ada"}""")._1, 202)
    eventually("awaiting the decision", within = 120.seconds)(
      Option.when(investigation(id).contains("awaiting-decision"))(id)
    )
    val (ds, decision) = post(
      api(s"/requests/$id/decide"),
      s"""{"selectedApproachId":"$id:c1","rejected":[{"approachId":"$id:c2","reason":"not the target species"}],"decidedBy":"ada","rationale":"the target species comes first"}"""
    )
    assertEquals(ds, 201, decision)
    id

  private def awaitingApproval(id: String): String =
    eventually("the design awaits approval", within = 120.seconds)(
      Option.when(investigation(id).contains("awaiting-approval"))(design(id))
    )

  test(
    "a twelve-section design whose references resolve; its rendering adds nothing; changes requested make a new version; the second is approved; everything survives a restart"
  ) {
    Seq("paper-a.xml", "paper-b.xml", "paper-c.xml").foreach(f =>
      recordsSettled(ingestFixture(f, extract = true))
    )
    val id = decidedRequest()
    val d1 = awaitingApproval(id)
    DesignSections.Names.foreach(n => assert(d1.contains(s""""$n":"""), s"$n missing in $d1"))
    assert(
      d1.contains("\"status\":{\"type\":\"under_review\"}") && d1.contains(
        "\"version\":1,\"approachRef\""
      ),
      d1
    )
    assertEquals("\"passed\":true".r.findAllIn(d1).size, 4, d1)
    Seq("src:file:paper-a:v1:cl1", "src:file:paper-b:v1:cl1", "src:file:paper-c:v1:cl1").foreach {
      c =>
        assert(d1.contains(s""""id":"$c""""), s"$c not cited in $d1")
        val claim = get(api(s"/claims/$c"))._2
        assert(
          claim.contains("\"status\":{\"type\":\"verified\"}") && claim.contains(
            "\"resolves\":true"
          ),
          claim
        )
    }
    val (ms, md) = get(api(s"/designs/$id:design?format=md"))
    assertEquals(ms, 200, md)
    assert(
      md.contains("Proposal awaiting review") && md.contains("# Experimental design for") && md
        .contains("## 12. Evidence manifest"),
      md
    )
    val manifest = d1.substring(d1.indexOf("\"evidenceManifest\":{"))
    val manifestIds = """"id":"([^"]+)"""".r
      .findAllMatchIn(manifest.substring(0, manifest.indexOf("\"spans\":[")))
      .map(_.group(1))
      .toSet
    val referenced = Rendering.claimsReferenced(md).toSet
    assert(referenced.nonEmpty && referenced.subsetOf(manifestIds), s"$referenced vs $manifestIds")
    assert(md.contains("as a surrogate model was not chosen"), md)

    val (unlisted, _) = post(
      api(s"/designs/$id:design/approval"),
      """{"version":1,"decision":"approved","reviewer":"nobody","rationale":"x"}"""
    )
    assertEquals(unlisted, 400)
    assert(!design(id).contains("\"approvals\":[{"), "nothing persisted by the refusal")
    val (cs, changed) = post(
      api(s"/designs/$id:design/approval"),
      s"""{"version":1,"decision":"changes_requested","reviewer":"ada","rationale":"the inconclusive criterion has no threshold","requestedChanges":["$Changes"]}"""
    )
    assertEquals(cs, 201, changed)
    assert(changed.contains("\"decision\":{\"type\":\"changes_requested\"}"), changed)
    val d2 = eventually("a second version is under review", within = 120.seconds) {
      val d = design(id);
      Option.when(
        d.contains("\"version\":2,\"approachRef\"") && d.contains(
          "\"status\":{\"type\":\"under_review\"}"
        )
      )(d)
    }
    assert(
      d2.contains("between 1.5-fold and 2-fold") && d2.contains(
        s""""changesApplied":["$Changes"]"""
      ),
      d2
    )
    assert(d2.contains("\"status\":{\"type\":\"superseded\"}"), d2)
    val (as, approved) = post(
      api(s"/designs/$id:design/approval"),
      """{"version":2,"decision":"approved","reviewer":"ada","rationale":"threshold stated"}"""
    )
    assertEquals(as, 201, approved)
    eventually("the investigation ends designed")(
      Option.when(investigation(id).contains("designed"))(id)
    )
    assert(get(api(s"/requests/$id"))._2.contains("\"status\":{\"type\":\"designed\"}"))
    assertEquals(
      post(
        api(s"/designs/$id:design/approval"),
        """{"version":2,"decision":"rejected","reviewer":"ada","rationale":"late"}"""
      )._1,
      409
    )

    restart()

    val after = design(id)
    assert(
      after.contains("\"version\":1,\"approachRef\"") && after.contains(
        "\"status\":{\"type\":\"superseded\"}"
      ),
      after
    )
    assert(
      after.contains("\"decision\":{\"type\":\"changes_requested\"}") && after.contains(
        "\"decision\":{\"type\":\"approved\"}"
      ),
      after
    )
    val v1 = get(api(s"/designs/$id:design?version=1"))._2
    assert(
      v1.contains("\"version\":1,\"approachRef\"") && !v1.contains("\"version\":2,\"approachRef\""),
      v1
    )
    assert(get(api(s"/designs/$id:design?format=md&version=2"))._2.contains("**Approved** by ada"))
    val rows = eventually("the design rows") {
      val r = get(api("/designs"))._2; Option.when(r.contains(s"$id:design"))(r)
    }
    assert(rows.contains("\"approvedVersion\":2"), rows)
  }

  test(
    "a rejection on a version stays visible with its reviewer, time and reason, and the design is not presented as approved"
  ) {
    val id = decidedRequest()
    awaitingApproval(id)
    val (rs, rejected) = post(
      api(s"/designs/$id:design/approval"),
      """{"version":1,"decision":"rejected","reviewer":"ada","rationale":"the model is not ready"}"""
    )
    assertEquals(rs, 201, rejected)
    assert(
      rejected.contains("\"decision\":{\"type\":\"rejected\"}") && rejected.contains(
        "\"reviewer\":\"ada\""
      ) && rejected.contains("\"rationale\":\"the model is not ready\"") && rejected.contains(
        "\"at\":\""
      ),
      rejected
    )
    assert(rejected.contains("\"status\":{\"type\":\"rejected\"}"), rejected)
    val md = get(api(s"/designs/$id:design?format=md"))._2
    assert(
      md.contains("**rejected** by ada") && !md.contains("**Approved**") && md.contains(
        "Proposal awaiting review"
      ),
      md
    )
    eventually("the investigation ends rejected")(
      Option.when(investigation(id).contains("design-rejected"))(id)
    )
  }

  test("a design never submitted is presented as a proposal; submitting it puts it under review") {
    val sections = DesignSections(
      "Q",
      Vector(
        Grounded(
          "B",
          Vector(Ref(RecordKind.claim, "src:file:paper-a:v1:cl1", 1)),
          Vector(SourceSpan("src:file:paper-a", 1, "sec-1", 0, 4, "Prim"))
        )
      ),
      "H",
      "M",
      "V",
      "E",
      "S",
      "R",
      "L",
      "A",
      "O",
      Manifest(
        Vector(Ref(RecordKind.claim, "src:file:paper-a:v1:cl1", 1)),
        Vector.empty,
        Vector.empty
      )
    )
    testKit.service.componentClient
      .forEventSourcedEntity(EntityId("rq-manual:design"))
      .call(DesignEntity.generate)
      .invoke(
        GenerateDesign(
          Ref(RecordKind.request, "rq-manual", 1),
          Ref(RecordKind.approach, "rq-manual:c1", 1),
          Ref(RecordKind.decision, "rq-manual:d1", 1),
          sections,
          Vector.empty,
          "design@v1",
          "test",
          Vector.empty
        )
      )
    val md = get(api("/designs/rq-manual:design?format=md"))._2
    assert(
      md.contains("Proposal awaiting review") && md.contains(
        "Nothing in this document is an authorised procedure"
      ),
      md
    )
    assert(get(api("/designs/rq-manual:design"))._2.contains("\"status\":{\"type\":\"draft\"}"))
    val (ss, submitted) = post(api("/designs/rq-manual:design/submit"), """{"version":1}""")
    assertEquals(ss, 200, submitted)
    assert(submitted.contains("\"status\":{\"type\":\"under_review\"}"), submitted)
  }
