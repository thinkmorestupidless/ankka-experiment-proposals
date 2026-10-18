package com.thinkmorestupidless.experimentproposals.domain.request

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Ref, ReviewFlag}
import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

import java.time.Instant

/** One question as the model proposes it; the gate checks it against the facets. */
final case class QuestionProposal(
    question: String,
    facets: Vector[String],
    evidenceNeeded: Vector[String],
    answerFields: Vector[String],
    sufficiencyCriteria: Vector[String]
)

/**
 * The derivation agent's reply. `uninvestigated` names mandatory facets no question covers, and
 * why.
 */
final case class QuestionSet(
    questions: Vector[QuestionProposal],
    uninvestigated: Vector[String] = Vector.empty
)

final case class ScientificQuestion(
    id: String,
    registered: Boolean = false,
    requestId: String = "",
    question: String = "",
    facetRefs: Vector[Ref] = Vector.empty,
    facetNames: Vector[String] = Vector.empty,
    evidenceNeeded: Vector[String] = Vector.empty,
    answerFields: Vector[String] = Vector.empty,
    sufficiencyCriteria: Vector[String] = Vector.empty,
    status: QuestionStatus = QuestionStatus.unresolved,
    missingInformation: Vector[String] = Vector.empty,
    planId: Option[String] = None,
    advancedBy: Option[Ref] = None,
    flags: Vector[ReviewFlag] = Vector.empty,
    version: Int = 0
)

object ScientificQuestion:
  def empty(id: String): ScientificQuestion = ScientificQuestion(id)

enum QuestionEvent:
  case Derived(
      requestId: String,
      question: String,
      facetRefs: Vector[Ref],
      facetNames: Vector[String],
      evidenceNeeded: Vector[String],
      answerFields: Vector[String],
      sufficiencyCriteria: Vector[String],
      at: Instant
  )
  case PlanRecorded(planId: String)
  case StatusAdvanced(
      status: QuestionStatus,
      byRef: Option[Ref],
      by: Option[String],
      missingInformation: Vector[String],
      at: Instant
  )
  case FlaggedForReview(changedRef: Ref, reason: String, at: Instant)

final case class DeriveQuestion(
    requestId: String,
    question: String,
    facetRefs: Vector[Ref],
    facetNames: Vector[String],
    evidenceNeeded: Vector[String],
    answerFields: Vector[String],
    sufficiencyCriteria: Vector[String]
)
final case class AdvanceQuestion(
    status: QuestionStatus,
    byRef: Option[Ref],
    by: Option[String],
    missingInformation: Vector[String]
)

object QuestionTransitions:
  def allowed(from: QuestionStatus, to: QuestionStatus): Boolean = (from, to) match
    case (
          QuestionStatus.unresolved,
          QuestionStatus.partial | QuestionStatus.answered | QuestionStatus.requires_review
        ) =>
      true
    case (QuestionStatus.partial, QuestionStatus.answered | QuestionStatus.requires_review) => true
    case (QuestionStatus.requires_review, _)                                                => true
    case (a, b) if a == b                                                                   => true
    case _                                                                                  => false

object QuestionCodecs:
  given Serializer[ScientificQuestion] = Codecs.serializer[ScientificQuestion]("question")
  given Serializer[QuestionEvent]      = Codecs.serializer[QuestionEvent]("question-event")
  given Serializer[DeriveQuestion]     = Codecs.serializer[DeriveQuestion]("derive-question")
  given Serializer[AdvanceQuestion]    = Codecs.serializer[AdvanceQuestion]("advance-question")
  given Serializer[QuestionSet]        = Codecs.serializer[QuestionSet]("question-set")
