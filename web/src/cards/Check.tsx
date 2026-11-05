// A verification check: its name, its result by glyph, word and colour, and what it rests on.
import type { VerificationCheck } from "../backend/types.ts";

export function Check({ name, check }: { name: string; check: VerificationCheck }) {
  return (
    <li className={`edp-check ${check.passed ? "edp-check-passed" : "edp-check-failed"}`}>
      <span className="edp-check-result">
        <span aria-hidden="true">{check.passed ? "✓" : "✗"}</span> {name}: {check.passed ? "passed" : "failed"}
      </span>
      {check.note ? <span className="edp-check-note">{check.note}</span> : null}
      {check.evidenceQuote ? <q className="edp-check-quote">{check.evidenceQuote}</q> : null}
    </li>
  );
}

export function Checks({ checks }: { checks: Record<string, VerificationCheck> }) {
  const entries = Object.entries(checks);
  if (entries.length === 0) return <p className="ui-hint">No checks recorded.</p>;
  return (
    <ul className="edp-checks">
      {entries.map(([name, c]) => (
        <Check key={name} name={name} check={c} />
      ))}
    </ul>
  );
}
