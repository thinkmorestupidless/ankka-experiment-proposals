package com.thinkmorestupidless.experimentproposals.domain.conversation

import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

import java.time.Instant

/** One completed exchange: recorded only when the answer's stream completed. */
final case class Turn(
    n: Int,
    question: String,
    answer: String,
    references: Vector[Reference],
    prefill: Option[Prefill],
    at: Instant
)

/** An operator's act the assistant proposed; done only when a person confirms it. */
final case class Proposal(
    n: Int,
    action: String,
    arguments: Map[String, String],
    status: String = "proposed",
    result: Option[Reference] = None,
    at: Instant
)

/**
 * A conversation (data-model.md): `request:<requestId>` or `free:<key>`, its budget fixed when it
 * opened, the turns and the proposals.
 */
final case class Conversation(
    id: String,
    registered: Boolean = false,
    subject: Option[String] = None,
    budget: Long = 0L,
    used: Long = 0L,
    turns: Vector[Turn] = Vector.empty,
    proposals: Vector[Proposal] = Vector.empty
):
  def proposal(n: Int): Option[Proposal] = proposals.find(_.n == n)

object Conversation:
  private val Free = "free:([A-Za-z0-9_-]{8,64})".r

  /** The subject an id names, or why the id is not a conversation's. */
  def subjectOf(id: String): Either[String, Option[String]] = id match
    case s"request:$requestId" if requestId.nonEmpty => Right(Some(requestId))
    case Free(_)                                     => Right(None)
    case other =>
      Left(
        s"'$other' is not a conversation id; use request:<requestId> or free:<8 to 64 of A-Z a-z 0-9 _ ->"
      )

enum ConversationEvent:
  case Opened(subject: Option[String], budget: Long, at: Instant)
  case TurnAnswered(
      n: Int,
      question: String,
      answer: String,
      references: Vector[Reference],
      prefill: Option[Prefill],
      tokensUsedTotal: Long,
      at: Instant
  )
  case ActionProposed(n: Int, action: String, arguments: Map[String, String], at: Instant)
  case ActionPerformed(n: Int, result: Reference, at: Instant)
  case ActionDeclined(n: Int, at: Instant)

final case class OpenConversation(subject: Option[String])
final case class Answered(
    question: String,
    answer: String,
    references: Vector[Reference],
    prefill: Option[Prefill],
    tokensUsedTotal: Long
)
final case class Propose(action: String, arguments: Map[String, String])
final case class Performed(n: Int, result: Reference)

object ConversationCodecs:
  given Serializer[Reference]         = Codecs.serializer[Reference]("reference")
  given Serializer[Prefill]           = Codecs.serializer[Prefill]("prefill")
  given Serializer[Conversation]      = Codecs.serializer[Conversation]("conversation")
  given Serializer[ConversationEvent] = Codecs.serializer[ConversationEvent]("conversation-event")
  given Serializer[OpenConversation]  = Codecs.serializer[OpenConversation]("open-conversation")
  given Serializer[Answered]          = Codecs.serializer[Answered]("turn-answered-command")
  given Serializer[Propose]           = Codecs.serializer[Propose]("propose-action")
  given Serializer[Proposal]          = Codecs.serializer[Proposal]("proposal")
  given Serializer[Performed]         = Codecs.serializer[Performed]("action-performed-command")
