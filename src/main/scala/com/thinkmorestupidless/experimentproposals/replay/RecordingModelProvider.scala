package com.thinkmorestupidless.experimentproposals.replay

import com.thinkmorestupidless.ankka.agent.{ModelProvider, ModelRequest, ModelResponse}

import scala.concurrent.{ExecutionContext, Future}

/**
 * The live tier with `EDP_RECORD=1`: every reply the model gives is written as a cassette under its
 * key, marked `recorded`, so the replay tier can answer the same request later.
 */
final class RecordingModelProvider(inner: ModelProvider, cassettes: Cassettes)(using
    ExecutionContext
) extends ModelProvider:
  def name: String      = s"recording(${inner.name})"
  def modelName: String = inner.modelName

  def complete(request: ModelRequest): Future[ModelResponse] =
    inner.complete(request).map { response =>
      CassetteKey.of(request).foreach { key =>
        cassettes.write(
          Cassette(
            promptVersion = key.promptVersion,
            `case` = key.caseMarker,
            reply = response.text,
            recorded = true,
            refusal = response.refusalReason,
            model = Some(inner.modelName)
          )
        )
      }
      response
    }
