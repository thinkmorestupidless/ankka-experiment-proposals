package com.thinkmorestupidless.experimentproposals.api.pages

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.DesignStatus
import com.thinkmorestupidless.experimentproposals.domain.design.DesignState
import com.thinkmorestupidless.ankka.http.Html

/** The design as the reviewer reads it: the rendering, the history, and the decision form. */
object DesignPage:
  import Pages.esc

  def render(design: DesignState, version: Int, markdown: String, reviewers: Vector[String]): Html =
    val options = reviewers.map(r => s"""<option value="${esc(r)}">${esc(r)}</option>""").mkString
    val v       = design.version(version)
    val status  = v.map(_.status).getOrElse(DesignStatus.draft)
    val versions = design.versions
      .map(x =>
        s"""<a href="/review/designs/${esc(
            design.id
          )}?version=${x.version}">v${x.version} (${x.status})</a>"""
      )
      .mkString(" · ")
    val form =
      if status == DesignStatus.under_review then
        s"""<h2>Decide on version $version</h2>
           |<form method="post" action="/review/designs/${esc(design.id)}/approval">
           |<input type="hidden" name="version" value="$version">
           |<label><input type="radio" name="decision" value="approved" required> Approve</label>
           |<label><input type="radio" name="decision" value="rejected"> Reject</label>
           |<label><input type="radio" name="decision" value="changes_requested"> Request changes</label><br>
           |<select name="reviewer">$options</select> <input type="text" name="rationale" placeholder="rationale" required>
           |<input type="text" name="requestedChanges" placeholder="requested changes, separated by ;">
           |<button type="submit">Record</button></form>""".stripMargin
      else s"<p class=muted>Version $version is $status; nothing to decide on it.</p>"
    val flags =
      if design.flags.isEmpty then ""
      else
        s"<h2>Flagged for review</h2><ul>${design.flags
            .map(f =>
              s"<li class=bad>v${f.version}: ${esc(f.changedRef.key)} changed at ${f.at}: ${esc(f.reason)}</li>"
            )
            .mkString}</ul>"
    Pages.layout(
      s"Design ${design.id}",
      s"""<p class=muted>Versions: $versions</p>$flags<pre style="white-space:pre-wrap">${esc(
          markdown
        )}</pre>$form"""
    )
