package com.thinkmorestupidless.experimentproposals.replay

import com.thinkmorestupidless.experimentproposals.config.{Settings, Tier}
import com.thinkmorestupidless.ankka.agent.{AnthropicProvider, Effort, ModelProvider}

import java.nio.file.Path
import scala.concurrent.ExecutionContext

/** Which model the service talks to, by tier (research.md R4, R19). */
object ModelProviders:
  def forTier(settings: Settings): ModelProvider =
    settings.tier match
      case Tier.replay => ReplayModelProvider(settings.cassettes)
      case Tier.live =>
        val live = AnthropicProvider.fromEnv(
          settings.model,
          AnthropicProvider.Defaults(effort = Some(Effort.High))
        )
        if settings.record then
          given ExecutionContext = ExecutionContext.global
          new RecordingModelProvider(live, Cassettes(Path.of(settings.cassettes)))
        else live

  /** Why the assistant has no model; the route and `GET /assistant` both say so (research R12). */
  val NoAssistant = "the assistant has no model: set ANTHROPIC_API_KEY (or EDP_ASSISTANT_SCRIPT)"

  /**
   * The assistant's model, apart from the pipeline's: a script when one is named, else live when a
   * key is present in either tier, else none.
   */
  def forAssistant(settings: Settings, env: Map[String, String]): Either[String, ModelProvider] =
    settings.assistantScript match
      case Some(file) => ScriptedModelProvider.fromFile(Path.of(file))
      case None if env.get("ANTHROPIC_API_KEY").exists(_.trim.nonEmpty) =>
        Right(AnthropicProvider.fromEnv(settings.assistantModel, AnthropicProvider.Defaults()))
      case None => Left(NoAssistant)
