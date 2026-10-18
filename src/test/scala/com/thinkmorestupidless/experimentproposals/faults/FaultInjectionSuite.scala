package com.thinkmorestupidless.experimentproposals.faults

import com.thinkmorestupidless.experimentproposals.integration.ServiceFixture
import com.thinkmorestupidless.ankka.agent.{
  ChatMessage,
  MessageContent,
  ModelRequest,
  ModelResponse
}

/**
 * SC-005: each of the six reference faults is caught at its gate and the altered attempt is never
 * admitted as affirmative evidence; a package built afterwards cites nothing of it. The verifier's
 * replies for the altered records are scripted by record, since their content is the fault's.
 */
class FaultInjectionSuite extends ServiceFixture:

  private def checks(failing: Map[String, String]): String =
    Seq("negation", "scope", "species", "treatment", "endpoint")
      .map(n =>
        s""""$n":{"passed":${!failing.contains(n)},"evidenceQuote":"","note":"${failing
            .getOrElse(n, "agrees with the text")}"}"""
      )
      .mkString("{", ",", "}")
  private def reply(label: String, failing: Map[String, String], rationale: String): String =
    s"""{"label":"$label","checks":${checks(failing)},"rationale":"$rationale"}"""

  private def verifying(recordId: String)(r: ModelRequest): Boolean =
    r.messages.exists {
      case ChatMessage.User(content) =>
        content.exists {
          case MessageContent.Text(t) => t.contains(s"# case: verify:$recordId:")
          case _                      => false
        }
      case _ => false
    }

  override def beforeAll(): Unit =
    super.beforeAll()
    // The genuine verifications first (exact cassettes), then one standing rule per altered record.
    scriptFromCassettes("extract@v1", "verify@v1", "reextract@v1")
    val faults = Map(
      "src:file:paper-a:v1:fd1" -> reply(
        "contradicted",
        Map("endpoint" -> "the passage reports proliferation, not lipid accumulation"),
        "the endpoint does not match"
      ),
      "src:file:paper-c:v1:fd1" -> reply(
        "contradicted",
        Map(
          "negation" -> "the text says cell number did not increase and the method was unsuccessful"
        ),
        "the outcome is negated in the text"
      ),
      "src:file:paper-b:v1:fd1" -> reply(
        "contradicted",
        Map("species" -> "the text names human HepG2 cells; the record states no species"),
        "the species qualification is missing"
      ),
      "src:file:paper-b:v1:cl1" -> reply(
        "contradicted",
        Map.empty,
        "the cited passage concerns viability, not lipid accumulation; it does not support the claim"
      )
    )
    faults.foreach((id, text) => model.whenRequest(verifying(id))(ModelResponse(text)))

  private def record(id: String): String = get(
    api(if id.contains(":fd") then s"/findings/$id" else s"/claims/$id")
  )._2

  private def inject(id: String, mutation: String): String =
    val kind = if id.contains(":fd") then "finding" else "claim"
    val (status, body) = post(
      api("/faults:inject"),
      s"""{"target":{"kind":"$kind","id":"$id"},"mutation":"$mutation"}"""
    )
    assertEquals(status, 202, body)
    eventually(s"$id leaves proposed after $mutation") {
      val r = record(id)
      // `proposed` is the default and is written as the absence of a status: wait for a settled one.
      Option.when(
        Seq("rejected", "quarantined", "requires_review").exists(x =>
          r.contains(s""""status":{"type":"$x"}""")
        )
      )(r)
    }

  private def summary(json: String): String =
    val status = """"status":\{"type":"([a-z_]+)"\}""".r
      .findFirstMatchIn(json)
      .map(_.group(1))
      .getOrElse("proposed")
    val defects =
      """"defect":\{"kind":\{"type":"([a-z_]+)"\},"severity":\{"type":"[a-z]+"\},"detail":"([^"]{0,160})""".r
        .findAllMatchIn(json)
        .map(m => s"${m.group(1)} (${m.group(2)})")
        .toVector
    val labels =
      """"label":\{"type":"([a-z_]+)"\}""".r.findAllMatchIn(json).map(_.group(1)).toVector
    val by = """"generatedBy":"([^"]+)"""".r.findAllMatchIn(json).map(_.group(1)).toVector
    s"status=$status defects=${defects.mkString(";")} labels=$labels generatedBy=$by"

  private def caught(json: String, kind: String): Unit =
    assert(json.contains(s""""defect":{"kind":{"type":"$kind"}"""), summary(json))
    assert(
      json.contains("\"status\":{\"type\":\"rejected\"}") || json.contains(
        "\"status\":{\"type\":\"quarantined\"}"
      ),
      json.take(300)
    )

  test("the six faults are caught at their gates and none becomes affirmative evidence") {
    Seq("paper-a.xml", "paper-b.xml", "paper-c.xml").foreach(f =>
      recordsSettled(ingestFixture(f, extract = true))
    )
    val calls = model.callCount
    val a     = inject("src:file:paper-a:v1:fd1", "endpoint_swap")
    caught(a, "endpoint_mismatch")
    val c1 = inject("src:file:paper-c:v1:fd1", "negation_flip")
    caught(c1, "negation_error")
    val b = inject("src:file:paper-b:v1:fd1", "species_drop")
    caught(b, "species_overgeneralisation")
    val broken = inject("src:file:paper-a:v1:cl1", "span_break")
    caught(broken, "span_unresolved")
    val modelCallsBeforeSpanBreak = model.callCount
    assert(
      broken.contains("\"gate\":{\"type\":\"deterministic\"}"),
      "a broken span fails before verification"
    )
    val cite = inject("src:file:paper-b:v1:cl1", "citation_mismatch")
    caught(cite, "unsupported_by_span")
    assert(model.callCount > calls, "the model-checked faults went to the verifier")
    val _ = modelCallsBeforeSpanBreak
    // Zero leakage: nothing admitted; the review queue does not offer them as evidence either.
    Seq(
      "src:file:paper-a:v1:fd1",
      "src:file:paper-c:v1:fd1",
      "src:file:paper-b:v1:fd1",
      "src:file:paper-a:v1:cl1",
      "src:file:paper-b:v1:cl1"
    ).foreach { id =>
      assert(!record(id).contains("\"status\":{\"type\":\"verified\"}"), id)
    }
  }

  test("failure presented as success on Paper C is caught as failure_as_success") {
    recordsSettled(ingestFixture("paper-c.xml", extract = true))
    val c = record("src:file:paper-c:v1:fd1")
    // The record was already faulted above; a second fault is a new attempt on the current content.
    val (status, body) = post(
      api("/faults:inject"),
      """{"target":{"kind":"finding","id":"src:file:paper-c:v1:fd1"},"mutation":"failure_to_success"}"""
    )
    assert(status == 202 || status == 409, s"$status $body")
    if status == 202 then
      val after = eventually("the failure-as-success attempt is judged") {
        val r = record("src:file:paper-c:v1:fd1")
        Option.when(
          Seq("rejected", "quarantined", "requires_review").exists(x =>
            r.contains(s""""status":{"type":"$x"}""")
          ) && r.contains("successful")
        )(r)
      }
      assert(
        after.contains("\"type\":\"failure_as_success\"") || after.contains(
          "\"type\":\"negation_error\""
        ),
        summary(after)
      )
    val _ = c
  }
