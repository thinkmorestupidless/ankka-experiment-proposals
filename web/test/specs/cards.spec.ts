// SC-003: a card is the stored record. The reference request is taken to an approved design through
// the API; then every node of the spine and of the lipid question's lane is selected, and for each
// card the inspector shows, the same record is fetched from /backend/api/v1 and every scalar leaf of
// its JSON must appear in the card's text.
import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { leaves, show } from "../../src/cards/leaves.ts";

const token = process.env.EDP_API_TOKEN ?? "";
const api = "/backend/api/v1";
const q = encodeURIComponent;
const lipid = "Has relevant lipid accumulation been demonstrated in appropriate avian cell models?";

const headers = () => ({ Authorization: `Bearer ${token}` });
const tagOf = (v: unknown) => (v && typeof v === "object" && "type" in (v as object) ? String((v as { type: unknown }).type) : String(v ?? ""));

async function get<T>(r: APIRequestContext, path: string): Promise<T> {
  const res = await r.get(api + path, { headers: headers() });
  expect(res.ok(), `GET ${path}: ${res.status()}`).toBeTruthy();
  return (await res.json()) as T;
}
async function post<T>(r: APIRequestContext, path: string, body: unknown): Promise<T> {
  const res = await r.post(api + path, { headers: headers(), data: body });
  expect(res.ok(), `POST ${path}: ${res.status()} ${await res.text()}`).toBeTruthy();
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
async function eventually<T>(what: string, check: () => Promise<T | undefined>, within = 240_000): Promise<T> {
  const until = Date.now() + within;
  for (;;) {
    const v = await check();
    if (v) return v;
    if (Date.now() > until) throw new Error(`timed out waiting for ${what}`);
    await new Promise((res) => setTimeout(res, 500));
  }
}

interface Journey {
  spine: { stage: string; state: string }[];
  questions: { questionId: string; text: string; nodes: { stage: string; state: string }[] }[];
}
const journey = (r: APIRequestContext, rid: string) => get<Journey>(r, `/requests/${q(rid)}/journey`);
const at = (j: Journey, stage: string) => j.spine.find((n) => n.stage === stage)?.state;

/** The reference request at an approved design, with the corpus ingested if it is not there. */
async function seed(r: APIRequestContext): Promise<string> {
  const sources = new Set((await get<{ id: string }[]>(r, "/sources")).map((s) => s.id));
  for (const f of ["paper-a", "paper-b", "paper-c", "retracted"]) {
    if (sources.has(`src:file:${f}`)) continue;
    const w = await post<{ workflowId: string }>(r, "/sources:ingest", { adapter: "file", identifier: `${f}.xml`, extract: f !== "retracted" });
    await eventually(`${f} ingested`, async () => {
      const v = await get<{ isTerminal: boolean }>(r, `/workflows/ingestion/${q(w.workflowId)}`);
      return v.isTerminal ? v : undefined;
    });
    await eventually(`the records of ${f} settle`, async () => {
      const rows = await get<{ status: string }[]>(r, `/sources/${q(`src:file:${f}`)}/records`);
      return rows.every((x) => x.status !== "proposed") ? rows : undefined;
    });
  }
  const { id: rid } = await post<{ id: string }>(r, "/requests", { text: "Can you make foie gras?", submittedBy: "customer" });
  await eventually("the facet review", async () => (at(await journey(r, rid), "facet-review") === "paused" ? true : undefined));
  const facets = await get<{ id: string; importance: unknown; status: unknown }[]>(r, `/requests/${q(rid)}/facets`);
  for (const f of facets.filter((f) => tagOf(f.importance) === "mandatory" && tagOf(f.status) !== "confirmed")) {
    await post(r, `/facets/${q(f.id)}/review`, { action: "confirm", reviewer: "demo-reviewer", rationale: "for the cards" });
  }
  await post(r, `/requests/${q(rid)}/facets:confirmed`, { reviewer: "demo-reviewer" });
  await eventually("the decision", async () => (at(await journey(r, rid), "decision") === "paused" ? true : undefined));
  await post(r, `/requests/${q(rid)}/decide`, {
    selectedApproachId: `${rid}:c1`,
    rejected: [{ approachId: `${rid}:c2`, reason: "not the target species" }],
    decidedBy: "demo-reviewer",
    rationale: "the target species comes first",
  });
  await eventually("the approval", async () => (at(await journey(r, rid), "approval") === "paused" ? true : undefined));
  await post(r, `/designs/${q(rid)}:design/approval`, { version: 1, decision: "approved", reviewer: "demo-reviewer", rationale: "ready" });
  await eventually("approved", async () => (at(await journey(r, rid), "approval") === "reached" ? true : undefined));
  return rid;
}

/** The stored record a card of `kind` shows. */
async function stored(r: APIRequestContext, kind: string, id: string): Promise<unknown> {
  const paths: Record<string, string> = {
    request: `/requests/${q(id)}`,
    facets: `/requests/${q(id)}/facets`,
    question: `/questions/${q(id)}`,
    "search-plan": `/search-plans/${q(id)}`,
    "search-execution": `/search-executions/${q(id)}`,
    source: `/sources/${q(id)}`,
    finding: `/findings/${q(id)}`,
    claim: `/claims/${q(id)}`,
    assessment: `/assessments/${q(id)}`,
    package: `/packages/${q(id)}`,
    candidates: `/requests/${q(id)}/approaches`,
    design: `/designs/${q(id)}`,
    "stage-run": `/runs/${q(id)}`,
  };
  if (kind === "sufficiency") {
    const view = await get<{ sufficiency: { id: string }[] }>(r, `/questions/${q(id.slice(0, id.lastIndexOf(":")))}`);
    return view.sufficiency.find((s) => s.id === id);
  }
  if (kind === "decision") return (await get<{ decision: unknown }>(r, `/requests/${q(id.replace(/:d\d+$/, ""))}/approaches`)).decision;
  return paths[kind] ? get(r, paths[kind]) : undefined;
}

async function token0(page: Page): Promise<void> {
  await page.addInitScript((t) => {
    try {
      localStorage.setItem("edp.token", t);
    } catch {
      // the page asks for the token instead
    }
  }, token);
}

test.describe("SC-003: a card is the stored record", () => {
  test.setTimeout(600_000);

  test("every card on the canvas shows every field of its stored record", async ({ page, request }) => {
    const rid = await seed(request);
    await token0(page);
    await page.goto(`/requests/${q(rid)}`);
    await expect(page.locator(`.edp-canvas[data-request="${rid}"]`)).toBeVisible({ timeout: 30_000 });
    const j = await journey(request, rid);
    const lane = j.questions.find((x) => x.text === lipid)!;
    // An unreached node (the impact, with no source changed) has produced no record to compare.
    const nodes = [
      ...j.spine.map((n) => ({ stage: n.stage, state: n.state, question: undefined as string | undefined })),
      ...lane.nodes.map((n) => ({ stage: n.stage, state: n.state, question: lane.questionId })),
    ].filter((n) => n.state !== "unreached");
    const kinds = new Set<string>();
    for (const n of nodes) {
      if (n.question) await page.getByLabel("Scientific question", { exact: true }).selectOption(n.question);
      const lanes = n.question ? `.edp-lane[data-lane="${n.question}"]` : '.edp-lane[data-lane="spine"]';
      await page.locator(`${lanes} button[data-stage="${n.stage}"]`).click();
      const stage = page.locator(`.ac-inspector .edp-stage[data-stage="${n.stage}"]`);
      await expect(stage).toBeVisible({ timeout: 30_000 });
      await expect(stage.locator("section.edp-card").first()).toBeVisible({ timeout: 30_000 });
      const cards = await stage.locator("section.edp-card").evaluateAll((all) => all.map((c) => [c.getAttribute("data-kind") ?? "", c.getAttribute("data-id") ?? ""]));
      for (const [kind, id] of cards) {
        if (kind === "impact") continue; // computed from several records, not one stored record
        const record = await stored(request, kind, id);
        expect(record, `the stored ${kind} ${id}`).toBeDefined();
        const text = (await stage.locator(`section.edp-card[data-kind="${kind}"][data-id="${id}"]`).first().textContent()) ?? "";
        const missing = leaves(record)
          .filter((l) => !text.includes(show(l.value)))
          .map((l) => `${l.path}=${show(l.value)}`);
        expect(missing, `${kind} ${id} on the stage ${n.stage}`).toEqual([]);
        kinds.add(kind);
      }
    }
    for (const kind of ["request", "facets", "question", "search-plan", "search-execution", "finding", "claim", "assessment", "sufficiency", "package", "decision", "design", "stage-run"]) {
      expect([...kinds], `a ${kind} card was checked`).toContain(kind);
    }
  });

  test.fixme("every card the conversation shows inline is its stored record (with US4)", async () => {
    // The conversation's inline cards come with the assistant (Phase 6).
  });
});
