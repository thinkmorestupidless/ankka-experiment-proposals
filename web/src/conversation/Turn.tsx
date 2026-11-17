// One exchange: the question, and the answer as it is written, its references drawn as the cards of
// the records they name and a prefill as the decision's form. A failed answer stays, marked, with
// what was written before it failed; the service recorded nothing of it.
import { Fragment } from "react";
import type { Reference } from "../backend/types.ts";
import { segments } from "./markers.ts";
import type { ShownTurn } from "./useConversation.ts";
import { InlineCard, Unresolved } from "./InlineCard.tsx";
import { PrefillForm } from "./PrefillForm.tsx";
import type { Selection } from "../journey/Canvas.tsx";

const key = (r: Reference) => `${r.kind}:${r.id}`;

export function Turn({ turn, onShowStage }: { turn: ShownTurn; onShowStage?: (requestId: string, selection: Selection) => void }) {
  const writing = turn.state === "writing";
  const { segments: parts } = segments(turn.answer, writing);
  const resolved = new Map(turn.references.map((r) => [key(r), r]));
  return (
    <li className="edp-turn" data-turn={turn.n ?? ""} data-state={turn.state}>
      <p className="edp-question">
        <span className="edp-visually-hidden">The question: </span>
        {turn.question}
      </p>
      <div className="edp-answer" data-tokens={turn.tokens} aria-live={writing ? "polite" : undefined} aria-busy={writing || undefined}>
        <span className="edp-visually-hidden">The answer: </span>
        {parts.map((s, i) => {
          if (s.type === "text") return <Fragment key={i}>{s.text}</Fragment>;
          if (s.type === "code") return <code key={i}>{s.text}</code>;
          if (s.type === "prefill") return writing ? null : <PrefillForm key={i} prefill={s.prefill} />;
          const k = key(s.reference);
          // While the answer is written, and in an answer that failed, a reference is not resolved; once
          // it is answered the service's resolution decides, and a record that cannot be read is unresolved.
          if (turn.state !== "answered") return <code key={i} className="edp-reference-pending">{k}</code>;
          // The service writes `resolved` only when it is true: a reference it lists without it did not resolve.
          const r = resolved.get(k);
          return r && r.resolved === true ? <InlineCard key={i} reference={r} onShowStage={onShowStage} /> : <Unresolved key={i} reference={r ?? s.reference} />;
        })}
        {writing && turn.answer === "" ? <span className="ui-hint">Writing…</span> : null}
      </div>
      {turn.state === "failed" ? (
        <p role="alert" className="edp-refusal edp-turn-failed" data-failed="">
          <span aria-hidden="true">✕ </span>This answer failed and nothing of it was recorded: {turn.reason}
        </p>
      ) : null}
      {turn.state === "declined" ? (
        <p role="alert" className="edp-refusal" data-declined="">
          {turn.reason}
        </p>
      ) : null}
    </li>
  );
}
