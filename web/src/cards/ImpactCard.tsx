// The impact on a request of a change to a source: each flag with the changed source and version,
// and every dependent the change flagged for review.
import type { DependentView, DesignFlag } from "../backend/types.ts";
import { tag } from "../backend/types.ts";
import { Card } from "./Card.tsx";
import type { CardProps } from "./GenericCard.tsx";

export interface RequestImpact {
  requestId: string;
  flags: DesignFlag[];
  changes: { ref: string; dependents: DependentView[] }[];
}

export function ImpactCard({ id, record, onOpen, onShowStage }: CardProps) {
  const impact = record as RequestImpact;
  return (
    <Card kind="impact" id={id} record={record} onOpen={onOpen} onShowStage={onShowStage}>
      {impact.changes.length === 0 && impact.flags.length === 0 ? <p className="ui-hint">Nothing this request rests on has changed.</p> : null}
      {impact.changes.map((c) => (
        <section key={c.ref} className="edp-impact" data-changed={c.ref} aria-label={`The change ${c.ref}`}>
          <h4>
            Changed: <code>{c.ref}</code>
          </h4>
          <ul className="edp-list">
            {c.dependents.map((d) => (
              <li key={`${d.kind}-${d.id}`} data-dependent={d.id} data-flagged={d.flagged ? "true" : "false"}>
                {d.kind} {d.id} version {d.version}: {d.flagged ? `flagged for review, naming ${c.ref}` : "not flagged"}
              </li>
            ))}
          </ul>
        </section>
      ))}
      {impact.flags.length > 0 ? (
        <div className="edp-flags">
          <h4>Flags on the design</h4>
          <ul className="edp-list">
            {impact.flags.map((f, n) => (
              <li key={n}>
                design version {f.version}: {tag(f.changedRef.kind)} {f.changedRef.id} version {f.changedRef.version}, {f.reason}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </Card>
  );
}
