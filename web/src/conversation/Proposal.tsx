// An action the assistant proposed: what it is and each argument exactly as it will be performed,
// with confirm and decline. Nothing is done until the person confirms; then the result is shown as
// its card, read again while it is still being worked on (a source's records as they are verified,
// a finding as its verification runs). The action's own refusal is shown as the service gave it.
import { useState } from "react";
import { BackendError } from "../backend/client.ts";
import { confirmProposal, declineProposal } from "../backend/records.ts";
import type { Proposal as P } from "../backend/types.ts";
import { InlineCard } from "./InlineCard.tsx";
import type { Selection } from "../journey/Canvas.tsx";

export const actionTitle: Record<string, string> = {
  "submit-request": "Submit a request",
  "ingest-source": "Ingest a source",
  "inject-fault": "Inject a reference fault",
  "set-source-status": "Set a source's status",
};

export function Proposal({
  conversationId,
  proposal,
  onSettled,
  onShowStage,
}: {
  conversationId: string;
  proposal: P;
  onSettled: (p: P) => void;
  onShowStage?: (requestId: string, selection: Selection) => void;
}) {
  const [state, setState] = useState<{ busy?: boolean; refusal?: string }>({});
  const status = proposal.status ?? "proposed";
  const settle = async (verb: "confirm" | "decline") => {
    setState({ busy: true });
    try {
      const outcome = await (verb === "confirm" ? confirmProposal : declineProposal)(conversationId, proposal.n);
      onSettled({ ...proposal, ...outcome });
      setState({});
    } catch (e) {
      setState({ refusal: e instanceof BackendError || e instanceof Error ? e.message : String(e) });
    }
  };
  const title = actionTitle[proposal.action] ?? proposal.action;
  return (
    <section className="ac-card edp-action-proposal" aria-label={`Proposed action ${proposal.n}: ${title}`} data-proposal={proposal.n} data-action={proposal.action} data-status={status}>
      <header className="edp-card-head">
        <h3>
          Proposed: {title} <span className="ac-pill">#{proposal.n}</span>
        </h3>
        <span className="ac-pill" data-proposal-status="">
          {status === "proposed" ? "awaiting your confirmation" : status}
        </span>
      </header>
      <dl className="ac-facts">
        {Object.entries(proposal.arguments ?? {}).map(([k, v]) => (
          <div key={k}>
            <dt>{k}</dt>
            <dd>
              <code>{v}</code>
            </dd>
          </div>
        ))}
      </dl>
      {status === "proposed" ? (
        <div className="ac-actions">
          <button type="button" className="ac-button ac-button-primary" disabled={state.busy} onClick={() => settle("confirm")}>
            Confirm: {title.toLowerCase()}
          </button>
          <button type="button" className="ac-button ac-button-quiet" disabled={state.busy} onClick={() => settle("decline")}>
            Decline
          </button>
        </div>
      ) : status === "declined" ? (
        <p className="ac-hint">Declined; nothing was done.</p>
      ) : null}
      {state.refusal ? (
        <p role="alert" className="edp-refusal" data-refusal="">
          {state.refusal}
        </p>
      ) : null}
      {status === "performed" && proposal.result ? (
        <div className="edp-proposal-result">
          <p role="status" className="edp-recorded">
            Done.
          </p>
          <InlineCard reference={{ ...proposal.result, resolved: undefined }} onShowStage={onShowStage} refreshEvery={proposal.result.kind === "request" ? undefined : 2000} />
        </div>
      ) : null}
    </section>
  );
}
