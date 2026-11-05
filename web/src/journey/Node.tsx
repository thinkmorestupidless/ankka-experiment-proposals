// One stage on the canvas: a dot whose light is its state, and a pill with its title beside it. A
// paused stage is where the investigation waits; a paused stage whose last run failed is drawn
// failed, with the defect it was classified with under its pill.
import type { NodeState, StageNode } from "../backend/types.ts";
import { State } from "../cards/State.tsx";
import { stages, stageTitle } from "./stages.ts";
import type { Side } from "./Canvas.tsx";

/** The state as a reader hears it, the same words as the state badge shows. */
const words: Record<NodeState, string> = {
  reached: "reached",
  current: "running",
  paused: "paused",
  failed: "failed",
  unreached: "not reached",
};

/** What the canvas draws for a node: its own state, or failed when its last run failed. */
export function shownState(node: StageNode): { state: NodeState; waits: boolean; defect?: string } {
  const last = node.runs?.[0];
  const waits = node.state === "paused" || node.state === "failed";
  if (node.state === "failed" || (node.state === "paused" && last?.status === "failed")) {
    return { state: "failed", waits, defect: last?.defect ?? undefined };
  }
  return { state: node.state, waits };
}

export function Node({
  node,
  selected,
  onSelect,
  lane,
  x,
  y,
  side = "below",
}: {
  node: StageNode;
  selected: boolean;
  onSelect: () => void;
  lane?: string;
  x: number;
  y: number;
  side?: Side;
}) {
  const Icon = stages[node.stage]?.icon;
  const title = stageTitle(node.stage);
  const { state, waits, defect } = shownState(node);
  const sub = node.detail ?? (state === "unreached" ? "not yet" : "");
  // The name is the title, the state, whether the investigation waits, then the detail and the defect:
  // "facets, failed, the investigation waits here, extract-facets: …". No aria-label, so nothing the
  // node shows is hidden from its name (WCAG 2.5.3). The title and state are read from one hidden
  // span; the pill's title, glyph and detail repeat them for the eye and are left out.
  const after = [sub, defect].filter(Boolean).length > 0 ? "," : "";
  const heard = `, ${words[state]}${waits ? ", the investigation waits here" : ""}${after}`;
  return (
    <li className="edp-node-item" style={{ left: x, top: y }} data-x={x} data-y={y} data-side={side}>
      <button
        type="button"
        className="edp-node"
        aria-pressed={selected}
        data-stage={node.stage}
        data-state={state}
        data-node-state={node.state}
        data-waits={waits ? "true" : "false"}
        data-selected={selected ? "true" : "false"}
        data-lane={lane}
        onClick={onSelect}
      >
        <span className="edp-dot" aria-hidden="true" />
        <span className="edp-pill">
          <span className="edp-pill-icon" aria-hidden="true">
            {Icon ? <Icon /> : null}
          </span>
          <span className="edp-pill-title" aria-hidden="true">
            {title}
          </span>
          <span className="edp-visually-hidden">{`${title}${heard}`}</span>
          <span className="edp-pill-state" aria-hidden="true">
            <State state={state} />
          </span>
        </span>
        {sub ? (
          <span className="edp-node-sub">
            {sub}
            {defect ? <span className="edp-visually-hidden">,</span> : null}
          </span>
        ) : null}
        {defect ? <span className="edp-node-defect">{defect}</span> : null}
      </button>
    </li>
  );
}
