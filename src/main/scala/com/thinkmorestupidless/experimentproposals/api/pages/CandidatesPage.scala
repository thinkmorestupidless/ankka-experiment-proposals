package com.thinkmorestupidless.experimentproposals.api.pages

import com.thinkmorestupidless.experimentproposals.domain.design.{
  Comparison,
  DecisionRecord,
  ExperimentalApproach
}
import com.thinkmorestupidless.ankka.http.Html

/**
 * The candidates side by side on the eight criteria; the reviewer selects one with reasons for the
 * rest.
 */
object CandidatesPage:
  import Pages.esc

  def render(
      requestId: String,
      approaches: Vector[ExperimentalApproach],
      comparison: Option[Comparison],
      decision: Option[DecisionRecord],
      reviewers: Vector[String]
  ): Html =
    val options = reviewers.map(r => s"""<option value="${esc(r)}">${esc(r)}</option>""").mkString
    def list(title: String, items: Vector[String]) =
      if items.isEmpty then ""
      else s"<p><b>$title</b></p><ul>${items.map(i => s"<li>${esc(i)}</li>").mkString}</ul>"
    val cards = approaches.map { a =>
      s"""<details open><summary><b>${esc(
          a.title
        )}</b> <span class=muted>${a.id} · ${a.status}</span></summary>
         |<p>${esc(a.hypothesis)}</p><p class=muted>${esc(a.rationale)}</p>
         |<p><b>Model</b> ${esc(a.biologicalModel)}</p>${list(
          "Model limitations",
          a.modelLimitations
        )}
         |${list("Supporting evidence", a.affirmativeClaimRefs.map(_.id))}${list(
          "Contrary evidence",
          a.contraryClaimRefs.map(_.id)
        )}${list("Indirect evidence", a.indirectClaimRefs.map(_.id))}${list(
          "Missing evidence",
          a.missingEvidence
        )}
         |<p><b>Assumptions</b></p><ul>${a.assumptions
          .map(s => s"<li>${esc(s.statement)} <span class=muted>(${s.evidenceStatus})</span></li>")
          .mkString}</ul>
         |${list("Endpoints", a.endpoints)}${list("Measurements", a.measurements)}${list(
          "Controls",
          a.controls
        )}${list("Resources", a.resources)}${list("Failure modes", a.failureModes)}
         |${a.rejectionReason.fold("")(r =>
          s"<p class=bad>Rejected: ${esc(r)}</p>"
        )}</details>""".stripMargin
    }.mkString
    val table = comparison.fold("<p class=muted>No comparison yet.</p>") { c =>
      val head = c.approachRefs
        .map(r => s"<th>${esc(approaches.find(_.id == r.id).map(_.title).getOrElse(r.id))}</th>")
        .mkString
      val rows = c.criteria.map { cr =>
        val cells = c.approachRefs.map { r =>
          cr.perApproach
            .get(r.id)
            .fold("<td class=muted>not rated</td>")(x =>
              s"<td><b>${esc(x.rating)}</b><br><span class=muted>${esc(x.rationale)}</span></td>"
            )
        }.mkString
        s"<tr><td>${esc(cr.name)}</td>$cells</tr>"
      }.mkString
      s"<table><tr><th>Criterion</th>$head</tr>$rows</table>"
    }
    val form = decision match
      case Some(d) =>
        s"""<p class=ok>Decided by ${esc(d.decidedBy)} at ${d.decidedAt.fold("")(
            _.toString
          )}: ${esc(d.selectedApproachRef.map(_.id).getOrElse(""))} selected.</p>
           |<p class=muted>${esc(d.rationale)}</p><ul>${d.rejected
            .map(r => s"<li>${esc(r.approachRef.id)}: ${esc(r.reason)}</li>")
            .mkString}</ul>""".stripMargin
      case None if approaches.isEmpty => "<p class=muted>No candidates yet.</p>"
      case None =>
        val choices = approaches
          .map(a =>
            s"""<label><input type="radio" name="selected" value="${esc(a.id)}" required> ${esc(
                a.title
              )}</label><br>"""
          )
          .mkString
        val reasons = approaches
          .map(a =>
            s"""<p>Reason if <b>${esc(
                a.title
              )}</b> is not selected: <input type="text" name="reason-${esc(a.id)}"></p>"""
          )
          .mkString
        s"""<h2>Decide</h2><form method="post" action="/review/requests/${esc(requestId)}/decide">
           |$choices$reasons
           |<p><select name="reviewer">$options</select> <input type="text" name="rationale" placeholder="rationale" required>
           |<button type="submit">Record the decision</button></p></form>""".stripMargin
    Pages.layout(s"Candidates for $requestId", s"$cards<h2>Comparison</h2>$table$form")
