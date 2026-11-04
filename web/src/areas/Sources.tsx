// The sources: every one in the store with its latest version and status. Selecting one shows its
// card, with its records, in the inspector, and a form that sets its status as the operator does
// (`POST /sources/{id}/status`, by "operator"; no reviewer decides a source's status).
import { useId, useState, type FormEvent } from "react";
import { useSearchParams } from "react-router";
import { Page } from "../shell/Page.tsx";
import { useLoad } from "../cards/useLoad.ts";
import { Shown } from "../cards/Shown.tsx";
import { getSource, listSources, setSourceStatus } from "../backend/records.ts";
import { BackendError } from "../backend/client.ts";
import { tag } from "../backend/types.ts";

const statuses = ["active", "corrected", "retracted", "superseded", "restricted"] as const;

function StatusForm({ sourceId, current, onDone }: { sourceId: string; current: string; onDone: () => void }) {
  const id = useId();
  const [status, setStatus] = useState(current || "active");
  const [reason, setReason] = useState("");
  const [state, setState] = useState<{ busy?: boolean; refusal?: string; done?: string }>({});
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setState({ busy: true });
    try {
      await setSourceStatus(sourceId, status, reason, "operator");
      setState({ done: `The status is now ${status}.` });
      onDone();
    } catch (err) {
      setState({ refusal: err instanceof BackendError || err instanceof Error ? err.message : String(err) });
    }
  };
  return (
    <form className="ac-card edp-status-form" onSubmit={submit} aria-label={`Set the status of ${sourceId}`}>
      <fieldset>
        <legend>Set the status, as the operator</legend>
        <div className="ac-field">
          <label htmlFor={`${id}-status`}>Status</label>
          <select id={`${id}-status`} className="ac-input" value={status} onChange={(e) => setStatus(e.target.value)}>
            {statuses.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
        <div className="ac-field">
          <label htmlFor={`${id}-reason`}>Reason</label>
          <input id={`${id}-reason`} className="ac-input" value={reason} onChange={(e) => setReason(e.target.value)} />
        </div>
        <div className="ac-actions">
          <button type="submit" className="ac-button ac-button-primary" disabled={state.busy}>
            Set the status
          </button>
        </div>
        {state.refusal ? (
          <p role="alert" className="edp-refusal">
            {state.refusal}
          </p>
        ) : null}
        {state.done ? (
          <p role="status" className="edp-recorded">
            {state.done}
          </p>
        ) : null}
      </fieldset>
    </form>
  );
}

export function Sources() {
  const [params, setParams] = useSearchParams();
  const selected = params.get("source") ?? undefined;
  const [reload, setReload] = useState(0);
  const { value: rows, error } = useLoad(listSources, [reload]);
  const { value: source } = useLoad(() => (selected ? getSource(selected) : Promise.resolve(undefined)), [selected, reload]);
  const choose = (id?: string) => {
    const next = new URLSearchParams(params);
    if (id) next.set("source", id);
    else next.delete("source");
    setParams(next, { replace: true });
  };
  return (
    <Page
      inspectorLabel={selected ? `The source ${selected}` : undefined}
      onCloseInspector={() => choose(undefined)}
      inspector={
        selected ? (
          source ? (
            <>
              <Shown kind="source" id={selected} record={source} revision={reload} />
              <StatusForm key={selected} sourceId={selected} current={tag(source.status)} onDone={() => setReload((r) => r + 1)} />
            </>
          ) : (
            <p className="ac-hint">Reading the source…</p>
          )
        ) : undefined
      }
    >
      <h1>Sources</h1>
      <p className="ac-lede">Every source in the store, its latest version and its status. A source is added by ingesting it, from the conversation.</p>
      {error ? (
        <p role="alert" className="edp-refusal">
          {error}
        </p>
      ) : null}
      {rows === undefined ? (
        <p className="ac-hint">Reading the sources…</p>
      ) : rows.length === 0 ? (
        <p className="ac-hint">No source has been ingested.</p>
      ) : (
        <ul className="edp-sources">
          {rows.map((r) => (
            <li key={r.id} className="ac-card edp-source-row" data-source={r.id} data-status={tag(r.status)}>
              <button type="button" className="ac-button ac-button-quiet" aria-pressed={selected === r.id} onClick={() => choose(r.id)}>
                <strong>{r.title || r.id}</strong> <code>{r.id}</code>
              </button>
              <span>
                version {r.latestVersion}, {tag(r.status)}
                {r.quarantined ? ", quarantined" : ""}
                {r.eligible === false ? ", not eligible" : ""}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Page>
  );
}
