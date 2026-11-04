// Where a question is typed. Disabled, with the reason, while an answer is being written, when the
// conversation has spent its budget, and when the assistant is unavailable; the tokens spent are
// shown against the total beneath it.
import { useId, useState, type FormEvent } from "react";

export function Composer({
  onAsk,
  disabled,
  used,
  budget,
}: {
  onAsk: (question: string) => void;
  disabled?: string;
  used: number;
  budget: number;
}) {
  const id = useId();
  const [text, setText] = useState("");
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const q = text.trim();
    if (!q || disabled) return;
    setText("");
    onAsk(q);
  };
  return (
    <form className="edp-composer" onSubmit={submit} aria-label="Ask the assistant">
      <label htmlFor={`${id}-q`}>Your question</label>
      <div className="edp-composer-row">
        <textarea
          id={`${id}-q`}
          className="ac-input"
          rows={2}
          value={text}
          disabled={disabled !== undefined}
          aria-describedby={`${id}-spend${disabled ? ` ${id}-why` : ""}`}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              submit(e);
            }
          }}
        />
        <button type="submit" className="ac-button ac-button-primary" disabled={disabled !== undefined || text.trim() === ""}>
          Ask
        </button>
      </div>
      {disabled ? (
        <p id={`${id}-why`} className="ac-hint" data-disabled="">
          {disabled}
        </p>
      ) : null}
      <p id={`${id}-spend`} className="ac-hint edp-spend" data-used={used} data-budget={budget}>
        {used.toLocaleString("en-GB")} of {budget.toLocaleString("en-GB")} tokens used
      </p>
    </form>
  );
}
