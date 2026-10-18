package com.thinkmorestupidless.experimentproposals.integration

import scala.concurrent.duration.*

/**
 * US10 through the whole service: retracting Paper B after an approved design flags every dependent
 * while the approved version stays byte-identical; the flag names the source version; a second
 * status change adds no duplicate; a faulted finding flags its assessments; flags survive a
 * restart.
 */
class ImpactSuite extends ServiceFixture:

  override val munitTimeout = 15.minutes

  private val A = "src:file:paper-a:v1:fd1"
  private val B = "src:file:paper-b:v1:fd1"
  private val C = "src:file:paper-c:v1:fd1"

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
  private def versionsOf(d: String): String =
    d.substring(d.indexOf("\"versions\":["), d.indexOf("\"approvals\":"))

  private def approvedDesign(): String =
    val (status, body) =
      post(api("/requests"), """{"text":"Can you make foie gras?","submittedBy":"customer"}""")
    assertEquals(status, 201, body)
    val id = """"id":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).get
    eventually(s"$id pauses for facet review")(
      Option.when(investigation(id).contains("awaiting-facet-review"))(id)
    )
    Seq("f1", "f2", "f3", "f8").foreach(f =>
      post(
        api(s"/facets/$id:$f/review"),
        """{"action":"confirm","reviewer":"ada","rationale":"ok"}"""
      )
    )
    assertEquals(post(api(s"/requests/$id/facets:confirmed"), """{"reviewer":"ada"}""")._1, 200)
    eventually("the lipid question is retrieved", within = 5.minutes)(
      Option.when(question(s"$id:q4").contains("\"status\":\"retrieved\""))(id)
    )
    assertEquals(
      post(api(s"/questions/$id:q4/assess"), s"""{"recordIds":["$A","$B","$C"]}""")._1,
      202
    )
    eventually("three assessments", within = 3.minutes)(
      Option.when("\"relationship\":".r.findAllIn(question(s"$id:q4")).size == 3)(id)
    )
    assertEquals(post(api(s"/requests/$id/packages"), """{"reviewer":"ada"}""")._1, 202)
    eventually("awaiting the decision", within = 5.minutes)(
      Option.when(investigation(id).contains("awaiting-decision"))(id)
    )
    assertEquals(
      post(
        api(s"/requests/$id/decide"),
        s"""{"selectedApproachId":"$id:c1","rejected":[{"approachId":"$id:c2","reason":"not the target species"}],"decidedBy":"ada","rationale":"the target species comes first"}"""
      )._1,
      201
    )
    eventually("awaiting approval", within = 5.minutes)(
      Option.when(investigation(id).contains("awaiting-approval"))(id)
    )
    assertEquals(
      post(
        api(s"/designs/$id:design/approval"),
        """{"version":1,"decision":"approved","reviewer":"ada","rationale":"ready"}"""
      )._1,
      201
    )
    eventually("designed", within = 3.minutes)(
      Option.when(investigation(id).contains("designed"))(id)
    )
    id

  test(
    "retracting Paper B flags every dependent, leaves the approved version unchanged, names the source version, and adds no duplicate on a second change"
  ) {
    Seq("paper-a.xml", "paper-b.xml", "paper-c.xml").foreach(f =>
      recordsSettled(ingestFixture(f, extract = true))
    )
    val id     = approvedDesign()
    val before = design(id)
    assert(before.contains("src:file:paper-b"), "the design cites Paper B")
    val versions = versionsOf(before)
    // The dependents view is eventually consistent; the walk reads it when the change arrives.
    eventually("the dependents of Paper B are projected", within = 3.minutes) {
      val (_, j) = get(api("/impact?ref=source:src:file:paper-b:v1"))
      Option.when(
        Seq(s"$id:design", s"$id:q4:pk1", s"$id:c1", s"$id:q4:$B").forall(x =>
          j.contains(s""""id":"$x"""")
        )
      )(j)
    }
    assertEquals(
      post(
        api("/sources/src:file:paper-b/status"),
        """{"status":"retracted","reason":"publisher notice","by":"ada"}"""
      )._1,
      200
    )
    var last = ""
    val impact =
      try
        eventually("every dependent of Paper B is flagged", within = 6.minutes) {
          val (_, j) = get(api("/impact?ref=source:src:file:paper-b:v1"))
          last = j
          Option.when(j.contains("\"allFlagged\":true"))(j)
        }
      catch
        case e: AssertionError =>
          val pkg  = get(api(s"/packages/$id:q4:pk1"))._2
          val tail = pkg.substring(math.max(0, pkg.indexOf("\"qualityStatus\"")))
          fail(s"${e.getMessage}; last reply: ${last.take(1200)}; package tail: ${tail.take(1500)}")
    Seq("finding", "claim", "assessment", "package", "approach", "design").foreach(k =>
      assert(impact.contains(s""""kind":"$k""""), s"$k missing in $impact")
    )
    assert(
      impact.contains(s""""id":"$id:design"""") && impact.contains(
        s""""id":"$id:q4:pk1""""
      ) && impact.contains(s""""id":"$id:c1""""),
      impact
    )
    val after = design(id)
    assertEquals(versionsOf(after), versions, "the approved version's content is unchanged")
    assert(
      after.contains(
        "\"changedRef\":{\"kind\":{\"type\":\"source\"},\"id\":\"src:file:paper-b\",\"version\":1}"
      ),
      after
    )
    assert(after.contains("\"status\":{\"type\":\"approved\"}"), after)
    val assessment = get(api(s"/assessments/$id:q4:$B"))._2
    assert(
      assessment.contains("\"flags\":[{") && assessment.contains("\"id\":\"src:file:paper-b\""),
      assessment
    )
    assert(get(api(s"/packages/$id:q4:pk1"))._2.contains("\"flags\":[{"))
    assert(get(api(s"/requests/$id/approaches"))._2.contains("\"flags\":[{"))
    val md = get(api(s"/designs/$id:design?format=md"))._2
    assert(md.contains("**Approved** by ada"), md)

    assertEquals(
      post(
        api("/sources/src:file:paper-b/status"),
        """{"status":"corrected","reason":"second change","by":"operator"}"""
      )._1,
      200
    )
    Thread.sleep(3000)
    val again = design(id)
    assertEquals(
      "\"id\":\"src:file:paper-b\",\"version\":1}".r
        .findAllIn(again.substring(again.indexOf("\"flags\":")))
        .size,
      1,
      "one flag per changed ref"
    )

    restart()

    val persisted = design(id)
    assertEquals(versionsOf(persisted), versions)
    assert(persisted.contains("\"flags\":[{"), persisted)
    assert(get(api("/impact?ref=source:src:file:paper-b:v1"))._2.contains("\"allFlagged\":true"))
    assertEquals(
      post(
        api("/sources/src:file:paper-b/status"),
        """{"status":"active","reason":"restored","by":"operator"}"""
      )._1,
      200
    )
  }

  test("a finding reproposed by a fault flags the assessments made from it") {
    val b = get(api("/sources/src:file:paper-b"))._2
    assert(
      !b.contains("\"status\":{\"type\":\"retracted\"}"),
      "Paper B is still retracted from the first test"
    )
    val id = approvedDesign()
    assertEquals(
      post(
        api("/faults:inject"),
        s"""{"target":{"kind":"finding","id":"$A"},"mutation":"endpoint_swap"}"""
      )._1,
      202
    )
    val flagged = eventually("the assessment of Paper A is flagged", within = 5.minutes) {
      val a = get(api(s"/assessments/$id:q4:$A"))._2
      Option.when(a.contains("\"flags\":[{") && a.contains(s""""id":"$A""""))(a)
    }
    assert(flagged.contains("\"kind\":{\"type\":\"finding\"}"), flagged)
    val impact = get(api(s"/impact?ref=finding:$A:v1"))._2
    assert(
      impact.contains("\"kind\":\"assessment\"") && impact.contains("\"kind\":\"package\""),
      impact
    )
  }
