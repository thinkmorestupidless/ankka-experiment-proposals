package com.thinkmorestupidless.experimentproposals.features.steps

import com.thinkmorestupidless.ankka.testkit.GherkinSuite

import scala.concurrent.duration.*

/** Steps for `features/assurance/impact.feature`. */
trait ImpactSteps extends GherkinSuite with AssuranceSteps:

  private var approvedVersions: String = ""
  private var impactJson: String       = ""

  private def versionsOf(design: String): String =
    design.substring(design.indexOf("\"versions\":["), design.indexOf("\"approvals\":"))

  private def markSource(name: String, status: String): Unit =
    val id = s"src:file:${fixtureOf(name).stripSuffix(".xml")}"
    // The dependents view is eventually consistent; the walk reads it when the change arrives.
    testKit.eventually(s"the dependents of $name are projected", 3.minutes) {
      val (_, j) = get(s"/impact?ref=source:$id:v1")
      Option.when(
        Seq(designId, s"$questionId:pk1", s"$requestId:c1").forall(x =>
          j.contains(s""""id":"$x"""")
        )
      )(j)
    }
    val (code, body) = post(
      s"/sources/$id/status",
      s"""{"status":"$status","reason":"publisher notice","by":"demo-reviewer"}"""
    )
    assertEquals(code, 200, body)

  // ── impact.feature ───────────────────────────────────────────────────────────

  Given("an approved experimental design that depends on the source {string}") { (name: String) =>
    assert(fixtureOf.contains(name), name)
    selectApproach()
    designGenerated()
    val (status, body) = post(
      s"/designs/$designId/approval",
      s"""{"version":$designVersion,"decision":"approved","reviewer":"demo-reviewer","rationale":"ready"}"""
    )
    assertEquals(status, 201, body)
    designJson = testKit.eventually("the design is approved", 2.minutes) {
      val d = get(s"/designs/$designId")._2
      Option.when(d.contains("\"status\":{\"type\":\"approved\"}"))(d)
    }
    assert(
      designJson.contains(s"src:file:${fixtureOf(name).stripSuffix(".xml")}"),
      s"the design does not cite $name: $designJson"
    )
    approvedVersions = versionsOf(designJson)
  }

  When("the source {string} is marked {string}") { (name: String, status: String) =>
    markSource(name, status)
  }

  Then(
    "every dependent assessment, evidence package, candidate approach and experimental design is flagged for review"
  ) { () =>
    impactJson = testKit.eventually("every dependent of Paper B is flagged", 6.minutes) {
      val (_, j) = get("/impact?ref=source:src:file:paper-b:v1")
      Option.when(
        j.contains("\"allFlagged\":true") && Seq("assessment", "package", "approach", "design")
          .forall(k => j.contains(s""""kind":"$k""""))
      )(j)
    }
    assert(impactJson.contains(s""""id":"$designId""""), impactJson)
    assert(!impactJson.contains("\"flagged\":false"), impactJson)
  }

  Given("the source {string} has been marked {string}") { (name: String, status: String) =>
    markSource(name, status)
    testKit.eventually("the design is flagged", 6.minutes) {
      val d = get(s"/designs/$designId")._2
      Option.when(d.contains("\"flags\":[{"))(d)
    }
  }

  When("the flagged experimental design is inspected") { () =>
    designJson = get(s"/designs/$designId")._2
    assert(designJson.contains("\"flags\":[{"), designJson)
  }

  Then("the approved version is unchanged") { () =>
    assertEquals(versionsOf(designJson), approvedVersions)
    assert(designJson.contains("\"status\":{\"type\":\"approved\"}"), designJson)
  }

  Then("the flag names the changed source version") { () =>
    assert(
      designJson.contains(
        "\"changedRef\":{\"kind\":{\"type\":\"source\"},\"id\":\"src:file:paper-b\",\"version\":1}"
      ),
      designJson
    )
    assert(designJson.contains("\"reason\":\"the source's status changed to retracted"), designJson)
  }
