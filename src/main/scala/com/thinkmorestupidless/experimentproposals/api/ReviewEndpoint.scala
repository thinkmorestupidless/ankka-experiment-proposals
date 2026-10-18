package com.thinkmorestupidless.experimentproposals.api

import com.thinkmorestupidless.experimentproposals.api.pages.{
  CandidatesPage,
  DesignPage,
  Pages,
  QueuePage
}
import com.thinkmorestupidless.experimentproposals.application.{Approvals, Decisions}
import com.thinkmorestupidless.experimentproposals.domain.design.{DesignState, Rendering}
import com.thinkmorestupidless.experimentproposals.application.entities.{
  ApproachEntity,
  DecisionEntity,
  DesignEntity,
  FacetEntity,
  Records,
  RequestEntity,
  SourceEntity
}
import com.thinkmorestupidless.experimentproposals.application.views.{ClaimRows, FindingRows}
import com.thinkmorestupidless.experimentproposals.domain.evidence.ReviewRecord
import com.thinkmorestupidless.experimentproposals.domain.sources.SpanResolution
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.experimentproposals.application.workflows.InvestigationWorkflow
import com.thinkmorestupidless.experimentproposals.config.{Configured, Settings}
import com.thinkmorestupidless.experimentproposals.domain.Enumerations.FacetAction
import com.thinkmorestupidless.experimentproposals.domain.request.*
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient

import java.net.URLDecoder

