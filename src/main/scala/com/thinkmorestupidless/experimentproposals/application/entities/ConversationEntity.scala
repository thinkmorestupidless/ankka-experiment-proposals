package com.thinkmorestupidless.experimentproposals.application.entities

import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.conversation.*
import com.thinkmorestupidless.experimentproposals.domain.conversation.ConversationCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.conversation.ConversationEvent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/**
 * A conversation with the assistant (data-model.md): the budget, every completed turn and every
 * proposal. It decides nothing about the records; it only refuses what its own rules refuse.
 */
final class ConversationEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Conversation, ConversationEvent]:

  def emptyState: Conversation = Conversation(context.entityId)

  def applyEvent(event: ConversationEvent): Conversation = event match
    case Opened(subject, budget, _) =>
      currentState.copy(registered = true, subject = subject, budget = budget)
    case TurnAnswered(n, question, answer, references, prefill, total, at) =>
      currentState.copy(
        used = total,
        turns = currentState.turns :+ Turn(n, question, answer, references, prefill, at)
      )
    case ActionProposed(n, action, arguments, at) =>
      currentState.copy(proposals =
        currentState.proposals :+ Proposal(n, action, arguments, at = at)
      )
    case ActionPerformed(n, result, _) =>
      settle(n)(_.copy(status = "performed", result = Some(result)))
    case ActionDeclined(n, _) =>
      settle(n)(_.copy(status = "declined"))

  private def settle(n: Int)(f: Proposal => Proposal): Conversation =
    currentState.copy(proposals = currentState.proposals.map(p => if p.n == n then f(p) else p))

  /** The first call fixes the subject and the budget; a later one answers the conversation. */
  def open(request: OpenConversation): Effect[Conversation] =
    if currentState.registered then
      if request.subject.isDefined && request.subject != currentState.subject then
        effects.error(s"this conversation's subject is ${currentState.subject.getOrElse("none")}")
      else effects.reply(currentState)
    else
      Conversation.subjectOf(context.entityId) match
        case Left(problem) => effects.error(problem)
        case Right(named) if request.subject.isDefined && request.subject != named =>
          effects.error(
            s"the subject ${request.subject.get} does not match the id ${context.entityId}"
          )
        case Right(named) =>
          effects
            .persist(Opened(named, Configured.conversationBudget, Instant.now()))
            .thenReplyState

  /** Whether another question may be put: refused at the budget, before the model is called. */
  def ask: Effect[Conversation] =
    if !currentState.registered then
      effects.error(s"no conversation '${context.entityId}'; open it first", ErrorCode.NotFound)
    else if currentState.used >= currentState.budget then
      effects.error(
        s"the conversation's budget is reached: ${currentState.used} of ${currentState.budget} tokens",
        ErrorCode.Conflict
      )
    else effects.reply(currentState)

  def answered(request: Answered): Effect[Conversation] =
    if !currentState.registered then
      effects.error(s"no conversation '${context.entityId}'", ErrorCode.NotFound)
    else if request.tokensUsedTotal < currentState.used then
      effects.error(
        s"the tokens used cannot decrease: ${request.tokensUsedTotal} after ${currentState.used}"
      )
    else
      effects
        .persist(
          TurnAnswered(
            currentState.turns.size + 1,
            request.question,
            request.answer,
            request.references,
            request.prefill,
            request.tokensUsedTotal,
            Instant.now()
          )
        )
        .thenReplyState

  /** Records a proposal, numbered; nothing is done until a person confirms it. */
  def propose(request: Propose): Effect[Proposal] =
    if !currentState.registered then
      effects.error(s"no conversation '${context.entityId}'", ErrorCode.NotFound)
    else
      Actions.validate(request.action, request.arguments) match
        case Left(problem) => effects.error(problem)
        case Right(action) =>
          val n = currentState.proposals.size + 1
          effects
            .persist(ActionProposed(n, action.wire, action.arguments, Instant.now()))
            .thenReply(_.proposal(n).get)

  private def pending(n: Int)(record: => ConversationEvent): Effect[Conversation] =
    currentState.proposal(n) match
      case None => effects.error(s"no proposal #$n", ErrorCode.NotFound)
      case Some(p) if p.status != "proposed" =>
        effects.error(s"proposal #$n is already ${p.status}", ErrorCode.Conflict)
      case Some(_) => effects.persist(record).thenReplyState

  def performed(request: Performed): Effect[Conversation] =
    pending(request.n)(ActionPerformed(request.n, request.result, Instant.now()))

  def declined(n: Int): Effect[Conversation] =
    pending(n)(ActionDeclined(n, Instant.now()))

  def get: ReadOnlyEffect[Conversation] = effects.reply(currentState)

object ConversationEntity
    extends EventSourcedEntity.Companion[ConversationEntity, Conversation, ConversationEvent](
      componentId = ComponentId("conversation"),
      stateSerializer = summon[Serializer[Conversation]],
      eventSerializer = summon[Serializer[ConversationEvent]]
    ):
  def create(context: EventSourcedEntityContext) = new ConversationEntity(context)

  val open      = command("open")(_.open)
  val ask       = command("ask")(_.ask)
  val answered  = command("answered")(_.answered)
  val propose   = command("propose")(_.propose)
  val performed = command("performed")(_.performed)
  val declined  = command("declined")(_.declined)
  val get       = query("get")(_.get)
