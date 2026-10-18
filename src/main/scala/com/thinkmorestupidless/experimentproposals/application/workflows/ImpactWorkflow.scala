package com.thinkmorestupidless.experimentproposals.application.workflows

import com.thinkmorestupidless.experimentproposals.application.entities.*
import com.thinkmorestupidless.experimentproposals.application.views.{DependentRow, Dependents}
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.RecordKind
import com.thinkmorestupidless.experimentproposals.domain.design.FlagDesign
import com.thinkmorestupidless.experimentproposals.domain.provenance.Ref
import com.thinkmorestupidless.experimentproposals.domain.request.FlagForReview
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*

import scala.concurrent.duration.*

final case class ImpactState(
    changedRef: Option[Ref] = None,
    reason: String = "",
    queue: Vector[Ref] = Vector.empty,
    visited: Vector[String] = Vector.empty,
    flagged: Vector[String] = Vector.empty,
    status: String = "not-started",
    pending: Vector[Ref] = Vector.empty
):
  def started: Boolean = status != "not-started"

final case class StartImpact(changedRef: Ref, reason: String)

/**
 * When a source, finding or claim changes, everything made from it is flagged for review, breadth
 * first through the dependents view, without rewriting anything (FR-045, SC-009). Each step walks a
 * bounded number of records and journals its progress, so a crash resumes where it was; a flag is
 * idempotent, so a step run twice flags nothing twice.
 */
final class ImpactWorkflow(context: WorkflowContext) extends Workflow[ImpactState]:

  private val client = context.componentClient
  private val Batch  = 20

  def emptyState: ImpactState = ImpactState()

  override def settings: WorkflowSettings =
    WorkflowSettings.builder.defaultStepTimeout(2.minutes).build

  def start(cmd: StartImpact): Effect[ImpactState] =
    if currentState.started then effects.reply(currentState)
    else
      effects
        .updateState(
          ImpactState(
            Some(cmd.changedRef),
            cmd.reason,
            Vector(cmd.changedRef),
            Vector.empty,
            Vector.empty,
            "walking"
          )
        )
        .transitionTo(ImpactWorkflow.walk.ref)
        .thenReplyState

  def status: ReadOnlyEffect[ImpactState] = effects.reply(currentState)

  private def views =
    Configured.views.getOrElse(throw IllegalStateException("the impact walk needs the view client"))

  private def flag(dependent: Ref, changed: Ref, reason: String): Unit =
    val id = EntityId(dependent.id)
    try
      dependent.kind match
        case RecordKind.finding | RecordKind.claim =>
          Records
            .companionFor(dependent.id)
            .foreach(c =>
              client
                .forEventSourcedEntity(id)
                .call(c.flagForReview)
                .invoke(FlagForReview(changed, reason))
            )
        case RecordKind.assessment =>
          client
            .forEventSourcedEntity(id)
            .call(AssessmentEntity.flagForReview)
            .invoke(FlagForReview(changed, reason)): Unit
        case RecordKind.sufficiency =>
          client
            .forEventSourcedEntity(id)
            .call(SufficiencyEntity.flagForReview)
            .invoke(FlagForReview(changed, reason)): Unit
        case RecordKind.`package` =>
          client
            .forEventSourcedEntity(id)
            .call(PackageEntity.flagForReview)
            .invoke(FlagForReview(changed, reason)): Unit
        case RecordKind.approach =>
          client
            .forEventSourcedEntity(id)
            .call(ApproachEntity.flagForReview)
            .invoke(FlagForReview(changed, reason)): Unit
        case RecordKind.design =>
          client
            .forEventSourcedEntity(id)
            .call(DesignEntity.flagForReview)
            .invoke(FlagDesign(changed, dependent.version, reason)): Unit
        case RecordKind.question =>
          client
            .forEventSourcedEntity(id)
            .call(QuestionEntity.flagForReview)
            .invoke(FlagForReview(changed, reason)): Unit
        case RecordKind.facet =>
          client
            .forEventSourcedEntity(id)
            .call(FacetEntity.flagForReview)
            .invoke(FlagForReview(changed, reason)): Unit
        case _ => ()
    catch case e: CommandError if e.code == ErrorCode.NotFound => ()

  /**
   * One short step at a time, each journalled: either flag a bounded batch of the dependents
   * already listed, or list the dependents of the next record to expand. A store with many
   * dependents takes more steps, never a longer one.
   */
  def walkStep: StepEffect =
    val changed = currentState.changedRef.getOrElse(throw IllegalStateException("no changed ref"))
    if currentState.pending.nonEmpty then
      val (now, later) = currentState.pending.splitAt(Batch)
      var flagged      = currentState.flagged
      val expand       = Vector.newBuilder[Ref]
      now.foreach { dependent =>
        if !flagged.contains(dependent.key) then
          flag(dependent, changed, currentState.reason)
          flagged = flagged :+ dependent.key
        if ImpactWorkflow.expandable(dependent.kind) && !currentState.visited.contains(
            dependent.unversioned
          )
        then expand += dependent
      }
      val queue = (currentState.queue ++ expand.result()).distinctBy(_.unversioned)
      stepEffects
        .updateState(currentState.copy(pending = later, queue = queue, flagged = flagged))
        .thenTransitionTo(ImpactWorkflow.walk.ref)
    else
      // Expand a few records at once; nothing depends on a design, a decision or a sufficiency judgement.
      val toExpand = currentState.queue
        .filterNot(r => currentState.visited.contains(r.unversioned))
        .filter(r => ImpactWorkflow.expandable(r.kind))
        .take(5)
      if toExpand.isEmpty then
        stepEffects.updateState(currentState.copy(queue = Vector.empty, status = "done")).thenEnd
      else
        val dependents = toExpand
          .flatMap { ref =>
            val rows: Vector[DependentRow] =
              views.forView(Dependents).ask(Dependents.ofAnyVersion, 5000, "ref" -> ref.unversioned)
            rows.map(row =>
              Ref(RecordKind.valueOf(row.dependentKind), row.dependentId, row.dependentVersion)
            )
          }
          .distinctBy(_.key)
        val expanded = toExpand.map(_.unversioned)
        stepEffects
          .updateState(
            currentState.copy(
              queue = currentState.queue.filterNot(r => expanded.contains(r.unversioned)),
              visited = currentState.visited ++ expanded,
              pending = dependents
            )
          )
          .thenTransitionTo(ImpactWorkflow.walk.ref)

object ImpactWorkflow
    extends Workflow.Companion[ImpactWorkflow, ImpactState](
      componentId = ComponentId("impact"),
      stateSerializer = Codecs.serializer[ImpactState]("impact-state")
    ):
  given Serializer[StartImpact] = Codecs.serializer[StartImpact]("start-impact")

  /** The kinds something else can depend on; a design, a decision or a judgement is a leaf. */
  def expandable(kind: RecordKind): Boolean = kind match
    case RecordKind.design | RecordKind.decision | RecordKind.sufficiency | RecordKind.stage_run =>
      false
    case _ => true

  /** One run per change: the changed version plus the event that announced it. */
  def idFor(changed: Ref, sequence: Long): String =
    s"impact:${changed.kind}:${changed.id}:v${changed.version}:s$sequence"

  def create(context: WorkflowContext) = new ImpactWorkflow(context)

  val walk   = step("walk")(_.walkStep)
  val start  = command("start")(_.start)
  val status = query("status")(_.status)
