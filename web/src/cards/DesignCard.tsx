// The experimental design: the twelve sections of its latest version, marked a proposal until an
// approval is recorded, every version with its status, the approvals by version; the approval form.
import type { DesignSections, DesignState, Grounded } from "../backend/types.ts";
import { tag } from "../backend/types.ts";
import { Card } from "./Card.tsx";
import { Facts, Field, List } from "./Field.tsx";
import type { CardProps } from "./GenericCard.tsx";
import { Approval } from "../decisions/Approval.tsx";

export const sectionTitles: [keyof DesignSections, string][] = [
  ["questionAndScope", "Question and scope"],
  ["background", "Background"],
  ["hypothesis", "Hypothesis"],
  ["modelAndPrerequisites", "Model and prerequisites"],
  ["variablesControlsComparators", "Variables, controls and comparators"],
  ["endpointsAndMeasurementPlan", "Endpoints and measurement plan"],
  ["successFailureInconclusiveCriteria", "Success, failure and inconclusive criteria"],
  ["resourcesTimelineDependencies", "Resources, timeline and dependencies"],
  ["limitations", "Limitations"],
  ["alternatives", "Alternatives"],
  ["outcomesAndDecisionPoints", "Outcomes and decision points"],
  ["evidenceManifest", "Evidence manifest"],
];

function Section({ field, sections }: { field: keyof DesignSections; sections: DesignSections }) {
  if (field === "background") {
    const items = sections.background as Grounded[];
    return (
      <ul className="edp-list">
        {items.map((g, n) => (
          <li key={n}>
            {g.text}
            {g.claimRefs.length > 0 ? <span className="ac-hint"> ({g.claimRefs.map((r) => r.id).join(", ")})</span> : null}
          </li>
        ))}
      </ul>
    );
  }
  if (field === "evidenceManifest") {
    const m = sections.evidenceManifest;
    return (
      <Facts>
        <Field label="Claims">
          <List items={m.claimRefs.map((r) => `${tag(r.kind)} ${r.id} version ${r.version}`)} />
        </Field>
        <Field label="Spans">
          <List items={m.spans.map((s) => `${s.sourceId} version ${s.sourceVersion}, ${s.sectionId} ${s.startOffset} to ${s.endOffset}`)} />
        </Field>
      </Facts>
    );
  }
  return <p className="edp-section-text">{String(sections[field] ?? "")}</p>;
}

export function DesignCard({ id, record, onOpen, onShowStage, onChanged }: CardProps) {
  const d = record as DesignState;
  const versions = d.versions ?? [];
  const latest = versions[versions.length - 1];
  const approvals = d.approvals ?? [];
  const approved = approvals.find((a) => tag(a.decision) === "approved");
  const shownVersion = approved ? versions.find((v) => v.version === approved.version) ?? latest : latest;
  return (
    <Card kind="design" id={id} version={shownVersion?.version} time={shownVersion?.generatedAt} record={record} onOpen={onOpen} onShowStage={onShowStage}>
      {approved ? (
        <p className="edp-approved" data-approved={approved.version}>
          Approved: version {approved.version} by {approved.reviewer} at <time dateTime={approved.at}>{approved.at}</time>: {approved.rationale}
        </p>
      ) : (
        <p className="edp-proposal" data-proposal="true">
          Proposal awaiting review
        </p>
      )}
      {shownVersion ? (
        <div className="edp-sections" data-version={shownVersion.version}>
          <p className="ac-hint">
            Version {shownVersion.version}, {tag(shownVersion.status)}
          </p>
          {sectionTitles.map(([field, title], n) => (
            <section key={field} className="edp-design-section" data-section={field} aria-label={`${n + 1}. ${title}`}>
              <h4>
                {n + 1}. {title}
              </h4>
              <Section field={field} sections={shownVersion.sections} />
            </section>
          ))}
          <h4>Checks</h4>
          <List items={shownVersion.checks.map((c) => `${c.name}: ${c.passed ? "passed" : "failed"}, ${c.detail}`)} />
        </div>
      ) : (
        <p className="ac-hint">No version is written yet.</p>
      )}
      <div className="edp-history" data-history="design">
        <h4>History</h4>
        <ol className="edp-list">
          {versions.map((v) => (
            <li key={v.version} data-design-version={v.version} data-status={tag(v.status)}>
              version {v.version}, {tag(v.status)}, written {v.generatedAt} by {v.generatedBy}
              {(v.changesApplied ?? []).length > 0 ? `; changes applied: ${(v.changesApplied ?? []).join("; ")}` : ""}
              <ul className="edp-list">
                {approvals
                  .filter((a) => a.version === v.version)
                  .map((a, n) => (
                    <li key={n} data-approval={tag(a.decision)}>
                      {tag(a.decision)} by {a.reviewer} at <time dateTime={a.at}>{a.at}</time>: {a.rationale}
                      {(a.requestedChanges ?? []).length > 0 ? `; changes requested: ${(a.requestedChanges ?? []).join("; ")}` : ""}
                    </li>
                  ))}
              </ul>
            </li>
          ))}
        </ol>
      </div>
      {(d.flags ?? []).length > 0 ? (
        <div className="edp-flags">
          <h4>Flags</h4>
          <List items={(d.flags ?? []).map((f) => `version ${f.version}: ${tag(f.changedRef.kind)} ${f.changedRef.id} version ${f.changedRef.version}, ${f.reason}`)} />
        </div>
      ) : null}
      {latest && tag(latest.status) === "under_review" ? <Approval designId={id} version={latest.version} onDone={onChanged} /> : null}
    </Card>
  );
}
