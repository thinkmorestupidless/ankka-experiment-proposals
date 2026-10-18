package com.thinkmorestupidless.experimentproposals.integration

/**
 * US3 through the whole service with the cassettes as the scripted model: extraction, gates,
 * verification, retry, review.
 */
class ExtractionSuite extends ServiceFixture:

  override def beforeAll(): Unit =
    super.beforeAll()
    scriptFromCassettes("extract@v1", "verify@v1", "reextract@v1")

  private def claimBody(
      sourceId: String,
      proposition: String,
      sectionId: String,
      quotation: String,
      offsets: Option[(Int, Int)] = None
  ) =
    val span = offsets match
      case Some((a, b)) =>
        s"""{"sectionId":"$sectionId","quotation":"$quotation","start":$a,"end":$b}"""
      case None => s"""{"sectionId":"$sectionId","quotation":"$quotation"}"""
    s"""{"sourceId":"$sourceId","version":1,"proposition":"$proposition","kind":"normalised_claim","spans":[$span],"reviewer":"ada"}"""

  test(
    "the three papers are extracted with distinct endpoints, species kept, a failure kept, and every span resolving; all verified"
  ) {
    val a = ingestFixture("paper-a.xml", extract = true)
    val b = ingestFixture("paper-b.xml", extract = true)
    val c = ingestFixture("paper-c.xml", extract = true)
    Seq(a, b, c).foreach(recordsSettled)
    val (_, fa) = get(api(s"/findings/$a:v1:fd1"))
    assert(
      fa.contains("\"observedEndpoint\":\"proliferation\"") && fa.contains(
        "not_established_by_assessed_material"
      ) && fa.contains("\"status\":{\"type\":\"verified\"}"),
      fa
    )
    assert(!fa.contains("\"resolves\":false"), fa)
    val (_, ca) = get(api(s"/claims/$a:v1:cl1"))
    assert(!ca.toLowerCase.contains("lipid accumulation\""), ca)
    val (_, fb) = get(api(s"/findings/$b:v1:fd1"))
    assert(fb.contains("\"species\":\"human\""), fb)
    val (_, cb) = get(api(s"/claims/$b:v1:cl1"))
    assert(cb.contains("species: human"), cb)
    val (_, fc) = get(api(s"/findings/$c:v1:fd1"))
    assert(
      fc.contains("\"outcomeDirection\":{\"type\":\"failure\"}") && fc.contains(
        "\"status\":{\"type\":\"verified\"}"
      ),
      fc
    )
    val (_, run) = get(api(s"/runs/run-$a:v1:fd1:a1"))
    assert(run.contains("\"stage\":\"validate\"") && run.contains("\"stage\":\"verify\""), run)
  }

  test(
    "a span that does not resolve is quarantined before any verification; a span that does not support its claim is not admitted"
  ) {
    val a = ingestFixture("paper-a.xml", extract = true)
    recordsSettled(a)
    val calls = model.callCount
    val (s1, broken) = post(
      api("/claims"),
      claimBody(
        a,
        "Duck hepatocytes grow forever",
        "sec-4",
        "Duck hepatocytes proliferated",
        Some((500, 530))
      )
    )
    assertEquals(s1, 201, broken)
    val brokenId = """"id":"([^"]+)"""".r.findFirstMatchIn(broken).map(_.group(1)).get
    val q = eventually("the broken claim is quarantined") {
      val (_, r) = get(api(s"/claims/$brokenId"))
      Option.when(r.contains("\"status\":{\"type\":\"quarantined\"}"))(r)
    }
    assert(q.contains("span_unresolved"), q)
    assertEquals(
      model.callCount,
      calls,
      "no model call was made for a record that failed validation"
    )

    val (s2, unsupported) = post(
      api("/claims"),
      claimBody(
        a,
        "Duck hepatocytes accumulate lipid in a defined serum-free medium",
        "sec-4",
        "Duck hepatocytes proliferated under the defined conditions"
      )
    )
    assertEquals(s2, 201, unsupported)
    val unsupportedId = """"id":"([^"]+)"""".r.findFirstMatchIn(unsupported).map(_.group(1)).get
    val r = eventually("the unsupported claim is routed to review") {
      val (_, r) = get(api(s"/claims/$unsupportedId"))
      Option.when(r.contains("\"status\":{\"type\":\"requires_review\"}"))(r)
    }
    assert(r.contains("insufficiently_supported"), r)
    eventually("the review queue lists the claim") {
      val (_, queue) = get(api("/review-queue?kind=claim"))
      Option.when(queue.contains(unsupportedId))(queue)
    }
    val (s3, reviewed) = post(
      api(s"/claims/$unsupportedId/review"),
      """{"action":"reject","reviewer":"ada","rationale":"the span is about proliferation"}"""
    )
    assertEquals(s3, 201, reviewed)
    assert(reviewed.contains("\"status\":{\"type\":\"rejected\"}"), reviewed)
    assertEquals(
      post(
        api(s"/claims/$unsupportedId/review"),
        """{"action":"verify","reviewer":"mallory","rationale":"x"}"""
      )._1,
      400
    )
  }

  test(
    "a retryable defect is re-extracted from the source within the budget, every attempt kept; the service restarts in between"
  ) {
    val b = ingestFixture("paper-b.xml", extract = true)
    recordsSettled(b)
    val (status, started) = post(
      api("/faults:inject"),
      s"""{"target":{"kind":"finding","id":"$b:v1:fd1"},"mutation":"condition_drop"}"""
    )
    assertEquals(status, 202, started)
    restart()
    val f = eventually(
      "the finding is verified again after a retry",
      scala.concurrent.duration.DurationInt(90).seconds
    ) {
      val (_, r) = get(api(s"/findings/$b:v1:fd1"))
      Option.when(
        r.contains("\"attempts\":[") && "\"n\":".r.findAllIn(r).size == 3 && r.contains(
          "\"status\":{\"type\":\"verified\"}"
        )
      )(r)
    }
    assert(f.contains("context_mismatch"), f)
    assert(f.contains("\"outcome\":{\"type\":\"rejected\"}"), f)
    assert(f.contains("\"triggeredBy\":{\"kind\":{\"type\":\"context_mismatch\"}"), f)
    assert(f.contains("\"contextSectionIds\":[\"sec-"), f)
  }
