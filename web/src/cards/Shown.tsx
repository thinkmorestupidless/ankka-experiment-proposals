// A card for a record already fetched: the component its kind draws, with the place's actions. A
// card that cannot draw its record falls back to the generic card, which shows every field, so one
// unexpected record never takes the page down with it.
import { Component, type ReactNode } from "react";
import { cardFor } from "./index.ts";
import type { CardActions } from "./Card.tsx";
import { GenericCard } from "./GenericCard.tsx";

class Fallback extends Component<{ kind: string; id: string; record: unknown; children: ReactNode }, { failed?: string }> {
  state: { failed?: string } = {};
  static getDerivedStateFromError(e: unknown) {
    return { failed: e instanceof Error ? e.message : String(e) };
  }
  render() {
    if (!this.state.failed) return this.props.children;
    return (
      <div className="edp-card-fallback">
        <p className="ac-hint">This card could not be drawn ({this.state.failed}); every field of the record is below.</p>
        <GenericCard kind={this.props.kind} id={this.props.id} record={this.props.record} />
      </div>
    );
  }
}

export function Shown({
  kind,
  id,
  record,
  stages,
  revision,
  onChanged,
  ...actions
}: { kind: string; id: string; record: unknown; stages?: string[]; revision?: number; onChanged?: () => void } & CardActions) {
  const Drawn = cardFor(kind);
  return (
    <Fallback kind={kind} id={id} record={record}>
      <Drawn kind={kind} id={id} record={record} stages={stages} revision={revision} onChanged={onChanged} {...actions} />
    </Fallback>
  );
}
