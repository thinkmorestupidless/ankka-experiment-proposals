// Every request, newest first: its text, who submitted it, its status and where it stands on its
// journey (the node it waits at or is running), each a link to its canvas. The bar's primary action
// submits a request, which is done in the conversation.
import { Link } from "react-router";
import { Page } from "../shell/Page.tsx";
import { usePageSlots } from "../shell/slots.tsx";
import { useLoad } from "../cards/useLoad.ts";
import { getJourney, listRequests } from "../backend/records.ts";
import type { Journey, RequestRow, StageNode } from "../backend/types.ts";
import { tag } from "../backend/types.ts";
import { stageTitle } from "../journey/stages.ts";
import { shownState } from "../journey/Node.tsx";
import { State } from "../cards/State.tsx";

function standing(j?: Journey): StageNode | undefined {
  if (!j) return undefined;
  const all = [...j.spine, ...j.questions.flatMap((q) => q.nodes)];
  return all.find((n) => n.state === "paused" || n.state === "failed") ?? all.find((n) => n.state === "current");
}

async function load(): Promise<{ row: RequestRow; journey?: Journey }[]> {
  const rows = [...(await listRequests())].sort((a, b) => b.submittedAt.localeCompare(a.submittedAt));
  return Promise.all(rows.map(async (row) => ({ row, journey: await getJourney(row.id).catch(() => undefined) })));
}

export function Requests() {
  usePageSlots(
    [],
    <Link to="/chat" className="ac-button ac-button-primary">
      Submit a request
    </Link>,
  );
  const { value, error } = useLoad(load, []);
  return (
    <Page>
      <h1>Requests</h1>
      <p className="ac-lede">Every request, newest first, with its status and where it stands.</p>
      {error ? (
        <p role="alert" className="edp-refusal">
          {error}
        </p>
      ) : null}
      {value === undefined ? (
        <p className="ac-hint">Reading the requests…</p>
      ) : value.length === 0 ? (
        <p className="ac-hint">No request yet. Submit one in the conversation.</p>
      ) : (
        <div className="edp-table-scroll">
          <table className="edp-table edp-requests">
            <thead>
              <tr>
                <th scope="col">Request</th>
                <th scope="col">Submitted by</th>
                <th scope="col">Submitted</th>
                <th scope="col">Status</th>
                <th scope="col">Where it stands</th>
              </tr>
            </thead>
            <tbody>
              {value.map(({ row, journey }) => {
                const at = standing(journey);
                return (
                  <tr key={row.id} data-request={row.id}>
                    <td>
                      <Link to={`/requests/${encodeURIComponent(row.id)}`}>{row.text ?? row.id}</Link>
                      <div className="ac-hint">
                        <code>{row.id}</code>
                      </div>
                    </td>
                    <td>{row.submittedBy ?? ""}</td>
                    <td>
                      <time dateTime={row.submittedAt}>{row.submittedAt}</time>
                    </td>
                    <td>{tag(row.status)}</td>
                    <td>
                      {at ? (
                        <>
                          {stageTitle(at.stage)} <State state={shownState(at).state} />
                        </>
                      ) : journey ? (
                        "done"
                      ) : (
                        ""
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </Page>
  );
}
