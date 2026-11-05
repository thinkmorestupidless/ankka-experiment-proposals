// The steps of features/interface/conversation.feature. The assistant answers from the script
// `EDP_ASSISTANT_SCRIPT` names (evals/assistant/walkthrough.json), so each question is worded to
// meet one of its rules; every card it shows is checked against the record the service holds.
import { expect, type Locator } from "@playwright/test";
import { Given, Then, When, tagOf, type World } from "../support/world.ts";
import { inspector, rid, submitWaiting } from "../support/drive.ts";
import {
  approvedRequest,
  ask,
  awaitingApproval,
  cardsIn,
  composer,
  conversation,
  conversationOf,
  current,
  freshFree,
  lastTurn,
  openFree,
  openRequest,
  type ConversationJson,
} from "../support/chat.ts";
import { leaves, show } from "../../../src/cards/leaves.ts";

const q = encodeURIComponent;
const turn = (w: World) => lastTurn(w);
const answerOf = (t: Locator) => t.locator(".edp-answer");

/** Every scalar leaf of the stored record appears in the card: the card is the record. */
async function isStored(w: World, card: Locator, kind: string, id: string): Promise<void> {
  const paths: Record<string, string> = {
    request: `/requests/${q(id)}`,
    facets: `/requests/${q(id)}/facets`,
    finding: `/findings/${q(id)}`,
    claim: `/claims/${q(id)}`,
    assessment: `/assessments/${q(id)}`,
    "search-execution": `/search-executions/${q(id)}`,
    source: `/sources/${q(id)}`,
  };
  let record: unknown;
  if (kind === "sufficiency") {
    const view = await w.get<{ sufficiency: { id: string }[] }>(`/questions/${q(id.slice(0, id.lastIndexOf(":")))}`);
    record = view.sufficiency.find((s) => s.id === id);
  } else record = await w.get(paths[kind]);
  expect(record, `the stored ${kind} ${id}`).toBeDefined();
  const text = (await card.textContent()) ?? "";
  const missing = leaves(record)
    .filter((l) => !text.includes(show(l.value)))
    .map((l) => `${l.path}=${show(l.value)}`);
  expect(missing, `${kind} ${id}`).toEqual([]);
}

async function inRequestConversation(w: World): Promise<void> {
  const r = w.remembered.get("request") ?? (await approvedRequest(w));
  await openRequest(w, r);
}

// ── background ────────────────────────────────────────────────────────────────

Given("the customer request {string} has an approved experimental design", async ({ world, $testInfo }, text: string) => {
  expect(text).toBe("Can you make foie gras?");
  // A request's conversation is spent by the scenarios that talk in it; taking a new request to an
  // approved design, when the last one's is half spent, takes minutes in the replay tier.
  $testInfo.setTimeout(600_000);
  await approvedRequest(world);
});

Given("a model is configured for the assistant", async ({ world, $tags }) => {
  // The scenario tagged @no-model says otherwise in its own Given, and runs against a stack without one.
  if ($tags.includes("@no-model")) return;
  const status = await world.get<{ available: boolean }>("/assistant");
  expect(status.available, "start the service with EDP_ASSISTANT_SCRIPT=evals/assistant/walkthrough.json").toBe(true);
});

// ── the four questions ───────────────────────────────────────────────────────

When("the person wants to know why the finding from the source {string} is indirect", async ({ world }, source: string) => {
  await inRequestConversation(world);
  await ask(world, `Why is the finding from ${source} indirect?`);
});

Then("the answer names the species rule and the assessment", async ({ world }) => {
  const t = turn(world);
  await expect(t).toHaveAttribute("data-state", "answered");
  await expect(answerOf(t)).toContainText("species rule");
  await expect(cardsIn(t, "assessment")).toHaveCount(1);
});

Then("the card of the assessment is shown with its applicability and its rationale", async ({ world }) => {
  const c = cardsIn(turn(world), "assessment");
  await expect(c).toBeVisible({ timeout: 30_000 });
  const id = (await c.getAttribute("data-id"))!;
  const a = await world.get<{ applicability: unknown; rationale: string }>(`/assessments/${q(id)}`);
  expect(tagOf(a.applicability)).toBe("indirect");
  await expect(c).toContainText(tagOf(a.applicability));
  await expect(c).toContainText(a.rationale);
  await isStored(world, c, "assessment", id);
});

