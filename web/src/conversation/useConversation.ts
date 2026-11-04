// One conversation with the assistant: opened on the service, its recorded turns and proposals
// loaded, a question put and its answer read as it is written (contracts/http-api.md, the turns
// stream). A turn that fails is kept on the page, marked, until the page is reloaded; the service
// records nothing of it. Each token is drawn as it arrives, so the answer is seen being written.
import { useCallback, useEffect, useRef, useState } from "react";
import { flushSync } from "react-dom";
import { BackendError } from "../backend/client.ts";
import { askTurn, assistantStatus, openConversation } from "../backend/records.ts";
import type { AssistantStatus, Prefill, Proposal, Reference } from "../backend/types.ts";

export interface ShownTurn {
  /** This page's key for the turn. */
  key: string;
  /** The service's number, once the turn is recorded. */
  n?: number;
  question: string;
  answer: string;
  references: Reference[];
  prefill?: Prefill | null;
  state: "writing" | "answered" | "failed" | "declined";
  /** Why the turn failed, or why the assistant declined the question. */
  reason?: string;
  /** How many tokens have been drawn, for the person (and the tests) to see it being written. */
  tokens: number;
}

export interface ConversationState {
  id: string;
  status?: AssistantStatus;
  opened: boolean;
  refusal?: string;
  turns: ShownTurn[];
  proposals: Proposal[];
  used: number;
  budget: number;
  busy: boolean;
}

const freeKey = "edp.conversation";
const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

function newKey(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => alphabet[b % alphabet.length]).join("");
}

/** The free-standing conversation's key, kept by this browser (or for this page, when it cannot keep it). */
let inMemoryKey: string | null = null;
export function freeConversationId(fresh = false): string {
  let key: string | null = null;
  try {
    key = fresh ? null : localStorage.getItem(freeKey);
  } catch {
    key = fresh ? null : inMemoryKey;
  }
  if (!key || !/^[A-Za-z0-9_-]{8,64}$/.test(key)) {
    key = newKey();
    try {
      localStorage.setItem(freeKey, key);
    } catch {
      // kept for this page only
    }
  }
  inMemoryKey = key;
  return `free:${key}`;
}

const message = (e: unknown) => (e instanceof BackendError || e instanceof Error ? e.message : String(e));

export function useConversation(id: string, subject?: string) {
  const [state, setState] = useState<ConversationState>({ id, opened: false, turns: [], proposals: [], used: 0, budget: 0, busy: false });
  const abort = useRef<AbortController | null>(null);

  useEffect(() => {
    let alive = true;
    setState({ id, opened: false, turns: [], proposals: [], used: 0, budget: 0, busy: false });
    (async () => {
      const status = await assistantStatus().catch((e: unknown) => ({ available: false, reason: message(e) }) as AssistantStatus);
      try {
        const c = await openConversation(id, subject);
        if (!alive) return;
        setState((s) => ({
          ...s,
          status,
          opened: true,
          used: c.used ?? 0,
          budget: c.budget ?? 0,
          proposals: (c.proposals ?? []).map((p) => ({ ...p, status: p.status ?? "proposed" })),
          turns: (c.turns ?? []).map((t) => ({
            key: `t${t.n}`,
            n: t.n,
            question: t.question,
            answer: t.answer,
            references: t.references ?? [],
            prefill: t.prefill ?? null,
            state: "answered",
            tokens: 0,
          })),
        }));
      } catch (e) {
        if (alive) setState((s) => ({ ...s, status, refusal: message(e) }));
      }
    })();
    return () => {
      alive = false;
      abort.current?.abort();
    };
  }, [id, subject]);

  const update = (key: string, f: (t: ShownTurn) => ShownTurn) =>
    setState((s) => ({ ...s, turns: s.turns.map((t) => (t.key === key ? f(t) : t)) }));
  const seq = useRef(0);

  const ask = useCallback(
    async (question: string) => {
      const index = `asked-${++seq.current}`;
      setState((s) => ({ ...s, busy: true, turns: [...s.turns, { key: index, question, answer: "", references: [], state: "writing", tokens: 0 }] }));
      const controller = new AbortController();
      abort.current = controller;
      let ended = false;
      try {
        await askTurn(id, question, controller.signal, {
          token: (text) => flushSync(() => update(index, (t) => ({ ...t, answer: t.answer + text, tokens: t.tokens + 1 }))),
          proposal: (p) => setState((s) => ({ ...s, proposals: [...s.proposals.filter((x) => x.n !== p.n), p] })),
          done: (d) => {
            ended = true;
            update(index, (t) => ({ ...t, n: d.n, references: d.references ?? [], prefill: d.prefill ?? null, state: "answered" }));
            setState((s) => ({ ...s, used: d.used, budget: d.budget }));
          },
          error: (reason) => {
            ended = true;
            update(index, (t) => ({ ...t, state: "failed", reason }));
          },
        });
        if (!ended && !controller.signal.aborted) update(index, (t) => ({ ...t, state: "failed", reason: "the answer ended before it was complete" }));
      } catch (e) {
        // A refusal before any answer: the budget reached, a turn already being answered, no model.
        const declined = e instanceof BackendError && (e.status === 409 || e.status === 503);
        update(index, (t) => ({ ...t, state: declined ? "declined" : "failed", reason: message(e) }));
        if (e instanceof BackendError && e.status === 409 && /budget/i.test(e.message)) setState((s) => ({ ...s, used: Math.max(s.used, s.budget) }));
      } finally {
        setState((s) => ({ ...s, busy: false }));
      }
    },
    [id],
  );

  const settled = useCallback((p: Proposal) => setState((s) => ({ ...s, proposals: s.proposals.map((x) => (x.n === p.n ? { ...x, ...p } : x)) })), []);

  return { state, ask, settled };
}
