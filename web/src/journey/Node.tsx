// One stage on the canvas: a console part that is a button, with its line icon, its title, its
// detail and an inset row with its state. A paused stage is where the investigation waits; a paused
// stage whose last run failed is drawn failed, with the defect it was classified with.
import type { NodeState, StageNode } from "../backend/types.ts";
import { State } from "../cards/State.tsx";
import { stages, stageTitle } from "./stages.ts";


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
}: {
  node: StageNode;
  selected: boolean;
  onSelect: () => void;
  lane?: string;
}) {
  const Icon = stages[node.stage]?.icon;
  const title = stageTitle(node.stage);
  const { state, waits, defect } = shownState(node);
  const sub = node.detail ?? (state === "unreached" ? "not yet" : "");
  // The name is the title, the state, whether the investigation waits, then the detail and the defect:
  // "facets, failed, the investigation waits here, extract-facets: …". No aria-label, so nothing the
  // node shows is hidden from its name (WCAG 2.5.3). The title and state are read from one hidden span,
  // since a reader puts a space before a positioned one; the badge repeats the state and is left out.
  const after = [sub, defect].filter(Boolean).length > 0 ? "," : "";
  const heard = `, ${words[state]}${waits ? ", the investigation waits here" : ""}${after}`;
  const hidden = (text: string) => <span className="edp-visually-hidden">{text}</span>;
  return (
      <button
        type="button"
        className={`ac-part edp-node${selected ? " ac-part-self" : ""}`}
        aria-pressed={selected}
        data-stage={node.stage}
        data-state={state}
        data-node-state={node.state}
        data-waits={waits ? "true" : "false"}
        data-lane={lane}
        onClick={onSelect}
      >
        <span className="ac-part-icon">{Icon ? <Icon aria-hidden="true" /> : null}</span>
        <span className="ac-part-title" aria-hidden="true">
          {title}
        </span>
        {hidden(`${title}${heard}`)}
        <span className="ac-part-sub">
          {sub}
          {sub && defect ? hidden(",") : null}
        </span>
        <span className="ac-part-inset" aria-hidden="true">
          <State state={state} />
          {waits ? <span className="edp-waits">waits here</span> : null}
        </span>
        {defect ? <span className="edp-node-defect">{defect}</span> : null}
      </button>
  );
}
