package com.thinkmorestupidless.experimentproposals.application.agents

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ids
import com.thinkmorestupidless.experimentproposals.domain.search.SearchPlanProposal
import com.thinkmorestupidless.experimentproposals.domain.search.SearchCodecs.given
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Serializer}

final case class PlanRequest(
    questionId: String,
    question: String,
    evidenceNeeded: Vector[String],
    answerFields: Vector[String],
    availableSources: Vector[String]
)

given JsonValueCodec[SearchPlanProposal] = Codecs.make[SearchPlanProposal]

/**
 * The search planner (plan: component table; contracts/stages.md `search-agent.plan`). One
 * interaction, no memory; the small model at low effort, because a plan is a list of queries, not a
 * judgement. The replay tier keys on the question's marker.
 */
final class SearchAgent extends Agent:

  // docs:start plan-handler
  def plan(request: PlanRequest): Effect[SearchPlanProposal] =
    val prompt = SearchAgent.planPrompt
    effects
      .systemMessage(prompt.system)
      .userMessage(
        Prompts.userMessage(
          SearchAgent.planMarker(request.question),
          "Write the search plan for this scientific question."
        )
      )
      .withContext(
        s"Scientific question:\n${request.question}\n\nEvidence needed:\n${request.evidenceNeeded
            .map("- " + _)
            .mkString("\n")}\n\nAnswer fields:\n${request.answerFields.mkString(", ")}\n\nAvailable sources:\n${request.availableSources
            .mkString(", ")}"
      )
      .memory(MemoryProvider.none)
      .thenReplyAs[SearchPlanProposal]
  // docs:end plan-handler

object SearchAgent extends Agent.Companion[SearchAgent](ComponentId("search-agent")):

  val planPrompt: Prompt = Prompts.load("plan", 1)

  /** The model the planner asks for in the live tier; the replay tier ignores it. */
  val Model  = "claude-haiku-5-5"
  val Effort = "low"

  def planMarker(question: String): String = s"plan:${Ids.short(question)}"

  given Serializer[PlanRequest] = Codecs.serializer[PlanRequest]("plan-request")

  def create(context: AgentContext) = new SearchAgent

  val plan = command("plan")(_.plan)
