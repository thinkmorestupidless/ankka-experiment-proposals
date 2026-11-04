// The steps of features/interface/journey.feature. A Given moves the request through the service; a
// When shows the canvas or selects a node; a Then reads the canvas and the inspector's card and
// compares them with the record the service stores.
import { expect } from "@playwright/test";
import { After, Given, Then, When, tagOf, type World } from "../support/world.ts";
import {
  approveByApi,
  card,
  inspector,
  journeyOf,
  node,
  rid,
  selectStage,
  showJourney,
  spine,
  stageOf,
  toApproval,
  toDecision,
} from "../support/drive.ts";
import { leaves, show } from "../../../src/cards/leaves.ts";

const q = encodeURIComponent;
const lipid = "Has relevant lipid accumulation been demonstrated in appropriate avian cell models?";

/** The record behind a card, read from the service as the card's kind reads it. */
async function stored(w: World, kind: string, id: string): Promise<unknown> {
  switch (kind) {
    case "request":
      return w.get(`/requests/${q(id)}`);
    case "facets":
      return w.get(`/requests/${q(id)}/facets`);
    case "question":
      return w.get(`/questions/${q(id)}`);
    case "search-plan":
      return w.get(`/search-plans/${q(id)}`);
    case "search-execution":
      return w.get(`/search-executions/${q(id)}`);
    case "source":
      return w.get(`/sources/${q(id)}`);
    case "finding":
      return w.get(`/findings/${q(id)}`);
    case "claim":
      return w.get(`/claims/${q(id)}`);
    case "assessment":
      return w.get(`/assessments/${q(id)}`);
    case "package":
      return w.get(`/packages/${q(id)}`);
    case "candidates":
      return w.get(`/requests/${q(id)}/approaches`);
    case "design":
      return w.get(`/designs/${q(id)}`);
    case "stage-run":
      return w.get(`/runs/${q(id)}`);
    case "sufficiency": {
      const view = await w.get<{ sufficiency: { id: string }[] }>(`/questions/${q(id.slice(0, id.lastIndexOf(":")))}`);
      return view.sufficiency.find((s) => s.id === id);
    }
    default:
      return undefined;
  }
}

/** Every scalar leaf of the stored record appears in the card's text (SC-003). */
export async function expectCardIsRecord(w: World, kind: string, id: string): Promise<number> {
  const record = await stored(w, kind, id);
  expect(record, `the stored ${kind} ${id}`).toBeDefined();
  const text = (await inspector(w).locator(`section.edp-card[data-kind="${kind}"][data-id="${id}"]`).first().textContent()) ?? "";
  const all = leaves(record);
  const missing = all.filter((l) => !text.includes(show(l.value))).map((l) => `${l.path}=${show(l.value)}`);
  expect(missing, `fields of ${kind} ${id} not in its card`).toEqual([]);
  return all.length;
}

// ── where the journey stands ───────────────────────────────────────────────────

Given("the investigation waits at the facet review", async ({ world }) => {
  await world.stageState(rid(world), "facet-review", "paused");
});

/** How often the page has asked for the journey (not its stream) since it was shown. */
let asked = 0;

// "Given the journey is shown" and "When the journey is shown" are one step: it counts the page's
// requests for the journey from here on, so a later step can say it was not asked for again.
When("the journey is shown", async ({ world }) => {
  asked = 0;
  world.page.on("request", (r) => {
    if (new URL(r.url()).pathname.endsWith("/journey")) asked++;
  });
  await showJourney(world, rid(world));
});

Then(
  "the stages {string}, {string}, {string} and {string} are reached",
  async ({ world }, a: string, b: string, c: string, d: string) => {
    for (const title of [a, b, c, d]) {
      const n = node(world, stageOf[title]);
      await expect(n).toBeVisible();
      await expect(n).not.toHaveAttribute("data-node-state", "unreached");
      await expect(n).toHaveAccessibleName(new RegExp(`^${title}, `));
    }
  },
);

Then("the stage {string} is where the investigation waits", async ({ world }, title: string) => {
  const n = node(world, stageOf[title]);
  await expect(n).toHaveAttribute("data-waits", "true");
  await expect(n).toHaveAttribute("data-node-state", "paused");
  await expect(n).toHaveAccessibleName(/the investigation waits here/);
  await expect(world.page.locator('.edp-lane button[data-waits="true"]')).toHaveCount(1);
});