When("the person wants to know what blocked the decision {string}", async ({ world }, decision: string) => {
  await inRequestConversation(world);
  world.remembered.set("decision", decision);
  await ask(world, `What blocked the decision "${decision}"?`);
});

Then("the answer names the critical gap", async ({ world }) => {
  await expect(answerOf(turn(world))).toContainText("critical gap");
});

Then("the card of the sufficiency judgement is shown with its status, its critical gaps and its assumptions", async ({ world }) => {
  const c = cardsIn(turn(world), "sufficiency");
  await expect(c).toBeVisible({ timeout: 30_000 });
  const id = (await c.getAttribute("data-id"))!;
  const view = await world.get<{ sufficiency: { id: string; status: unknown; intendedDecision: string; criticalGapIds?: string[]; assumptions?: string[] }[] }>(
    `/questions/${q(id.slice(0, id.lastIndexOf(":")))}`,
  );
  const s = view.sufficiency.find((x) => x.id === id)!;
  expect(s.intendedDecision).toBe(world.remembered.get("decision"));
  await expect(c).toContainText(tagOf(s.status));
  expect((s.criticalGapIds ?? []).length, "the judgement has a critical gap").toBeGreaterThan(0);
  for (const g of s.criticalGapIds ?? []) await expect(c).toContainText(g);
  for (const a of s.assumptions ?? []) await expect(c).toContainText(a);
  await isStored(world, c, "sufficiency", id);
});

When("the person wants to know what the search for the scientific question {string} excluded", async ({ world }, question: string) => {
  await inRequestConversation(world);
  await ask(world, `What was excluded by the search for "${question}"?`);
});

Then("the answer names the retracted source and the eligibility rule", async ({ world }) => {
  const a = answerOf(turn(world));
  await expect(a).toContainText("retracted source");
  await expect(a).toContainText("eligibility.retracted");
});

Then("the card of the search execution is shown with its exclusions", async ({ world }) => {
  const c = cardsIn(turn(world), "search-execution");
  await expect(c).toBeVisible({ timeout: 30_000 });
  const id = (await c.getAttribute("data-id"))!;
  const e = await world.get<{ exclusions?: { sourceId: string; ruleId: string }[] }>(`/search-executions/${q(id)}`);
  expect((e.exclusions ?? []).length).toBeGreaterThan(0);
  for (const x of e.exclusions ?? []) {
    await expect(c).toContainText(x.sourceId);
    await expect(c).toContainText(x.ruleId);
  }
  expect((e.exclusions ?? []).map((x) => x.sourceId)).toContain("src:file:retracted");
});

When("the person wants to see the facets", async ({ world }) => {
  await inRequestConversation(world);
  await ask(world, "Show me the facets");
});

Then("the card of the facets is shown", async ({ world }) => {
  const c = cardsIn(turn(world), "facets");
  await expect(c).toBeVisible({ timeout: 30_000 });
  await expect(c).toHaveAttribute("data-id", rid(world));
  await isStored(world, c, "facets", rid(world));
});

// ── every citation a card ────────────────────────────────────────────────────

When("the person puts any question the records answer", async ({ world }) => {
  await inRequestConversation(world);
  await ask(world, "Why is the finding from Paper B indirect?");
});

Then("each record the answer cites is named by a reference", async ({ world }) => {
  const c = await conversationOf(world, current(world));
  const last = c.turns![c.turns!.length - 1];
  const refs = last.references ?? [];
  expect(refs.length).toBeGreaterThan(0);
  expect(refs.every((r) => r.resolved === true), "every reference resolved").toBe(true);
  const shown = await turn(world).locator(".edp-inline-card").evaluateAll((all) => all.map((e) => e.getAttribute("data-reference")));
  expect(shown.sort()).toEqual(refs.map((r) => `${r.kind}:${r.id}`).sort());
  world.remembered.set("references", JSON.stringify(refs));
});

Then("each reference is shown as the card of the stored record", async ({ world }) => {
  const refs = JSON.parse(world.remembered.get("references")!) as { kind: string; id: string }[];
  for (const r of refs) {
    const c = cardsIn(turn(world), r.kind).and(turn(world).locator(`section.edp-card[data-id="${r.id}"]`));
    await expect(c).toBeVisible({ timeout: 30_000 });
    await isStored(world, c, r.kind, r.id);
  }
});

