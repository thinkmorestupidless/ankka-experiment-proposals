// The frame every card shares: its kind, its id, its version and time where it has them, "open in
// the inspector" and "show its stage" where the place showing it offers them, and every scalar leaf
// of the record under "every field", so nothing the service returned is left out.
import type { ReactNode } from "react";
import { leaves, show } from "./leaves.ts";

export const kindTitle: Record<string, string> = {
  request: "Request",
  facets: "Facets",
  question: "Scientific question",
  "search-plan": "Search plan",
  "search-execution": "Search execution",
  source: "Source",
  finding: "Finding",
  claim: "Claim",
  assessment: "Assessment",
  sufficiency: "Sufficiency",
  package: "Evidence package",
  candidates: "Candidate approaches",
  decision: "Decision",
  design: "Experimental design",
  impact: "Impact",
  "stage-run": "Stage run",
};

export interface CardActions {
  onOpen?: () => void;
  onShowStage?: () => void;
}

export function Card({
  kind,
  id,
  version,
  time,
  record,
  children,
  onOpen,
  onShowStage,
}: {
  kind: string;
  id: string;
  version?: number | null;
  time?: string | null;
  record?: unknown;
  children?: ReactNode;
} & CardActions) {
  const title = kindTitle[kind] ?? kind;
  const all = record === undefined ? [] : leaves(record);
  return (
    <section className="ui-card edp-card" aria-label={`${title} ${id}`} data-kind={kind} data-id={id}>
      <header className="edp-card-head">
        <div>
          <span className="edp-card-tag" aria-hidden="true">
            #{kind}
          </span>
          <h3>{title}</h3>
        </div>
        <span className="edp-card-id">
          <code>{id}</code>
          {version ? <span className="ui-pill">version {version}</span> : null}
          {time ? <time dateTime={time}>{time}</time> : null}
        </span>
      </header>
      {children}
      {onOpen || onShowStage ? (
        <div className="ui-actions">
          {onOpen ? (
            <button type="button" className="ui-button ui-button-small" onClick={onOpen}>
              Open in the inspector
            </button>
          ) : null}
          {onShowStage ? (
            <button type="button" className="ui-button ui-button-small ui-button-quiet" onClick={onShowStage}>
              Show its stage
            </button>
          ) : null}
        </div>
      ) : null}
      {all.length > 0 ? (
        <details className="ui-more edp-every-field">
          <summary>Every field</summary>
          <dl className="ui-facts">
            {all.map((l) => (
              <div key={l.path}>
                <dt>
                  <code>{l.path}</code>
                </dt>
                <dd>{show(l.value)}</dd>
              </div>
            ))}
          </dl>
        </details>
      ) : null}
    </section>
  );
}
