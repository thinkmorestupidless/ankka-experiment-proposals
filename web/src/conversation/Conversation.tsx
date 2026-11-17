// A conversation: a request's (`request:<id>`, the request as its subject) or the free-standing one
// (`free:<key>`, the key kept by this browser). The turns as they were recorded, each new answer as
// it is written, the proposals with their confirm and decline, and the composer.
import type { Selection } from "../journey/Canvas.tsx";
import { Composer } from "./Composer.tsx";
import { Proposal } from "./Proposal.tsx";
import { Turn } from "./Turn.tsx";
import { useConversation } from "./useConversation.ts";

export function Conversation({
  id,
  subject,
  onShowStage,
  onNew,
}: {
  id: string;
  subject?: string;
  onShowStage?: (requestId: string, selection: Selection) => void;
  /** Starts a new conversation in place of this one, where the place allows it. */
  onNew?: () => void;
}) {
  const { state, ask, settled } = useConversation(id, subject);
  const unavailable = state.status && !state.status.available ? `The assistant is unavailable: ${state.status.reason ?? "no model is configured"}.` : undefined;
  const spent = state.opened && state.budget > 0 && state.used >= state.budget ? "This conversation has reached its budget; start a new one to ask more." : undefined;
  const disabled = unavailable ?? spent ?? state.refusal ?? (state.busy ? "The assistant is writing an answer." : undefined) ?? (state.opened ? undefined : "Opening the conversation…");
  return (
    <section className="edp-conversation" aria-label="Conversation with the assistant" data-conversation={id}>
      <header className="edp-conversation-head">
        <h2>{subject ? <>Discuss the request <code>{subject}</code></> : "Ask the assistant"}</h2>
        {onNew ? (
          <button type="button" className="ui-button ui-button-small" onClick={onNew}>
            New conversation
          </button>
        ) : null}
      </header>
      {unavailable ? (
        <p role="status" className="edp-unavailable" data-unavailable="">
          {unavailable} The journey, the cards and the decisions work without it.
        </p>
      ) : null}
      {state.refusal ? (
        <p role="alert" className="edp-refusal">
          {state.refusal}
        </p>
      ) : null}
      <p className="ui-hint">
        The assistant answers from the records and shows each one it cites as its card. It proposes what an operator can do and you confirm it; it decides
        nothing a reviewer decides.
      </p>
      <ol className="edp-turns">
        {state.turns.map((t) => (
          <Turn key={t.key} turn={t} onShowStage={onShowStage} />
        ))}
      </ol>
      {state.proposals.length > 0 ? (
        <div className="edp-proposals">
          {state.proposals.map((p) => (
            <Proposal key={p.n} conversationId={id} proposal={p} onSettled={settled} onShowStage={onShowStage} />
          ))}
        </div>
      ) : null}
      <Composer onAsk={ask} disabled={disabled} used={state.used} budget={state.budget} />
    </section>
  );
}
