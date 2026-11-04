package com.thinkmorestupidless.experimentproposals.application.journey

import com.thinkmorestupidless.experimentproposals.application.workflows.{
  InvestigationState,
  QuestionInvestigationState
}
import com.thinkmorestupidless.experimentproposals.domain.journey.*
import com.thinkmorestupidless.experimentproposals.domain.journey.NodeState.*
import com.thinkmorestupidless.experimentproposals.domain.provenance.{StageExecution, StageRun}

/**
 * The journey as a pure function of what the workflows recorded (contracts/journey.md): the
 * investigation's state and standing, each question's, the stage runs, and the impact flags. The
 * engine's pending step decides where a workflow is when it is known; the status the workflow
 * recorded decides otherwise. The reader gathers the inputs; nothing here reads or writes.
 */
object JourneyDerivation:

  /** One question as the derivation needs it. */
  final case class Lane(
      text: String,
      state: QuestionInvestigationState,
      standing: Standing,
      run: StageRun
  )

  def derive(
      requestId: String,
      investigation: InvestigationState,
      standing: Standing,
      run: StageRun,
      questions: Vector[(String, Lane)],
      impactFlags: Int,
      decisions: Int
  ): Journey =
    val lanes =
      questions.map((qid, lane) => QuestionLane(qid, lane.text, laneNodes(lane, decisions)))
    Journey(requestId, spineNodes(investigation, standing, run, lanes, impactFlags), lanes)

  // ── the spine ─────────────────────────────────────────────────────────────

  /** The spine stage each recorded stage name belongs to. */
  private val spineRunStage: Map[String, String] = Map(
    "extract-facets"   -> "facets",
    "round-trip"       -> "round-trip",
    "derive-questions" -> "questions",
    "build-packages"   -> "packages",
    "candidates"       -> "candidates",
    "design"           -> "design"
  )

  /**
   * The spine stage each investigation step belongs to; `record-failure` is decided by the defect.
   */
  private val spineStepStage: Map[String, String] = Map(
    "extract-facets"        -> "facets",
    "round-trip"            -> "round-trip",
    "await-facet-review"    -> "facet-review",
    "derive-questions"      -> "questions",
    "investigate-questions" -> "questions",
    "build-packages"        -> "packages",
    "candidates"            -> "candidates",
    "await-decision"        -> "decision",
    "design"                -> "design",
    "await-approval"        -> "approval",
    "close"                 -> "approval"
  )

  private val pauses = Set("facet-review", "decision", "approval")

  /** The spine stage a recorded failure names: `<stage>: <kind> (<detail>)`. */
  private def failedStage(failure: Option[String]): String =
    val stage = failure.map(_.takeWhile(_ != ':').trim).getOrElse("")
    spineRunStage.getOrElse(stage, "facets")

  private def byStatus(s: InvestigationState): (String, NodeState) = s.status match
    case "extracting-facets"                              => ("facets", current)
    case "round-trip"                                     => ("round-trip", current)
    case "awaiting-facet-review"                          => ("facet-review", paused)
    case "deriving-questions" | "investigating-questions" => ("questions", current)
    case "questions-investigating"                        => ("questions", current)
    case "building-packages"                              => ("packages", current)
    case "generating-candidates"                          => ("candidates", current)
    case "awaiting-decision"                              => ("decision", paused)
    case "designing"                                      => ("design", current)
    case "awaiting-approval"                              => ("approval", paused)
    case "closing"                                        => ("approval", current)
    case "designed" | "design-rejected"                   => ("approval", reached)
    case "failed"                                         => (failedStage(s.failure), paused)
    case _                                                => ("request", reached)

  /** Where the investigation is and in what state (contracts/journey.md, node states). */
  private def position(s: InvestigationState, standing: Standing): (String, NodeState) =
    val step = standing.pendingStep
    if standing.failed then
      (
        step.flatMap(spineStepStage.get).getOrElse(failedStage(s.failure.orElse(standing.failure))),
        failed
      )
    else if standing.completed then ("approval", reached)
    else if step.contains("record-failure") then (failedStage(s.failure), paused)
    else
      step.flatMap(spineStepStage.get) match
        case Some(stage) if pauses(stage) && (standing.paused || !standing.running) =>
          (stage, paused)
        case Some(stage) if stage == "approval" && s.status == "closing" => (stage, current)
        case Some(stage) if standing.running                             => (stage, current)
        case Some(stage) if stage == "questions"                         => (stage, current)
        case _                                                           => byStatus(s)

  private def spineNodes(
      s: InvestigationState,
      standing: Standing,
      run: StageRun,
      lanes: Vector[QuestionLane],
      impactFlags: Int
  ): Vector[StageNode] =
    val (at, atState0) = position(s, standing)
    val laneTrouble =
      lanes.flatMap(_.nodes).find(n => n.state == failed.toString || n.state == paused.toString)
    val allReported = lanes.nonEmpty && lanes.forall(_.nodes.forall(_.state == reached.toString))
    val (atIndex, atState) =
      if at == "questions" && atState0 == current && s.status == "questions-investigating" then
        if laneTrouble.isDefined then (Stages.spine.indexOf(at), paused)
        else if allReported then (Stages.spine.indexOf("packages"), current)
        else (Stages.spine.indexOf(at), current)
      else (Stages.spine.indexOf(at), atState0)
    Stages.spine.zipWithIndex.map { (stage, i) =>
      val state =
        if stage == "impact" then if impactFlags > 0 then reached else unreached
        else if i < atIndex then reached
        else if i == atIndex then atState
        else unreached
      val detail = stage match
        case "facets" if s.facetIds.nonEmpty => Some(plural(s.facetIds.size, "facet"))
        case "facet-review" if s.confirmedBy.isDefined =>
          s.confirmedBy.map(by => s"confirmed by $by")
        case "questions" if s.questionIds.nonEmpty =>
          val reported = lanes.count(_.nodes.forall(_.state == reached.toString))
          Some(s"${plural(s.questionIds.size, "question")}, $reported reported")
        case "packages" if s.packageIds.nonEmpty    => Some(plural(s.packageIds.size, "package"))
        case "candidates" if s.approachIds.nonEmpty => Some(plural(s.approachIds.size, "approach"))
        case "decision" if s.decisionId.isDefined   => s.decisionId
        case "design" if s.designVersions > 0       => Some(s"version ${s.designVersions}")
        case "approval" =>
          val outcome = s.lastApproval.map(a => s"${a.decision} version ${a.version}")
          if s.designVersions > 1 then
            Some((outcome.toVector :+ s"${s.designVersions} versions").mkString(", "))
          else outcome
        case "impact" if impactFlags > 0 => Some(plural(impactFlags, "flag"))
        case _                           => None
      val defect =
        if i != atIndex then None
        else if state == failed then s.failure.orElse(standing.failure)
        else if state == paused && stage == "questions" then laneTrouble.flatMap(_.detail)
        else if state == paused && !pauses(stage) then s.failure
        else None
      StageNode(
        stage,
        state.toString,
        defect.orElse(detail),
        refs(run, spineRunStage.collect { case (name, st) if st == stage => name }.toSet)
      )
    }

  // ── a question's lane ─────────────────────────────────────────────────────

  private val laneRunStage: Map[String, String] = Map(
    "plan"        -> "search-plan",
    "execute"     -> "executions",
    "eligibility" -> "eligibility",
    "assess"      -> "assessment",
    "sufficiency" -> "sufficiency"
  )

  private val laneStepStage: Map[String, String] = Map(
    "plan"               -> "search-plan",
    "execute"            -> "executions",
    "execute-failed"     -> "executions",
    "eligibility"        -> "eligibility",
    "resolve"            -> "verification",
    "await-verification" -> "verification",
    "assess"             -> "assessment",
    "assess-all"         -> "assessment",
    "sufficiency"        -> "sufficiency",
    "judge-all"          -> "sufficiency",
    "report"             -> "sufficiency"
  )

  /** The lane stage a recorded failure names; an unnamed one is the search's. */
  private def laneFailedStage(f: String): String =
    val stage = f.takeWhile(_ != ':').trim
    laneStepStage.getOrElse(stage, laneRunStage.getOrElse(stage, "assessment"))

  /** A failure the lane cannot get past by itself: the search's, or an unsettled verification. */
  private def terminal(f: String): Boolean =
    f.startsWith("execute:") || f.contains("verification_unsettled") || f.contains(
      "verification unsettled"
    )

  private def laneByStatus(q: QuestionInvestigationState): (String, NodeState) = q.status match
    case "planning"                         => ("search-plan", current)
    case "executing" | "supplemental"       => ("executions", current)
    case "applying-eligibility"             => ("eligibility", current)
    case "resolving"                        => ("verification", current)
    case "awaiting-verification"            => ("verification", current)
    case "assessing"                        => ("assessment", current)
    case "judging"                          => ("sufficiency", current)
    case "reported"                         => ("sufficiency", reached)
    case "retrieved" | "awaiting-retrieval" => ("verification", reached)
    case _                                  => ("search-plan", unreached)

  private def lanePosition(q: QuestionInvestigationState, standing: Standing): (String, NodeState) =
    val step = standing.pendingStep
    if standing.failed then
      (
        step
          .flatMap(laneStepStage.get)
          .orElse(q.failure.map(laneFailedStage))
          .getOrElse("search-plan"),
        failed
      )
    else if step.contains("execute-failed") then ("executions", failed)
    else
      step.flatMap(laneStepStage.get).filter(_ => standing.running) match
        case Some(stage) => (stage, current)
        case None =>
          q.failure match
            // A defect is the lane's state only while it rests; a later stage that ran is progress.
            case Some(f) if Set("retrieved", "awaiting-retrieval").contains(q.status) =>
              (laneFailedStage(f), if terminal(f) then failed else paused)
            case _ => laneByStatus(q)

  private def laneNodes(lane: Lane, decisions: Int): Vector[StageNode] =
    val q             = lane.state
    val (at, atState) = lanePosition(q, lane.standing)
    val atIndex       = Stages.lane.indexOf(at)
    Stages.lane.zipWithIndex.map { (stage, i) =>
      val state =
        if i < atIndex then reached
        else if i == atIndex then atState
        else unreached
      val detail = stage match
        case "search-plan" => q.planId
        case "executions" if q.executionIds.nonEmpty =>
          Some(plural(q.executionIds.size, "execution"))
        case "verification" if q.settled.nonEmpty || q.unsettled.nonEmpty =>
          Some(s"${q.settled.size} of ${q.settled.size + q.unsettled.size} settled")
        case "verification" if q.recordIds.nonEmpty => Some(plural(q.recordIds.size, "record"))
        case "assessment" if q.assessmentIds.nonEmpty =>
          Some(plural(q.assessmentIds.size, "assessment"))
        case "sufficiency" if q.sufficiencyIds.nonEmpty || decisions > 0 =>
          Some(s"${q.sufficiencyIds.size} of $decisions decisions judged")
        case _ => None
      val defect =
        if i == atIndex && (state == failed || state == paused) then
          q.failure.orElse(lane.standing.failure)
        else None
      StageNode(
        stage,
        state.toString,
        defect.orElse(detail),
        refs(lane.run, laneRunStage.collect { case (name, st) if st == stage => name }.toSet)
      )
    }

  // ── shared ────────────────────────────────────────────────────────────────

  private def refs(run: StageRun, names: Set[String]): Vector[StageRunRef] =
    run.stages
      .filter(e => names.contains(e.stage))
      .sortBy(e => (e.startedAt, e.attempt))
      .reverse
      .map(ref(run.runId, _))

  private def ref(runId: String, e: StageExecution): StageRunRef =
    StageRunRef(
      runId,
      e.stage,
      e.attempt,
      if e.endedAt.isEmpty then "running" else e.status.toString,
      e.startedAt.toString,
      e.endedAt.map(_.toString),
      e.defect.map(d => s"${d.kind}: ${d.detail}")
    )

  private def plural(n: Int, word: String): String =
    val w = if n == 1 then word else if word.endsWith("ch") then word + "es" else word + "s"
    s"$n $w"
