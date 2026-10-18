package com.thinkmorestupidless.experimentproposals.config

import com.thinkmorestupidless.experimentproposals.adapters.{Adapter, Adapters}
import com.thinkmorestupidless.experimentproposals.domain.provenance.Reviewers
import com.thinkmorestupidless.experimentproposals.retrieval.{Embedder, HashEmbedder}
import com.thinkmorestupidless.ankka.runtime.ViewClient

/**
 * What entities consult that is fixed for the life of the process: the configured reviewer list and
 * the budgets. Set once by `Main` (and by a test's fixture) before the service starts; an entity
 * reads it as a pure in-memory value, never performing I/O.
 */
object Configured:
  @volatile var reviewers: Reviewers    = new Reviewers(Vector.empty)
  @volatile var retryBudget: Int        = 2
  @volatile var supplementalBudget: Int = 2
  @volatile var adapters: Adapters      = new Adapters(Vector.empty)
  @volatile var embedder: Embedder      = HashEmbedder
  @volatile var modelName: String       = "replay"
  @volatile var tier: Tier              = Tier.replay

  /** Set once the service has started: retrieval in a workflow step reads the chunk view. */
  @volatile var views: Option[ViewClient] = None
  @volatile var decisions: Vector[String] = Settings.DefaultDecisions

  def apply(
      settings: Settings,
      reviewers: Reviewers,
      adapters: Vector[Adapter],
      embedder: Embedder
  ): Unit =
    this.reviewers = reviewers
    this.retryBudget = settings.retryBudget
    this.supplementalBudget = settings.supplementalBudget
    this.adapters = new Adapters(adapters)
    this.embedder = embedder
    this.modelName = if settings.tier == Tier.replay then "replay" else settings.model
    this.tier = settings.tier
    this.decisions = settings.decisions

  /** `operator` or a configured reviewer; anyone else is refused before anything is persisted. */
  /** External indexes are searched only in the live tier; the replay tier reaches no network. */
  def externalSearch: Boolean = tier == Tier.live

  def actor(name: String): Either[String, String] =
    if name == Reviewers.Operator then Right(name) else reviewers.check(name).map(_.name)
