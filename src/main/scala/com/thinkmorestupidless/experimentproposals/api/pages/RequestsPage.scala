package com.thinkmorestupidless.experimentproposals.api.pages

import com.thinkmorestupidless.experimentproposals.application.views.RequestRow
import com.thinkmorestupidless.ankka.http.Html

/** Every request, newest first, with the way to its facets, its candidates and its design. */
object RequestsPage:
  import Pages.esc

  def render(rows: Vector[RequestRow]): Html =
    def link(href: String, label: String) = s"""<a href="${esc(href)}">${esc(label)}</a>"""
    val table = rows.map { r =>
      val pages = Vector(
        Some(link(s"/review/requests/${r.id}/facets", "facets")),
        Option.when(r.status == "investigating" || r.status == "designed" || r.status == "closed")(
          link(s"/review/requests/${r.id}/candidates", "candidates")
        ),
        Option.when(r.status == "designed" || r.status == "closed")(
          link(s"/review/designs/${r.id}:design", "design")
        )
      ).flatten.mkString(" · ")
      val roundTrip = r.roundTripPassed match
        case Some(true)  => """<span class=ok>round trip passed</span>"""
        case Some(false) => """<span class=bad>round trip failed</span>"""
        case None        => """<span class=muted>round trip pending</span>"""
      s"""<tr><td>${esc(r.text)}<br><span class=muted>${esc(r.id)} · ${esc(r.submittedBy)} · ${esc(
          r.submittedAt.take(19).replace('T', ' ')
        )}</span></td>
         |<td>${esc(
          r.status
        )}<br><span class=muted>${r.facets} facets, ${r.questions} questions</span><br>$roundTrip</td>
         |<td>$pages</td></tr>""".stripMargin
    }.mkString
    Pages.layout(
      "Requests",
      if rows.isEmpty then
        """<p>No requests yet. Submit one with <code>POST /api/v1/requests</code>.</p>
          |<p><a href="/review/queue">Review queue</a></p>""".stripMargin
      else
        s"""<p><a href="/review/queue">Review queue</a></p>
           |<table><tr><th>Request</th><th>Where it stands</th><th>Pages</th></tr>$table</table>""".stripMargin
    )
