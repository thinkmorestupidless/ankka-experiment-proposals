// Moving a request along its journey through the service, as the operator's API does, and finding
// the canvas's nodes and the inspector's cards on the page. A Given sets a state up with these; a
// When acts on the page.
import { expect, type Locator } from "@playwright/test";
import { tagOf, type World } from "./world.ts";

const q = encodeURIComponent;

/** A stage's title on the canvas, and the stage it names. */
export const stageOf: Record<string, string> = {
  request: "request",
  facets: "facets",
  "round-trip check": "round-trip",
  "facet review": "facet-review",
  questions: "questions",
  packages: "packages",
  candidates: "candidates",
  decision: "decision",
  design: "design",
  approval: "approval",
  impact: "impact",
  "search plan": "search-plan",
  search: "executions",
  eligibility: "eligibility",
  verification: "verification",
  assessment: "assessment",
  sufficiency: "sufficiency",
};

export const spine = ["request", "facets", "round-trip", "facet-review", "questions", "packages", "candidates", "decision", "design", "approval", "impact"];

export interface JourneyJson {
  requestId: string;
  spine: { stage: string; state: string; detail?: string; runs?: { runId: string; stage: string; attempt: number; status: string; defect?: string | null }[] }[];
  questions: { questionId: string; text: string; nodes: { stage: string; state: string }[] }[];
}

export const journeyOf = (w: World, rid: string) => w.get<JourneyJson>(`/requests/${q(rid)}/journey`);

/** The corpus is ingested once per database; the browser suites share it. */
export async function ensureCorpus(w: World): Promise<void> {
  const sources = await w.get<{ id: string }[]>("/sources");
  const have = new Set(sources.map((s) => s.id));
  const missing = ["paper-a.xml", "paper-b.xml", "paper-c.xml", "retracted.xml"].filter((f) => !have.has(`src:file:${f.replace(".xml", "")}`));
  if (missing.length === 0) return;
  await w.ingest(...missing);
  // The search reads the store through views; a request submitted before they hold the new sources
  // searches without them (the retracted one goes unexcluded). Wait, as scripts/walkthrough.sh does,
  // for the papers' records to be verified and the retracted source to be listed by its status.
  for (const id of ["src:file:paper-a", "src:file:paper-b", "src:file:paper-c"]) {
    await w.eventually(`the records of ${id} verified`, async () => {
      const rows = await w.get<{ status: unknown }[]>(`/sources/${q(id)}/records`);
      return rows.some((r) => JSON.stringify(r.status).includes("verified")) ? rows : undefined;
    }, 240_000);
  }
  await w.eventually("the retracted source listed", async () => {
    const rows = await w.get<{ id: string }[]>("/sources?status=retracted");
    return rows.some((r) => r.id === "src:file:retracted") ? rows : undefined;
  }, 60_000);
}

export async function submitWaiting(w: World, text = "Can you make foie gras?"): Promise<string> {
  const rid = await w.submit(text);
  await w.stageState(rid, "facet-review", "paused");
  return rid;
}

export async function toDecision(w: World, rid: string): Promise<void> {
  const j = await journeyOf(w, rid);
  if (j.spine.find((n) => n.stage === "facet-review")?.state === "paused") await w.confirmFacets(rid);
  await w.eventually(`the candidates of ${rid}`, async () => {
    const s = (await journeyOf(w, rid)).spine;
    return s.find((n) => n.stage === "decision")?.state !== "unreached" ? s : undefined;
  }, 240_000);
}

export const decideByApi = (w: World, rid: string, reviewer = "demo-reviewer") =>
  w.post(`/requests/${q(rid)}/decide`, {
    selectedApproachId: `${rid}:c1`,
    rejected: [{ approachId: `${rid}:c2`, reason: "not the target species" }],
    decidedBy: reviewer,
    rationale: "the target species comes first",
  });

export async function toApproval(w: World, rid: string): Promise<void> {
  await toDecision(w, rid);
  const view = await w.get<{ decision?: unknown }>(`/requests/${q(rid)}/approaches`);
  if (!view.decision) await decideByApi(w, rid);
  await w.stageState(rid, "approval", "paused", 240_000);
}

export async function approveByApi(w: World, rid: string, reviewer = "demo-reviewer"): Promise<void> {
  await toApproval(w, rid);
  await w.post(`/designs/${q(rid)}:design/approval`, { version: 1, decision: "approved", reviewer, rationale: "ready to run" });
  await w.eventually("the approval is recorded", async () => {
    const d = await w.get<{ approvals?: { decision: unknown }[] }>(`/designs/${q(rid)}:design`);
    return (d.approvals ?? []).some((a) => tagOf(a.decision) === "approved") ? d : undefined;
  });
}

/** The canvas's node of a stage: on the spine, or in a question's lane. */
export function node(w: World, stage: string, question?: string): Locator {
  const p = w.page;
  return question ? p.locator(`.edp-lane[data-lane="${question}"] button[data-stage="${stage}"]`) : p.locator(`.edp-lane[data-lane="spine"] button[data-stage="${stage}"]`);
}

export const inspector = (w: World) => w.page.locator(".ui-inspector");

/** A card in the inspector, by kind. */
export const card = (w: World, kind: string) => inspector(w).locator(`section.edp-card[data-kind="${kind}"]`);

/** Opens the request's canvas, if it is not shown already. */
export async function showJourney(w: World, rid: string): Promise<void> {
  if (!w.page.url().includes(`/requests/${q(rid)}`)) await w.open(`/requests/${q(rid)}`);
  await expect(w.page.locator(`.edp-canvas[data-request="${rid}"]`)).toBeVisible({ timeout: 30_000 });
}

/** Selects a stage on the canvas, choosing the question's lane first when it is in one. */
export async function selectStage(w: World, rid: string, stage: string, question?: string): Promise<void> {
  await showJourney(w, rid);
  if (question) await w.page.getByLabel("Scientific question", { exact: true }).selectOption(question);
  const n = node(w, stage, question);
  await expect(n).toBeVisible();
  await n.click();
  await expect(inspector(w).locator(`.edp-stage[data-stage="${stage}"]`)).toBeVisible();
}

export function rid(w: World): string {
  const r = w.remembered.get("request");
  if (!r) throw new Error("no request remembered");
  return r;
}