// ── a wish to approve ─────────────────────────────────────────────────────────

Given("an experimental design awaiting approval", async ({ world, $testInfo }) => {
  $testInfo.setTimeout(600_000);
  const r = await awaitingApproval(world);
  const d = await world.get<{ approvals?: unknown[] }>(`/designs/${q(r)}:design`);
  world.remembered.set("approvals", String((d.approvals ?? []).length));
});

When("the person tells the assistant to approve it", async ({ world }) => {
  await openRequest(world, rid(world));
  await ask(world, "Approve it.");
});

Then("no approval is recorded", async ({ world }) => {
  const d = await world.get<{ approvals?: unknown[] }>(`/designs/${q(rid(world))}:design`);
  expect((d.approvals ?? []).length).toBe(Number(world.remembered.get("approvals")));
  const j = await world.get<{ spine: { stage: string; state: string }[] }>(`/requests/${q(rid(world))}/journey`);
  expect(j.spine.find((n) => n.stage === "approval")?.state).toBe("paused");
});

Then("a prefilled decision is shown for the reviewer to complete", async ({ world }) => {
  const form = turn(world).locator(`.edp-prefill[data-prefill="approval"][data-target="${rid(world)}:design"]`);
  await expect(form).toBeVisible({ timeout: 30_000 });
  await expect(form.getByLabel("Approve", { exact: true })).toBeChecked();
  await expect(form.getByLabel("Reviewer", { exact: true })).toHaveValue("");
  await expect(form.getByRole("button", { name: "Record the decision on the design" })).toBeVisible();
});

Then("the answer says a reviewer must decide", async ({ world }) => {
  await expect(answerOf(turn(world))).toContainText("reviewer must");
});

// ── what the records do not answer ────────────────────────────────────────────

When("the person puts a question the records do not answer", async ({ world }) => {
  await inRequestConversation(world);
  await ask(world, "What is the price of a kilogram of foie gras?");
});

Then("the answer states what is not established", async ({ world }) => {
  await expect(answerOf(turn(world))).toContainText("not established");
});

Then("the answer supplies no record that does not exist", async ({ world }) => {
  const t = turn(world);
  await expect(cardsIn(t)).toHaveCount(0);
  await expect(t.locator(".edp-unresolved")).toHaveCount(0);
  const c = await conversationOf(world, current(world));
  expect(c.turns![c.turns!.length - 1].references ?? []).toEqual([]);
});

Given("an answer that cites a record that does not exist", async ({ world }) => {
  world.remembered.set("question", "Cite the finding that does not exist.");
});

When("the answer is shown", async ({ world }) => {
  await inRequestConversation(world);
  await ask(world, world.remembered.get("question")!);
});

Then("the reference is marked as unresolved", async ({ world }) => {
  const chip = turn(world).locator('.edp-unresolved[data-unresolved="finding:src:file:nowhere:v1:fd9"]');
  await expect(chip).toBeVisible();
  const c = await conversationOf(world, current(world));
  const ref = c.turns![c.turns!.length - 1].references?.find((r) => r.id === "src:file:nowhere:v1:fd9");
  expect(ref, "the turn names the reference").toBeDefined();
  // The service writes `resolved` only when it is true.
  expect(ref?.resolved ?? false).toBe(false);
});

Then("no card is shown for it", async ({ world }) => {
  await expect(cardsIn(turn(world))).toHaveCount(0);
});

// ── as it is written ──────────────────────────────────────────────────────────

