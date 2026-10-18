package com.thinkmorestupidless.experimentproposals.rules

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Defect, Ids, Ref}
import com.thinkmorestupidless.experimentproposals.domain.request.*

/**
 * The question gate (FR-005, FR-006): every question investigates at least one facet of the set and
 * carries its evidence needed and answer fields; every mandatory facet is investigated by at least
 * one question or named as uninvestigated. Questions are not search queries (FR-007): nothing here
 * runs one.
 */
object QuestionRules:
  val investigates     = Rule("questions.investigates-a-facet", 1)
  val fields           = Rule("questions.answer-fields", 1)
  val coverage         = Rule("questions.mandatory-coverage", 1)
  val ruleSet: RuleSet = RuleSet("questions", 1, Vector(investigates, fields, coverage))

  def marker(text: String, facets: Vector[Facet]): String =
    val lines = facets
      .filter(_.status != FacetStatus.rejected)
      .sortBy(_.name)
      .map(f => s"${f.name}=${f.statement}")
      .mkString("\n")
    s"questions:${Ids.short(text + "\n" + lines)}"

  def gate(
      requestId: String,
      facets: Vector[Facet],
      set: QuestionSet
  ): Either[Defect, Vector[DeriveQuestion]] =
    val live   = facets.filter(_.status != FacetStatus.rejected)
    val byName = live.map(f => f.name -> f).toMap
    if set.questions.isEmpty then
      Left(Rules.defect(DefectKind.schema_invalid, "no questions were derived", retryable = true))
    else
      val derived = set.questions.map { q =>
        val names   = q.facets.map(_.trim.toLowerCase).distinct
        val unknown = names.filterNot(byName.contains)
        if q.question.isBlank then Left(s"a question has no text")
        else if names.isEmpty then Left(s"question '${q.question}' investigates no facet")
        else if unknown.nonEmpty then
          Left(
            s"question '${q.question}' names facets the set does not have: ${unknown.mkString(", ")}"
          )
        else if q.answerFields.isEmpty || q.evidenceNeeded.isEmpty then
          Left(s"question '${q.question}' lacks its evidence needed or answer fields")
        else
          Right(
            DeriveQuestion(
              requestId,
              q.question.trim,
              names.map(n => Ref(RecordKind.facet, byName(n).id, byName(n).version)),
              names,
              q.evidenceNeeded.map(_.trim),
              q.answerFields.map(_.trim.toLowerCase),
              q.sufficiencyCriteria.map(_.trim)
            )
          )
      }
      derived.collectFirst { case Left(problem) => problem } match
        case Some(problem) =>
          Left(Rules.defect(DefectKind.schema_invalid, problem, retryable = true))
        case None =>
          val ok = derived.collect { case Right(d) => d }
          val investigated =
            ok.flatMap(_.facetNames).toSet ++ set.uninvestigated.map(_.trim.toLowerCase)
          val missing = live
            .filter(f => f.importance == Importance.mandatory && !investigated.contains(f.name))
            .map(_.name)
          if missing.nonEmpty then
            Left(
              Rules.defect(
                DefectKind.coverage_gap,
                s"mandatory facets no question investigates: ${missing.mkString(", ")}",
                retryable = true
              )
            )
          else Right(ok)
