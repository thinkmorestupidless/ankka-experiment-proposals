// The review queue: every record awaiting a reviewer, as its card beside its passages, with the
// review form. A reviewed record leaves the list when it is fetched again; the inspector shows the
// selected record's source.
import { useState } from "react";
import { Page } from "../shell/Page.tsx";
import { useLoad } from "../cards/useLoad.ts";
import { getClaim, getFinding, getAssessment, getSource, reviewQueue } from "../backend/records.ts";
import type { RecordRow } from "../backend/types.ts";
import { tag } from "../backend/types.ts";
import { Shown } from "../cards/Shown.tsx";

function awaitsReview(record: unknown): boolean {
  const r = record as { record?: { status?: unknown }; reviewStatus?: unknown };
  const status = tag(r.record?.status ?? r.reviewStatus);
  return status === "" || status === "requires_review" || status === "proposed";
}

async function load(): Promise<{ row: RecordRow; record: unknown }[]> {
  const rows = await reviewQueue();
  const read = (r: RecordRow) => (r.kind === "claim" ? getClaim(r.id) : r.kind === "assessment" ? getAssessment(r.id) : getFinding(r.id));
  const all = await Promise.all(rows.map(async (row) => ({ row, record: await read(row).catch(() => undefined) })));
  // The queue is a view and lags a review by a moment; the record itself does not, so a record a
  // reviewer has settled leaves the list as soon as it is read again.
  return all.filter((x) => x.record !== undefined && awaitsReview(x.record));
}

export function Queue() {
  const [reload, setReload] = useState(0);
  const [selected, setSelected] = useState<string>();
  const { value, error } = useLoad(load, [reload]);
  const sourceId = value?.find((x) => x.row.id === selected)?.row.sourceId;
  const { value: source } = useLoad(() => (sourceId ? getSource(sourceId) : Promise.resolve(undefined)), [sourceId, reload]);
  return (
    <Page
      inspectorLabel="The source of the selected record"
      onCloseInspector={() => setSelected(undefined)}
      inspector={sourceId && source ? <Shown kind="source" id={sourceId} record={source} revision={reload} /> : undefined}
    >
      <h1>Review queue</h1>
      <p className="ac-lede">The records awaiting a reviewer, beside their passages.</p>
      {error ? (
        <p role="alert" className="edp-refusal">
          {error}
        </p>
      ) : null}
      {value === undefined ? (
        <p className="ac-hint">Reading the queue…</p>
      ) : value.length === 0 ? (
        <p className="ac-hint" data-empty-queue="">
          Nothing awaits a reviewer.
        </p>
      ) : (
        <ul className="edp-queue">
          {value.map(({ row, record }) => (
            <li key={row.id} data-queued={row.id}>
              <Shown
                kind={row.kind}
                id={row.id}
                record={record}
                revision={reload}
                onChanged={() => setReload((r) => r + 1)}
                onOpen={() => setSelected(row.id)}
              />
            </li>
          ))}
        </ul>
      )}
    </Page>
  );
}
