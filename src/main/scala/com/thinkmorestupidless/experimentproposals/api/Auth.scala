package com.thinkmorestupidless.experimentproposals.api

import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Principal, RequestContext}

/**
 * The shared token is who may call (research.md R16): as a bearer header for the API and the tests,
 * or as the `edp_session` cookie for the review pages. The reviewer's name is data in the request
 * body, checked against the configured list by the entities, never by this ACL.
 */
object Auth:
  val Cookie    = "edp_session"
  val Principal = "operator"

  def acl(token: String): Acl = Acl.Authenticate { context =>
    if presented(context).contains(token) then AuthDecision.Allow(principal)
    else AuthDecision.Unauthenticated("""realm="experiment-proposals"""")
  }

  val principal: Principal =
    com.thinkmorestupidless.ankka.http.Principal(Principal, name = Some(Principal))

  def presented(context: RequestContext): Option[String] =
    bearer(context.header("Authorization")).orElse(cookie(context.header("Cookie")))

  def bearer(authorization: Option[String]): Option[String] =
    authorization
      .map(_.trim)
      .collect { case a if a.regionMatches(true, 0, "Bearer ", 0, 7) => a.drop(7).trim }
      .filter(_.nonEmpty)

  def cookie(header: Option[String]): Option[String] =
    header.toVector
      .flatMap(_.split(';'))
      .map(_.trim)
      .collectFirst {
        case c if c.startsWith(Cookie + "=") => c.drop(Cookie.length + 1)
      }
      .filter(_.nonEmpty)

  def setCookie(token: String): String = s"$Cookie=$token; Path=/review; HttpOnly; SameSite=Strict"
