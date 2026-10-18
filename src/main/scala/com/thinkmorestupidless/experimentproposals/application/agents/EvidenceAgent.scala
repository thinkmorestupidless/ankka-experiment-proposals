package com.thinkmorestupidless.experimentproposals.application.agents

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.experimentproposals.domain.evidence.*
import com.thinkmorestupidless.experimentproposals.domain.evidence.AssessmentCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.evidence.RecordCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.evidence.SufficiencyCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.sources.Section
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Serializer}

final case class ExtractRequest(
    sourceId: String,
    version: Int,
    sections: Vector[Section],
    questions: Vector[String]
)
final case class VerifyRequest(
    recordId: String,
    marker: String,
    record: String,
    sections: Vector[Section]
)
final case class ReextractRequest(
    recordId: String,
    marker: String,
    previous: String,
    defect: String,
    sections: Vector[Section]
)
final case class RecordForAssessment(
    recordId: String,
    kind: String,
    summary: String,
    passages: Vector[String]
)
final case class AssessRequest(
    questionId: String,
    marker: String,
    question: String,
    answerFields: Vector[String],
    targetSpecies: Option[String],
    records: Vector[RecordForAssessment]
)
final case class AssessmentForSufficiency(
    assessmentId: String,
    recordId: String,
    relationship: String,
    applicability: String,
    dimensions: Map[String, String],
    rationale: String
)
final case class SufficiencyRequest(
    questionId: String,
    marker: String,
    question: String,
    decision: String,
    assessments: Vector[AssessmentForSufficiency],
    emptySearch: Boolean
)

given JsonValueCodec[ExtractionSet]       = Codecs.make[ExtractionSet]
given JsonValueCodec[ClaimVerification]   = Codecs.make[ClaimVerification]
given JsonValueCodec[AssessmentSet]       = Codecs.make[AssessmentSet]
given JsonValueCodec[SufficiencyProposal] = Codecs.make[SufficiencyProposal]

/**
 * Extraction, verification and re-extraction (plan: component table). The verifier is given the
 * original sections, never the extractor's prose. Every handler is one interaction without memory.
 */
final class EvidenceAgent extends Agent:

  private def context(sections: Vector[Section]): String =
    Sections.render(Sections.keyed(sections)(_.title, _.text))

  def extract(request: ExtractRequest): Effect[ExtractionSet] =
    val prompt = EvidenceAgent.extractPrompt
    val ask =
      if request.questions.isEmpty then "Extract the findings and claims this source reports."
      else
        s"Extract the findings and claims this source reports, with these questions in mind:\n${request.questions.map("- " + _).mkString("\n")}"
    effects
      .systemMessage(prompt.system)
      .userMessage(
        Prompts.userMessage(EvidenceAgent.extractMarker(request.sourceId, request.version), ask)
      )
      .withContext(context(request.sections))
      .memory(MemoryProvider.none)
      .thenReplyAs[ExtractionSet]

  def verify(request: VerifyRequest): Effect[ClaimVerification] =
    val prompt = EvidenceAgent.verifyPrompt
    effects
      .systemMessage(prompt.system)
      .userMessage(
        Prompts
          .userMessage(request.marker, s"Verify this record against the text:\n${request.record}")
      )
      .withContext(context(request.sections))
      .memory(MemoryProvider.none)
      .thenReplyAs[ClaimVerification]

  def reextract(request: ReextractRequest): Effect[ExtractionSet] =
    val prompt = EvidenceAgent.reextractPrompt
    effects
      .systemMessage(prompt.system)
      .userMessage(
        Prompts.userMessage(
          request.marker,
          s"Previous attempt:\n${request.previous}\n\nDefect found by verification:\n${request.defect}\n\nExtract the record again from the text."
        )
      )
      .withContext(context(request.sections))
      .memory(MemoryProvider.none)
      .thenReplyAs[ExtractionSet]

  def assess(request: AssessRequest): Effect[AssessmentSet] =
    val prompt = EvidenceAgent.assessPrompt
    val records = request.records
      .map(r =>
        s"- ${r.recordId} (${r.kind}): ${r.summary}\n  cited passages: ${r.passages.map(p => s"\"$p\"").mkString("; ")}"
      )
      .mkString("\n")
    effects
      .systemMessage(prompt.system)
      .userMessage(Prompts.userMessage(request.marker, s"Assess each record against the question."))
      .withContext(
        s"Question: ${request.question}\nAnswer fields: ${request.answerFields.mkString(", ")}\nTarget species: ${request.targetSpecies.getOrElse("not stated")}\n\nVerified records:\n$records"
      )
      .memory(MemoryProvider.none)
      .thenReplyAs[AssessmentSet]

  def judgeSufficiency(request: SufficiencyRequest): Effect[SufficiencyProposal] =
    val prompt = EvidenceAgent.sufficiencyPrompt
    val assessments =
      if request.assessments.isEmpty then
        "(no assessments: no record was retrieved or verified for this question)"
      else
        request.assessments
          .map(a =>
            s"- ${a.assessmentId} on ${a.recordId}: ${a.relationship}, ${a.applicability}; ${a.dimensions.map((k, v) => s"$k=$v").mkString(", ")}; ${a.rationale}"
          )
          .mkString("\n")
    effects
      .systemMessage(prompt.system)
      .userMessage(
        Prompts.userMessage(
          request.marker,
          s"Judge the sufficiency of the evidence for the decision: ${request.decision}"
        )
      )
      .withContext(
        s"Question: ${request.question}\nDecision: ${request.decision}\nEmpty search: ${request.emptySearch}\n\nAssessments:\n$assessments"
      )
      .memory(MemoryProvider.none)
      .thenReplyAs[SufficiencyProposal]