Then("every later stage is unreached", async ({ world }) => {
  for (const stage of spine.slice(spine.indexOf("facet-review") + 1)) {
    await expect(node(world, stage)).toHaveAttribute("data-node-state", "unreached");
    await expect(node(world, stage)).toHaveAccessibleName(/not reached/);
  }
  await expect(world.page.locator(".edp-question-lane")).toHaveCount(0);
});

// ── selecting a stage ─────────────────────────────────────────────────────────

Given("the stage {string} is selected", async ({ world }, title: string) => {
  const r = rid(world);
  const stage = stageOf[title];
  if (["candidates", "decision"].includes(stage)) await toDecision(world, r);
  if (["design", "approval"].includes(stage)) await toApproval(world, r);
  await selectStage(world, r, stage);
});

Given(
  "the stage {string} of the scientific question {string} is selected",
  async ({ world }, title: string, text: string) => {
    const r = rid(world);
    await world.confirmFacets(r);
    const lane = await world.eventually(`the lane of "${text}"`, async () => {
      const l = (await journeyOf(world, r)).questions.find((x) => x.text === text);
      return l && l.nodes.find((n) => n.stage === "eligibility")?.state === "reached" ? l : undefined;
    }, 120_000);
    world.remembered.set("question", lane.questionId);
    await selectStage(world, r, stageOf[title], lane.questionId);
  },
);

Given("the stage {string} of a finding is selected", async ({ world }, title: string) => {
  const r = rid(world);
  await world.confirmFacets(r);
  const lane = await world.eventually("the lipid question's records are verified", async () => {
    const l = (await journeyOf(world, r)).questions.find((x) => x.text === lipid);
    return l && l.nodes.find((n) => n.stage === "verification")?.state === "reached" ? l : undefined;
  }, 120_000);
  await selectStage(world, r, stageOf[title], lane.questionId);
});

When("its card is shown", async ({ world }) => {
  await expect(inspector(world).locator("section.edp-card, .edp-running").first()).toBeVisible({ timeout: 30_000 });
});

// ── the facets card ───────────────────────────────────────────────────────────

interface FacetJson {
  id: string;
  name: string;
  origin: unknown;
  importance: unknown;
  status: unknown;
  requestSpans?: { start: number; end: number; quotation: string }[];
}

Then("the card lists every facet with its origin, its importance and its status", async ({ world }) => {
  const facets = await world.get<FacetJson[]>(`/requests/${q(rid(world))}/facets`);
  const c = card(world, "facets");
  await expect(c.locator("li.edp-facet")).toHaveCount(facets.length);
  for (const f of facets) {
    const li = c.locator(`li.edp-facet[data-facet="${f.id}"]`);
    await expect(li).toHaveAttribute("data-origin", tagOf(f.origin));
    await expect(li).toHaveAttribute("data-importance", tagOf(f.importance));
    await expect(li).toHaveAttribute("data-status", tagOf(f.status));
    for (const word of [f.name, tagOf(f.origin), tagOf(f.importance), tagOf(f.status)]) await expect(li).toContainText(word);
  }
});

Then(
  "each facet with the origin {string} shows its quotation and its source span in the customer request",
  async ({ world }, origin: string) => {
    const r = rid(world);
    const text = (await world.get<{ request: { text: string } }>(`/requests/${q(r)}`)).request.text;
    const facets = (await world.get<FacetJson[]>(`/requests/${q(r)}/facets`)).filter((f) => tagOf(f.origin) === origin);
    expect(facets.length, `facets with the origin ${origin}`).toBeGreaterThan(0);
    for (const f of facets) {
      const li = card(world, "facets").locator(`li.edp-facet[data-facet="${f.id}"]`);
      expect(f.requestSpans?.length ?? 0, `${f.id} has a span`).toBeGreaterThan(0);
      for (const s of f.requestSpans ?? []) {
        expect(text.slice(s.start, s.end)).toBe(s.quotation);
        const quote = li.locator(`[data-span="${s.start}-${s.end}"]`);
        await expect(quote).toContainText(s.quotation);
        await expect(quote).toContainText(`characters ${s.start} to ${s.end}`);
        await expect(quote).not.toContainText("does not match");
      }
    }
  },
);

