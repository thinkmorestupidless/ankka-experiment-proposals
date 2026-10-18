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
