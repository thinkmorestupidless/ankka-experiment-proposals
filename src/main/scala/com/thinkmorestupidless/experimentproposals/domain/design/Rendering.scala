package com.thinkmorestupidless.experimentproposals.domain.design

import com.thinkmorestupidless.experimentproposals.domain.Enumerations.*

/**
 * A design version as Markdown (FR-041, research.md R13): a pure function of the record. Every
 * claim reference in the output is written from the manifest; nothing is added. A version that is
 * not approved is headed "Proposal awaiting review"; nothing in it is presented as authorised.
 */
object Rendering:
  private def claim(id: String): String = s"[claim: $id]"

  def render(requestId: String, v: DesignVersion, approvals: Vector[Approval]): String =
    val s = v.sections
    val banner = v.status match
      case DesignStatus.approved =>
        val a = approvals
          .filter(a => a.version == v.version && a.decision == ApprovalDecision.approved)
          .lastOption
        s"**Approved** by ${a.map(_.reviewer).getOrElse("a reviewer")} at ${a.map(_.at.toString).getOrElse("")}."
      case DesignStatus.rejected =>
        s"**Proposal awaiting review** — this version was rejected; see the approval history. Nothing in it is authorised."
      case _ => "**Proposal awaiting review.** Nothing in this document is an authorised procedure."
    val background = s.background.zipWithIndex
      .map((g, i) => s"${i + 1}. ${g.text} ${g.claimRefs.map(r => claim(r.id)).mkString(" ")}")
      .mkString("\n")
    val manifestClaims =
      s.evidenceManifest.claimRefs.map(r => s"- ${claim(r.id)} (v${r.version})").mkString("\n")
    val manifestSpans = s.evidenceManifest.spans
      .map(sp =>
        s"- ${sp.sourceId} v${sp.sourceVersion} ${sp.sectionId} ${sp.startOffset}–${sp.endOffset}: “${sp.quotation}”"
      )
      .mkString("\n")
    val history =
      if approvals.isEmpty then "No reviewer decision yet."
      else
        approvals
          .map(a =>
            s"- v${a.version}: **${a.decision}** by ${a.reviewer} at ${a.at}: ${a.rationale}" + (if a.requestedChanges.isEmpty
                                                                                                 then
                                                                                                   ""
                                                                                                 else
                                                                                                   a.requestedChanges.mkString(
                                                                                                     " Requested: ",
                                                                                                     "; ",
                                                                                                     ""
                                                                                                   ))
          )
          .mkString("\n")
    s"""# Experimental design for $requestId, version ${v.version}
       |
       |$banner
       |
       |Generated ${v.generatedAt} by ${v.generatedBy} from approach ${v.approachRef.id} under decision ${v.decisionRef.id}.
       |
       |## 1. Question and scope
       |${s.questionAndScope}
       |
       |## 2. Background
       |$background
       |
       |## 3. Hypothesis
       |${s.hypothesis}
       |
       |## 4. Model and prerequisites
       |${s.modelAndPrerequisites}
       |
       |## 5. Variables, controls and comparators
       |${s.variablesControlsComparators}
       |
       |## 6. Endpoints and measurement plan
       |${s.endpointsAndMeasurementPlan}
       |
       |## 7. Success, failure and inconclusive criteria
       |${s.successFailureInconclusiveCriteria}
       |
       |## 8. Resources, timeline and dependencies
       |${s.resourcesTimelineDependencies}
       |
       |## 9. Limitations
       |${s.limitations}
       |
       |## 10. Alternatives
       |${s.alternatives}
       |
       |## 11. Outcomes and decision points
       |${s.outcomesAndDecisionPoints}
       |
       |## 12. Evidence manifest
       |Claims:
       |$manifestClaims
       |
       |Spans:
       |$manifestSpans
       |
       |## Approval history
       |$history
       |""".stripMargin

  /** The claim ids a rendering references, for the check that none is outside the manifest. */
  def claimsReferenced(markdown: String): Vector[String] =
    """\[claim: ([^\]]+)\]""".r.findAllMatchIn(markdown).map(_.group(1)).toVector.distinct