Then("the card shows the omissions and unsupported additions of the round-trip check", async ({ world }) => {
  const view = await world.get<{ request: { roundTrip: { check: { omissions: { description: string }[]; unsupportedAdditions: { description: string }[] } } } }>(
    `/requests/${q(rid(world))}`,
  );
  const rt = card(world, "facets").locator(".edp-roundtrip");
  for (const [title, items] of [
    ["Omissions", view.request.roundTrip.check.omissions],
    ["Unsupported additions", view.request.roundTrip.check.unsupportedAdditions],
  ] as const) {
    const part = rt.locator(`[data-issues="${title}"]`);
    await expect(part.locator("h5")).toHaveText(title);
    if (items.length === 0) await expect(part).toContainText("none");
    for (const i of items) await expect(part).toContainText(i.description);
  }
  await expect(rt).toContainText(/Result(passed|failed)/);
});

// ── a search card ─────────────────────────────────────────────────────────────

interface PlanJson {
  queries: { kind: unknown; text: string }[];
  executionIds: string[];
}
interface ExecutionJson {
  id: string;
  fused?: boolean;
  results?: { rank: number; sourceId: string }[];
  exclusions?: { sourceId: string; reason: string; ruleId: string }[];
}

const planOf = (w: World) => w.get<PlanJson>(`/search-plans/${q(w.remembered.get("question") ?? "")}:plan`);

Then("the card shows the lexical queries and semantic queries of the search plan", async ({ world }) => {
  const plan = await planOf(world);
  const c = card(world, "search-plan");
  for (const kind of ["lexical", "semantic"]) {
    const mine = plan.queries.filter((x) => tagOf(x.kind) === kind);
    expect(mine.length, `${kind} queries`).toBeGreaterThan(0);
    const list = c.locator(`[data-query-kind="${kind}"] li`);
    await expect(list).toHaveCount(mine.length);
    for (const x of mine) await expect(c.locator(`[data-query-kind="${kind}"]`)).toContainText(x.text);
  }
});

Then("the card shows every result of each search execution in rank order", async ({ world }) => {
  const plan = await planOf(world);
  for (const id of plan.executionIds) {
    const e = await world.get<ExecutionJson>(`/search-executions/${q(id)}`);
    const rows = inspector(world).locator(`section.edp-card[data-id="${id}"] [data-results="results"] tbody tr`);
    const expected = [...(e.results ?? [])].sort((a, b) => a.rank - b.rank);
    await expect(rows).toHaveCount(expected.length);
    const ranks = await rows.evaluateAll((trs) => trs.map((tr) => Number(tr.getAttribute("data-rank"))));
    expect(ranks).toEqual(expected.map((h) => h.rank));
    const sources = await rows.evaluateAll((trs) => trs.map((tr) => tr.getAttribute("data-source")));
    expect(sources).toEqual(expected.map((h) => h.sourceId));
  }
});

Then("the card shows each exclusion with the reason that removed it", async ({ world }) => {
  const plan = await planOf(world);
  let total = 0;
  for (const id of plan.executionIds) {
    const e = await world.get<ExecutionJson>(`/search-executions/${q(id)}`);
    const items = inspector(world).locator(`section.edp-card[data-id="${id}"] li[data-exclusion]`);
    await expect(items).toHaveCount(e.exclusions?.length ?? 0);
    for (const [n, x] of (e.exclusions ?? []).entries()) {
      await expect(items.nth(n)).toContainText(x.reason);
      await expect(items.nth(n)).toContainText(x.ruleId);
      total++;
    }
  }
  expect(total, "the retracted source is excluded somewhere").toBeGreaterThan(0);
});

Then("the card shows whether the re-run returned the same identifiers", async ({ world }) => {
  const plan = await planOf(world);
  const executions = await Promise.all(plan.executionIds.map((id) => world.get<ExecutionJson>(`/search-executions/${q(id)}`)));
  const fused = executions.find((e) => e.fused) ?? executions[0];
  const c = inspector(world).locator(`section.edp-card[data-id="${fused.id}"]`);
  await c.getByRole("button", { name: "Re-run the search" }).click();
  await expect(c.locator(".edp-rerun-result")).toContainText(/returned the same identifiers/, { timeout: 30_000 });
});

