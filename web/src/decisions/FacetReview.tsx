// The review of one facet: confirm, amend with the amendment, reject, leave unresolved and flagged,
// mark ambiguous, or assume a value; posted to `/facets/{id}/review` or `/facets/{id}/assume`.
import { useId, useState } from "react";
import type { Facet } from "../backend/types.ts";
import { assumeFacet, reviewFacet } from "../backend/records.ts";
import { DecisionForm } from "./Form.tsx";

const actions = [
  ["confirm", "Confirm"],
  ["amend", "Amend"],
  ["reject", "Reject"],
  ["leave_unresolved", "Leave unresolved and flagged"],
  ["mark_ambiguous", "Mark ambiguous"],
  ["assume", "Assume a value"],
] as const;

export function FacetReview({ facet, onDone }: { facet: Facet; onDone?: () => void }) {
  const id = useId();
  const [action, setAction] = useState<string>("confirm");
  const [value, setValue] = useState("");
  const name = facet.name ?? facet.id;
  return (
    <details className="ui-more edp-facet-review">
      <summary>Review the facet {name}</summary>
      <DecisionForm
        title={`Review the facet ${name}`}
        submitLabel="Record the review"
        ownReviewer={false}
        onDone={onDone}
        onSubmit={(reviewer, rationale) =>
          action === "assume"
            ? assumeFacet(facet.id, { value, reviewer, rationale })
            : reviewFacet(facet.id, { action, reviewer, rationale, ...(action === "amend" ? { statement: value } : {}) })
        }
      >
        <div className="ui-field">
          <label htmlFor={`${id}-action`}>Action</label>
          <select id={`${id}-action`} className="ui-input" value={action} onChange={(e) => setAction(e.target.value)}>
            {actions.map(([v, label]) => (
              <option key={v} value={v}>
                {label}
              </option>
            ))}
          </select>
        </div>
        {action === "amend" || action === "assume" ? (
          <div className="ui-field">
            <label htmlFor={`${id}-value`}>{action === "amend" ? "The amended statement" : "The assumed value"}</label>
            <input id={`${id}-value`} className="ui-input" value={value} onChange={(e) => setValue(e.target.value)} />
          </div>
        ) : null}
      </DecisionForm>
    </details>
  );
}
