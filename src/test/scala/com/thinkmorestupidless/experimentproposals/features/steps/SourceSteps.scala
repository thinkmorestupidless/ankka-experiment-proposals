package com.thinkmorestupidless.experimentproposals.features.steps

import com.thinkmorestupidless.ankka.testkit.GherkinSuite

import java.nio.file.Path

/** Steps for `features/sources/ingestion.feature`: identity, duplicates, flags, units, statuses. */
trait SourceSteps extends GherkinSuite with CommonSteps:

  private var fixture: String          = ""
  private var markedStatus: String     = ""
  private var ingestedSource: String   = ""
  private var sourceJson: String       = ""
  private var secondIngestion: String  = ""
  private var measurementsJson: String = ""

  private def sourceDocument(id: String): String = get(s"/sources/$id")._2

  Given("a literature source with a persistent identifier") { () => fixture = "paper-a.xml" }

  Given("a literature source that has been ingested") { () =>
    fixture = "paper-a.xml"
    ingestedSource = ingest(fixture)
  }

  Given("a source with a table that cannot be parsed reliably") { () => fixture = "paper-d.xml" }

  Given("an internal measurement with a unit") { () => fixture = "internal" }

  Given("a source marked {string}") { (status: String) =>
    markedStatus = status
    fixture = status match
      case "retracted"  => "retracted.xml"
      case "corrected"  => "corrected.txt"
      case "superseded" => "superseded.txt"
      case other        => fail(s"no fixture marked $other")
  }

  Given("a literature source whose full text cannot be fetched") { () =>
    fixture = "abstract-only.txt"
  }

  Given("a source whose extraction is corrupt or materially ambiguous") { () =>
    fixture = "corrupt.xml"
  }

  When("the source is ingested") { () =>
    ingestedSource = ingest(fixture)
    sourceJson = sourceDocument(ingestedSource)
  }

  /** The same file under another spelling of its path: a new workflow, the same content. */
  When("the same source is ingested again") { () =>
    val absolute = Path.of(s"evals/fixtures/papers/$fixture").toAbsolutePath.toString
    val (status, body) =
      post("/sources:ingest", s"""{"adapter":"file","identifier":"$absolute","extract":false}""")
    assertEquals(status, 202, body)
    val workflowId =
      """"workflowId":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(body))
    secondIngestion = eventually(s"ingestion $workflowId ends") {
      val (_, w) = get(s"/workflows/ingestion/$workflowId")
      Option.when(w.contains("\"isTerminal\":true"))(w)
    }
    sourceJson = sourceDocument(ingestedSource)
  }

  When("the measurement is ingested") { () =>
    val (status, body) =
      post(
        "/sources:ingest-internal",
        """{"manifestPath":"evals/fixtures/internal/manifest.json"}"""
      )
    assertEquals(status, 202, body)
    """"workflowId":"([^"]+)"""".r.findAllMatchIn(body).map(_.group(1)).foreach { id =>
      eventually(s"ingestion $id ends") {
        val (_, w) = get(s"/workflows/ingestion/$id")
        Option.when(w.contains("\"isTerminal\":true"))(w)
      }
    }
    measurementsJson = eventually("the measurement rows") {
      val (_, rows) = get("/measurements")
      Option.when(rows.contains("S-001") && rows.contains("S-005"))(rows)
    }
  }

  Then(
    "its identifier, title, authors, date, venue, location, licence, status, retrieval time, content hash, source version and parse version are recorded"
  ) { () =>
    Seq(
      "\"identifiers\":{\"doi\":",
      "\"title\":\"",
      "\"authors\":[\"",
      "\"publishedAt\":\"",
      "\"venue\":\"",
      "\"sourceUrl\":\"",
      "\"licence\":\"",
      "\"retrievedAt\":\"",
      "\"contentHash\":\"",
      "\"version\":1",
      "\"parseVersion\":\"jats@v1\""
    ).foreach(field => assert(sourceJson.contains(field), s"$field missing in $sourceJson"))
    // `active` is the default and is written as the absence of another status.
    assert(!sourceJson.contains("\"status\":{\"type\":"), sourceJson)
  }

  Then("it is recognised by its identifier and content hash") { () =>
    assert(secondIngestion.contains(ingestedSource), secondIngestion)
    assert(
      secondIngestion.contains("\"status\":\"duplicate\"") || secondIngestion.contains(
        "duplicateOf"
      ),
      secondIngestion
    )
  }

  Then("no duplicate record is created") { () =>
    assertEquals("\"version\":1".r.findAllIn(sourceJson).size, 1, sourceJson)
    assert(!sourceJson.contains("\"version\":2"), sourceJson)
    val (_, listed) = get("/sources")
    assertEquals(s""""id":"$ingestedSource"""".r.findAllIn(listed).size, 1, listed)
  }

  Then("the source carries a parse-completeness flag naming the unresolved table") { () =>
    assert(sourceJson.contains("\"completeness\":{\"type\":\"partial\"}"), sourceJson)
    assert(
      sourceJson.contains("\"kind\":\"table_unresolved\"") && sourceJson.contains("Table 2"),
      sourceJson
    )
  }

  Then("the unit is validated") { () =>
    val rows = objectsOf(measurementsJson)
    val s1   = rows.find(_.contains("S-001")).getOrElse(fail(measurementsJson))
    val s5   = rows.find(_.contains("S-005")).getOrElse(fail(measurementsJson))
    assert(s1.contains("\"unitValid\":true"), s1)
    assert(s5.contains("\"unitValid\":false"), s5)
    assert(sourceDocument("src:internal:lab-lims:measurements").contains("unit_invalid"))
  }

  Then("no conversion is applied") { () =>
    val rows = objectsOf(measurementsJson)
    val s1   = rows.find(_.contains("S-001")).get
    val s5   = rows.find(_.contains("S-005")).get
    assert(s1.contains("\"value\":21.5") && s1.contains("\"unit\":\"ug/mg\""), s1)
    assert(s5.contains("\"value\":93.0") && s5.contains("\"unit\":\"ug per mg\""), s5)
  }

  Then("the status is recorded") { () =>
    assert(sourceJson.contains(s""""status":{"type":"$markedStatus"}"""), sourceJson)
  }

  Then("the status is surfaced to eligibility") { () =>
    assert(sourceJson.contains(s"eligibility.$markedStatus"), sourceJson)
    val blocked = markedStatus != "corrected"
    assert(sourceJson.contains(s""""eligible":${!blocked}"""), sourceJson)
  }

  Then("its metadata and abstract are retained") { () =>
    assert(sourceJson.contains("\"title\":\"Lipid metabolism in waterfowl liver"), sourceJson)
    assert(sourceJson.contains("\"authors\":[\"Fisher T.\"]"), sourceJson)
    assert(sourceJson.contains("\"abstractText\":\"A review of lipid metabolism"), sourceJson)
  }

  Then("the source is marked {string}") { (mark: String) =>
    val wire = mark.replace('-', '_')
    assert(sourceJson.contains(s""""retention":{"type":"$wire"}"""), sourceJson)
  }

  Then("the extraction is quarantined with a reason") { () =>
    assert(sourceJson.contains("\"quarantined\":true"), sourceJson)
    assert(
      sourceJson.contains("\"quarantineReason\":\"the content could not be parsed"),
      sourceJson
    )
  }

  Then("nothing is dropped silently") { () =>
    // The fetched content is retained under its hash, the issue is named, the run records the stage.
    assert(
      sourceJson.contains("\"contentHash\":\"") && sourceJson.contains("\"kind\":\"corrupt\""),
      sourceJson
    )
    assert(
      sourceJson.contains("\"eligible\":false") && sourceJson.contains("eligibility.quarantined"),
      sourceJson
    )
    val (_, run) = get(s"/runs/run-ingest:file:$fixture")
    assert(run.contains("\"stage\":\"parse\""), run)
  }
