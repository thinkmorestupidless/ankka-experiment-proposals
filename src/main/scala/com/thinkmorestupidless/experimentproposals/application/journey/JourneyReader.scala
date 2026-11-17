package com.thinkmorestupidless.experimentproposals.application.journey

import com.thinkmorestupidless.experimentproposals.application.entities.{
  DesignEntity,
  PackageEntity,
  QuestionEntity,
  RequestEntity,
  StageRunEntity
}
import com.thinkmorestupidless.experimentproposals.application.views.StageRows
import com.thinkmorestupidless.experimentproposals.application.workflows.{
  InvestigationState,
  InvestigationWorkflow,
  QuestionInvestigationState,
  QuestionInvestigationWorkflow
}
import com.thinkmorestupidless.experimentproposals.config.Configured
import com.thinkmorestupidless.experimentproposals.domain.design.DesignState
import com.thinkmorestupidless.experimentproposals.domain.journey.{Journey, Standing}
import com.thinkmorestupidless.experimentproposals.domain.provenance.StageRun
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, WorkflowLifecycle}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
 * Gathers what the journey is derived from and derives it (contracts/journey.md). Every read is a
 * query; nothing is written. `watch` re-derives at once on a stage of the request's run or a
 * question's run, and on a tick otherwise, because a workflow's state is not watchable here
 * (notes/ankka-requests.md, 2026-11-04).
 */
final class JourneyReader(client: ComponentClient, views: ViewClient)(using ExecutionContext):

  /** The journey now, or `None` when no request has the id. */
  def journey(requestId: String): Future[Option[Journey]] =
    client
      .forEventSourcedEntity(EntityId(requestId))
      .call(RequestEntity.get)
      .invokeAsync()
      .flatMap { req =>
        if !req.registered then Future.successful(None)
        else derive(requestId).map(Some(_))
      }

  private def derive(requestId: String): Future[Journey] =
    val wf = client.forWorkflow(EntityId(requestId))
    for
      state    <- orElse(wf.call(InvestigationWorkflow.status).invokeAsync(), InvestigationState())
      standing <- standingOf(wf.lifecycle(InvestigationWorkflow).invokeAsync())
      run      <- runOf(s"run-$requestId")
      lanes    <- Future.traverse(state.questionIds)(lane)
      flags    <- impactFlags(requestId, state)
    yield JourneyDerivation.derive(
      requestId,
      state,
      standing,
      run,
      lanes,
      flags,
      Configured.decisions.size
    )

  private def lane(qid: String): Future[(String, JourneyDerivation.Lane)] =
    val wf = client.forWorkflow(EntityId(qid))
    for
      text <- orElse(
        client
          .forEventSourcedEntity(EntityId(qid))
          .call(QuestionEntity.get)
          .invokeAsync()
          .map(_.question),
        ""
      )
      state <- orElse(
        wf.call(QuestionInvestigationWorkflow.status).invokeAsync(),
        QuestionInvestigationState(questionId = qid)
      )
      standing <- standingOf(wf.lifecycle(QuestionInvestigationWorkflow).invokeAsync())
      run      <- runOf(if state.runId.nonEmpty then state.runId else s"run-$qid")
    yield qid -> JourneyDerivation.Lane(text, state, standing, run)

  /** Flags on the request's packages and its design: impact is reached when one exists. */
  private def impactFlags(requestId: String, state: InvestigationState): Future[Int] =
    val packages = Future.traverse(state.packageIds) { id =>
      orElse(
        client
          .forEventSourcedEntity(EntityId(id))
          .call(PackageEntity.get)
          .invokeAsync()
          .map(_.flags.size),
        0
      )
    }
    val design =
      if state.designVersions == 0 then Future.successful(0)
      else
        orElse(
          client
            .forEventSourcedEntity(EntityId(DesignState.idFor(requestId)))
            .call(DesignEntity.get)
            .invokeAsync()
            .map(_.flags.size),
          0
        )
    packages.zipWith(design)(_.sum + _)

  private def runOf(runId: String): Future[StageRun] =
    orElse(
      client.forEventSourcedEntity(EntityId(runId)).call(StageRunEntity.get).invokeAsync(),
      StageRun.empty(runId)
    ).map(r => if r.runId.isEmpty then r.copy(runId = runId) else r)

  private def standingOf(f: Future[WorkflowLifecycle]): Future[Standing] =
    orElse(f.map(l => Standing(l.status, l.pendingStep, l.failure)), Standing.none)

  /** A record or workflow that does not exist yet reads as its empty state. */
  private def orElse[A](f: Future[A], empty: => A): Future[A] =
    f.recover {
      case _: CommandError => empty
      case NonFatal(_)     => empty
    }

  /**
   * The journey now, then each time it differs: at once on a stage of the request's runs, and
   * within `tick` of any other change. Ends when the reader stops reading.
   */
  def watch(requestId: String, tick: FiniteDuration = 2.seconds): Source[Journey, NotUsed] =
    val stages = views
      .forView(StageRows)
      .watch(StageRows.byRun, "run" -> s"run-$requestId", "lanes" -> s"run-$requestId:%")
      .map(_ => ())
      .recoverWithRetries(-1, { case NonFatal(_) => Source.never[Unit] })
    Source
      .tick(Duration.Zero, tick, ())
      .mapMaterializedValue(_ => NotUsed)
      .merge(stages)
      .conflate((a, _) => a)
      .mapAsync(1)(_ => derive(requestId))
      .statefulMap(() => Option.empty[Journey])(
        (last, j) => if last.contains(j) then (last, None) else (Some(j), Some(j)),
        _ => None
      )
      .collect { case Some(j) => j }