When("the person puts a question", async ({ world }) => {
  if (!world.remembered.get("conversation")) await inRequestConversation(world);
  const id = current(world);
  // Until the conversation is open the composer is disabled for that reason alone.
  await expect(conversation(world, id).locator("[data-disabled]", { hasText: "Opening the conversation" })).toHaveCount(0, { timeout: 30_000 });
  if (await composer(world, id).isDisabled()) {
    // The conversation declines before a question can be put: the composer says why.
    world.remembered.set("declined", (await conversation(world, id).locator("[data-disabled]").textContent()) ?? "");
    return;
  }
  // Each drawn token changes the answer's count; the observer keeps every count it saw.
  await world.page.evaluate((cid) => {
    const w = window as unknown as { __edpTokens: string[] };
    w.__edpTokens = [];
    const root = document.querySelector(`section.edp-conversation[data-conversation="${cid}"] ol.edp-turns`)!;
    new MutationObserver((records) => {
      for (const r of records) {
        const el = r.target as Element;
        // The value before each change: the observer is called once for several changes in a row.
        if (r.type === "attributes" && el.classList?.contains("edp-answer")) w.__edpTokens.push(r.oldValue ?? "");
      }
    }).observe(root, { subtree: true, attributes: true, attributeOldValue: true, attributeFilter: ["data-tokens"] });
  }, id);
  await ask(world, "Why is the finding from Paper B indirect?");
});

Then("the answer appears as it is written", async ({ world }) => {
  const seen = await world.page.evaluate(() => (window as unknown as { __edpTokens: string[] }).__edpTokens);
  // Each value the count had before it changed: 0, 1, 2, … for an answer drawn token by token.
  const distinct = new Set(seen);
  expect(distinct.size, `the answer was drawn ${distinct.size} times as it was written`).toBeGreaterThanOrEqual(2);
  const words = ((await answerOf(turn(world)).getAttribute("data-tokens")) ?? "0");
  expect(Number(words)).toBeGreaterThanOrEqual(2);
  await expect(turn(world)).toHaveAttribute("data-state", "answered");
});

// ── a failure records nothing ─────────────────────────────────────────────────

Given("the model fails while an answer is being written", async ({ world }) => {
  await inRequestConversation(world);
  const before = await conversationOf(world, current(world));
  world.remembered.set("question", "This answer is cut short.");
  world.nothingRecorded = async () => {
    const after = await conversationOf(world, current(world));
    expect((after.turns ?? []).length).toBe((before.turns ?? []).length);
    expect((after.turns ?? []).map((t) => t.question)).not.toContain("This answer is cut short.");
  };
});

When("the answer ends", async ({ world }) => {
  await ask(world, world.remembered.get("question")!);
});

Then("the answer is marked as failed", async ({ world }) => {
  const t = turn(world);
  await expect(t).toHaveAttribute("data-state", "failed");
  await expect(t.locator("[data-failed]")).toContainText("nothing of it was recorded");
  await expect(answerOf(t)).toContainText("The answer begins and then");
});

// ── two conversations ─────────────────────────────────────────────────────────

Given("two conversations", async ({ world }) => {
  world.remembered.set("first", await freshFree(world));
  world.remembered.set("second", await freshFree(world));
});

When("a question is put in each", async ({ world }) => {
  await openFree(world, world.remembered.get("first")!);
  await ask(world, "What is the price of a duck?");
  await openFree(world, world.remembered.get("second")!);
  await ask(world, "Cite the finding that does not exist.");
});

Then("each answer belongs to its own conversation", async ({ world }) => {
  const first = await conversationOf(world, world.remembered.get("first")!);
  const second = await conversationOf(world, world.remembered.get("second")!);
  expect((first.turns ?? []).map((t) => t.question)).toEqual(["What is the price of a duck?"]);
  expect((second.turns ?? []).map((t) => t.question)).toEqual(["Cite the finding that does not exist."]);
});

Then("neither conversation sees the history of the other", async ({ world }) => {
  // The page shows each conversation's own turns only.
  const id = world.remembered.get("second")!;
  await expect(conversation(world, id).locator("li.edp-turn")).toHaveCount(1);
  await openFree(world, world.remembered.get("first")!);
  const first = conversation(world, world.remembered.get("first")!);
  await expect(first.locator("li.edp-turn")).toHaveCount(1);
  await expect(first).not.toContainText("does not exist");
});

// ── the request's subject ─────────────────────────────────────────────────────

Given("the conversation of the customer request is opened from its journey", async ({ world }) => {
  await world.open(`/requests/${q(rid(world))}`);
  await expect(world.page.locator(`.edp-canvas[data-request="${rid(world)}"]`)).toBeVisible({ timeout: 30_000 });
  await world.page.getByRole("button", { name: "Discuss this request" }).click();
  const id = `request:${rid(world)}`;
  await expect(conversation(world, id)).toBeVisible();
  await expect(composer(world, id)).toBeEnabled({ timeout: 30_000 });
  world.remembered.set("conversation", id);
});

