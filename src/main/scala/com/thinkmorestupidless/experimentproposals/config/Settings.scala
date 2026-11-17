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
    ncbiApiKey: Option[String] = None,
    conversationBudget: Long = Settings.DefaultConversationBudget,
    verificationBound: FiniteDuration = Settings.DefaultVerificationBound,
    assistantModel: String = "claude-opus-5-5",
    assistantScript: Option[String] = None
)

object Settings:
  val DefaultDecisions: Vector[String] = Vector(
    "recommend production feasibility",
    "propose a feasibility experiment on lipid accumulation"
  )

  val DefaultConversationBudget: Long          = 200000L
  val DefaultVerificationBound: FiniteDuration = 3.minutes

  /** `90s`, `3m`, `500ms`: a whole number and a unit. */
  def duration(name: String, value: String): Either[String, FiniteDuration] =
    val Pattern = raw"\s*(\d+)\s*(ms|s|m)\s*".r
    value match
      case Pattern(n, "ms") => Right(n.toLong.millis)
      case Pattern(n, "s")  => Right(n.toLong.seconds)
      case Pattern(n, "m")  => Right(n.toLong.minutes)
      case _ => Left(s"$name must be a whole number and a unit (ms, s or m), was '$value'")

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
      budget <- env.get("EDP_CONVERSATION_BUDGET").filter(_.trim.nonEmpty) match
        case None => Right(DefaultConversationBudget)
        case Some(v) =>
          v.trim.toLongOption
            .filter(_ > 0)
            .toRight(s"EDP_CONVERSATION_BUDGET must be a positive number of tokens, was '$v'")
      bound <- env.get("EDP_VERIFICATION_BOUND").filter(_.trim.nonEmpty) match
        case None    => Right(DefaultVerificationBound)
        case Some(v) => duration("EDP_VERIFICATION_BOUND", v)
      model = env.getOrElse("EDP_MODEL", "claude-opus-5-5")
    yield Settings(
      apiToken = token,
      tier = tier,
      record = env.get("EDP_RECORD").exists(v => v == "1" || v.equalsIgnoreCase("true")),
      cassettes = env.getOrElse("EDP_CASSETTES", "evals/cassettes"),
      model = model,
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
      ncbiApiKey = env.get("EDP_NCBI_API_KEY").map(_.trim).filter(_.nonEmpty),
      conversationBudget = budget,
      verificationBound = bound,
      assistantModel =
        env.get("EDP_ASSISTANT_MODEL").map(_.trim).filter(_.nonEmpty).getOrElse(model),
      assistantScript = env.get("EDP_ASSISTANT_SCRIPT").map(_.trim).filter(_.nonEmpty)
    )
