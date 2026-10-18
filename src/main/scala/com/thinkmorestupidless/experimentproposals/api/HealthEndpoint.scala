package com.thinkmorestupidless.experimentproposals.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.experimentproposals.config.Settings
import com.thinkmorestupidless.ankka.core.Codecs
import com.thinkmorestupidless.ankka.http.*

final case class Health(status: String, tier: String, model: String)

/** Open and read-only: a probe needs no token, and nothing here names a secret. */
final class HealthEndpoint(settings: Settings) extends HttpEndpoint(""):
  private given JsonValueCodec[Health] = Codecs.make[Health]

  val acl: Acl = Acl.AllowAll

  get("/healthz") { () =>
    Health(
      "ok",
      settings.tier.toString,
      if settings.tier.toString == "replay" then "replay" else settings.model
    )
  }
