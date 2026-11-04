// A node's state by glyph as well as by word and colour: a tick (reached), a ring that pulses
// (current; still when motion is reduced), pause bars (paused), a cross (failed), nothing (unreached).
import type { NodeState } from "../backend/types.ts";

const words: Record<NodeState, string> = {
  reached: "reached",
  current: "running",
  paused: "paused",
  failed: "failed",
  unreached: "not reached",
};

function Glyph({ state }: { state: NodeState }) {
  switch (state) {
    case "reached":
      return (
        <svg viewBox="0 0 16 16" aria-hidden="true" className="edp-glyph">
          <path d="M3 8.5l3 3 7-7" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      );
    case "current":
      return (
        <svg viewBox="0 0 16 16" aria-hidden="true" className="edp-glyph edp-glyph-ring">
          <circle cx="8" cy="8" r="5" fill="none" stroke="currentColor" strokeWidth="2" />
        </svg>
      );
    case "paused":
      return (
        <svg viewBox="0 0 16 16" aria-hidden="true" className="edp-glyph">
          <rect x="4" y="3" width="2.5" height="10" rx="1" fill="currentColor" />
          <rect x="9.5" y="3" width="2.5" height="10" rx="1" fill="currentColor" />
        </svg>
      );
    case "failed":
      return (
        <svg viewBox="0 0 16 16" aria-hidden="true" className="edp-glyph">
          <path d="M4 4l8 8M12 4l-8 8" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
        </svg>
      );
    default:
      return <span className="edp-glyph" aria-hidden="true" />;
  }
}

export function State({ state }: { state: NodeState }) {
  return (
    <span className={`edp-state edp-state-${state}`} data-state={state}>
      <Glyph state={state} />
      <span>{words[state]}</span>
    </span>
  );
}