// ── a verification card ───────────────────────────────────────────────────────

interface RecordJson {
  record: { id: string; attempts: { verification?: { rationale: string; checks: Record<string, { passed: boolean }> } }[] };
  spans: { span: { quotation: string; sourceId: string }; resolves: boolean; text?: string }[];
}

Then("the finding is shown beside the text each of its source spans resolves to", async ({ world }) => {
  const c = card(world, "finding").first();
  await expect(c).toBeVisible();
  const id = (await c.getAttribute("data-id")) ?? "";
  world.remembered.set("finding", id);
  const view = await world.get<RecordJson>(`/findings/${q(id)}`);
  expect(view.spans.length).toBeGreaterThan(0);
  const passages = c.locator(".edp-beside figure.edp-passage");
  await expect(passages).toHaveCount(view.spans.length);
  for (const [n, s] of view.spans.entries()) {
    expect(s.resolves, "the span resolves").toBe(true);
    await expect(passages.nth(n)).toContainText(s.span.quotation);
    if (s.text) await expect(passages.nth(n)).toContainText(s.text);
  }
});

const verificationOf = async (w: World) => {
  const view = await w.get<RecordJson>(`/findings/${q(w.remembered.get("finding") ?? "")}`);
  const v = [...view.record.attempts].reverse().find((a) => a.verification)?.verification;
  expect(v, "the finding was verified").toBeDefined();
  return v!;
};

Then("every verification check is named as passed or failed", async ({ world }) => {
  const v = await verificationOf(world);
  const c = inspector(world).locator(`section.edp-card[data-id="${world.remembered.get("finding")}"]`);
  const names = Object.keys(v.checks);
  expect(names.length).toBeGreaterThan(0);
  await expect(c.locator(".edp-verification li.edp-check")).toHaveCount(names.length);
  for (const [name, check] of Object.entries(v.checks)) {
    await expect(c.locator(".edp-verification")).toContainText(`${name}: ${check.passed ? "passed" : "failed"}`);
  }
});

Then("the rationale of the verification is shown", async ({ world }) => {
  const v = await verificationOf(world);
  const c = inspector(world).locator(`section.edp-card[data-id="${world.remembered.get("finding")}"]`);
  await expect(c.locator(".edp-verification .edp-rationale")).toHaveText(v.rationale);
});

// ── the candidates card ───────────────────────────────────────────────────────

interface ApproachesJson {
  approaches: { id: string; title: string; missingEvidence?: string[]; contraryClaimRefs?: { id: string }[]; indirectClaimRefs?: { id: string }[]; supportingClaimRefs?: { id: string }[] }[];
  comparison: { criteria: { name: string; perApproach: Record<string, { rating: string; rationale: string }> }[] };
}

Then("the candidate approaches are shown side by side on every comparison criterion", async ({ world }) => {
  const view = await world.get<ApproachesJson>(`/requests/${q(rid(world))}/approaches`);
  const c = card(world, "candidates");
  await expect(c.locator("thead th[data-approach]")).toHaveCount(view.approaches.length);
  expect(view.comparison.criteria.length).toBe(8);
  for (const criterion of view.comparison.criteria) {
    const row = c.locator(`tr[data-criterion="${criterion.name}"]`);
    await expect(row.locator("td")).toHaveCount(view.approaches.length);
    for (const [n, a] of view.approaches.entries()) {
      const cell = criterion.perApproach[a.id];
      await expect(row.locator("td").nth(n)).toContainText(cell.rating);
      await expect(row.locator("td").nth(n)).toContainText(cell.rationale);
    }
  }
});

