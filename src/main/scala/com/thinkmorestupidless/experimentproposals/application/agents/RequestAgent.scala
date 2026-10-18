package com.thinkmorestupidless.experimentproposals.application.agents

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.experimentproposals.domain.request.*
import com.thinkmorestupidless.experimentproposals.domain.request.FacetCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.request.QuestionCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.request.RequestCodecs.given
import com.thinkmorestupidless.experimentproposals.rules.FacetRules
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId, Serializer}

final case class ExtractFacets(requestId: String, text: String)
final case class FacetLine(name: String, statement: String, origin: String, status: String)
final case class RoundTripRequest(requestId: String, text: String, facets: Vector[FacetLine])
final case class QuestionLine(name: String, statement: String, importance: String, status: String)
final case class DeriveQuestions(
    requestId: String,
    text: String,
    marker: String,
    facets: Vector[QuestionLine]
)

// Declared at the top level, beside the types: `thenReplyAs` is called inside the agent class.
given JsonValueCodec[FacetSet]       = Codecs.make[FacetSet]
given JsonValueCodec[RoundTripCheck] = Codecs.make[RoundTripCheck]
given JsonValueCodec[QuestionSet]    = Codecs.make[QuestionSet]

/**
 * The request's model-dependent stages (plan: component table). Each handler is one interaction
 * with no session memory; the request text travels as context, and the user message carries the
 * case marker the replay tier keys on.
 */
final class RequestAgent extends Agent:

  def extractFacets(request: ExtractFacets): Effect[FacetSet] =
    val prompt = RequestAgent.facetsPrompt
    effects
      .systemMessage(prompt.system)
      .userMessage(
        Prompts.userMessage(
          FacetRules.marker(request.text),
          "Interpret the customer request into its eight facets."
        )
      )
      .withContext(s"Customer request, exactly as entered:\n${request.text}")
      .memory(MemoryProvider.none)
      .thenReplyAs[FacetSet]

  def roundTrip(request: RoundTripRequest): Effect[RoundTripCheck] =
    val prompt = RequestAgent.roundTripPrompt
    val marker =
      FacetRules.roundTripMarker(request.text, request.facets.map(f => f.name -> f.statement))
    val facets = request.facets
      .map(f => s"- ${f.name} (${f.origin}, ${f.status}): ${f.statement}")
      .mkString("\n")
    effects
      .systemMessage(prompt.system)
      .userMessage(
        Prompts
          .userMessage(marker, "Reconstruct the request from the facets and report the problems.")
      )
      .withContext(s"Original request:\n${request.text}\n\nFacets:\n$facets")
      .memory(MemoryProvider.none)
      .thenReplyAs[RoundTripCheck]

  def deriveQuestions(request: DeriveQuestions): Effect[QuestionSet] =
    val prompt = RequestAgent.questionsPrompt
    val facets = request.facets
      .map(f => s"- ${f.name} (${f.importance}, ${f.status}): ${f.statement}")
      .mkString("\n")
    effects
      .systemMessage(prompt.system)
      .userMessage(
        Prompts
          .userMessage(request.marker, "Derive the scientific questions from the confirmed facets.")
      )
      .withContext(s"Customer request:\n${request.text}\n\nFacets:\n$facets")
      .memory(MemoryProvider.none)
      .thenReplyAs[QuestionSet]

object RequestAgent extends Agent.Companion[RequestAgent](ComponentId("request-agent")):

  val facetsPrompt: Prompt    = Prompts.load("facets", 1)
  val roundTripPrompt: Prompt = Prompts.load("roundtrip", 1)
  val questionsPrompt: Prompt = Prompts.load("questions", 1)

  given Serializer[ExtractFacets]    = Codecs.serializer[ExtractFacets]("extract-facets")
  given Serializer[RoundTripRequest] = Codecs.serializer[RoundTripRequest]("round-trip-request")
  given Serializer[DeriveQuestions]  = Codecs.serializer[DeriveQuestions]("derive-questions")

  def create(context: AgentContext) = new RequestAgent

  val extractFacets   = command("extract-facets")(_.extractFacets)
  val roundTrip       = command("round-trip")(_.roundTrip)
  val deriveQuestions = command("derive-questions")(_.deriveQuestions)
