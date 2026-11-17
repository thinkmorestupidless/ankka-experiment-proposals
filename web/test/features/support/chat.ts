// The conversation on the page: opening a request's or the free-standing one, putting a question
// through the composer and waiting for its answer, and reading a conversation back from the service.
import { expect, type Locator } from "@playwright/test";
import { tagOf, type World } from "./world.ts";
import { approveByApi, ensureCorpus, submitWaiting, toApproval } from "./drive.ts";

const q = encodeURIComponent;

export interface TurnJson {
  n: number;
  question: string;
  answer: string;
  references?: { kind: string; id: string; resolved?: boolean }[];
  prefill?: unknown;
}
export interface ConversationJson {
  id: string;
  budget: number;
  used: number;
  turns?: TurnJson[];
  proposals?: { n: number; action: string; arguments: Record<string, string>; status?: string; result?: { kind: string; id: string } }[];
}

export const conversationOf = (w: World, id: string) => w.get<ConversationJson>(`/conversations/${q(id)}`);

/** The conversation section on the page, by id. */
export const conversation = (w: World, id: string): Locator => w.page.locator(`section.edp-conversation[data-conversation="${id}"]`);

/** The conversation the scenario talks in. */
export function current(w: World): string {
  const c = w.remembered.get("conversation");
  if (!c) throw new Error("no conversation opened");
  return c;
}

/** A free-standing conversation's id, opened on the service, with a key no other scenario uses. */
export async function freshFree(w: World): Promise<string> {
  const key = `bdd${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
  const id = `free:${key}`;
  await w.post(`/conversations/${q(id)}`, {});
  return id;
}

/** Opens a free-standing conversation in the page, as this browser's own. */
export async function openFree(w: World, id: string): Promise<void> {
  const key = id.replace(/^free:/, "");
  await w.page.addInitScript((k) => {
    try {
      localStorage.setItem("edp.conversation", k);
    } catch {
      // the page makes its own
    }
  }, key);
  await w.open("/chat");
  await expect(conversation(w, id)).toBeVisible({ timeout: 30_000 });
  w.remembered.set("conversation", id);
}

/** Opens the request's conversation beside its journey. */
export async function openRequest(w: World, requestId: string): Promise<void> {
  const id = `request:${requestId}`;
  if (!w.page.url().includes(`/requests/${q(requestId)}`) || !(await conversation(w, id).isVisible())) {
    await w.open(`/requests/${q(requestId)}?discuss=1`);
  }
  await expect(conversation(w, id)).toBeVisible({ timeout: 30_000 });
  await expect(composer(w, id)).toBeEnabled({ timeout: 30_000 });
  w.remembered.set("conversation", id);
}

export const composer = (w: World, id = current(w)) => conversation(w, id).getByLabel("Your question");

/** The last turn of the conversation on the page. */
export const lastTurn = (w: World, id = current(w)) => conversation(w, id).locator("li.edp-turn").last();

/** Puts a question through the composer and waits until its answer is no longer being written. */
export async function ask(w: World, question: string, id = current(w)): Promise<Locator> {
  const before = await conversation(w, id).locator("li.edp-turn").count();
  await composer(w, id).fill(question);
  await composer(w, id).press("Enter");
  const turn = conversation(w, id).locator("li.edp-turn").nth(before);
  await expect(turn).toBeVisible();
  await expect(turn).not.toHaveAttribute("data-state", "writing", { timeout: 60_000 });
  return turn;
}

/** The turn's cards, inline in its answer. */
export const cardsIn = (turn: Locator, kind?: string) => turn.locator(kind ? `section.edp-card[data-kind="${kind}"]` : "section.edp-card");

/**
 * The reference request at an approved design. The conversation of a request is spent by every
 * scenario that talks in it, so one that has spent half its budget is passed over for a new one.
 */
export async function approvedRequest(w: World): Promise<string> {
  await ensureCorpus(w);
  const rows = await w.get<{ id: string; text?: string }[]>("/requests");
  for (const row of rows) {
    const d = await w.attempt("GET", `/designs/${q(row.id)}:design`);
    if (d.status !== 200) continue;
    const design = JSON.parse(d.body) as { approvals?: { decision: unknown }[] };
    if (!(design.approvals ?? []).some((a) => tagOf(a.decision) === "approved")) continue;
    const c = await w.attempt("GET", `/conversations/${q(`request:${row.id}`)}`);
    if (c.status === 200) {
      const conv = JSON.parse(c.body) as ConversationJson;
      // A request's turns re-read their history, so each costs more than the last; leave room for a run.
      if (conv.used > conv.budget * 0.6) continue;
    }
    w.remembered.set("request", row.id);
    return row.id;
  }
  // Submitted and waiting at the facet review first: toDecision reads the journey once, and confirms
  // the facets only if the review is already waiting.
  const rid = await submitWaiting(w);
  await approveByApi(w, rid);
  return rid;
}

/** A request whose design waits for its approval: one already waiting, or a new one taken there. */
export async function awaitingApproval(w: World): Promise<string> {
  const rows = await w.get<{ id: string }[]>("/requests");
  for (const row of rows) {
    const j = await w.get<{ spine: { stage: string; state: string }[] }>(`/requests/${q(row.id)}/journey`);
    if (j.spine.find((n) => n.stage === "approval")?.state === "paused") {
      w.remembered.set("request", row.id);
      return row.id;
    }
  }
  const rid = await submitWaiting(w);
  await toApproval(w, rid);
  return rid;
}