Then("each candidate approach lists its supporting, contrary, indirect and missing evidence", async ({ world }) => {
  const view = await world.get<ApproachesJson>(`/requests/${q(rid(world))}/approaches`);
  const c = card(world, "candidates");
  const fields = [
    ["supportingClaimRefs", "Supporting evidence"],
    ["contraryClaimRefs", "Contrary evidence"],
    ["indirectClaimRefs", "Indirect evidence"],
  ] as const;
  for (const [field, label] of fields) {
    const row = c.locator(`tr[data-evidence="${field}"]`);
    await expect(row.locator("th")).toHaveText(label);
    for (const [n, a] of view.approaches.entries()) {
      const ids = (a[field] ?? []).map((r) => r.id);
      if (ids.length === 0) await expect(row.locator("td").nth(n)).toHaveText("none");
      for (const id of ids) await expect(row.locator("td").nth(n)).toContainText(id);
    }
  }
  const missing = c.locator('tr[data-evidence="missingEvidence"]');
  for (const [n, a] of view.approaches.entries()) for (const m of a.missingEvidence ?? []) await expect(missing.locator("td").nth(n)).toContainText(m);
});

Then("no numeric success probability is shown", async ({ world }) => {
  const text = (await card(world, "candidates").textContent()) ?? "";
  expect(text).not.toMatch(/probabilit|chance of success|likelihood/i);
  expect(text).not.toMatch(/\d+(\.\d+)?\s*%/);
});

// ── the design card ───────────────────────────────────────────────────────────

interface DesignJson {
  versions: { version: number; status: unknown; sections: { hypothesis: string } }[];
  approvals?: { version: number; decision: unknown; reviewer: string; at: string; rationale: string }[];
}
const designOf = (w: World) => w.get<DesignJson>(`/designs/${q(rid(w))}:design`);

Given("the experimental design is not approved", async ({ world }) => {
  const d = await designOf(world);
  expect((d.approvals ?? []).filter((a) => tagOf(a.decision) === "approved")).toEqual([]);
});

Given("the reviewer has approved the experimental design", async ({ world }) => {
  await approveByApi(world, rid(world));
});

Then("the twelve sections of the experimental design are rendered", async ({ world }) => {
  const d = await designOf(world);
  const c = card(world, "design");
  await expect(c.locator("section.edp-design-section")).toHaveCount(12);
  for (let n = 1; n <= 12; n++) await expect(c.locator("section.edp-design-section").nth(n - 1).locator("h4")).toContainText(`${n}. `);
  await expect(c.locator('[data-section="hypothesis"]')).toContainText(d.versions[d.versions.length - 1].sections.hypothesis);
});

Then("the card is marked as a proposal awaiting review", async ({ world }) => {
  await expect(card(world, "design").locator("[data-proposal]")).toHaveText("Proposal awaiting review");
});

Then("the card is not marked as a proposal", async ({ world }) => {
  await expect(card(world, "design").locator("[data-proposal]")).toHaveCount(0, { timeout: 10_000 });
  await expect(card(world, "design")).not.toContainText("Proposal awaiting review");
});

Then("the approval is shown with the reviewer, the time and the rationale", async ({ world }) => {
  const d = await designOf(world);
  const approval = (d.approvals ?? []).find((a) => tagOf(a.decision) === "approved")!;
  const shown = card(world, "design").locator(".edp-approved");
  await expect(shown).toContainText(approval.reviewer);
  await expect(shown).toContainText(approval.at);
  await expect(shown).toContainText(approval.rationale);
  await expect(shown).toContainText(`version ${approval.version}`);
});

// ── following the investigation ───────────────────────────────────────────────

When("the investigation reaches the next stage", async ({ world }) => {
  await expect(node(world, "facet-review")).toHaveAttribute("data-node-state", "paused");
  await world.confirmFacets(rid(world));
  world.remembered.set("moved-at", String(Date.now()));
});

Then("the journey shows that stage as reached within five seconds", async ({ world }) => {
  await expect(node(world, "facet-review")).toHaveAttribute("data-node-state", "reached", { timeout: 5_000 });
  expect(Date.now() - Number(world.remembered.get("moved-at"))).toBeLessThanOrEqual(5_000);
});

Then("the journey was not asked for again", async ({ world }) => {
  expect(asked).toBe(1);
});

// ── a failed stage ────────────────────────────────────────────────────────────

/** A request no cassette answers: its facets stage fails after its retries, in the replay tier. */
async function failedRequest(w: World): Promise<string> {
  const r = await w.submit(`Can you make saffron from yeast? (${Date.now()})`);
  await w.eventually("the facets stage fails after its retries", async () => {
    const n = (await journeyOf(w, r)).spine.find((x) => x.stage === "facets");
    return n && n.state === "paused" && n.runs?.[0]?.status === "failed" ? n : undefined;
  });
  return r;
}

