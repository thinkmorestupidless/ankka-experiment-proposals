// What the plain browser specs share: the token, calls to the service under the mount, waiting,
// the corpus and the reference request taken to a stage through the operator's routes.
import { expect, type APIRequestContext, type Locator, type Page } from "@playwright/test";

export const token = process.env.EDP_API_TOKEN ?? "";
export const api = "/backend/api/v1";
export const q = encodeURIComponent;
export const lipid = "Has relevant lipid accumulation been demonstrated in appropriate avian cell models?";
const headers = () => ({ Authorization: `Bearer ${token}` });

export const tagOf = (v: unknown) => (v && typeof v === "object" && "type" in (v as object) ? String((v as { type: unknown }).type) : String(v ?? ""));

export async function get<T>(r: APIRequestContext, path: string): Promise<T> {
  const res = await r.get(api + path, { headers: headers() });
  expect(res.ok(), `GET ${path}: ${res.status()}`).toBeTruthy();
  return (await res.json()) as T;
}

export async function post<T>(r: APIRequestContext, path: string, body: unknown): Promise<T> {
  const res = await r.post(api + path, { headers: headers(), data: body });
  expect(res.ok(), `POST ${path}: ${res.status()} ${await res.text()}`).toBeTruthy();
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export async function eventually<T>(what: string, check: () => Promise<T | undefined | false>, within = 240_000): Promise<T> {
  const until = Date.now() + within;
  for (;;) {
    const v = await check();
    if (v) return v;
    if (Date.now() > until) throw new Error(`timed out waiting for ${what}`);
    await new Promise((res) => setTimeout(res, 500));
  }
}

export interface Journey {
  spine: { stage: string; state: string }[];
  questions: { questionId: string; text: string; nodes: { stage: string; state: string }[] }[];
}
export const journey = (r: APIRequestContext, rid: string) => get<Journey>(r, `/requests/${q(rid)}/journey`);
export const at = (j: Journey, stage: string) => j.spine.find((n) => n.stage === stage)?.state;

/** Ingests the fixtures not yet in the store, by file name, and waits for their records to settle. */
export async function ingest(r: APIRequestContext, ...files: string[]): Promise<void> {
  const have = new Set((await get<{ id: string }[]>(r, "/sources")).map((s) => s.id));
  for (const file of files) {
    const id = `src:file:${file.replace(".xml", "")}`;
    if (have.has(id)) continue;
    const w = await post<{ workflowId: string }>(r, "/sources:ingest", { adapter: "file", identifier: file, extract: !file.startsWith("retracted") });
    await eventually(`${file} ingested`, async () => (await get<{ isTerminal: boolean }>(r, `/workflows/ingestion/${q(w.workflowId)}`)).isTerminal);
    await eventually(`the records of ${file} settle`, async () => {
      const rows = await get<{ status: string }[]>(r, `/sources/${q(id)}/records`);
      return rows.every((x) => x.status !== "proposed") ? rows : undefined;
    });
    // A retracted source has no records to wait for; the search sees it once its status is listed.
    if (file.startsWith("retracted")) {
      await eventually(`${file} listed as retracted`, async () =>
        (await get<{ id: string }[]>(r, "/sources?status=retracted")).some((s) => s.id === id),
      );
    }
  }
}

export const corpus = (r: APIRequestContext) => ingest(r, "paper-a.xml", "paper-b.xml", "paper-c.xml", "retracted.xml");

/** The reference request taken to an approved design through the operator's routes. */
export async function approved(r: APIRequestContext): Promise<string> {
  await corpus(r);
  for (const row of await get<{ id: string }[]>(r, "/requests")) {
    const d = await r.get(`${api}/designs/${q(row.id)}:design`, { headers: headers() });
    if (!d.ok()) continue;
    const design = (await d.json()) as { approvals?: { decision: unknown }[] };
    if ((design.approvals ?? []).some((a) => tagOf(a.decision) === "approved")) return row.id;
  }
  const { id: rid } = await post<{ id: string }>(r, "/requests", { text: "Can you make foie gras?", submittedBy: "customer" });
  await eventually("the facet review", async () => at(await journey(r, rid), "facet-review") === "paused");
  const facets = await get<{ id: string; importance: unknown; status: unknown }[]>(r, `/requests/${q(rid)}/facets`);
  for (const f of facets.filter((f) => tagOf(f.importance) === "mandatory" && tagOf(f.status) !== "confirmed")) {
    await post(r, `/facets/${q(f.id)}/review`, { action: "confirm", reviewer: "demo-reviewer", rationale: "for the browser specs" });
  }
  await post(r, `/requests/${q(rid)}/facets:confirmed`, { reviewer: "demo-reviewer" });
  await eventually("the decision", async () => at(await journey(r, rid), "decision") === "paused");
  await post(r, `/requests/${q(rid)}/decide`, {
    selectedApproachId: `${rid}:c1`,
    rejected: [{ approachId: `${rid}:c2`, reason: "not the target species" }],
    decidedBy: "demo-reviewer",
    rationale: "the target species comes first",
  });
  await eventually("the approval", async () => at(await journey(r, rid), "approval") === "paused");
  await post(r, `/designs/${q(rid)}:design/approval`, { version: 1, decision: "approved", reviewer: "demo-reviewer", rationale: "ready" });
  await eventually("approved", async () => at(await journey(r, rid), "approval") === "reached");
  return rid;
}

/** The page with the token already admitted. */
export async function admitted(page: Page): Promise<void> {
  await page.addInitScript((t) => {
    try {
      localStorage.setItem("edp.token", t);
    } catch {
      // the page asks for the token instead
    }
  }, token);
}

/** Presses Tab until `target` has the focus: the page reached by keyboard alone. */
export async function tabTo(page: Page, target: Locator, max = 800): Promise<void> {
  await expect(target).toBeVisible({ timeout: 60_000 });
  const handle = await target.elementHandle();
  for (let i = 0; i < max; i++) {
    if (await page.evaluate((el) => el === document.activeElement, handle)) return;
    await page.keyboard.press("Tab");
  }
  throw new Error(`${target} was not reached in ${max} presses of Tab`);
}

/** Tab to a control, then press a key on it. */
export async function press(page: Page, target: Locator, key = "Enter"): Promise<void> {
  await tabTo(page, target);
  await page.keyboard.press(key);
}
