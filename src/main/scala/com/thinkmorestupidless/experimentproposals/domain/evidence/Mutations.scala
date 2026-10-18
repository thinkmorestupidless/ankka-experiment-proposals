package com.thinkmorestupidless.experimentproposals.domain.evidence

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*
import com.thinkmorestupidless.experimentproposals.domain.sources.SourceSpan

/** The six named faults (glossary: fault), each a pure change to a record's content or spans. */
object Mutations:
  val names: Vector[String] = Vector(
    "endpoint_swap",
    "negation_flip",
    "species_drop",
    "failure_to_success",
    "span_break",
    "citation_mismatch"
  )

  /**
   * The defect kind each fault is expected to be caught as
   * (features/assurance/fault-injection.feature).
   */
  def expectedDetection(name: String): Option[DefectKind] = name match
    case "endpoint_swap"      => Some(DefectKind.endpoint_mismatch)
    case "negation_flip"      => Some(DefectKind.negation_error)
    case "species_drop"       => Some(DefectKind.species_overgeneralisation)
    case "failure_to_success" => Some(DefectKind.failure_as_success)
    case "span_break"         => Some(DefectKind.span_unresolved)
    case "citation_mismatch"  => Some(DefectKind.unsupported_by_span)
    case _                    => None

  def apply(
      name: String,
      content: RecordContent,
      spans: Vector[SourceSpan]
  ): Either[String, (RecordContent, Vector[SourceSpan])] =
    (name, content) match
      case ("endpoint_swap", RecordContent.OfFinding(f)) =>
        Right(
          RecordContent.OfFinding(
            f.copy(observedEndpoint =
              if f.observedEndpoint == "proliferation" then "lipid accumulation"
              else "proliferation"
            )
          ) -> spans
        )
      case ("negation_flip", RecordContent.OfFinding(f)) =>
        val flipped = f.reportedOutcome
          .replaceAll("(?i)did not increase", "increased")
          .replaceAll("(?i)no increase", "an increase")
          .replaceAll("(?i)unsuccessful", "successful")
        Right(
          RecordContent.OfFinding(
            f.copy(
              reportedOutcome = flipped,
              outcomeDirection =
                if f.outcomeDirection == OutcomeDirection.decrease || f.outcomeDirection == OutcomeDirection.no_change
                then OutcomeDirection.increase
                else f.outcomeDirection
            )
          ) -> spans
        )
      case ("species_drop", RecordContent.OfFinding(f)) =>
        Right(
          RecordContent.OfFinding(
            f.copy(
              species = None,
              qualifications = f.qualifications.filterNot(_.toLowerCase.contains("species"))
            )
          ) -> spans
        )
      case ("species_drop", RecordContent.OfClaim(c)) =>
        Right(
          RecordContent.OfClaim(
            c.copy(
              speciesScope = None,
              qualifications = c.qualifications.filterNot(q =>
                q.toLowerCase.contains("species") || q.toLowerCase.contains("human")
              )
            )
          ) -> spans
        )
      case ("failure_to_success", RecordContent.OfFinding(f)) =>
        Right(
          RecordContent.OfFinding(
            f.copy(
              reportedOutcome = f.reportedOutcome
                .replaceAll("(?i)unsuccessful", "successful")
                .replaceAll("(?i)fell below", "rose above"),
              outcomeDirection = OutcomeDirection.increase
            )
          ) -> spans
        )
      case ("condition_drop", RecordContent.OfFinding(f)) =>
        // Not one of the six reference faults: a retryable defect, for the bounded-retry scenario.
        Right(
          RecordContent.OfFinding(
            f.copy(
              conditions = Vector.empty,
              reportedOutcome = f.reportedOutcome + " under all conditions"
            )
          ) -> spans
        )
      case ("span_break", _) =>
        Right(
          content -> spans.map(s =>
            s.copy(startOffset = s.startOffset + 7, endOffset = s.endOffset + 7)
          )
        )
      case ("citation_mismatch", _) =>
        // The span still resolves, but points at another passage: the caller fills the quotation from the source.
        Right(
          content -> spans
            .take(1)
            .map(s => s.copy(sectionId = "sec-3", startOffset = 0, endOffset = 40, quotation = ""))
        )
      case (other, _) if !(names :+ "condition_drop").contains(other) =>
        Left(s"no mutation named '$other'; one of ${names.mkString(", ")}")
      case (other, _) =>
        Left(s"the mutation '$other' does not apply to a ${content.getClass.getSimpleName}")