Given("a stage that failed after its retries", async ({ world }) => {
  await failedRequest(world);
});

Then("the stage is marked as failed with its classified defect", async ({ world }) => {
  const facets = (await journeyOf(world, rid(world))).spine.find((n) => n.stage === "facets")!;
  const defect = facets.runs![0].defect!;
  expect(defect).toMatch(/^[a-z_]+: /);
  const n = node(world, "facets");
  await expect(n).toHaveAttribute("data-state", "failed");
  await expect(n).toHaveAccessibleName(/^facets, failed/);
  await expect(n.locator(".edp-node-defect")).toHaveText(defect);
});

Then("the stage is where the investigation waits", async ({ world }) => {
  const n = node(world, "facets");
  await expect(n).toHaveAttribute("data-waits", "true");
  await expect(n).toHaveAccessibleName(/the investigation waits here/);
  await expect(world.page.locator('.edp-lane button[data-waits="true"]')).toHaveCount(1);
});

// ── the impact card ───────────────────────────────────────────────────────────

const paperB = "src:file:paper-b";

Given("the source {string} has been marked {string}", async ({ world, $testInfo }, name: string, status: string) => {
  expect(name).toBe("Paper B");
  // Flagging walks every dependent of the paper in the database, every earlier scenario's included,
  // and `GET /impact` walks them again; late in a full run that takes minutes, not seconds.
  $testInfo.setTimeout(480_000);
  await world.post(`/sources/${q(paperB)}/status`, { status, reason: "the journey feature", by: "demo-reviewer" });
  world.remembered.set("restore", paperB);
  // Other requests in the database depend on Paper B too, some at stages that are never flagged; the
  // scenario waits for this request's dependents and the paper's own records.
  const r = rid(world);
  let waiting = "nothing read yet";
  await world
    .eventually("every dependent of Paper B in this request is flagged", async () => {
      const i = await world.get<ImpactJson>(`/impact?ref=${q(`source:${paperB}:v1`)}`);
      const mine = i.dependents.filter((d) => d.id.startsWith(`${r}:`) || d.kind === "finding" || d.kind === "claim");
      waiting = `${mine.length} dependents, design ${mine.some((d) => d.kind === "design")}, unflagged ${mine
        .filter((d) => !d.flagged)
        .map((d) => d.id)
        .join(", ")}`;
      return mine.some((d) => d.kind === "design") && mine.every((d) => d.flagged) ? i : undefined;
    }, 300_000)
    .catch((e: Error) => {
      throw new Error(`${e.message}: ${waiting}`);
    });
});

After(async ({ world }) => {
  const s = world.remembered.get("restore");
  if (s) await world.post(`/sources/${q(s)}/status`, { status: "active", reason: "restored after the scenario", by: "operator" });
});

When("the card of the stage {string} is shown", async ({ world }, title: string) => {
  await selectStage(world, rid(world), stageOf[title]);
  // The impact is read from `GET /impact`, which walks every dependent in the database.
  await expect(card(world, stageOf[title]).first()).toBeVisible({ timeout: 90_000 });
});

interface ImpactJson {
  dependents: { kind: string; id: string; version: number; flagged: boolean }[];
}

Then("every dependent flagged for review is listed", async ({ world }) => {
  const r = rid(world);
  const i = await world.get<ImpactJson>(`/impact?ref=${q(`source:${paperB}:v1`)}`);
  const mine = i.dependents.filter((d) => d.flagged && (d.id.startsWith(`${r}:`) || d.kind === "finding" || d.kind === "claim"));
  expect(mine.some((d) => d.kind === "design")).toBe(true);
  const c = card(world, "impact");
  for (const d of mine) await expect(c.locator(`li[data-dependent="${d.id}"]`)).toHaveAttribute("data-flagged", "true");
});

Then("each flag names the changed source version", async ({ world }) => {
  const c = card(world, "impact");
  const items = c.locator("li[data-dependent]");
  expect(await items.count()).toBeGreaterThan(0);
  for (const text of await items.allTextContents()) expect(text).toContain(`source:${paperB}:v1`);
  await expect(c.locator(".edp-flags")).toContainText(`${paperB} version 1`);
});

