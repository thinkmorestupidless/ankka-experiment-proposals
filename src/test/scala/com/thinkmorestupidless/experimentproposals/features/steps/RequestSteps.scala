package com.thinkmorestupidless.experimentproposals.features.steps

import com.thinkmorestupidless.ankka.testkit.GherkinSuite

/**
 * Steps for `features/request/facets.feature`, in the glossary's words; the helpers name the
 * routes.
 */
trait RequestSteps extends GherkinSuite with CommonSteps:

  protected var requestId: String        = ""
  protected var requestText: String      = ""
  protected var facetUnderReview: String = ""
  protected var lastFacetsJson: String   = ""
  protected var lastRequestJson: String  = ""
  protected var lastProposalJson: String = ""

  // ── helpers ──────────────────────────────────────────────────────────────────

  protected def submitRequest(text: String): Unit =
    requestText = text
    val (status, body) =
      post("/requests", s"""{"text":${quote(text)},"submittedBy":${quote(scoped("customer"))}}""")
    assertEquals(status, 201, body)
    requestId = """"id":"([^"]+)"""".r.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(body))

  protected def awaitFacetReview(): Unit =
    eventually(s"the facets of $requestId are proposed") {
      val (_, w) = get(s"/workflows/investigation/$requestId")
      Option.when(w.contains("\"status\":\"Paused\"") && w.contains("awaiting-facet-review"))(w)
    }
    lastFacetsJson = get(s"/requests/$requestId/facets")._2
    lastRequestJson = get(s"/requests/$requestId")._2

  protected def facetJson(name: String): String =
    val facets  = get(s"/requests/$requestId/facets")._2
    val pattern = ("\\{[^{}]*\"name\":\"" + java.util.regex.Pattern.quote(name) + "\"").r
    // The facet objects are nested; cut the object by brace counting from its start.
    val start = facets.indexOf(s""""name":"$name"""")
    assert(start >= 0, s"no facet '$name' in $facets")
    val objStart = facets.lastIndexOf("{", start)
    var depth    = 0; var i = objStart; var end = -1
    while end < 0 && i < facets.length do
      facets(i) match
        case '{' => depth += 1
        case '}' => depth -= 1; if depth == 0 then end = i
        case _   => ()
      i += 1
    val _ = pattern
    facets.substring(objStart, end + 1)

  /** A field holding its default is omitted from the JSON, so absence means the default. */
  protected def enumIs(json: String, field: String, value: String, default: String): Boolean =
    val wire = value.replace('-', '_')
    json.contains(s""""$field":{"type":"$wire"}""") || (wire == default && !json.contains(
      s""""$field":"""
    ))

  protected def facetId(name: String): String =
    """"id":"([^"]+)"""".r
      .findFirstMatchIn(facetJson(name))
      .map(_.group(1))
      .getOrElse(fail(s"no id for $name"))

  protected def review(
      name: String,
      action: String,
      statement: Option[String] = None,
      reviewer: String = "demo-reviewer"
  ): Unit =
    val body =
      s"""{"action":"$action","reviewer":"$reviewer","rationale":"feature step"${statement.fold("")(
          s => s""","statement":${quote(s)}"""
        )}}"""
    val (status, reply) = post(s"/facets/${facetId(name)}/review", body)
    assertEquals(status, 201, reply)

  protected def rerunRoundTrip(): Unit =
    val before = """"run":(\d+)""".r
      .findFirstMatchIn(get(s"/requests/$requestId")._2)
      .map(_.group(1).toInt)
      .getOrElse(0)
    val (status, body) = post(s"/requests/$requestId/round-trip:rerun", "{}")
    assertEquals(status, 202, body)
    lastRequestJson = eventually("the round-trip check runs again") {
      val (_, r) = get(s"/requests/$requestId")
      val run    = """"run":(\d+)""".r.findFirstMatchIn(r).map(_.group(1).toInt).getOrElse(0)
      Option.when(run > before && r.contains("awaiting-facet-review"))(r)
    }

  protected def roundTrip: String =
    val i = lastRequestJson.indexOf("\"roundTrip\":")
    assert(i >= 0, s"no round-trip check in $lastRequestJson")
    lastRequestJson.substring(i)

  // ── steps ────────────────────────────────────────────────────────────────────

  Given("the customer request {string}") { (text: String) =>
    submitRequest(text)
  }

  When("the facets are extracted") { () =>
    awaitFacetReview()
  }

  Then("the stored customer request is identical to the entered text") { () =>
    assert(lastRequestJson.contains(s""""text":${quote(requestText)}"""), lastRequestJson)
  }

  Then("the facet {string} has the origin {string}") { (name: String, origin: String) =>
    val f = facetJson(name)
    assert(enumIs(f, "origin", origin, "inferred"), f)
  }

  Then("the facet {string} has the origin {string} and the status {string}") {
    (name: String, origin: String, status: String) =>
      val f = facetJson(name)
      assert(enumIs(f, "origin", origin, "inferred"), f)
      assert(enumIs(f, "status", status, "proposed"), f)
  }

  Given("an extracted facet that nothing in the customer request supports") { () =>
    submitRequest("Can you make foie gras?")
    awaitFacetReview()
    review("time", "amend", Some("Delivery to a lunar laboratory"))
    facetUnderReview = "time"
  }

  When("the round-trip check runs") { () =>
    rerunRoundTrip()
  }

  Then("the facet is reported as an unsupported addition") { () =>
    val rt = roundTrip
    assert(
      rt.contains("\"unsupportedAdditions\":[{") && rt.contains(
        s""""facetName":"$facetUnderReview""""
      ),
      rt
    )
  }

  Given("a part of the customer request that no extracted facet covers") { () =>
    submitRequest("Can you make foie gras?")
    awaitFacetReview()
    review("product", "reject")
  }

  Then("that part is reported as an omission") { () =>
    val rt = roundTrip
    assert(rt.contains("\"omissions\":[{") && rt.contains("foie gras"), rt)
  }

  Given(
    "a reconstruction that resembles the customer request and contains an unsupported addition"
  ) { () =>
    submitRequest("Can you make foie gras?")
    awaitFacetReview()
    review("time", "amend", Some("Delivery to a lunar laboratory"))
  }

  Then("the round-trip check fails") { () =>
    val rt = roundTrip
    assert(
      rt.contains("\"reconstruction\":\"Can you make foie gras") && !rt.contains("\"passed\":true"),
      rt
    )
  }

  Given("a facet with the status {string}") { (status: String) =>
    submitRequest("Can you make foie gras?")
    awaitFacetReview()
    facetUnderReview = status match
      case "proposed"   => "production approach"
      case "unresolved" => "biological source"
      case other        => fail(s"no reference facet with status $other")
  }

  When("the reviewer confirms the facet") { () =>
    review(facetUnderReview, "confirm")
  }

  Then("the facet has the origin {string}") { (origin: String) =>
    assert(
      enumIs(facetJson(facetUnderReview), "origin", origin, "inferred"),
      facetJson(facetUnderReview)
    )
  }

  Then("the reviewer and the time of the confirmation are recorded") { () =>
    val f = facetJson(facetUnderReview)
    assert(f.contains("\"reviewer\":\"demo-reviewer\"") && f.contains("\"at\":\"20"), f)
  }

  Then("the earlier state of the facet remains visible in its history") { () =>
    val f = facetJson(facetUnderReview)
    assert(
      f.contains("\"history\":[{") && f.contains(
        "\"statement\":\"Cell cultivation\",\"origin\":{\"type\":\"inferred\"}"
      ),
      f
    )
  }

  Given("a mandatory facet with the status {string}") { (status: String) =>
    submitRequest("Can you make foie gras?")
    awaitFacetReview()
    facetUnderReview = status match
      case "unresolved" => "biological source"
      case "proposed"   => "product"
      case other        => fail(s"no reference mandatory facet with status $other")
  }

  When("a proposal is produced") { () =>
    lastProposalJson = get(s"/requests/$requestId/proposal")._2
  }

  Then("the proposal lists the facet as unresolved") { () =>
    assert(
      lastProposalJson.contains(s""""unresolvedAndFlagged":[""") && lastProposalJson.contains(
        s""""$facetUnderReview""""
      ),
      lastProposalJson
    )
  }

  Then("the proposal assumes no value for the facet") { () =>
    assert(!lastProposalJson.contains(s""""$facetUnderReview":"""), lastProposalJson)
    assert(
      !lastProposalJson.contains(s""""assumedWithApproval":["$facetUnderReview"""),
      lastProposalJson
    )
  }
