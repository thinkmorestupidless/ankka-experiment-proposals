package com.thinkmorestupidless.experimentproposals.application.views

import com.thinkmorestupidless.experimentproposals.application.entities.*
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.RecordKind
import com.thinkmorestupidless.experimentproposals.domain.design.{
  ApproachEvent,
  DesignEvent,
  PackageEvent
}
import com.thinkmorestupidless.experimentproposals.domain.evidence.{
  AssessmentEvent,
  RecordEvent,
  SufficiencyEvent
}
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ref
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

/** One edge of the dependency graph: `dependent` was made from `on` (FR-045). */
final case class DependentRow(
    key: String,
    onKind: String,
    onId: String,
    onVersion: Int,
    onKey: String,
    onUnversioned: String,
    dependentKind: String,
    dependentId: String,
    dependentVersion: Int,
    dependentKey: String
)

object DependentRow:
  def of(on: Ref, dependent: Ref): (String, DependentRow) =
    val key = s"${on.key}|${dependent.key}"
    key -> DependentRow(
      key,
      on.kind.toString,
      on.id,
      on.version,
      on.key,
      on.unversioned,
      dependent.kind.toString,
      dependent.id,
      dependent.version,
      dependent.key
    )

/**
 * A keyed view over every record kind that depends on another: one row per `(on, dependent)` from
 * the `dependsOn` each record is proposed, judged, built or generated with. The impact workflow
 * walks it; nothing decides on it.
 */
final class DependentsView extends KeyedView[DependentRow]:

  private def rows(dependent: Ref, on: Vector[Ref]): Effect =
    val edges =
      on.distinct.filterNot(_.key == dependent.key).map(o => DependentRow.of(o, dependent))
    if edges.isEmpty then effects.ignore() else effects.updateRows(edges)

  def onFinding(event: RecordEvent, change: Change): Effect =
    onRecord(RecordKind.finding, event, change)
  def onClaim(event: RecordEvent, change: Change): Effect =
    onRecord(RecordKind.claim, event, change)

  private def onRecord(kind: RecordKind, event: RecordEvent, change: Change): Effect = event match
    case RecordEvent.Proposed(_, sourceRef, content, spans, _, dependsOn, _) =>
      val claimRefs = content match
        case com.thinkmorestupidless.experimentproposals.domain.evidence.RecordContent.OfClaim(c) =>
          c.findingRefs ++ c.derivedFromClaimRefs
        case _ => Vector.empty
      rows(
        Ref(kind, change.subject, 1),
        (sourceRef +: dependsOn) ++ claimRefs ++ spans.map(s =>
          Ref(RecordKind.source, s.sourceId, s.sourceVersion)
        )
      )
    case _ => effects.ignore()

  def onAssessment(event: AssessmentEvent, change: Change): Effect = event match
    case AssessmentEvent.Proposed(questionRef, recordRef, _, _, _, _, _, _, _, dependsOn, _) =>
      rows(
        Ref(RecordKind.assessment, change.subject, 1),
        Vector(questionRef, recordRef) ++ dependsOn
      )
    case _ => effects.ignore()

  def onSufficiency(event: SufficiencyEvent, change: Change): Effect = event match
    case SufficiencyEvent.Judged(
          questionRef,
          _,
          _,
          _,
          _,
          assessmentRefs,
          _,
          _,
          _,
          _,
          _,
          dependsOn,
          _
        ) =>
      rows(
        Ref(RecordKind.sufficiency, change.subject, 1),
        (questionRef +: assessmentRefs) ++ dependsOn
      )
    case _ => effects.ignore()

  def onPackage(event: PackageEvent, change: Change): Effect = event match
    case PackageEvent.Built(
          questionRefs,
          requestRef,
          facetRefs,
          assessmentRefs,
          recordRefs,
          spans,
          _,
          _,
          _,
          _,
          _,
          _,
          _,
          _,
          dependsOn,
          _
        ) =>
      rows(
        Ref(RecordKind.`package`, change.subject, 1),
        questionRefs ++ facetRefs ++ assessmentRefs ++ recordRefs ++ dependsOn ++ spans.map(s =>
          Ref(RecordKind.source, s.sourceId, s.sourceVersion)
        ) :+ requestRef
      )
    case _ => effects.ignore()

  def onApproach(event: ApproachEvent, change: Change): Effect = event match
    case ApproachEvent.Proposed(requestRef, packageRefs, content, _, dependsOn, _) =>
      rows(
        Ref(RecordKind.approach, change.subject, 1),
        (requestRef +: packageRefs) ++ dependsOn ++ content.affirmativeClaimRefs ++ content.contraryClaimRefs ++ content.indirectClaimRefs
      )
    case _ => effects.ignore()

  def onDesign(event: DesignEvent, change: Change): Effect = event match
    case DesignEvent.DraftGenerated(
          requestRef,
          version,
          approachRef,
          decisionRef,
          sections,
          _,
          _,
          _,
          _,
          _
        ) =>
      val manifest = sections.evidenceManifest
      rows(
        Ref(RecordKind.design, change.subject, version),
        Vector(requestRef, approachRef, decisionRef) ++ manifest.claimRefs ++ manifest.spans.map(
          s => Ref(RecordKind.source, s.sourceId, s.sourceVersion)
        )
      )
    case _ => effects.ignore()

object Dependents
    extends KeyedView.Companion[DependentsView, DependentRow](
      ComponentId("dependents"),
      Codecs.serializer[DependentRow]("dependent-row")
    ):

  val findings    = source(ChangeSource.eventsOf(FindingEntity))(_.onFinding)
  val claims      = source(ChangeSource.eventsOf(ClaimEntity))(_.onClaim)
  val assessments = source(ChangeSource.eventsOf(AssessmentEntity))(_.onAssessment)
  val sufficiency = source(ChangeSource.eventsOf(SufficiencyEntity))(_.onSufficiency)
  val packages    = source(ChangeSource.eventsOf(PackageEntity))(_.onPackage)
  val approaches  = source(ChangeSource.eventsOf(ApproachEntity))(_.onApproach)
  val designs     = source(ChangeSource.eventsOf(DesignEntity))(_.onDesign)

  /** Everything made from exactly this version of a record. */
  val of = query("of")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'onKey' = :ref ORDER BY row_key"
  )

  /** Everything made from any version of a record. */
  val ofAnyVersion = query("of-any-version")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'onUnversioned' = :ref ORDER BY row_key"
  )

  def create(ctx: ViewComponentContext) = new DependentsView
