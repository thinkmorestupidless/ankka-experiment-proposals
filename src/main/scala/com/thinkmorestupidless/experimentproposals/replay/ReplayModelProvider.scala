package com.thinkmorestupidless.experimentproposals.replay

import com.thinkmorestupidless.ankka.agent.{
  ModelCallFailed,
  ModelProvider,
  ModelRequest,
  ModelResponse,
  StopReason
}

import java.nio.file.Path
import scala.concurrent.Future

/**
 * The replay tier's model: answers from the cassette for `(prompt version, case)` and fails loudly,
 * naming the key, when there is none. A cassette with `refusal` set answers a refusal, as the live
 * model would.
 */
final class ReplayModelProvider(val cassettes: Cassettes) extends ModelProvider:
  def name: String      = "replay"
  def modelName: String = "replay"

  def complete(request: ModelRequest): Future[ModelResponse] =
    CassetteKey.of(request) match
      case Left(problem) =>
        Future.failed(ModelCallFailed(name, s"cannot key the request: $problem"))
      case Right(key) =>
        cassettes.read(key) match
          case None =>
            Future.failed(
              ModelCallFailed(
                name,
                s"no cassette for $key; expected ${key.path(cassettes.dir)} (record it with EDP_TIER=live EDP_RECORD=1, or author it with CassetteAuthor)"
              )
            )
          case Some(c) if c.refusal.isDefined =>
            Future.successful(
              ModelResponse("", stopReason = StopReason.Refusal, refusalReason = c.refusal)
            )
          case Some(c) => Future.successful(ModelResponse(c.reply))

object ReplayModelProvider:
  def apply(dir: String): ReplayModelProvider = new ReplayModelProvider(Cassettes(Path.of(dir)))
