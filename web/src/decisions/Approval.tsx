// The approval of a design version: approve, reject, or request changes with the changes asked for;
// posted to `POST /designs/{id}/approval` with the version.
import { useId, useState } from "react";
import { approveDesign } from "../backend/records.ts";
import { DecisionForm } from "./Form.tsx";

const decisions = [
  ["approved", "Approve"],
  ["rejected", "Reject"],
  ["changes_requested", "Request changes"],
] as const;

/** A prefill's `outcome` as the decision it names. */
export const outcomeOf: Record<string, string> = {
  approve: "approved",
  approved: "approved",
  reject: "rejected",
  rejected: "rejected",
  "request-changes": "changes_requested",
  changes: "changes_requested",
  changes_requested: "changes_requested",
};

export function Approval({
  designId,
  version,
  onDone,
  initial,
}: {
  designId: string;
  version: number;
  onDone?: () => void;
  /** Fields a prefill sets: the decision and the rationale; the reviewer still chooses and submits. */
  initial?: { decision?: string; rationale?: string };
}) {
  const id = useId();
  const [decision, setDecision] = useState<string>(initial?.decision ?? "approved");
  const [v, setV] = useState(String(version));
  const [changes, setChanges] = useState("");
  return (
    <DecisionForm
      title={`Decide on version ${version} of the design`}
      submitLabel="Record the decision on the design"
      onDone={onDone}
      initialRationale={initial?.rationale ?? ""}
      onSubmit={(reviewer, rationale) =>
        approveDesign(designId, {
          version: Number(v),
          decision,
          reviewer,
          rationale,
          ...(decision === "changes_requested"
            ? {
                requestedChanges: changes
                  .split("\n")
                  .map((c) => c.trim())
                  .filter(Boolean),
              }
            : {}),
        })
      }
    >
      <div className="ac-field">
        <label htmlFor={`${id}-version`}>Version</label>
        <input id={`${id}-version`} className="ac-input" type="number" min={1} value={v} onChange={(e) => setV(e.target.value)} />
      </div>
      <fieldset className="edp-choices">
        <legend>Decision</legend>
        {decisions.map(([value, label]) => (
          <div key={value} className="edp-choice">
            <input type="radio" id={`${id}-${value}`} name={`${id}-decision`} value={value} checked={decision === value} onChange={() => setDecision(value)} />
            <label htmlFor={`${id}-${value}`}>{label}</label>
          </div>
        ))}
      </fieldset>
      {decision === "changes_requested" ? (
        <div className="ac-field">
          <label htmlFor={`${id}-changes`}>The changes requested, one a line</label>
          <textarea id={`${id}-changes`} className="ac-input" rows={3} value={changes} onChange={(e) => setChanges(e.target.value)} />
        </div>
      ) : null}
    </DecisionForm>
  );
}
