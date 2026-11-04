// A source: its identifiers, adapter, versions and status, and the records extracted from it with
// their status, fetched again on each journey change while the card is shown.
import type { RecordRow, SourceDocument } from "../backend/types.ts";
import { tag } from "../backend/types.ts";
import { sourceRecords } from "../backend/records.ts";
import { Card } from "./Card.tsx";
import { Facts, Field, List } from "./Field.tsx";
import type { CardProps } from "./GenericCard.tsx";
import { useLoad } from "./useLoad.ts";

export function SourceCard({ id, record, onOpen, onShowStage, revision }: CardProps) {
  const s = record as SourceDocument;
  const { value: rows } = useLoad<RecordRow[]>(() => sourceRecords(id), [id, revision, record]);
  const latest = s.versions?.[s.versions.length - 1];
  return (
    <Card kind="source" id={id} version={latest?.version} record={record} onOpen={onOpen} onShowStage={onShowStage}>
      <p className="edp-source-title">{s.title}</p>
      <Facts>
        <Field label="Identifiers">
          <List items={Object.entries(s.identifiers ?? {}).map(([k, v]) => `${k}: ${v}`)} />
        </Field>
        <Field label="Adapter">{id.split(":")[1] ?? ""}</Field>
        <Field label="Kind">{tag(s.kind)}</Field>
        <Field label="Status">{tag(s.status)}</Field>
        <Field label="Versions">
          <List items={(s.versions ?? []).map((v) => `version ${v.version}, retrieved ${v.retrievedAt}${v.quarantined ? ", quarantined" : ""}`)} />
        </Field>
        <Field label="Eligible">{s.eligibility ? (s.eligibility.eligible ? "yes" : "no") : null}</Field>
      </Facts>
      <h4>Records</h4>
      {rows === undefined ? (
        <p className="ac-hint">Reading the records…</p>
      ) : rows.length === 0 ? (
        <p className="ac-hint">none</p>
      ) : (
        <ul className="edp-list edp-source-records">
          {rows.map((r) => (
            <li key={r.id} data-record={r.id} data-status={r.status}>
              <code>{r.id}</code> {r.kind}, {tag(r.status)}
              {r.flagged ? ", flagged" : ""}: {r.proposition || `${r.endpoint} ${r.outcome}`.trim()}
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
