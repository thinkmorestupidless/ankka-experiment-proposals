// A record an answer cites, drawn as its card from the record the service holds, never from the
// answer's words. A reference the service could not resolve, or whose record cannot be read, is a
// chip saying so, with no card.
import { useEffect, useState } from "react";
import { fetchRecord } from "../cards/index.ts";
import { Shown } from "../cards/Shown.tsx";
import { kindTitle } from "../cards/Card.tsx";
import type { Reference } from "../backend/types.ts";
import { stageOf } from "./stageOf.ts";
import type { Selection } from "../journey/Canvas.tsx";

export function Unresolved({ reference, why }: { reference: Reference; why?: string }) {
  return (
    <span className="edp-unresolved" data-unresolved={`${reference.kind}:${reference.id}`} role="note">
      <span aria-hidden="true">⚠ </span>unresolved reference: {kindTitle[reference.kind] ?? reference.kind} <code>{reference.id}</code>
      {why ? <span className="ac-hint"> ({why})</span> : null}
    </span>
  );
}

export function InlineCard({
  reference,
  onShowStage,
  refreshEvery,
}: {
  reference: Reference;
  /** Selects the record's stage on the journey; given only where a journey can be shown. */
  onShowStage?: (requestId: string, selection: Selection) => void;
  /** Reads the record again on this interval (ms), for a record still being worked on. */
  refreshEvery?: number;
}) {
  const [state, setState] = useState<{ record?: unknown; error?: string }>({});
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    if (reference.resolved === false) return;
    let alive = true;
    fetchRecord(reference.kind, reference.id).then(
      (record) => alive && setState({ record }),
      (e: unknown) => alive && setState((s) => (s.record === undefined ? { error: e instanceof Error ? e.message : String(e) } : s)),
    );
    return () => {
      alive = false;
    };
  }, [reference.kind, reference.id, reference.resolved, revision]);
  useEffect(() => {
    if (!refreshEvery) return;
    const t = setInterval(() => setRevision((r) => r + 1), refreshEvery);
    const stop = setTimeout(() => clearInterval(t), 5 * 60_000);
    return () => {
      clearInterval(t);
      clearTimeout(stop);
    };
  }, [refreshEvery]);
  if (reference.resolved === false) return <Unresolved reference={reference} why="the service holds no such record" />;
  // A proposal's result, and a turn's resolved reference, are read here; one that cannot be read is unresolved.
  if (state.error) return <Unresolved reference={reference} why={state.error} />;
  if (state.record === undefined)
    return (
      <p className="ac-hint" data-reading={`${reference.kind}:${reference.id}`}>
        Reading {kindTitle[reference.kind] ?? reference.kind} {reference.id}…
      </p>
    );
  const stage = onShowStage ? stageOf(reference.kind, reference.id) : undefined;
  return (
    <div className="edp-inline-card" data-reference={`${reference.kind}:${reference.id}`}>
      <Shown
        kind={reference.kind}
        id={reference.id}
        record={state.record}
        revision={revision}
        onChanged={() => setRevision((r) => r + 1)}
        onShowStage={stage && onShowStage ? () => onShowStage(stage.requestId, stage.selection) : undefined}
      />
    </div>
  );
}