/** The reviewer's pages: the same records and commands as the API, as forms (R12). */
final class ReviewEndpoint(settings: Settings, client: ComponentClient, views: ViewClient)
    extends HttpEndpoint("/review"):

  val acl: Acl = Acl.Authenticate { context =>
    // The session route itself must be reachable to set the cookie; it checks the token it is given.
    if context.path
        .endsWith("/review/session") || Auth.presented(context).contains(settings.apiToken)
    then AuthDecision.Allow(Auth.principal)
    else AuthDecision.Unauthenticated("""realm="experiment-proposals review"""")
  }

  private def request(id: String) = client.forEventSourcedEntity(EntityId(id))
  private def facet(id: String)   = client.forEventSourcedEntity(EntityId(id))

  get("/session") { () =>
    val token = query.required[String]("token")
    if token != settings.apiToken then throw HttpProblem.unauthorized("not the token")
    Respond(
      (),
      303,
      Vector("Location" -> "/review/requests", "Set-Cookie" -> Auth.setCookie(token))
    )
  }

  get("/requests") { () =>
    Pages.layout(
      "Requests",
      "<p>Open a request's facets at <code>/review/requests/{id}/facets</code>.</p>"
    )
  }

  get("/requests/{id}/facets") { (id: String) =>
    val req = request(id).call(RequestEntity.get).invoke()
    if !req.registered then throw HttpProblem.notFound(s"no request '$id'")
    val facets = req.facets.map(f => facet(f.id).call(FacetEntity.get).invoke())
    Pages.facets(req, facets, Configured.reviewers.all.map(_.name))
  }

  postBody("/facets/{id}/review") { (id: String, body: String) =>
    val form = ReviewEndpoint.form(body)
    val action = FacetAction.values
      .find(_.toString == form.getOrElse("action", ""))
      .getOrElse(throw HttpProblem.badRequest("unknown action"))
    val f = facet(id)
      .call(FacetEntity.review)
      .invoke(
        ReviewFacet(
          action,
          form.getOrElse("reviewer", ""),
          form.getOrElse("rationale", ""),
          form.get("statement").filter(_.nonEmpty)
        )
      )
    Respond.redirect(s"/review/requests/${f.requestId}/facets")
  }

  post("/requests/{id}/facets:confirmed") { (id: String) =>
    val by =
      query.optional[String]("reviewer").getOrElse(request.header("X-Reviewer").getOrElse(""))
    ReviewEndpoint.confirm(client, id, by)
    Respond.redirect(s"/review/requests/$id/facets")
  }

  get("/queue") { () =>
    val rows =
      views.forView(FindingRows).ask(FindingRows.byStatus, "status" -> "requires_review") ++
        views.forView(ClaimRows).ask(ClaimRows.byStatus, "status" -> "requires_review")
    val records = rows.flatMap { row =>
      Records.companionFor(row.id).map { companion =>
        val r = client.forEventSourcedEntity(EntityId(row.id)).call(companion.get).invoke()
        val passages = r.spans.flatMap { span =>
          client
            .forEventSourcedEntity(EntityId(span.sourceId))
            .call(SourceEntity.resolveSpan)
            .invoke(span) match
            case SpanResolution.Resolved(text) => Some(text)
            case _                             => None
        }
        r -> passages
      }
    }
    QueuePage.render(records, Configured.reviewers.all.map(_.name))
  }

  postBody("/records/{id}/review") { (id: String, body: String) =>
    val form = ReviewEndpoint.form(body)
    val companion =
      Records.companionFor(id).getOrElse(throw HttpProblem.notFound(s"not a record id: '$id'"))
    client
      .forEventSourcedEntity(EntityId(id))
      .call(companion.review)
      .invoke(
        ReviewRecord(
          form.getOrElse("action", ""),
          form.getOrElse("reviewer", ""),
          form.getOrElse("rationale", "")
        )
      )
    Respond.redirect("/review/queue")
  }

  get("/requests/{id}/candidates") { (id: String) =>
    val state = client.forWorkflow(EntityId(id)).call(InvestigationWorkflow.status).invoke()
    val approaches =
      state.approachIds.map(a =>
        client.forEventSourcedEntity(EntityId(a)).call(ApproachEntity.get).invoke()
      )
    val decision = state.decisionId
      .map(d => client.forEventSourcedEntity(EntityId(d)).call(DecisionEntity.get).invoke())
      .filter(_.registered)
    CandidatesPage.render(
      id,
      approaches,
      state.comparison,
      decision,
      Configured.reviewers.all.map(_.name)
    )
  }

  postBody("/requests/{id}/decide") { (id: String, body: String) =>
    val form     = ReviewEndpoint.form(body)
    val selected = form.getOrElse("selected", "")
    val reasons = form.collect {
      case (k, v) if k.startsWith("reason-") => k.stripPrefix("reason-") -> v
    }.toVector
    try
      Decisions.decide(
        client,
        id,
        selected,
        reasons,
        form.getOrElse("reviewer", ""),
        form.getOrElse("rationale", "")
      )
    catch
      case Decisions.Problem(409, m) => throw HttpProblem.conflict(m)
      case Decisions.Problem(_, m)   => throw HttpProblem.badRequest(m)
    Respond.redirect(s"/review/requests/$id/candidates")
  }

  get("/designs/{id}") { (id: String) =>
    val d = client.forEventSourcedEntity(EntityId(id)).call(DesignEntity.get).invoke()
    if !d.registered then throw HttpProblem.notFound(s"no design '$id'")
    val version = query
      .optional[Int]("version")
      .orElse(d.latest.map(_.version))
      .getOrElse(throw HttpProblem.notFound("no version yet"))
    val v = d.version(version).getOrElse(throw HttpProblem.notFound(s"no version $version"))
    DesignPage.render(
      d,
      version,
      Rendering.render(DesignState.requestOf(id), v, d.approvals),
      Configured.reviewers.all.map(_.name)
    )
  }

  postBody("/designs/{id}/approval") { (id: String, body: String) =>
    val form = ReviewEndpoint.form(body)
    val version =
      form.get("version").flatMap(_.toIntOption).getOrElse(throw HttpProblem.badRequest("version"))
    val changes =
      form.getOrElse("requestedChanges", "").split(';').toVector.map(_.trim).filter(_.nonEmpty)
    try
      Approvals.record(
        client,
        id,
        version,
        form.getOrElse("decision", ""),
        form.getOrElse("reviewer", ""),
        form.getOrElse("rationale", ""),
        changes
      )
    catch
      case Approvals.Problem(409, m) => throw HttpProblem.conflict(m)
      case Approvals.Problem(_, m)   => throw HttpProblem.badRequest(m)
    Respond.redirect(s"/review/designs/$id")
  }

  post("/requests/{id}/round-trip:rerun") { (id: String) =>
    client.forWorkflow(EntityId(id)).call(InvestigationWorkflow.rerunRoundTrip).invoke()
    Respond.redirect(s"/review/requests/$id/facets")
  }

object ReviewEndpoint:
  /** A browser's `application/x-www-form-urlencoded` body. */
  def form(body: String): Map[String, String] =
    body
      .split('&')
      .toVector
      .filter(_.nonEmpty)
      .map { pair =>
        val (k, v) = pair.span(_ != '=')
        URLDecoder.decode(k, "UTF-8") -> URLDecoder.decode(v.drop(1), "UTF-8")
      }
      .toMap

  /**
   * Confirms the facets on the request, then resumes the investigation; one door for both pages and
   * API.
   */
  def confirm(client: ComponentClient, requestId: String, by: String): Unit =
    val req = client.forEventSourcedEntity(EntityId(requestId)).call(RequestEntity.get).invoke()
    val states = req.facets.map { ref =>
      val f = client.forEventSourcedEntity(EntityId(ref.id)).call(FacetEntity.get).invoke()
      FacetState(f.id, f.name, f.importance, f.status)
    }
    client
      .forEventSourcedEntity(EntityId(requestId))
      .call(RequestEntity.confirmFacets)
      .invoke(ConfirmFacets(by, states))
    client
      .forWorkflow(EntityId(requestId))
      .call(InvestigationWorkflow.facetsConfirmed)
      .invoke(by): Unit
