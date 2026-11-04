// A request: the canvas of its journey, followed as it changes, and the selected node's card in the
// inspector. The selection is kept in the address, so it survives a redraw and a reload; the bar's
// primary action is the decision the investigation waits for.
import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router";
import { MessagesSquare } from "lucide-react";
import { Conversation } from "../conversation/Conversation.tsx";
import { journeyPath } from "./Chat.tsx";
import { Page } from "../shell/Page.tsx";
import { usePageSlots } from "../shell/slots.tsx";
import { Canvas, type Selection } from "../journey/Canvas.tsx";
import { useJourney } from "../journey/useJourney.ts";
import { cardsFor, pending, type StageCards } from "../journey/stageCard.ts";
import { shownState } from "../journey/Node.tsx";
import { stageTitle } from "../journey/stages.ts";
import { Shown } from "../cards/Shown.tsx";
import { State } from "../cards/State.tsx";
import type { Journey } from "../backend/types.ts";
import type { RequestImpact } from "../cards/ImpactCard.tsx";

const pauses: Record<string, string> = {
  "facet-review": "Confirm the facets",
  decision: "Decide",
  approval: "Approve the design",
};

function waitingAt(journey?: Journey): string | undefined {
  return journey?.spine.find((n) => n.state === "paused" && pauses[n.stage])?.stage;
}

