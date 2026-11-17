// The decision between the candidate approaches: one selected, a reason for each one rejected;
// posted to `POST /requests/{id}/decide` with the operator's body.
import { useId, useState } from "react";
import type { ExperimentalApproach } from "../backend/types.ts";
import { decide } from "../backend/records.ts";
import { DecisionForm } from "./Form.tsx";

export function Decide({ requestId, approaches, onDone }: { requestId: string; approaches: ExperimentalApproach[]; onDone?: () => void }) {
  const id = useId();
  const [selected, setSelected] = useState("");
  const [reasons, setReasons] = useState<Record<string, string>>({});
  return (
    <DecisionForm
      title="Decide between the candidate approaches"
      submitLabel="Record the decision"
      onDone={onDone}
      onSubmit={(reviewer, rationale) =>
        decide(requestId, {
          selectedApproachId: selected,
          rejected: approaches.filter((a) => a.id !== selected).map((a) => ({ approachId: a.id, reason: reasons[a.id] ?? "" })),
          decidedBy: reviewer,
          rationale,
        })
      }
    >
      <fieldset className="edp-choices">
        <legend>The approach selected</legend>
        {approaches.map((a) => (
          <div key={a.id} className="edp-choice">
            <input
              type="radio"
              id={`${id}-${a.id}`}
              name={`${id}-selected`}
              value={a.id}
              checked={selected === a.id}
              onChange={() => setSelected(a.id)}
              required
            />
            <label htmlFor={`${id}-${a.id}`}>{a.title ?? a.id}</label>
          </div>
        ))}
      </fieldset>
      {approaches
        .filter((a) => selected && a.id !== selected)
        .map((a) => (
          <div key={a.id} className="ui-field">
            <label htmlFor={`${id}-${a.id}-reason`}>Reason for rejecting {a.title ?? a.id}</label>
            <input
              id={`${id}-${a.id}-reason`}
              className="ui-input"
              data-reason-for={a.id}
              value={reasons[a.id] ?? ""}
              onChange={(e) => setReasons({ ...reasons, [a.id]: e.target.value })}
            />
          </div>
        ))}
    </DecisionForm>
  );
}
