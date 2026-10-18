package com.thinkmorestupidless.experimentproposals.features.steps

import com.thinkmorestupidless.ankka.testkit.GherkinSuite

/**
 * Steps for `features/evidence/extraction.feature` and `features/evidence/verification.feature`.
 */
trait EvidenceSteps extends GherkinSuite with CommonSteps:

  protected var sourceId: String       = ""
  protected var recordId: String       = ""
  protected var lastRecordJson: String = ""

  protected val fixtureOf: Map[String, String] =
    Map("Paper A" -> "paper-a.xml", "Paper B" -> "paper-b.xml", "Paper C" -> "paper-c.xml")

  protected def ingestPaper(name: String): String =
    val file = fixtureOf.getOrElse(name, fail(s"no fixture for $name"))
    sourceId = ingest(file, extract = true)
    // An earlier scenario may have changed the shared fixture's status (impact.feature); a scenario
    // starts from the fixture as published, so the status is put back by the operator.
    val doc = get(s"/sources/$sourceId")._2
    if doc.contains("\"status\":{\"type\":") then
      val (status, body) = post(
        s"/sources/$sourceId/status",
        """{"status":"active","reason":"restored for the next scenario","by":"operator"}"""
      )
      assertEquals(status, 200, body)
    sourceId

  protected def settled(source: String): String =
    eventually(s"the records of $source leave proposed") {
      val (_, rows) = get(s"/sources/$source/records")
      Option.when(rows.contains("\"id\":") && !rows.contains("\"status\":\"proposed\""))(rows)
    }

  protected def recordJson(id: String): String =
    val path = if id.contains(":fd") then s"/findings/$id" else s"/claims/$id"
    get(path)._2

  protected def findings(source: String): Vector[String] =
    objectsOf(settled(source))
      .filter(_.contains("\"kind\":\"finding\""))
      .flatMap(r => """"id":"([^"]+)"""".r.findFirstMatchIn(r).map(_.group(1)))
      .map(recordJson)

  protected def claims(source: String): Vector[String] =
    objectsOf(settled(source))
      .filter(_.contains("\"kind\":\"claim\""))
      .flatMap(r => """"id":"([^"]+)"""".r.findFirstMatchIn(r).map(_.group(1)))
      .map(recordJson)

  protected def postClaim(
      source: String,
      proposition: String,
      sectionId: String,
      quotation: String,
      offsets: Option[(Int, Int)] = None
  ): String =
    val span = offsets match
      case Some((a, b)) =>
        s"""{"sectionId":"$sectionId","quotation":${quote(quotation)},"start":$a,"end":$b}"""
      case None => s"""{"sectionId":"$sectionId","quotation":${quote(quotation)}}"""
    val (status, body) = post(
      "/claims",
      s"""{"sourceId":"$source","version":1,"proposition":${quote(
          proposition
        )},"kind":"normalised_claim","spans":[$span],"reviewer":"demo-reviewer"}"""
    )
    assertEquals(status, 201, body)
    recordId = """"id":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(body))
    recordId

  protected def awaitStatus(id: String, statuses: String*): String =
    lastRecordJson = eventually(s"$id reaches one of ${statuses.mkString(", ")}") {
      val r = recordJson(id)
      Option.when(statuses.exists(s => r.contains(s""""status":{"type":"$s"}""")))(r)
    }
    lastRecordJson

  // ── extraction.feature ───────────────────────────────────────────────────────

  Given("the source {string} reporting {string}") { (name: String, _: String) =>
    ingestPaper(name)
  }

  When("findings and claims are extracted") { () =>
    settled(sourceId)
  }

  Then("a finding with the endpoint {string} is proposed") { (endpoint: String) =>
    assert(
      findings(sourceId).exists(_.contains(s""""observedEndpoint":"$endpoint"""")),
      findings(sourceId).mkString("\n")
    )
  }

  Then("no claim of lipid accumulation is derived from the source") { () =>
    val lipidClaims = claims(sourceId).filter(c =>
      c.contains("\"proposition\":\"") && c.toLowerCase.contains("accumulate lipid")
    )
    assert(lipidClaims.isEmpty, lipidClaims.mkString("\n"))
  }

  Then("the finding records the lipid accumulation outcome as {string}") { (status: String) =>
    val f = findings(sourceId).head
    assert(f.contains(s""""lipid accumulation":{"type":"${status.replace(' ', '_')}"}"""), f)
  }

  Then("the finding records the species of the source") { () =>
    val f = findings(sourceId).head
    assert(f.contains("\"species\":\"human\""), f)
  }

  Then("the normalised claim retains that species as a qualification") { () =>
    val c =
      claims(sourceId).find(_.contains("normalised_claim")).getOrElse(fail("no normalised claim"))
    assert(c.contains("species: human") && c.contains("\"speciesScope\":\"human\""), c)
  }

  Then("the finding records the outcome {string} under the tested conditions") {
    (outcome: String) =>
      val f = findings(sourceId).head
      assert(
        f.contains(s""""outcomeDirection":{"type":"$outcome"}""") && f.contains(
          "\"conditions\":[\""
        ),
        f
      )
  }

  Then("the finding is retained") { () =>
    val f = findings(sourceId).head
    assert(
      f.contains("\"registered\":true") && !f.contains("\"status\":{\"type\":\"quarantined\"}"),
      f
    )
  }

  Given("a source with findings extracted") { () =>
    ingestPaper("Paper A")
    settled(sourceId)
  }

  When("the cited source spans are read") { () =>
    lastRecordJson = (findings(sourceId) ++ claims(sourceId)).mkString("\n")
  }

  Then("every source span resolves to the text of the source") { () =>
    assert(
      lastRecordJson.contains("\"resolves\":true") && !lastRecordJson.contains(
        "\"resolves\":false"
      ),
      lastRecordJson
    )
  }

  // ── verification.feature ─────────────────────────────────────────────────────

  Given("a proposed claim whose cited source span resolves but does not support it") { () =>
    ingestPaper("Paper A"); settled(sourceId)
    postClaim(
      sourceId,
      "Duck hepatocytes accumulate lipid in a defined serum-free medium",
      "sec-4",
      "Duck hepatocytes proliferated under the defined conditions"
    )
  }

  When("the claim is verified") { () =>
    awaitStatus(recordId, "requires_review", "rejected", "verified", "quarantined")
  }

  Then("the claim is labelled {string} or {string}") { (a: String, b: String) =>
    assert(
      lastRecordJson.contains(s""""label":{"type":"${a.replace(' ', '_')}"}""") || lastRecordJson
        .contains(s""""label":{"type":"${b.replace(' ', '_')}"}"""),
      lastRecordJson
    )
  }

  Then("the claim is not admitted as affirmative evidence") { () =>
    assert(!lastRecordJson.contains("\"status\":{\"type\":\"verified\"}"), lastRecordJson)
  }

  Given("a proposed claim with a source span that does not resolve") { () =>
    ingestPaper("Paper A"); settled(sourceId)
    postClaim(
      sourceId,
      "Duck hepatocytes grow forever",
      "sec-4",
      "Duck hepatocytes proliferated",
      Some((500, 530))
    )
  }

  When("the claim is validated") { () =>
    awaitStatus(recordId, "quarantined", "requires_review", "rejected", "verified")
  }

  Then("validation fails before verification") { () =>
    assert(
      lastRecordJson.contains("\"passed\":false") && !lastRecordJson.contains("\"verification\":{"),
      lastRecordJson
    )
  }

  Then("the claim is quarantined with the defect {string}") { (defect: String) =>
    assert(
      lastRecordJson.contains("\"status\":{\"type\":\"quarantined\"}") && lastRecordJson.contains(
        s""""kind":{"type":"${defect.replace(' ', '_')}"}"""
      ),
      lastRecordJson
    )
  }

  Given("a proposed finding that failed verification with a retryable defect") { () =>
    ingestPaper("Paper B"); settled(sourceId)
    recordId = s"$sourceId:v1:fd1"
    val (status, body) = post(
      "/faults:inject",
      s"""{"target":{"kind":"finding","id":"$recordId"},"mutation":"condition_drop"}"""
    )
    assertEquals(status, 202, body)
  }

  When("the extraction is retried") { () =>
    lastRecordJson = eventually("the finding is proposed again and verified") {
      val r = recordJson(recordId)
      // `proposed` is the default and is omitted from the JSON, so wait for a status that is written.
      Option.when(
        "\"n\":".r.findAllIn(r).size >= 3 && Seq(
          "verified",
          "rejected",
          "quarantined",
          "requires_review"
        ).exists(st => r.contains(s""""status":{"type":"$st"}"""))
      )(r)
    }
  }

  Then("the retry is triggered by the classified defect") { () =>
    assert(
      lastRecordJson.contains("\"triggeredBy\":{\"kind\":{\"type\":\"context_mismatch\"}"),
      lastRecordJson
    )
  }

  Then("the number of retries is bounded") { () =>
    assert("\"n\":".r.findAllIn(lastRecordJson).size <= 3, lastRecordJson)
  }

  Then("the failed attempt is kept in the history") { () =>
    assert(lastRecordJson.contains("\"outcome\":{\"type\":\"rejected\"}"), lastRecordJson)
  }

  Then("the retry is verified against the source and not against an earlier summary") { () =>
    val last = """"verification":\{"attempt":(\d+)""".r
      .findAllMatchIn(lastRecordJson)
      .map(_.group(1).toInt)
      .maxOption
      .getOrElse(0)
    assert(last >= 3, s"no verification of the retried attempt in $lastRecordJson")
    val from = lastRecordJson.indexOf(s""""verification":{"attempt":$last""")
    assert(
      lastRecordJson.indexOf("\"contextSectionIds\":[\"sec-", from) > from,
      lastRecordJson.substring(from).take(600)
    )
  }

  Given("a proposed claim whose verification is ambiguous") { () =>
    ingestPaper("Paper B"); settled(sourceId)
    postClaim(
      sourceId,
      "Avian hepatocytes may accumulate lipid under fatty acid loading",
      "sec-6",
      "Transfer of these findings to avian hepatocytes is plausible on mechanistic grounds but was not tested here."
    )
  }

  When("verification completes") { () =>
    awaitStatus(recordId, "requires_review", "rejected", "verified", "quarantined")
  }

  Then("the claim requires review by a reviewer") { () =>
    assert(lastRecordJson.contains("\"status\":{\"type\":\"requires_review\"}"), lastRecordJson)
  }

  Then("the claim is not accepted automatically") { () =>
    assert(!lastRecordJson.contains("\"status\":{\"type\":\"verified\"}"), lastRecordJson)
  }
