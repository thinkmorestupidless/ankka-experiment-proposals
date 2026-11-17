// The frame every decision shares: the reviewer, the rationale, the submit, the route's refusal shown
// as the message it gave, and the card fetched again once the decision is recorded. The body posted
// is the operator's, to the operator's route.
import { useId, useState, type FormEvent, type ReactNode } from "react";
import { BackendError } from "../backend/client.ts";
import { ReviewerSelect, useReviewer } from "./Reviewer.tsx";

export function DecisionForm({
  title,
  submitLabel,
  onSubmit,
  onDone,
  children,
  rationale = true,
  ownReviewer = true,
  initialRationale = "",
  className,
}: {
  title: string;
  submitLabel: string;
  onSubmit: (reviewer: string, rationale: string) => Promise<unknown>;
  onDone?: () => void;
  children?: ReactNode;
  /** Whether the decision takes a rationale (the confirmation of the facets does not). */
  rationale?: boolean;
  /** Whether the form has its own reviewer select, or uses the one the card shows. */
  ownReviewer?: boolean;
  initialRationale?: string;
  className?: string;
}) {
  const id = useId();
  const { reviewer } = useReviewer();
  const [text, setText] = useState(initialRationale);
  const [state, setState] = useState<{ busy?: boolean; refusal?: string; done?: string }>({});
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setState({ busy: true });
    try {
      await onSubmit(reviewer, text);
      setState({ done: `Recorded, in the name of ${reviewer}.` });
      onDone?.();
    } catch (err) {
      setState({ refusal: err instanceof BackendError || err instanceof Error ? err.message : String(err) });
    }
  };
  return (
    <form className={`edp-decision${className ? ` ${className}` : ""}`} aria-label={title} onSubmit={submit} data-decision={title}>
      <fieldset>
        <legend>{title}</legend>
        {children}
        {ownReviewer ? <ReviewerSelect required /> : null}
        {rationale ? (
          <div className="ui-field">
            <label htmlFor={`${id}-rationale`}>Rationale</label>
            <textarea id={`${id}-rationale`} className="ui-input" rows={2} value={text} onChange={(e) => setText(e.target.value)} />
          </div>
        ) : null}
        <div className="ui-actions">
          <button type="submit" className="ui-button ui-button-primary" disabled={state.busy}>
            {submitLabel}
          </button>
        </div>
        {state.refusal ? (
          <p role="alert" className="edp-refusal" data-refusal="">
            {state.refusal}
          </p>
        ) : null}
        {state.done ? (
          <p role="status" className="edp-recorded">
            {state.done}
          </p>
        ) : null}
      </fieldset>
    </form>
  );
}
