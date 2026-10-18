package com.thinkmorestupidless.experimentproposals.api.pages

import com.thinkmorestupidless.experimentproposals.domain.request.{CustomerRequest, Facet}
import com.thinkmorestupidless.ankka.http.Html

/** Minimal server-rendered pages for the reviewer (research.md R12). Pure functions to markup. */
object Pages:
  def esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

  def layout(title: String, body: String): Html = Html(s"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>${esc(title)}</title>
<style>body{font:16px/1.5 system-ui,sans-serif;max-width:60rem;margin:2rem auto;padding:0 1rem;color:#222}
table{border-collapse:collapse;width:100%}td,th{border-top:1px solid #ddd;padding:.4rem;vertical-align:top;text-align:left}
form{display:inline-block;margin:0 .25rem .25rem 0}input[type=text]{width:14rem}.muted{color:#666}.bad{color:#a00}.ok{color:#070}
details{margin:.25rem 0}</style></head><body>
<p class="muted">Experiment proposals · review</p>
<h1>${esc(title)}</h1>
$body
</body></html>""")

  def facets(request: CustomerRequest, facets: Vector[Facet], reviewers: Vector[String]): Html =
    val options = reviewers.map(r => s"""<option value="${esc(r)}">${esc(r)}</option>""").mkString
    def form(facetId: String, action: String, label: String, withStatement: Boolean = false) =
      s"""<form method="post" action="/review/facets/${esc(facetId)}/review">
         |<input type="hidden" name="action" value="$action">
         |<select name="reviewer">$options</select>
         |${
          if withStatement then
            """<input type="text" name="statement" placeholder="new statement">"""
          else ""
        }
         |<input type="text" name="rationale" placeholder="rationale" required>
         |<button type="submit">$label</button></form>""".stripMargin
    val rows = facets.map { f =>
      val history =
        if f.history.isEmpty then ""
        else
          s"""<details><summary>history (${f.history.size})</summary><ul>${f.history
              .map(h =>
                s"<li>${esc(h.statement)} <span class=muted>(${h.origin}, ${h.status}, ${h.at})</span></li>"
              )
              .mkString}</ul></details>"""
      val reviews = f.reviews
        .map(r => s"<li>${esc(r.reviewer)} ${r.action} at ${r.at}: ${esc(r.rationale)}</li>")
        .mkString
      s"""<tr><td><b>${esc(f.name)}</b><br><span class=muted>${f.importance}</span></td>
         |<td>${esc(f.statement)}$history${
          if reviews.nonEmpty then s"<ul class=muted>$reviews</ul>" else ""
        }</td>
         |<td>${f.origin}</td><td>${f.status}</td>
         |<td>${form(f.id, "confirm", "Confirm")}${form(
          f.id,
          "amend",
          "Amend",
          withStatement = true
        )}${form(f.id, "reject", "Reject")}${form(
          f.id,
          "leave_unresolved",
          "Leave unresolved"
        )}</td></tr>""".stripMargin
    }.mkString
    val roundTrip = request.roundTrip
      .map { r =>
        def list(
            title: String,
            items: Vector[com.thinkmorestupidless.experimentproposals.domain.request.Issue]
        ) =
          if items.isEmpty then ""
          else
            s"<p><b>$title</b></p><ul>${items.map(i => s"<li>${esc(i.description)}${i.facetName.fold("")(n => s" <span class=muted>($n)</span>")}</li>").mkString}</ul>"
        s"""<h2>Round-trip check <span class="${if r.passed then "ok" else "bad"}">${
            if r.passed then "passed" else "did not pass"
          }</span></h2>
         |<p class=muted>Reconstruction: ${esc(r.check.reconstruction)}</p>
         |${list("Omissions", r.check.omissions)}${list(
            "Unsupported additions",
            r.check.unsupportedAdditions
          )}${list("Contradictions", r.check.contradictions)}${list(
            "Ambiguities",
            r.check.ambiguities
          )}""".stripMargin
      }
      .getOrElse("<p class=muted>No round-trip check yet.</p>")
    layout(
      s"Facets of ${request.id}",
      s"""
<p>Customer request, exactly as entered:</p><blockquote>${esc(request.text)}</blockquote>
<table><tr><th>Facet</th><th>Statement</th><th>Origin</th><th>Status</th><th>Review</th></tr>$rows</table>
$roundTrip
<h2>Confirm the facets</h2>
<form method="post" action="/review/requests/${esc(
          request.id
        )}/facets:confirmed"><select name="reviewer">$options</select>
<button type="submit">Confirm the mandatory facets and continue</button></form>
<form method="post" action="/review/requests/${esc(
          request.id
        )}/round-trip:rerun"><button type="submit">Run the round-trip check again</button></form>
"""
    )
