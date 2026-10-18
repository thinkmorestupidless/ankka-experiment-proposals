package com.thinkmorestupidless.experimentproposals.rules

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{Defect, Ids}
import com.thinkmorestupidless.experimentproposals.domain.request.*

/**
 * The facet gate (FR-002, FR-003): the closed lists hold, the eight facets of the schema are
 * present, and every explicit facet quotes the request at a place that resolves. The round-trip
 * check passes only when its four lists are empty; similarity is never consulted.
 */
object FacetRules:
  val closedLists  = Rule("facets.closed-lists", 1)
  val schemaSet    = Rule("facets.schema-set", 1)
  val explicitSpan = Rule("facets.explicit-spans-resolve", 1)
  val roundTrip    = Rule("facets.round-trip-pass", 1)
  val ruleSet: RuleSet =
    RuleSet("facets", 1, Vector(closedLists, schemaSet, explicitSpan, roundTrip))

  /** The facet schema every request is interpreted into (source document, section 3). */
  val Names: Vector[String] = Vector(
    "product",
    "production approach",
    "biological source",
    "objective",
    "desired attributes",
    "time",
    "success",
    "resources"
  )

  def marker(text: String): String = s"facets:${Ids.short(text)}"

  /** The round-trip check's case marker: the text and the facets it is checked against. */
  def roundTripMarker(text: String, facets: Vector[(String, String)]): String =
    val lines = facets.sortBy(_._1).map((name, statement) => s"$name=$statement").mkString("\n")
    s"roundtrip:${Ids.short(text + "\n" + lines)}"

  /** Where a quotation sits in the text, whitespace-tolerant; `None` when it is not there. */
  def locate(text: String, quotation: String): Option[Span] =
    val q = quotation.trim
    if q.isEmpty then None
    else
      val exact = text.indexOf(q)
      if exact >= 0 then Some(Span(exact, exact + q.length, q))
      else
        val pattern = q.split("\\s+").map(java.util.regex.Pattern.quote).mkString("\\s+").r
        pattern
          .findFirstMatchIn(text)
          .map(m => Span(m.start, m.end, text.substring(m.start, m.end)))

  /**
   * The gate: a `Left` is the classified defect; a `Right` is what the facet entities are proposed
   * from.
   */
  def gate(requestId: String, text: String, set: FacetSet): Either[Defect, Vector[ProposeFacet]] =
    val parsed = set.facets.map { f =>
      for
        origin <- FacetOrigin.values
          .find(_.toString == f.origin.trim.replace('-', '_').replace(' ', '_'))
          .toRight(
            s"facet '${f.name}': origin '${f.origin}' is not one of ${FacetOrigin.values.mkString(", ")}"
          )
        importance <- Importance.values
          .find(_.toString == f.importance.trim)
          .toRight(
            s"facet '${f.name}': importance '${f.importance}' is not one of ${Importance.values.mkString(", ")}"
          )
        status <- FacetStatus.values
          .find(_.toString == f.status.trim)
          .toRight(
            s"facet '${f.name}': status '${f.status}' is not one of ${FacetStatus.values.mkString(", ")}"
          )
      yield (f, origin, importance, status)
    }
    parsed.collectFirst { case Left(problem) => problem } match
      case Some(problem) => Left(Rules.defect(DefectKind.schema_invalid, problem, retryable = true))
      case None =>
        val ok      = parsed.collect { case Right(v) => v }
        val names   = ok.map(_._1.name.trim.toLowerCase)
        val missing = Names.filterNot(names.contains)
        if missing.nonEmpty then
          Left(
            Rules.defect(
              DefectKind.schema_invalid,
              s"facets missing from the set: ${missing.mkString(", ")}",
              retryable = true
            )
          )
        else
          val spans = ok.map { case (f, origin, importance, status) =>
            val located = f.quotations.map(q => q -> locate(text, q))
            located.collectFirst { case (q, None) if origin == FacetOrigin.explicit => q } match
              case Some(q) =>
                Left(s"facet '${f.name}' is explicit but its quotation is not in the request: '$q'")
              case None =>
                Right(
                  ProposeFacet(
                    requestId,
                    f.name.trim.toLowerCase,
                    f.statement.trim,
                    origin,
                    importance,
                    status,
                    located.flatMap(_._2),
                    f.dependencies.map(_.trim.toLowerCase)
                  )
                )
          }
          spans.collectFirst { case Left(problem) => problem } match
            case Some(problem) =>
              Left(Rules.defect(DefectKind.span_unresolved, problem, retryable = true))
            case None =>
              val explicitWithoutSpan = spans.collect {
                case Right(p) if p.origin == FacetOrigin.explicit && p.spans.isEmpty => p.name
              }
              if explicitWithoutSpan.nonEmpty then
                Left(
                  Rules.defect(
                    DefectKind.span_unresolved,
                    s"explicit facets quote nothing: ${explicitWithoutSpan.mkString(", ")}",
                    retryable = true
                  )
                )
              else Right(spans.collect { case Right(p) => p })

  def passed(check: RoundTripCheck): Boolean =
    check.omissions.isEmpty && check.unsupportedAdditions.isEmpty && check.contradictions.isEmpty && check.ambiguities.isEmpty

  /** The accounting a proposal carries (FR-039, SC-001). */
  def proposalStatus(requestId: String, facets: Vector[Facet]): ProposalStatus =
    val mandatory = facets.filter(_.importance == Importance.mandatory)
    ProposalStatus(
      requestId = requestId,
      confirmed = mandatory.filter(_.status == FacetStatus.confirmed).map(_.name),
      assumedWithApproval = mandatory.filter(_.status == FacetStatus.assumed).map(_.name),
      unresolvedAndFlagged = mandatory
        .filter(f => f.status == FacetStatus.unresolved || f.status == FacetStatus.ambiguous)
        .map(_.name),
      stillProposed = mandatory.filter(_.status == FacetStatus.proposed).map(_.name),
      assumedValues = mandatory.flatMap(f => f.assumedValue.map(v => f.name -> v)).toMap
    )