Then("the card of the stage {string} shows the approved version unchanged", async ({ world }, title: string) => {
  await selectStage(world, rid(world), stageOf[title]);
  const d = await designOf(world);
  const v1 = d.versions.find((v) => v.version === 1)!;
  expect(tagOf(v1.status)).toBe("approved");
  const c = card(world, "design");
  await expect(c.locator(".edp-sections")).toHaveAttribute("data-version", "1");
  await expect(c.locator('[data-section="hypothesis"]')).toContainText(v1.sections.hypothesis);
  await expect(c.locator('li[data-design-version="1"]')).toHaveAttribute("data-status", "approved");
  await expect(c.locator(".edp-approved")).toContainText("version 1");
});

// ── a card is the stored record ───────────────────────────────────────────────

Given("any stage that produced a record", async ({ world }) => {
  await world.stageState(rid(world), "facet-review", "paused");
  world.remembered.set("stage", "facets");
  await selectStage(world, rid(world), "facets");
});

Then("the content of the card is identical to the stored record at its version", async ({ world }) => {
  const cards = inspector(world).locator("section.edp-card");
  await expect(cards.first()).toBeVisible();
  const shown = await cards.evaluateAll((all) => all.map((c) => [c.getAttribute("data-kind") ?? "", c.getAttribute("data-id") ?? ""]));
  expect(shown.map(([k]) => k)).toContain("facets");
  for (const [kind, id] of shown) expect(await expectCardIsRecord(world, kind, id)).toBeGreaterThan(0);
});

// ── a running stage ───────────────────────────────────────────────────────────

Given("a stage that is running", async ({ world }) => {
  // The replay tier answers at once, so no stage stays running long enough to look at. The stage is
  // the failed facets stage of a request no cassette answers, shown as its next attempt would be
  // while it runs: the journey the page reads says so, and every record it fetches is the service's.
  const r = await failedRequest(world);
  const rewrite = (body: string) => {
    const j = JSON.parse(body) as JourneyRunning;
    const n = j.spine.find((x) => x.stage === "facets")!;
    n.state = "current";
    const last = n.runs![0];
    n.runs = [{ ...last, attempt: last.attempt + 1, status: "running", endedAt: null, defect: null }, ...n.runs!];
    return JSON.stringify(j);
  };
  await world.page.route(`**/backend/api/v1/requests/${q(r)}/journey`, async (route) => {
    const res = await route.fetch();
    await route.fulfill({ response: res, body: rewrite(await res.text()) });
  });
  await world.page.route(`**/backend/api/v1/requests/${q(r)}/journey:watch`, async (route) => {
    const current = await world.get<JourneyRunning>(`/requests/${q(r)}/journey`);
    await route.fulfill({ status: 200, contentType: "text/event-stream", body: `event: journey\ndata: ${rewrite(JSON.stringify(current))}\n\n` });
  });
  await showJourney(world, r);
  await selectStage(world, r, "facets");
});

interface JourneyRunning {
  spine: { stage: string; state: string; runs?: { attempt: number; status: string; endedAt?: string | null; defect?: string | null }[] }[];
}

Then("the card says the stage is running", async ({ world }) => {
  await expect(node(world, "facets")).toHaveAttribute("data-state", "current");
  await expect(inspector(world).locator("[data-running] [role=status]")).toHaveText(/This stage is running/);
});

Then("the card shows what earlier attempts recorded", async ({ world }) => {
  const r = rid(world);
  const facets = (await journeyOf(world, r)).spine.find((n) => n.stage === "facets")!;
  const earlier = facets.runs!;
  const list = inspector(world).locator("[data-running] li[data-attempt]");
  await expect(list).toHaveCount(earlier.length);
  for (const run of earlier) await expect(inspector(world).locator(`[data-running] li[data-attempt="${run.attempt}"]`)).toContainText(run.defect ?? run.status);
  const executions = card(world, "stage-run").locator('li[data-run-stage="extract-facets"]');
  await expect(executions).toHaveCount(earlier.length);
  await expect(executions.first()).toHaveAttribute("data-status", "failed");
});
