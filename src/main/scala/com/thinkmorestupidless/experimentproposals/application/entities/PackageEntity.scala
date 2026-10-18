package com.thinkmorestupidless.experimentproposals.application.entities

import com.thinkmorestupidless.experimentproposals.domain.design.*
import com.thinkmorestupidless.experimentproposals.domain.design.PackageCodecs.given
import com.thinkmorestupidless.experimentproposals.domain.provenance.ReviewFlag
import com.thinkmorestupidless.experimentproposals.domain.provenance.Refs.given
import com.thinkmorestupidless.experimentproposals.domain.request.FlagForReview
import com.thinkmorestupidless.experimentproposals.domain.request.FacetCodecs.given
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/** An evidence package (FR-033, FR-034): built once, validated, `valid` only on every check. */
final class PackageEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[EvidencePackage, PackageEvent]:

  def emptyState: EvidencePackage = EvidencePackage.empty(context.entityId)

  def applyEvent(event: PackageEvent): EvidencePackage = event match
    case PackageEvent.Built(
          qs,
          req,
          facets,
          assessments,
          records,
          spans,
          excluded,
          contra,
          constraints,
          open,
          gaps,
          manifest,
          trims,
          by,
          dependsOn,
          _
        ) =>
      currentState.copy(
        registered = true,
        questionRefs = qs,
        requestRef = Some(req),
        facetRefs = facets,
        assessmentRefs = assessments,
        recordRefs = records,
        includedSpans = spans,
        excluded = excluded,
        contradictoryAssessmentRefs = contra,
        resourceConstraints = constraints,
        openQuestionIds = open,
        gapIds = gaps,
        provenanceManifest = manifest,
        trims = trims,
        builtBy = by,
        dependsOn = dependsOn,
        version = 1
      )
    case PackageEvent.Validated(checks, ruleSet, quality, _) =>
      currentState.copy(
        checks = checks,
        ruleSetVersion = Some(ruleSet),
        qualityStatus = quality,
        version = currentState.version + 1
      )
    case PackageEvent.ApproachLinked(ref) => currentState.copy(approachRef = Some(ref))
    case PackageEvent.FlaggedForReview(ref, reason, at) =>
      currentState.copy(flags = currentState.flags :+ ReviewFlag(ref, reason, at))

  def build(request: BuildPackage): Effect[EvidencePackage] =
    if currentState.registered then effects.reply(currentState)
    else if request.questionRefs.isEmpty then effects.error("a package names its question")
    else
      effects
        .persist(
          PackageEvent.Built(
            request.questionRefs,
            request.requestRef,
            request.facetRefs,
            request.assessmentRefs,
            request.recordRefs,
            request.includedSpans,
            request.excluded,
            request.contradictoryAssessmentRefs,
            request.resourceConstraints,
            request.openQuestionIds,
            request.gapIds,
            request.provenanceManifest,
            request.trims,
            request.builtBy,
            request.dependsOn,
            Instant.now()
          )
        )
        .thenReplyState

  /** The entity, not the caller, decides the status from the checks and its own trims (FR-034). */
  def validate(request: ValidatePackage): Effect[EvidencePackage] =
    if !currentState.registered then effects.error("no such package", ErrorCode.NotFound)
    else if request.checks.isEmpty then effects.error("a validation carries its checks")
    else
      effects
        .persist(
          PackageEvent.Validated(
            request.checks,
            request.ruleSetVersion,
            EvidencePackage.quality(request.checks, currentState.trims),
            Instant.now()
          )
        )
        .thenReplyState

  def linkApproach(
      ref: com.thinkmorestupidless.experimentproposals.domain.provenance.Ref
  ): Effect[Done] =
    if currentState.approachRef.contains(ref) then effects.reply(Done)
    else effects.persist(PackageEvent.ApproachLinked(ref)).thenReply(_ => Done)

  def flagForReview(request: FlagForReview): Effect[Done] =
    if currentState.flags.exists(_.changedRef == request.changedRef) then effects.reply(Done)
    else
      effects
        .persist(PackageEvent.FlaggedForReview(request.changedRef, request.reason, Instant.now()))
        .thenReply(_ => Done)

  def get: ReadOnlyEffect[EvidencePackage] = effects.reply(currentState)

object PackageEntity
    extends EventSourcedEntity.Companion[PackageEntity, EvidencePackage, PackageEvent](
      componentId = ComponentId("package"),
      stateSerializer = summon[Serializer[EvidencePackage]],
      eventSerializer = summon[Serializer[PackageEvent]]
    ):
  def create(context: EventSourcedEntityContext) = new PackageEntity(context)

  val build         = command("build")(_.build)
  val validate      = command("validate")(_.validate)
  val linkApproach  = command("link-approach")(_.linkApproach)
  val flagForReview = command("flag-for-review")(_.flagForReview)
  val get           = query("get")(_.get)