When(
  "the person wants to know why the finding from the source {string} is indirect without naming the customer request",
  async ({ world }, source: string) => {
    await ask(world, `Why is the finding from ${source} indirect?`);
  },
);

Then("the answer is about that customer request", async ({ world }) => {
  const c = cardsIn(turn(world), "assessment");
  await expect(c).toBeVisible({ timeout: 30_000 });
  expect(await c.getAttribute("data-id")).toMatch(new RegExp(`^${rid(world)}:q\\d+:`));
});

Then("the card of the assessment selects the stage {string} on the journey", async ({ world }, stage: string) => {
  const c = cardsIn(turn(world), "assessment");
  await c.getByRole("button", { name: "Show its stage" }).click();
  await expect(inspector(world).locator(`.edp-stage[data-stage="${stage}"]`)).toBeVisible({ timeout: 30_000 });
  const lane = (await c.getAttribute("data-id"))!.split(":").slice(0, 2).join(":");
  expect(new URL(world.page.url()).searchParams.get("question")).toBe(lane);
  await expect(world.page.locator(`.edp-lane[data-lane="${lane}"] button[data-stage="${stage}"]`)).toHaveAttribute("aria-pressed", "true");
});

// ── the free-standing conversation ────────────────────────────────────────────

Given("two customer requests", async ({ world }) => {
  const a = await submitWaiting(world);
  const b = await submitWaiting(world);
  world.remembered.set("requests", JSON.stringify([a, b]));
  // The listing the assistant reads is a view, and lags the requests by a moment.
  await world.eventually("both requests listed", async () => {
    const ids = (await world.get<{ id: string }[]>("/requests")).map((r) => r.id);
    return ids.includes(a) && ids.includes(b);
  });
});

Given("the free-standing conversation is opened from the rail", async ({ world }) => {
  await world.open("/");
  await world.page.getByRole("navigation", { name: "Areas" }).getByRole("link", { name: "Conversation" }).click();
  const section = world.page.locator("section.edp-conversation");
  await expect(section).toBeVisible({ timeout: 30_000 });
  const id = (await section.getAttribute("data-conversation"))!;
  expect(id).toMatch(/^free:/);
  world.remembered.set("conversation", id);
  await expect(composer(world, id)).toBeEnabled({ timeout: 30_000 });
});

When("the person wants to know which customer requests are waiting for a reviewer", async ({ world }) => {
  await ask(world, "Which requests are waiting for a reviewer?");
});

Then("the answer names both customer requests that are waiting", async ({ world }) => {
  const c = await conversationOf(world, current(world));
  const refs = (c.turns![c.turns!.length - 1].references ?? []).filter((r) => r.kind === "request").map((r) => r.id);
  for (const r of JSON.parse(world.remembered.get("requests")!) as string[]) expect(refs).toContain(r);
});

Then("the card of each customer request is shown", async ({ world }) => {
  for (const r of JSON.parse(world.remembered.get("requests")!) as string[]) {
    const c = turn(world).locator(`section.edp-card[data-kind="request"][data-id="${r}"]`);
    await expect(c).toBeVisible({ timeout: 30_000 });
    const row = (await world.get<{ id: string; status: string }[]>("/requests")).find((x) => x.id === r)!;
    expect(row.status).toContain("facet");
    await expect(c).toContainText(r);
  }
});

// ── without a model ───────────────────────────────────────────────────────────

Given("no model is configured for the assistant", async ({ world }) => {
  const status = await world.get<{ available: boolean; reason?: string }>("/assistant");
  expect(status.available, "the no-model project runs against a service started without EDP_ASSISTANT_SCRIPT or a key").toBe(false);
  world.remembered.set("reason", status.reason ?? "");
});

When("the conversation is opened", async ({ world }) => {
  await world.open("/chat");
  await expect(world.page.locator("section.edp-conversation")).toBeVisible({ timeout: 30_000 });
});

Then("the conversation says the assistant is unavailable and why", async ({ world }) => {
  const note = world.page.locator("section.edp-conversation [data-unavailable]");
  await expect(note).toContainText("unavailable");
  await expect(note).toContainText(world.remembered.get("reason")!);
  await expect(world.page.getByLabel("Your question")).toBeDisabled();
});

