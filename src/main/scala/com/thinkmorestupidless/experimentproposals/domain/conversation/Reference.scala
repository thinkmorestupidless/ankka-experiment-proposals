package com.thinkmorestupidless.experimentproposals.domain.conversation

/**
 * The kinds of record a card is made for (contracts/assistant.md). Each wire name is what a
 * reference marker and the interface use; for `facets`, `candidates`, `decision` and `impact` the
 * id a reference carries is the request's.
 */
enum CardKind(val wire: String):
  case request          extends CardKind("request")
  case facets           extends CardKind("facets")
  case question         extends CardKind("question")
  case search_plan      extends CardKind("search-plan")
  case search_execution extends CardKind("search-execution")
  case source           extends CardKind("source")
  case finding          extends CardKind("finding")
  case claim            extends CardKind("claim")
  case assessment       extends CardKind("assessment")
  case sufficiency      extends CardKind("sufficiency")
  case `package`        extends CardKind("package")
  case candidates       extends CardKind("candidates")
  case decision         extends CardKind("decision")
  case design           extends CardKind("design")
  case impact           extends CardKind("impact")
  case stage_run        extends CardKind("stage-run")

object CardKind:
  def fromWire(s: String): Option[CardKind] = values.find(_.wire == s)

/** A typed pointer in an answer to a record; `resolved` once the service has read it. */
final case class Reference(
    kind: String,
    id: String,
    version: Option[Int] = None,
    resolved: Boolean = false
)

/** The forms a prefill may fill in; the reviewer always completes them. */
enum DecisionKind(val wire: String):
  case facet_review     extends DecisionKind("facet-review")
  case facets_confirmed extends DecisionKind("facets-confirmed")
  case record_review    extends DecisionKind("record-review")
  case decide           extends DecisionKind("decide")
  case approval         extends DecisionKind("approval")

object DecisionKind:
  def fromWire(s: String): Option[DecisionKind] = values.find(_.wire == s)

/** A decision's form filled in from what a person typed; it records nothing. */
final case class Prefill(decision: String, target: String, fields: Map[String, String])
