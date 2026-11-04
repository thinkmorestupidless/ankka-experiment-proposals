// What every browser step shares: the shared token, the service reached directly under the mount
// for the Given steps that set a state up, and the page signed in with the token. A Given sets the
// records up through the service; a When acts through the page; a Then reads the page.
import { expect, type APIRequestContext, type Page } from "@playwright/test";
import { createBdd, test as base } from "playwright-bdd";

export const token = process.env.EDP_API_TOKEN ?? "";

/** The service writes an enumeration as `{"type": "<value>"}`; this reads either form. */
export function tagOf(v: unknown): string {
  if (v && typeof v === "object" && "type" in (v as Record<string, unknown>)) return String((v as { type: unknown }).type);
  return v === undefined || v === null ? "" : String(v);
}
const api = "/backend/api/v1";

export interface Problem {
  status: number;
  body: string;
}

export class World {
  readonly page: Page;
  readonly request: APIRequestContext;
  /** What a scenario remembers between its steps: ids it made, the record it acts on. */
  readonly remembered = new Map<string, string>();
  /**
   * What "nothing is recorded" checks in this scenario, when it is not the facet a decision was
   * refused on: set by the Given or When that says what must stay as it was.
   */
  nothingRecorded?: () => Promise<void>;

  constructor(page: Page, request: APIRequestContext) {
    this.page = page;
    this.request = request;
  }

  private headers(): Record<string, string> {
    return { Authorization: `Bearer ${token}` };
  }

  async get<T>(path: string): Promise<T> {
    const r = await this.request.get(api + path, { headers: this.headers() });
    expect(r.ok(), `GET ${path}: ${r.status()} ${await r.text()}`).toBeTruthy();
    return (await r.json()) as T;
  }

  async post<T>(path: string, body: unknown = {}): Promise<T> {
    const r = await this.request.post(api + path, { headers: this.headers(), data: body });
    expect(r.ok(), `POST ${path}: ${r.status()} ${await r.text()}`).toBeTruthy();
    const text = await r.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  /** A call whose refusal the scenario expects: its status and body, never thrown. */
  async attempt(method: "GET" | "POST", path: string, body?: unknown): Promise<Problem> {
    const r =
      method === "GET"
        ? await this.request.get(api + path, { headers: this.headers() })
        : await this.request.post(api + path, { headers: this.headers(), data: body ?? {} });
    return { status: r.status(), body: await r.text() };
  }

  /** The walkthrough's suites start from an empty database: no request may be there already. */
  async freshDatabase(): Promise<void> {
    const rows = await this.get<unknown[]>("/requests");
    expect(rows, "the browser suites need a fresh database (just app-down -v, then up)").toHaveLength(0);
  }

  /** Until `check` answers a value, within the bound. */
  async eventually<T>(what: string, check: () => Promise<T | undefined | null | false>, within = 60_000): Promise<T> {
    const until = Date.now() + within;
    for (;;) {
      const v = await check();
      if (v) return v;
      if (Date.now() > until) throw new Error(`timed out waiting for ${what}`);
      await new Promise((r) => setTimeout(r, 500));
    }
  }

  async submit(text: string, submittedBy = "customer"): Promise<string> {
    const created = await this.post<{ id: string }>("/requests", { text, submittedBy });
    this.remembered.set("request", created.id);
    return created.id;
  }

  /** Ingests fixtures by file name and waits until none of their records is still proposed. */
  async ingest(...files: string[]): Promise<string[]> {
    const ids: string[] = [];
    for (const file of files) {
      // The retracted fixture is ingested without extraction, as the walkthrough does.
      const started = await this.post<{ workflowId: string; sourceId?: string }>("/sources:ingest", {
        adapter: "file",
        identifier: file,
        extract: file !== "retracted.xml",
      });
      const ended = await this.eventually(`ingestion of ${file}`, async () => {
        const w = await this.get<{ isTerminal: boolean; sourceId?: string }>(`/workflows/ingestion/${encodeURIComponent(started.workflowId)}`);
        return w.isTerminal ? w : undefined;
      });
      const id = ended.sourceId ?? started.sourceId ?? "";
      await this.eventually(`the records of ${file} settle`, async () => {
        const rows = await this.get<{ status: string }[]>(`/sources/${encodeURIComponent(id)}/records`);
        return rows.every((r) => r.status !== "proposed") ? rows : undefined;
      });
      ids.push(id);
    }
    return ids;
  }

  /** The reference corpus: papers A, B and C and the retracted source. */
  ingestReferenceCorpus(): Promise<string[]> {
    return this.ingest("paper-a.xml", "paper-b.xml", "paper-c.xml", "retracted.xml");
  }

  /** Confirms each mandatory facet, then the facets, as `reviewer`. */
  async confirmFacets(requestId: string, reviewer = "demo-reviewer"): Promise<void> {
    await this.stageState(requestId, "facet-review", "paused");
    const facets = await this.get<{ id: string; importance?: unknown; status?: unknown }[]>(`/requests/${encodeURIComponent(requestId)}/facets`);
    for (const f of facets.filter((f) => tagOf(f.importance) === "mandatory" && tagOf(f.status) !== "confirmed")) {
      await this.post(`/facets/${encodeURIComponent(f.id)}/review`, { action: "confirm", reviewer, rationale: "confirmed for the walkthrough" });
    }
    await this.post(`/requests/${encodeURIComponent(requestId)}/facets:confirmed`, { reviewer });
  }

  /** Until the journey's `stage` is in `state`. */
  stageState(requestId: string, stage: string, state: string, within = 120_000) {
    return this.eventually(
      `${stage} ${state} on ${requestId}`,
      async () => {
        const j = await this.get<{ spine: { stage: string; state: string }[] }>(`/requests/${encodeURIComponent(requestId)}/journey`);
        return j.spine.find((n) => n.stage === stage)?.state === state ? j : undefined;
      },
      within,
    );
  }

  /** Opens the interface with the token already admitted. */
  async open(path = "/"): Promise<void> {
    await this.page.addInitScript((t) => {
      try {
        localStorage.setItem("edp.token", t);
      } catch {
        // a page that cannot store asks for the token instead
      }
    }, token);
    await this.page.goto(path);
  }
}

export const test = base.extend<{ world: World }>({
  world: async ({ page, request }, use) => {
    await use(new World(page, request));
  },
});

export const { Given, When, Then, Before, After } = createBdd(test);