Then("the journey, the cards and the decisions still work", async ({ world }) => {
  const r = rid(world);
  await world.open(`/requests/${q(r)}?stage=facet-review`);
  await expect(world.page.locator(`.edp-canvas[data-request="${r}"]`)).toBeVisible({ timeout: 30_000 });
  await expect(inspector(world).locator('section.edp-card[data-kind="facets"]')).toBeVisible({ timeout: 30_000 });
  await world.page.locator('.edp-lane[data-lane="spine"] button[data-stage="approval"]').click();
  const design = inspector(world).locator('section.edp-card[data-kind="design"]');
  await expect(design).toBeVisible({ timeout: 30_000 });
  await expect(design).toContainText("approved");
  // A decision's reviewer is chosen from the configured ones, which the page still reads.
  const reviewers = await world.get<{ name: string }[]>("/reviewers");
  const select = inspector(world).getByLabel("Reviewer", { exact: true }).first();
  if (await select.count()) await expect(select.locator("option")).toHaveCount(reviewers.length + 1);
});

// ── the budget ────────────────────────────────────────────────────────────────

/** Spends a free-standing conversation's budget through the service: each turn reads a whole question. */
async function spend(w: World, id: string, requestId: string): Promise<ConversationJson> {
  // A free-standing conversation has no subject; naming one in the question fills the script's ids, so
  // each turn reads the question's record and sends it back with the whole conversation so far.
  const question = `Why is the finding from Paper B indirect? This conversation's subject is the customer request ${requestId}.`;
  for (let i = 0; i < 60; i++) {
    const c = await conversationOf(w, id);
    if (c.used >= c.budget) return c;
    const r = await w.request.post(`/backend/api/v1/conversations/${q(id)}/turns`, {
      headers: { Authorization: `Bearer ${process.env.EDP_API_TOKEN ?? ""}`, Accept: "text/event-stream" },
      data: { question },
    });
    await r.text();
  }
  throw new Error("the budget was not spent in 60 turns");
}

Given("a conversation that has spent its budget", async ({ world, $testInfo }) => {
  $testInfo.setTimeout(900_000);
  const id = await freshFree(world);
  const spent = await spend(world, id, rid(world));
  expect(spent.used).toBeGreaterThanOrEqual(spent.budget);
  const refused = await world.attempt("POST", `/conversations/${q(id)}/turns`, { question: "What is the price?" });
  expect(refused.status).toBe(409);
  world.remembered.set("refusal", refused.body);
  await openFree(world, id);
});

Then("the assistant declines and says the budget is reached", async ({ world }) => {
  const id = current(world);
  const said = world.remembered.get("declined") ?? (await lastTurn(world, id).locator("[data-declined]").textContent()) ?? "";
  expect(said).toMatch(/budget/i);
  await expect(composer(world, id)).toBeDisabled();
  expect(world.remembered.get("refusal")).toMatch(/budget/i);
});

Then("a new conversation starts with a fresh budget", async ({ world }) => {
  const old = current(world);
  // The step before looked at the journey; the spent conversation is opened again from the rail.
  await openFree(world, old);
  await conversation(world, old).getByRole("button", { name: "New conversation" }).click();
  const section = world.page.locator("section.edp-conversation");
  await expect(section).not.toHaveAttribute("data-conversation", old);
  const id = (await section.getAttribute("data-conversation"))!;
  await expect(composer(world, id)).toBeEnabled({ timeout: 30_000 });
  const c = await conversationOf(world, id);
  expect(c.used ?? 0, "the service writes no `used` while it is zero").toBe(0);
  await expect(section.locator(".edp-spend")).toHaveAttribute("data-used", "0");
});

Given("a conversation with a budget", async ({ world }) => {
  await openFree(world, await freshFree(world));
});

When("an answer is written", async ({ world }) => {
  await ask(world, "What is the price of a duck?");
});

Then("the conversation shows the tokens used against the total", async ({ world }) => {
  const c = await conversationOf(world, current(world));
  expect(c.used).toBeGreaterThan(0);
  const spend = conversation(world, current(world)).locator(".edp-spend");
  await expect(spend).toHaveAttribute("data-used", String(c.used));
  await expect(spend).toHaveAttribute("data-budget", String(c.budget));
  await expect(spend).toContainText(`${c.used.toLocaleString("en-GB")} of ${c.budget.toLocaleString("en-GB")} tokens used`);
});

