package com.thinkmorestupidless.experimentproposals.features.steps

import com.thinkmorestupidless.experimentproposals.domain.design.Comparison
import com.thinkmorestupidless.ankka.testkit.GherkinSuite

/** Steps for `features/design/candidates.feature` (US7) and `experimental-design.feature` (US8). */
trait DesignSteps extends GherkinSuite with SearchSteps:

  protected var approachesJson: String = ""
  protected var decisionJson: String   = ""

  private def investigationView(): String = get(s"/workflows/investigation/$requestId")._2

  private def approachIds: Vector[String] =
    objectsOf(approachesJson.substring(approachesJson.indexOf("\"approaches\":[") + 14))
      .filter(_.contains("\"hypothesis\":"))
      .flatMap(a => """"id":"([^"]+)"""".r.findFirstMatchIn(a).map(_.group(1)))

  /**
   * The package is built; the candidates follow in the same chain, so the Given waits only for the
   * package.
   */
  protected def packageFor(questionText: String): Unit =
    Seq("Paper A", "Paper B", "Paper C").foreach(ingestPaper)
    referenceQuestion(questionText)
    assessRecords(
      Seq("src:file:paper-a:v1:fd1", "src:file:paper-b:v1:fd1", "src:file:paper-c:v1:fd1")
    )
    val (status, body) = post(s"/requests/$requestId/packages", """{"reviewer":"demo-reviewer"}""")
    assertEquals(status, 202, body)
    eventually("the lipid package is valid") {
      val (s, p) = get(s"/packages/$questionId:pk1")
      Option.when(s == 200 && p.contains("\"qualityStatus\":{\"type\":\"valid\"}"))(p)
    }

  protected def candidatesGenerated(): String =
    approachesJson = eventually("the candidates are compared") {
      val w = investigationView()
      Option.when(w.contains("awaiting-decision"))(get(s"/requests/$requestId/approaches")._2)
    }
    approachesJson

  // ── candidates.feature ───────────────────────────────────────────────────────

  Given("a valid evidence package for the scientific question {string}") { (text: String) =>
    packageFor(text)
  }

  When("candidate approaches are generated")(() => candidatesGenerated())

  Then("at least two candidate approaches are recorded") { () =>
    assert(approachIds.size >= 2, approachesJson)
  }

  Then("each candidate approach lists its supporting, contrary and missing evidence") { () =>
    val approaches = objectsOf(
      approachesJson.substring(approachesJson.indexOf("\"approaches\":[") + 14)
    ).filter(_.contains("\"hypothesis\":"))
    approaches.foreach { a =>
      // An empty list is written as the absence of the field; what matters is that each kind is addressed.
      assert(
        a.contains("\"affirmativeClaimRefs\":[") || a.contains("\"indirectClaimRefs\":[") || a
          .contains("\"contraryClaimRefs\":["),
        a
      )
      assert(a.contains("\"missingEvidence\":[\""), a)
    }
  }

  Then("each assumption without evidence is marked {string}") { (mark: String) =>
    assert(approachesJson.contains(s""""evidenceStatus":{"type":"$mark"}"""), approachesJson)
    val statuses = """"evidenceStatus":\{"type":"([a-z]+)"\}""".r
      .findAllMatchIn(approachesJson)
      .map(_.group(1))
      .toSet
    assert(statuses.subsetOf(Set("established", "assumed", "unknown")), statuses)
  }

  Given("two candidate approaches") { () =>
    packageFor(
      "Has relevant lipid accumulation been demonstrated in appropriate avian cell models?"
    )
    candidatesGenerated()
    assert(approachIds.size >= 2, approachesJson)
  }

  When("the candidate approaches are compared") { () =>
    assert(approachesJson.contains("\"comparison\":{"), approachesJson)
  }

  Then("each comparison criterion is shown with its rationale") { () =>
    Comparison.CriterionNames.foreach(n =>
      assert(approachesJson.contains(s""""name":"$n""""), s"$n missing")
    )
    val comparison = approachesJson.substring(approachesJson.indexOf("\"comparison\":{"))
    assert(
      "\"rationale\":\"[^\"]+\"".r.findAllIn(comparison).size >= Comparison.CriterionNames.size * 2,
      comparison
    )
  }

  Then("no numeric success probability is asserted") { () =>
    assert(!approachesJson.toLowerCase.contains("probability"), approachesJson)
    assert(
      !approachesJson.matches("(?s).*\\d+(\\.\\d+)?\\s?% (chance|probability|likelihood).*"),
      approachesJson
    )
  }

  Given("two candidate approaches that have been compared") { () =>
    packageFor(
      "Has relevant lipid accumulation been demonstrated in appropriate avian cell models?"
    )
    candidatesGenerated()
    assert(approachesJson.contains("\"comparison\":{"), approachesJson)
  }

  When("the reviewer selects one candidate approach") { () =>
    val ids      = approachIds
    val selected = ids.head
    val rejected = ids.tail
      .map(id => s"""{"approachId":${quote(id)},"reason":"not the target species"}""")
      .mkString(",")
    val (status, body) = post(
      s"/requests/$requestId/decide",
      s"""{"selectedApproachId":${quote(
          selected
        )},"rejected":[$rejected],"decidedBy":"demo-reviewer","rationale":"the target species comes first"}"""
    )
    assertEquals(status, 201, body)
    decisionJson = body
  }

  Then("the decision record names the selected candidate approach") { () =>
    assert(
      decisionJson.contains(
        s""""selectedApproachRef":{"kind":{"type":"approach"},"id":"${approachIds.head}""""
      ),
      decisionJson
    )
    assert(decisionJson.contains("\"decidedBy\":\"demo-reviewer\""), decisionJson)
  }

  Then("the rejected candidate approaches and their reasons remain retrievable") { () =>
    val after = get(s"/requests/$requestId/approaches")._2
    approachIds.tail.foreach(id => assert(after.contains(s""""id":"$id""""), after))
    assert(
      after.contains("\"rejectionReason\":\"not the target species\"") && after.contains(
        "\"status\":{\"type\":\"rejected\"}"
      ),
      after
    )
    assert(after.contains("\"reason\":\"not the target species\""), after)
  }

  // ── experimental-design.feature ──────────────────────────────────────────────

  protected var designJson: String  = ""
  protected var renderingMd: String = ""
  protected var designVersion: Int  = 0
  protected def designId: String    = s"$requestId:design"

  protected def selectApproach(): Unit =
    packageFor(
      "Has relevant lipid accumulation been demonstrated in appropriate avian cell models?"
    )
    candidatesGenerated()
    val ids = approachIds
    val rejected = ids.tail
      .map(id => s"""{"approachId":${quote(id)},"reason":"not the target species"}""")
      .mkString(",")
    val (status, body) = post(
      s"/requests/$requestId/decide",
      s"""{"selectedApproachId":${quote(
          ids.head
        )},"rejected":[$rejected],"decidedBy":"demo-reviewer","rationale":"the target species comes first"}"""
    )
    assertEquals(status, 201, body)

  protected def designGenerated(): String =
    designJson = eventually("the design is under review") {
      val w = investigationView()
      Option.when(w.contains("awaiting-approval"))(get(s"/designs/$designId")._2)
    }
    designVersion = """"version":(\d+),"approachRef"""".r
      .findAllMatchIn(designJson)
      .map(_.group(1).toInt)
      .toVector
      .lastOption
      .getOrElse(fail(designJson))
    designJson

  private def manifestClaimIds: Vector[String] =
    val manifest = designJson.substring(designJson.indexOf("\"evidenceManifest\":{"))
    """"id":"([^"]+)"""".r
      .findAllMatchIn(manifest.substring(0, manifest.indexOf("\"spans\":[")))
      .map(_.group(1))
      .toVector
      .distinct

  Given("a selected candidate approach")(() => selectApproach())

  When("the experimental design is generated")(() => designGenerated())

  Then("the experimental design has all twelve required sections") { () =>
    com.thinkmorestupidless.experimentproposals.domain.design.DesignSections.Names.foreach(n =>
      assert(designJson.contains(s""""$n":"""), s"$n missing in $designJson")
    )
    assert(designJson.contains("\"status\":{\"type\":\"under_review\"}"), designJson)
  }

  Then("every evidence reference in it resolves to a verified claim and a source span") { () =>
    val claims = manifestClaimIds
    assert(claims.nonEmpty, designJson)
    claims.foreach { id =>
      val (status, claim) = get(s"/claims/$id")
      assertEquals(status, 200, claim)
      assert(claim.contains("\"status\":{\"type\":\"verified\"}"), claim)
      assert(claim.contains("\"resolves\":true"), claim)
    }
    assert(designJson.contains("\"spans\":[{\"sourceId\":"), designJson)
    assert(designJson.contains("\"name\":\"design.spans-resolve@v1\",\"passed\":true"), designJson)
  }

  Given("a generated experimental design") { () =>
    selectApproach()
    designGenerated()
  }

  When("the experimental design is rendered") { () =>
    val (status, md) = get(s"/designs/$designId?format=md&version=$designVersion")
    assertEquals(status, 200, md)
    renderingMd = md
  }

  Then("the rendering is produced from the structured experimental design") { () =>
    val hypothesis = """"hypothesis":"([^"]{20,60})""".r
      .findFirstMatchIn(designJson)
      .map(_.group(1))
      .getOrElse(fail(designJson))
    assert(renderingMd.contains(hypothesis), renderingMd)
    assert(
      renderingMd.contains("## 12. Evidence manifest") && renderingMd.contains(
        "## 1. Question and scope"
      ),
      renderingMd
    )
  }

  Then("the rendering contains no claim absent from the experimental design") { () =>
    val referenced = com.thinkmorestupidless.experimentproposals.domain.design.Rendering
      .claimsReferenced(renderingMd)
    assert(referenced.nonEmpty, renderingMd)
    val outside = referenced.filterNot(manifestClaimIds.contains)
    assert(outside.isEmpty, s"claims outside the manifest: $outside")
  }

  Given("an experimental design under review") { () =>
    selectApproach()
    designGenerated()
    assert(designJson.contains("\"status\":{\"type\":\"under_review\"}"), designJson)
  }

  When("the reviewer rejects the experimental design with a reason") { () =>
    val (status, body) = post(
      s"/designs/$designId/approval",
      s"""{"version":$designVersion,"decision":"rejected","reviewer":"demo-reviewer","rationale":"the inconclusive criterion has no threshold"}"""
    )
    assertEquals(status, 201, body)
    designJson = body
    renderingMd = get(s"/designs/$designId?format=md&version=$designVersion")._2
  }

  Then("the rejection, the reviewer, the time and the reason are visible") { () =>
    assert(
      designJson.contains("\"decision\":{\"type\":\"rejected\"}") && designJson.contains(
        "\"reviewer\":\"demo-reviewer\""
      ),
      designJson
    )
    assert(
      designJson.contains(
        "\"rationale\":\"the inconclusive criterion has no threshold\""
      ) && designJson.contains("\"at\":\"20"),
      designJson
    )
    assert(renderingMd.contains("**rejected** by demo-reviewer"), renderingMd)
  }

  Then("the experimental design is not presented as approved") { () =>
    assert(
      designJson.contains("\"status\":{\"type\":\"rejected\"}") && !designJson.contains(
        "\"type\":\"approved\""
      ),
      designJson
    )
    assert(!renderingMd.contains("**Approved**"), renderingMd)
  }

  Given("an experimental design that is not approved") { () =>
    selectApproach()
    designGenerated()
  }

  When("the experimental design is presented") { () =>
    renderingMd = get(s"/designs/$designId?format=md")._2
  }

  Then("it is marked as a proposal awaiting review") { () =>
    assert(renderingMd.contains("Proposal awaiting review"), renderingMd)
  }

  Then("no detailed wet-lab procedure is presented as authorised") { () =>
    assert(renderingMd.contains("Nothing in this document is an authorised procedure"), renderingMd)
    assert(
      !renderingMd.contains("**Approved**") && !renderingMd.toLowerCase.contains(
        "authorised procedure:"
      ),
      renderingMd
    )
  }
