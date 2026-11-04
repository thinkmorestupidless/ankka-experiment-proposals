// A row of stages: the spine, or one scientific question's lane. A list whose items are buttons,
// joined by curves, scrolling sideways on a narrow screen.
import type { StageNode } from "../backend/types.ts";
import { Edge } from "./Edge.tsx";
import { Node } from "./Node.tsx";

export function Lane({
  label,
  nodes,
  selected,
  onSelect,
  lane,
}: {
  label: string;
  nodes: StageNode[];
  selected?: string;
  onSelect: (stage: string) => void;
  lane?: string;
}) {
  return (
    <div className="edp-lane-scroll">
      <ol className="edp-lane" role="list" aria-label={label} data-lane={lane ?? "spine"}>
        {nodes.map((n, i) => (
          <li key={n.stage} className="edp-node-item">
            {i > 0 ? <Edge reached={n.state !== "unreached"} /> : null}
            <Node node={n} lane={lane} selected={selected === n.stage} onSelect={() => onSelect(n.stage)} />
          </li>
        ))}
      </ol>
    </div>
  );
}
