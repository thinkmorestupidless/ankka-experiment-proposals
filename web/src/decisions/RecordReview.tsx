// The review of a finding, a claim or an assessment: verify, reject, or complain of its citation;
// posted to `/findings|claims|assessments/{id}/review`.
import { useId, useState } from "react";
import { reviewRecord } from "../backend/records.ts";
import { DecisionForm } from "./Form.tsx";

const actions = [
  ["verify", "Verify"],
  ["reject", "Reject"],
  ["complain_citation", "Complain of the citation"],
] as const;

export function RecordReview({
  kind,
  recordId,
  open,
  onDone,
}: {
  kind: "finding" | "claim" | "assessment";
  recordId: string;
  open: boolean;
  onDone?: () => void;
}) {
  const id = useId();
  const [action, setAction] = useState<string>("verify");
  return (
    <details className="ac-more edp-record-review" open={open}>
      <summary>Review this {kind}</summary>
      <DecisionForm
        title={`Review the ${kind} ${recordId}`}
        submitLabel="Record the review"
        onDone={onDone}
        onSubmit={(reviewer, rationale) => reviewRecord(kind, recordId, { action, reviewer, rationale })}
      >
        <div className="ac-field">
          <label htmlFor={`${id}-action`}>Action</label>
          <select id={`${id}-action`} className="ac-input" value={action} onChange={(e) => setAction(e.target.value)}>
            {actions.map(([v, label]) => (
              <option key={v} value={v}>
                {label}
              </option>
            ))}
          </select>
        </div>
      </DecisionForm>
    </details>
  );
}
