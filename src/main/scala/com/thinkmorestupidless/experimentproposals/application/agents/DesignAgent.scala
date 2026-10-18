package com.thinkmorestupidless.experimentproposals.application.agents

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.experimentproposals.domain.design.{ApproachSet, ExperimentalDesign}
import com.thinkmorestupidless.experimentproposals.domain.design.ApproachCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.design.DesignCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ids
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Serializer}

final case class AssessmentLine(
    recordId: String,
    kind: String,
    relationship: String,
    applicability: String,
    summary: String
)
final case class PackageForModel(
    packageId: String,
    questionId: String,
    question: String,
    qualityStatus: String,
    assessments: Vector[AssessmentLine],
    gaps: Vector[String],
    resourceConstraints: Vector[String]
)
final case class CandidatesRequest(
    requestId: String,
    marker: String,
    text: String,
    facets: Vector[FacetLine],
    packages: Vector[PackageForModel]
)

given JsonValueCodec[ApproachSet]        = Codecs.make[ApproachSet]
given JsonValueCodec[ExperimentalDesign] = Codecs.make[ExperimentalDesign]

final case class ApproachForModel(
    title: String,
    hypothesis: String,
    rationale: String,
    biologicalModel: String,
    endpoints: Vector[String],
    measurements: Vector[String],
    controls: Vector[String],
    assumptions: Vector[String],
    missingEvidence: Vector[String]
)
final case class RejectedLine(title: String, reason: String)
final case class ClaimLine(
    id: String,
    proposition: String,
    qualifications: Vector[String],
    source: String
)
final case class DesignRequest(
    requestId: String,
    marker: String,
    text: String,
    facets: Vector[FacetLine],
    approach: ApproachForModel,
    rejected: Vector[RejectedLine],
    claims: Vector[ClaimLine],
    requestedChanges: Vector[String]
)

/**
 * The design stages (plan: component table): candidates now, the design in US8. One interaction, no
 * memory; the output guardrail refuses a reply that asserts a probability of success (FR-037).
 */
final class DesignAgent extends Agent:

  def candidates(request: CandidatesRequest): Effect[ApproachSet] =
    val prompt = DesignAgent.candidatesPrompt
    val facets = request.facets
      .map(f => s"- ${f.name} (${f.origin}, ${f.status}): ${f.statement}")
      .mkString("\n")
    val packages = request.packages
      .map { p =>
        val lines = p.assessments
          .map(a =>
            s"  - ${a.recordId} (${a.kind}; ${a.relationship}, ${a.applicability}): ${a.summary}"
          )
          .mkString("\n")
        s"""Package ${p.packageId} (${p.qualityStatus}) for question ${p.questionId}: ${p.question}
           |  assessed records:
           |$lines
           |  gaps: ${if p.gaps.isEmpty then "none" else p.gaps.mkString("; ")}
           |  resource constraints: ${
            if p.resourceConstraints.isEmpty then "none stated"
            else p.resourceConstraints.mkString("; ")
          }""".stripMargin
      }
      .mkString("\n\n")
    effects
      .systemMessage(prompt.system)
      .userMessage(
        Prompts.userMessage(
          request.marker,
          "Propose at least two candidate approaches and compare them on the eight criteria."
        )
      )
      .withContext(
        s"Customer request:\n${request.text}\n\nFacets:\n$facets\n\nEvidence packages:\n$packages"
      )
      .memory(MemoryProvider.none)
      .guardrails(DesignAgent.noProbability)
      .thenReplyAs[ApproachSet]

  def design(request: DesignRequest): Effect[ExperimentalDesign] =
    val prompt = DesignAgent.designPrompt
    val facets = request.facets
      .map(f => s"- ${f.name} (${f.origin}, ${f.status}): ${f.statement}")
      .mkString("\n")
    val a = request.approach
    val approach =
      s"""${a.title}
         |Hypothesis: ${a.hypothesis}
         |Rationale: ${a.rationale}
         |Model: ${a.biologicalModel}
         |Endpoints: ${a.endpoints.mkString("; ")}
         |Measurements: ${a.measurements.mkString("; ")}
         |Controls: ${a.controls.mkString("; ")}
         |Assumptions: ${a.assumptions.mkString("; ")}
         |Missing evidence: ${a.missingEvidence.mkString("; ")}""".stripMargin
    val rejected =
      if request.rejected.isEmpty then "none"
      else request.rejected.map(r => s"- ${r.title}: ${r.reason}").mkString("\n")
    val claims = request.claims
      .map(c => s"- ${c.id}: ${c.proposition} [${c.qualifications.mkString("; ")}] (${c.source})")
      .mkString("\n")
    val changes = if request.requestedChanges.isEmpty then ""
    else
      s"\n\nChanges the reviewer asked for:\n${request.requestedChanges.map("- " + _).mkString("\n")}"
    effects
      .systemMessage(prompt.system)
      .userMessage(
        Prompts
          .userMessage(request.marker, "Write the experimental design for the selected approach.")
      )
      .withContext(
        s"Customer request:\n${request.text}\n\nFacets:\n$facets\n\nSelected approach:\n$approach\n\nRejected approaches:\n$rejected\n\nVerified claims available:\n$claims$changes"
      )
      .memory(MemoryProvider.none)
      .guardrails(DesignAgent.noProbability)
      .thenReplyAs[ExperimentalDesign]

object DesignAgent extends Agent.Companion[DesignAgent](ComponentId("design-agent")):

  val candidatesPrompt: Prompt = Prompts.load("candidates", 1)
  val designPrompt: Prompt     = Prompts.load("design", 1)

  def designMarker(text: String, approachTitle: String, requestedChanges: Vector[String]): String =
    s"design:${Ids.short(text)}:${Ids.short(approachTitle)}:${Ids.short(requestedChanges.mkString("|"))}"

  /** A reply that asserts a probability of success is refused before it is returned (FR-037). */
  val noProbability: Guardrail =
    Guardrail.forbidding(
      "no-probability",
      """(?i)\d+(\.\d+)?\s?% (chance|probability|likelihood)""".r
    )

  given Serializer[CandidatesRequest] = Codecs.serializer[CandidatesRequest]("candidates-request")
  given Serializer[DesignRequest]     = Codecs.serializer[DesignRequest]("design-request")

  def create(context: AgentContext) = new DesignAgent

  val candidates = command("candidates")(_.candidates)
  val design     = command("design")(_.design)