function StageInspector({ shown, revision, onChanged }: { shown: StageCards; revision: number; onChanged: () => void }) {
  const node = shown.node;
  if (!node) return <p className="ac-hint">This stage is not on the journey.</p>;
  const { state, waits, defect } = shownState(node);
  const earlier = shown.runs.filter((r) => r.status !== "running");
  return (
    <div className="edp-stage" data-stage={node.stage} data-state={state}>
      <header className="edp-stage-head">
        <h2>{stageTitle(node.stage)}</h2>
        <State state={state} />
      </header>
      {node.detail ? <p className="ac-hint">{node.detail}</p> : null}
      {waits ? <p className="edp-waits-note">The investigation waits here.</p> : null}
      {defect ? (
        <p className="edp-defect" role="note" data-defect="">
          Failed after {shown.runs.filter((r) => r.status === "failed").length} attempts: {defect}
        </p>
      ) : null}
      {shown.running ? (
        <div className="edp-running" data-running="">
          <p role="status">This stage is running; nothing is recorded yet.</p>
          <h3>Earlier attempts</h3>
          {earlier.length === 0 ? (
            <p className="ac-hint">This is the first attempt.</p>
          ) : (
            <ul className="edp-list">
              {earlier.map((r) => (
                <li key={`${r.runId}-${r.stage}-${r.attempt}`} data-attempt={r.attempt}>
                  {r.stage}, attempt {r.attempt}: {r.status}
                  {r.defect ? `, ${r.defect}` : ""}
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
      {state === "unreached" ? <p className="ac-hint">The investigation has not reached this stage.</p> : null}
      {shown.loading && state !== "unreached" ? <p className="ac-hint">Reading the card…</p> : null}
      {shown.cards.map((c) => (
        <Shown key={`${c.kind}:${c.id}`} kind={c.kind} id={c.id} record={c.record} stages={c.stages} revision={revision} onChanged={onChanged} />
      ))}
    </div>
  );
}

export function Request() {
  const { id = "" } = useParams();
  const { journey, error } = useJourney(id);
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const discussing = params.get("discuss") === "1";
  const stage = params.get("stage") ?? undefined;
  const question = params.get("question") ?? undefined;
  const selection: Selection | undefined = stage ? { stage, question } : undefined;
  const [lane, setLane] = useState<string | undefined>(question);
  const [revision, setRevision] = useState(0);
  const [reload, setReload] = useState(0);
  const [shown, setShown] = useState<StageCards>();
  const seq = useRef(0);
  const shownFor = useRef("");

  useEffect(() => {
    if (journey) setRevision((r) => r + 1);
  }, [journey]);

  useEffect(() => {
    if (!journey || !selection) {
      setShown(undefined);
      return;
    }
    const n = ++seq.current;
    // A new selection shows its stage at once and its cards as they are read; a refetch of the same
    // selection keeps the cards shown until the new ones arrive.
    const key = `${stage}|${question ?? ""}`;
    if (shownFor.current !== key) {
      shownFor.current = key;
      setShown(pending(journey, selection));
    }
    cardsFor(journey, selection).then(
      (s) => n === seq.current && setShown(s),
      () => undefined,
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [journey, stage, question, reload]);

  // A retraction flags its dependents one by one, and the journey does not change as they are
  // flagged: while the impact card still shows a dependent unflagged, read it again.
  useEffect(() => {
    const unflagged = shown?.cards.some(
      (c) => c.kind === "impact" && (c.record as RequestImpact).changes.some((ch) => ch.dependents.some((d) => !d.flagged)),
    );
    if (!unflagged) return;
    const t = setTimeout(() => setReload((r) => r + 1), 3000);
    return () => clearTimeout(t);
  }, [shown]);

  const select = (s: Selection) => {
    const next = new URLSearchParams(params);
    next.set("stage", s.stage);
    if (s.question) next.set("question", s.question);
    else next.delete("question");
    setParams(next, { replace: true });
    if (s.question) setLane(s.question);
  };

  // The conversation is a panel beside the canvas; on a narrow screen it is the whole page.
  const discuss = () => {
    if (!discussing && typeof window !== "undefined" && window.matchMedia?.("(max-width: 719px)").matches) {
      navigate(`/requests/${encodeURIComponent(id)}/chat`);
      return;
    }
    const next = new URLSearchParams(params);
    if (discussing) next.delete("discuss");
    else next.set("discuss", "1");
    setParams(next, { replace: true });
  };
  const showStage = (requestId: string, s: Selection) => (requestId === id ? select(s) : navigate(journeyPath(requestId, s)));

  const waiting = waitingAt(journey);
  usePageSlots(
    stage ? [{ label: stageTitle(stage) }] : [],
    waiting ? (
      <button type="button" className="ac-button ac-button-primary" onClick={() => select({ stage: waiting })} data-primary={waiting}>
        {pauses[waiting]}
      </button>
    ) : null,
    [stage, waiting, params.toString()],
  );

  return (
    <Page
      className="edp-request-page"
      inspectorLabel={stage ? `The card of ${stageTitle(stage)}` : undefined}
      onCloseInspector={() => {
        const next = new URLSearchParams(params);
        next.delete("stage");
        next.delete("question");
        setParams(next, { replace: true });
      }}
      inspector={
        selection ? (
          shown ? (
            <StageInspector shown={shown} revision={revision} onChanged={() => setReload((r) => r + 1)} />
          ) : (
            <p className="ac-hint">Reading the card…</p>
          )
        ) : undefined
      }
    >
      <div className="edp-request-head">
        <h1>
          The request <code>{id}</code>
        </h1>
        <button type="button" className="ac-button ac-button-small" aria-pressed={discussing} onClick={discuss} data-discuss="">
          <MessagesSquare aria-hidden="true" /> {discussing ? "Close the conversation" : "Discuss this request"}
        </button>
      </div>
      {error ? (
        <p role="alert" className="edp-refusal">
          {error}
        </p>
      ) : null}
      <div className={discussing ? "edp-request-split" : undefined}>
        <div className="edp-request-journey">
          {journey ? (
            <Canvas journey={journey} selection={selection} question={lane ?? question} onSelect={select} onQuestion={setLane} />
          ) : error ? null : (
            <p className="ac-hint">Reading the journey…</p>
          )}
        </div>
        {discussing ? (
          <aside className="edp-chat-panel" aria-label="The request's conversation">
            <Conversation id={`request:${id}`} subject={id} onShowStage={showStage} />
          </aside>
        ) : null}
      </div>
    </Page>
  );
}
