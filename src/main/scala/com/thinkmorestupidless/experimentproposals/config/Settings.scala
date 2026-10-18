package com.thinkmorestupidless.experimentproposals.config

import scala.concurrent.duration.*

enum Tier:
  case replay, live

/**
 * Every `EDP_*` variable with its default (contracts/stages.md). Names only; no value is logged.
 */
final case class Settings(
    apiToken: String,
    tier: Tier,
    record: Boolean,
    cassettes: String,
    model: String,
    embeddingModel: String,
    embeddingCache: String,
    retryBudget: Int,
    supplementalBudget: Int,
    decisions: Vector[String],
    internalDir: String,
    modelTimeout: FiniteDuration,
    contactEmail: Option[String] = None,
    ncbiApiKey: Option[String] = None
)

object Settings:
  val DefaultDecisions: Vector[String] = Vector(
    "recommend production feasibility",
    "propose a feasibility experiment on lipid accumulation"
  )

  def fromEnv(env: Map[String, String]): Either[String, Settings] =
    def int(name: String, default: Int): Either[String, Int] =
      env.get(name).filter(_.nonEmpty) match
        case None => Right(default)
        case Some(v) =>
          v.trim.toIntOption
            .filter(_ >= 0)
            .toRight(s"$name must be a non-negative integer, was '$v'")
    for
      token <- env.get("EDP_API_TOKEN").filter(_.nonEmpty).toRight("EDP_API_TOKEN is not set")
      tier <- env.getOrElse("EDP_TIER", "replay").trim match
        case "replay" => Right(Tier.replay)
        case "live"   => Right(Tier.live)
        case other    => Left(s"EDP_TIER must be replay or live, was '$other'")
      retry        <- int("EDP_RETRY_BUDGET", 2)
      supplemental <- int("EDP_SUPPLEMENTAL_BUDGET", 2)
    yield Settings(
      apiToken = token,
      tier = tier,
      record = env.get("EDP_RECORD").exists(v => v == "1" || v.equalsIgnoreCase("true")),
      cassettes = env.getOrElse("EDP_CASSETTES", "evals/cassettes"),
      model = env.getOrElse("EDP_MODEL", "claude-opus-5-5"),
      embeddingModel = env.getOrElse("EDP_EMBEDDING_MODEL", "BAAI/bge-small-en-v1.5"),
      embeddingCache = env.getOrElse("EDP_EMBEDDING_CACHE", sys.props("user.home") + "/.cache/edp"),
      retryBudget = retry,
      supplementalBudget = supplemental,
      decisions = env
        .get("EDP_DECISIONS")
        .map(_.split(';').toVector.map(_.trim).filter(_.nonEmpty))
        .filter(_.nonEmpty)
        .getOrElse(DefaultDecisions),
      internalDir = env.getOrElse("EDP_INTERNAL_DIR", "internal"),
      modelTimeout = 3.minutes,
      contactEmail = env.get("EDP_CONTACT_EMAIL").map(_.trim).filter(_.nonEmpty),
      ncbiApiKey = env.get("EDP_NCBI_API_KEY").map(_.trim).filter(_.nonEmpty)
    )