// ── acting, with confirmation (US5) ───────────────────────────────────────────

async function proposed(w: World, action: string): Promise<Locator> {
  const p = conversation(w, current(w)).locator(`section.edp-action-proposal[data-action="${action}"][data-status="proposed"]`).last();
  await expect(p).toBeVisible({ timeout: 30_000 });
  return p;
}

async function confirm(w: World, action: string): Promise<Locator> {
  const p = await proposed(w, action);
  const n = (await p.getAttribute("data-proposal"))!;
  await p.getByRole("button", { name: /^Confirm/ }).click();
  const done = conversation(w, current(w)).locator(`section.edp-action-proposal[data-proposal="${n}"]`);
  await expect(done).toHaveAttribute("data-status", "performed", { timeout: 60_000 });
  return done;
}

async function resultOf(w: World, action: string): Promise<{ kind: string; id: string }> {
  const c = await conversationOf(w, current(w));
  const p = [...(c.proposals ?? [])].reverse().find((x) => x.action === action && x.status === "performed");
  expect(p?.result, `the ${action} was performed`).toBeDefined();
  return p!.result!;
}

When("the person types {string} and confirms the action {string}", async ({ world }, text: string, action: string) => {
  expect(action).toBe("submit a request");
  await openFree(world, await freshFree(world));
  world.remembered.set("typed", text);
  const before = (await world.get<unknown[]>("/requests")).length;
  await ask(world, `Submit the request: ${text}`);
  // Proposed, and not yet submitted.
  expect((await world.get<unknown[]>("/requests")).length).toBe(before);
  await confirm(world, "submit-request");
});

Then("the customer request is stored identical to the typed text", async ({ world }) => {
  const r = await resultOf(world, "submit-request");
  expect(r.kind).toBe("request");
  const view = await world.get<{ request: { text: string } }>(`/requests/${q(r.id)}`);
  expect(view.request.text).toBe(world.remembered.get("typed"));
  world.remembered.set("request", r.id);
});

Then("the journey of the customer request is shown", async ({ world }) => {
  const r = rid(world);
  const c = conversation(world, current(world)).locator(`section.edp-card[data-kind="request"][data-id="${r}"]`);
  await expect(c).toBeVisible({ timeout: 30_000 });
  await c.getByRole("button", { name: "Show its stage" }).click();
  await expect(world.page.locator(`.edp-canvas[data-request="${r}"]`)).toBeVisible({ timeout: 30_000 });
});

When("the person has the assistant ingest the source {string} and confirms the action", async ({ world }, file: string) => {
  expect(file).toBe("paper-a.xml");
  await openFree(world, await freshFree(world));
  await ask(world, `Ingest ${file} with the file adapter.`);
  const p = await proposed(world, "ingest-source");
  await expect(p).toContainText(file);
  await confirm(world, "ingest-source");
});

Then("the source is ingested", async ({ world }) => {
  const r = await resultOf(world, "ingest-source");
  expect(r.kind).toBe("source");
  const s = await world.get<{ id: string; versions?: unknown[] }>(`/sources/${q(r.id)}`);
  expect((s.versions ?? []).length).toBeGreaterThan(0);
  world.remembered.set("source", r.id);
});

Then("the card of the source is shown with its version, its status and its records as they are verified", async ({ world }) => {
  const id = world.remembered.get("source")!;
  const c = conversation(world, current(world)).locator(`section.edp-card[data-kind="source"][data-id="${id}"]`);
  await expect(c).toBeVisible({ timeout: 30_000 });
  const s = await world.get<{ status: unknown; versions: { version: number }[] }>(`/sources/${q(id)}`);
  await expect(c).toContainText(`version ${s.versions[s.versions.length - 1].version}`);
  await expect(c).toContainText(tagOf(s.status));
  await expect(c.locator('li[data-record][data-status="verified"]').first()).toBeVisible({ timeout: 120_000 });
  const rows = await world.get<{ id: string }[]>(`/sources/${q(id)}/records`);
  await expect(c.locator("li[data-record]")).toHaveCount(rows.length);
});

