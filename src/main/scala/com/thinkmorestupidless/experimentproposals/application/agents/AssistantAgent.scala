package com.thinkmorestupidless.experimentproposals.application.agents

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString, JsonValueCodec}
import com.thinkmorestupidless.experimentproposals.api.Json.given
import com.thinkmorestupidless.experimentproposals.api.Readers
import com.thinkmorestupidless.experimentproposals.application.entities.ConversationEntity
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.conversation.{Actions, Propose}
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.{Codecs, CommandError, ComponentId, EntityId, Serializer}

import scala.util.control.NonFatal

/** One question in a conversation, with the subject's summary when the conversation has one. */
final case class AskAssistant(question: String, subject: Option[String] = None)

/**
 * The assistant (contracts/assistant.md): one session per conversation, answering from the records
 * through read tools that return the API's JSON, and proposing an operator's action that a person
 * then confirms. It never decides: there is no tool that records a reviewer's decision.
 */
final class AssistantAgent(context: AgentContext) extends Agent:

  private def readers: Readers =
    Readers(
      context.componentClient,
      Configured.views.getOrElse(throw IllegalStateException("the service has not started"))
    )

  private def subjectOf: Option[String] =
    Some(context.sessionId.toString).filter(_.startsWith("request:")).map(_.stripPrefix("request:"))

  /** A read whose failure is a message the model can act on, never an exception. */
  private def read[A](what: => A)(using codec: JsonValueCodec[A]): String =
    try writeToString(what)
    catch
      case e: CommandError => e.message
      case NonFatal(e)     => Option(e.getMessage).getOrElse(e.toString)

  private def requestIdOr(named: Option[String]): Either[String, String] =
    named
      .map(_.trim)
      .filter(_.nonEmpty)
      .orElse(subjectOf)
      .toRight(
        "no request is named and this conversation has no subject; call list_requests for the ids"
      )

  private def forRequest(named: Option[String])(f: String => String): String =
    requestIdOr(named).fold(identity, f)

  private val tools: Vector[FunctionTool] = Vector(
    FunctionTool
      .named("list_requests")
      .describedAs(
        "Every customer request, newest first: id, text, status and when it was submitted."
      )
      .handle(() => read(readers.requests())),
    FunctionTool
      .named("get_request")
      .describedAs(
        "A customer request with its facets (origin, importance, status, quotation), the round-trip " +
          "check, its scientific questions and its investigation. Defaults to the conversation's request."
      )
      .param[Option[String]](
        "requestId",
        "The request id, rq-…; omit for the conversation's request"
      )
      .handle((id: Option[String]) => forRequest(id)(r => read(readers.request(r)))),
    FunctionTool
      .named("get_question")
      .describedAs(
        "A scientific question with its search plan and queries, each execution with its results and " +
          "exclusions and the rule for each, its gaps, its assessments and its sufficiency per decision."
      )
      .param[String]("questionId", "The question id, rq-…:q4")
      .handle((id: String) => read(readers.question(id.trim))),
    FunctionTool
      .named("get_record")
      .describedAs(
        "A finding or a claim with its spans resolved to the source's passages, its verification checks " +
          "with their results and rationale, its status and its attempts."
      )
      .param[String](
        "recordId",
        "The record id, src:…:v1:fd1 for a finding or src:…:v1:cl1 for a claim"
      )
      .handle((id: String) => read(readers.record(id.trim))),
    FunctionTool
      .named("get_evidence")
      .describedAs(
        "The request's candidate approaches with the comparison on the eight criteria, and the decision " +
          "with the reasons for the rejected approaches. Packages are named by id in each question."
      )
      .param[Option[String]]("requestId", "The request id; omit for the conversation's request")
      .handle((id: Option[String]) => forRequest(id)(r => read(readers.approachesOf(r)))),
    FunctionTool
      .named("get_design")
      .describedAs(
        "The request's experimental design: its versions with the twelve sections, the approval " +
          "history and any impact flag naming the changed source version."
      )
      .param[Option[String]]("requestId", "The request id; omit for the conversation's request")
      .handle((id: Option[String]) => forRequest(id)(r => read(readers.designOf(s"$r:design")))),
    FunctionTool
      .named("list_sources")
      .describedAs("Every ingested source with its versions and status.")
      .handle(() => read(readers.sources())),
    FunctionTool
      .named("review_queue")
      .describedAs("The findings and claims awaiting a reviewer, with their status.")
      .handle(() => read(readers.reviewQueue())),
    FunctionTool
      .named("propose_action")
      .describedAs(
        "Propose an operator's action for the person to confirm: submit-request (text), ingest-source " +
          "(adapter, identifier), inject-fault (recordId, fault) or set-source-status (sourceId, " +
          "status). Nothing happens until the person confirms the proposal."
      )
      .param[String]("action", "submit-request, ingest-source, inject-fault or set-source-status")
      .param[String]("arguments", "The action's arguments as a JSON object of strings")
      .handle((action: String, arguments: String) => propose(action, arguments))
  )

  private def propose(action: String, arguments: String): String =
    val args =
      try Right(readFromString[Map[String, String]](arguments)(using AssistantAgent.argsCodec))
      catch case NonFatal(_) => Left("arguments must be a JSON object whose values are strings")
    args.flatMap(a => Actions.validate(action, a).map(_ => a)) match
      case Left(problem) => problem
      case Right(a) =>
        try
          val p = context.componentClient
            .forEventSourcedEntity(EntityId(context.sessionId.toString))
            .call(ConversationEntity.propose)
            .invoke(Propose(action, a))
          s"proposed as #${p.n}; the person must confirm it"
        catch case e: CommandError => e.message

  def answer(ask: AskAssistant): AgentStreamEffect =
    val withSubject = ask.subject.fold(
      effects.systemMessage(AssistantAgent.prompt.system)
    )(s => effects.systemMessage(AssistantAgent.prompt.system).withContext(s))
    Configured.assistantModel
      .fold(withSubject)(withSubject.model)
      .userMessage(ask.question)
      .tools(tools*)
      .maxToolCallSteps(12)
      .memory(MemoryProvider.limitedWindow)
      .thenStream()

object AssistantAgent extends Agent.Companion[AssistantAgent](ComponentId("assistant")):
  val prompt: Prompt = Prompts.load("assistant", 1)

  private[agents] val argsCodec: JsonValueCodec[Map[String, String]] =
    Codecs.make[Map[String, String]]

  given Serializer[AskAssistant] = Codecs.serializer[AskAssistant]("ask-assistant")

  def create(context: AgentContext) = new AssistantAgent(context)

  val answer = stream("answer")(_.answer)
