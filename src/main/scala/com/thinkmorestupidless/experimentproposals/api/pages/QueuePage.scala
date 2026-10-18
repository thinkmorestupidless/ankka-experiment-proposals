package com.thinkmorestupidless.experimentproposals.api.pages

import com.thinkmorestupidless.experimentproposals.domain.evidence.{EvidenceRecord, RecordContent}
import com.thinkmorestupidless.ankka.http.Html

object QueuePage:
  import Pages.esc

  /** Records awaiting a reviewer, each beside the passage its first span resolves to. */
  def render(records: Vector[(EvidenceRecord, Vector[String])], reviewers: Vector[String]): Html =
    val options = reviewers.map(r => s"""<option value="${esc(r)}">${esc(r)}</option>""").mkString
    def form(id: String, action: String, label: String) =
      s"""<form method="post" action="/review/records/${esc(
          id
        )}/review"><input type="hidden" name="action" value="$action">
         |<select name="reviewer">$options</select><input type="text" name="rationale" placeholder="rationale" required>
         |<button type="submit">$label</button></form>""".stripMargin
    val rows = records.map { (r, passages) =>
      val flags = r.flags
        .map(f => s"<li class=bad>${esc(f.changedRef.key)} changed: ${esc(f.reason)}</li>")
        .mkString
      val what = r.content match
        case Some(RecordContent.OfFinding(f)) =>
          s"<b>finding</b> ${esc(f.observedEndpoint)}: ${esc(f.reportedOutcome)}<br><span class=muted>${esc(f.species.getOrElse("species not stated"))}; ${esc(f.cellType.getOrElse(""))}</span>"
        case Some(RecordContent.OfClaim(c)) =>
          s"<b>claim</b> (${c.kind}) ${esc(c.proposition)}<br><span class=muted>${esc(c.qualifications.mkString("; "))}</span>"
        case None => ""
      val checks = r.attempts.lastOption
        .flatMap(_.verification)
        .map(v =>
          s"<ul class=muted>${v.checks
              .map((n, c) =>
                s"<li>$n: ${if c.passed then "passed" else "<span class=bad>failed</span>"} ${esc(c.note)}</li>"
              )
              .mkString}</ul><p class=muted>${esc(v.rationale)}</p>"
        )
        .getOrElse("")
      s"""<tr><td>${esc(
          r.id
        )}<br><span class=muted>${r.status}, attempt ${r.attempt}</span>${
          if flags.isEmpty then "" else s"<ul>$flags</ul>"
        }</td><td>$what$checks</td>
         |<td>${passages.map(p => s"<blockquote>${esc(p)}</blockquote>").mkString}</td>
         |<td>${form(r.id, "verify", "Verify")}${form(r.id, "reject", "Reject")}${form(
          r.id,
          "complain_citation",
          "Citation complaint"
        )}</td></tr>""".stripMargin
    }.mkString
    Pages.layout(
      "Review queue",
      if records.isEmpty then "<p>Nothing awaits review.</p>"
      else
        s"<table><tr><th>Record</th><th>What it says</th><th>Source passage</th><th>Decide</th></tr>$rows</table>"
    )