object EvidenceAgent extends Agent.Companion[EvidenceAgent](ComponentId("evidence-agent")):

  val extractPrompt: Prompt     = Prompts.load("extract", 1)
  val verifyPrompt: Prompt      = Prompts.load("verify", 1)
  val reextractPrompt: Prompt   = Prompts.load("reextract", 1)
  val assessPrompt: Prompt      = Prompts.load("assess", 1)
  val sufficiencyPrompt: Prompt = Prompts.load("sufficiency", 1)

  def extractMarker(sourceId: String, version: Int): String = s"extract:$sourceId:v$version"
  def assessMarker(question: String, recordIds: Vector[String]): String =
    s"assess:${com.thinkmorestupidless.experimentproposals.domain.provenance.Ids.short(question)}:${com.thinkmorestupidless.experimentproposals.domain.provenance.Ids.short(recordIds.sorted.mkString("\n"))}"
  def sufficiencyMarker(question: String, decision: String, recordIds: Vector[String]): String =
    s"sufficiency:${com.thinkmorestupidless.experimentproposals.domain.provenance.Ids.short(question)}:${com.thinkmorestupidless.experimentproposals.domain.provenance.Ids.short(decision)}:${com.thinkmorestupidless.experimentproposals.domain.provenance.Ids.short(recordIds.sorted.mkString("\n"))}"

  /**
   * What a record says, as a short stable text: the replay key must not depend on the attempt
   * number.
   */
  def fingerprint(
      content: RecordContent,
      spans: Vector[com.thinkmorestupidless.experimentproposals.domain.sources.SourceSpan]
  ): String =
    val quoted = spans.map(_.quotation).mkString(";")
    val text = content match
      case RecordContent.OfFinding(f) =>
        s"${f.species.getOrElse("")}|${f.observedEndpoint}|${f.reportedOutcome}|${f.conditions.mkString(";")}|$quoted"
      case RecordContent.OfClaim(c) => s"${c.proposition}|${c.qualifications.mkString(";")}|$quoted"
    com.thinkmorestupidless.experimentproposals.domain.provenance.Ids.short(text)

  def verifyMarker(recordId: String, print: String): String = s"verify:$recordId:$print"
  def reextractMarker(recordId: String, print: String, defectKind: String): String =
    s"reextract:$recordId:$print:$defectKind"

  given Serializer[ExtractRequest]   = Codecs.serializer[ExtractRequest]("extract-request")
  given Serializer[VerifyRequest]    = Codecs.serializer[VerifyRequest]("verify-request")
  given Serializer[ReextractRequest] = Codecs.serializer[ReextractRequest]("reextract-request")
  given Serializer[AssessRequest]    = Codecs.serializer[AssessRequest]("assess-request")
  given Serializer[SufficiencyRequest] =
    Codecs.serializer[SufficiencyRequest]("sufficiency-request")

  def create(context: AgentContext) = new EvidenceAgent

  val extract          = command("extract")(_.extract)
  val verify           = command("verify")(_.verify)
  val reextract        = command("reextract")(_.reextract)
  val assess           = command("assess")(_.assess)
  val judgeSufficiency = command("judge-sufficiency")(_.judgeSufficiency)