When("the person has the assistant inject the fault {string} on a finding and confirms the action", async ({ world }, fault: string) => {
  expect(fault).toBe("endpoint swap");
  await openFree(world, await freshFree(world));
  await ask(world, `Inject the fault ${fault} on a finding.`);
  const p = await proposed(world, "inject-fault");
  await expect(p).toContainText("endpoint_swap");
  const c = await conversationOf(world, current(world));
  const args = [...(c.proposals ?? [])].reverse().find((x) => x.action === "inject-fault")!.arguments;
  expect(args.recordId, "the script proposes the fault on the copy of Paper A (evals/assistant/walkthrough.json)").toBe("src:file:paper-a@endpoint_swap:v1:fd1");
  // The fault goes on the copy of Paper A the fault cassettes were recorded under, as the walkthrough
  // does, so its verification replays and the reference corpus stays as it was; the copy is ingested
  // only once the proposal is known to name it.
  const sources = (await world.get<{ id: string }[]>("/sources")).map((s) => s.id);
  if (!sources.includes("src:file:paper-a@endpoint_swap")) await world.ingest("paper-a.xml@endpoint_swap");
  const before = await world.get<{ record: { attempts: unknown[] } }>(`/findings/${q(args.recordId)}`);
  world.remembered.set("finding", args.recordId);
  world.remembered.set("attempts", String(before.record.attempts.length));
  await confirm(world, "inject-fault");
});

Then("the fault is recorded as a new attempt of the finding", async ({ world }) => {
  const id = world.remembered.get("finding")!;
  const before = Number(world.remembered.get("attempts"));
  await world.eventually("the new attempt", async () => {
    const f = await world.get<{ record: { attempts: unknown[] } }>(`/findings/${q(id)}`);
    return f.record.attempts.length === before + 1 ? f : undefined;
  });
});

Then("the card of the finding shows the verification detecting {string}", async ({ world }, detected: string) => {
  expect(detected).toBe("unsupported endpoint");
  const id = world.remembered.get("finding")!;
  await world.eventually(
    "the verification of the faulted attempt",
    async () => {
      const f = await world.get<{ record: { defect?: { kind: unknown } | null } }>(`/findings/${q(id)}`);
      return tagOf(f.record.defect?.kind) === "endpoint_mismatch" ? f : undefined;
    },
    120_000,
  );
  const c = conversation(world, current(world)).locator(`section.edp-card[data-kind="finding"][data-id="${id}"]`).last();
  await expect(c).toContainText("endpoint_mismatch", { timeout: 30_000 });
  await expect(c).toContainText("contradicted");
  // The copy has served its purpose; superseded, it stays out of every later scenario's search, whose
  // recorded assessments do not include it (as scripts/walkthrough.sh does).
  await world.post(`/sources/${q("src:file:paper-a@endpoint_swap")}/status`, { status: "superseded", reason: "the fault demonstration is done", by: "operator" });
});

When("the person has the assistant propose an action and does not confirm it", async ({ world }) => {
  await openFree(world, await freshFree(world));
  const source = await world.get<{ status: unknown }>("/sources/src:file:paper-c");
  const requests = (await world.get<unknown[]>("/requests")).length;
  await ask(world, "Mark Paper C retracted.");
  await proposed(world, "set-source-status");
  world.nothingRecorded = async () => {
    expect(tagOf((await world.get<{ status: unknown }>("/sources/src:file:paper-c")).status)).toBe(tagOf(source.status));
    expect((await world.get<unknown[]>("/requests")).length).toBe(requests);
    const c = await conversationOf(world, current(world));
    expect((c.proposals ?? []).map((p) => p.status ?? "proposed")).toEqual(["proposed"]);
  };
});

When("the person tells the assistant to approve an experimental design or to edit a record", async ({ world }) => {
  await openFree(world, await freshFree(world));
  await ask(world, "Approve the design, or else edit the record for me.");
});

Then("the assistant declines", async ({ world }) => {
  await expect(answerOf(turn(world))).toContainText("cannot");
  const c = await conversationOf(world, current(world));
  expect(c.proposals ?? []).toEqual([]);
});

Then("the answer names where the decision is made", async ({ world }) => {
  const a = answerOf(turn(world));
  await expect(a).toContainText("on the design's card");
  await expect(a).toContainText("review queue");
});
