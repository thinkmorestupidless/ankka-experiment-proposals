// The steps of features/interface/admission.feature. The interface the browser suites use is started
// beside the service by Playwright's webServer with `ankka local web`, as the operator starts it; the
// last scenario starts a second one the same way and times it.
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { expect } from "@playwright/test";
import { Given, Then, When, token, type World } from "../support/world.ts";
import { approvedRequest } from "../support/chat.ts";
import { card, inspector } from "../support/drive.ts";

const service = process.env.EDP_SERVICE_URL ?? "http://127.0.0.1:9000";
const web = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../../..");

async function besideTheService(w: World): Promise<void> {
  const page = await w.request.get("/");
  expect(page.ok(), "the interface answers").toBeTruthy();
  // The service answers under the interface's mount, and refuses a call without the token.
  const health = await w.request.get("/backend/healthz");
  expect(health.ok(), "the service answers under /backend").toBeTruthy();
  const refused = await w.request.get("/backend/api/v1/requests");
  expect(refused.status()).toBe(401);
}

Given("the interface is running beside the service", async ({ world }) => {
  await besideTheService(world);
});

Given("the interface is deployed beside the service", async ({ world }) => {
  await besideTheService(world);
});

async function enter(w: World, value: string): Promise<void> {
  await w.page.goto("/");
  const field = w.page.getByLabel("Shared token");
  await expect(field).toBeVisible();
  await field.fill(value);
  await w.page.getByRole("button", { name: "Enter" }).click();
}

When("the person enters the shared token", async ({ world }) => {
  await enter(world, token);
});

Then("the requests are listed", async ({ world }) => {
  await expect(world.page.getByRole("heading", { name: "Requests", level: 1 })).toBeVisible({ timeout: 30_000 });
  const rows = await world.get<unknown[]>("/requests");
  if (rows.length > 0) await expect(world.page.locator("table.edp-requests tbody tr")).toHaveCount(rows.length);
});

Then("the token is not asked for again in the same browser", async ({ world, context }) => {
  const again = await context.newPage();
  await again.goto("/");
  await expect(again.getByRole("heading", { name: "Requests", level: 1 })).toBeVisible({ timeout: 30_000 });
  await expect(again.getByLabel("Shared token")).toHaveCount(0);
  await again.close();
});

When("the person enters a token that is not the shared token", async ({ world }) => {
  const reads: string[] = [];
  world.page.on("response", (r) => {
    if (r.url().includes("/backend/api/v1/") && r.ok()) reads.push(r.url());
  });
  world.remembered.set("reads", "");
  await enter(world, `${token}-not-it`);
  (world as unknown as { reads: string[] }).reads = reads;
});

Then("nothing of the records is shown", async ({ world }) => {
  await expect(world.page.getByLabel("Shared token")).toBeVisible();
  await expect(world.page.locator("table.edp-requests, .edp-canvas, section.edp-card")).toHaveCount(0);
  expect((world as unknown as { reads: string[] }).reads, "no record was read").toEqual([]);
});

Then("the refusal says the token was refused", async ({ world }) => {
  await expect(world.page.getByRole("alert")).toHaveText("The token was refused.");
});

When("any card is shown", async ({ world }) => {
  const r = await approvedRequest(world);
  const read: string[] = [];
  world.page.on("response", (res) => {
    const u = new URL(res.url());
    if (res.ok() && u.pathname.startsWith("/backend/api/v1/")) read.push(decodeURIComponent(u.pathname + u.search));
  });
  (world as unknown as { read: string[] }).read = read;
  await world.open(`/requests/${encodeURIComponent(r)}?stage=facet-review`);
  await expect(card(world, "facets")).toBeVisible({ timeout: 30_000 });
  await world.page.locator('.edp-lane[data-lane="spine"] button[data-stage="approval"]').click();
  await expect(card(world, "design")).toBeVisible({ timeout: 30_000 });
});

Then("its content is read from the service beside the interface", async ({ world }) => {
  const read = (world as unknown as { read: string[] }).read;
  const shown = await inspector(world)
    .locator("section.edp-card")
    .evaluateAll((all) => all.map((c) => [c.getAttribute("data-kind") ?? "", c.getAttribute("data-id") ?? ""]));
  expect(shown.length).toBeGreaterThan(0);
  const paths: Record<string, (id: string) => string> = {
    facets: (id) => `/requests/${id}/facets`,
    design: (id) => `/designs/${id}`,
    "stage-run": (id) => `/runs/${id}`,
  };
  for (const [kind, id] of shown) {
    const p = paths[kind];
    if (!p) continue;
    expect(read, `the ${kind} card's record was read from /backend`).toContain(`/backend/api/v1${p(id)}`);
  }
  // Nothing the page reads comes from anywhere but this origin.
  const origins = await world.page.evaluate(() =>
    performance
      .getEntriesByType("resource")
      .map((e) => new URL((e as PerformanceResourceTiming).name).origin)
      .filter((o, i, all) => all.indexOf(o) === i),
  );
  expect(origins).toEqual([new URL(world.page.url()).origin]);
});

Then("the interface holds no copy of the record beyond what it shows", async ({ world }) => {
  const held = await world.page.evaluate(async () => {
    const local = Object.keys(localStorage);
    const session = Object.keys(sessionStorage);
    const dbs = indexedDB.databases ? (await indexedDB.databases()).map((d) => d.name) : [];
    const cached = "caches" in window ? await caches.keys() : [];
    const workers = navigator.serviceWorker ? (await navigator.serviceWorker.getRegistrations()).length : 0;
    return { local, session, dbs, cached, workers };
  });
  for (const k of held.local) expect(["edp.token", "edp.conversation"]).toContain(k);
  expect(held.session).toEqual([]);
  expect(held.dbs).toEqual([]);
  expect(held.cached).toEqual([]);
  expect(held.workers).toBe(0);
});

Given("the service is running on the same machine", async ({ world }) => {
  const r = await world.request.get(`${service}/healthz`);
  expect(r.ok(), `the service at ${service}`).toBeTruthy();
});

const freePort = () =>
  new Promise<number>((resolve, reject) => {
    const s = createServer();
    s.listen(0, "127.0.0.1", () => {
      const port = (s.address() as { port: number }).port;
      s.close(() => resolve(port));
    });
    s.on("error", reject);
  });

When("the operator starts the interface as documented", async ({ world }) => {
  const port = await freePort();
  const started = Date.now();
  const child = spawn("ankka", ["local", "web", "--port", String(port), "--service", `ankka-experiment-proposals=${service}`, "--", "npm", "run", "dev"], {
    cwd: web,
    detached: true,
    stdio: "ignore",
  });
  const stop = () => {
    try {
      process.kill(-child.pid!, "SIGTERM");
    } catch {
      // already stopped
    }
  };
  try {
    const url = `http://127.0.0.1:${port}/`;
    for (;;) {
      if (Date.now() - started > 60_000) throw new Error("the interface did not answer within one minute");
      const ok = await world.request.get(url, { timeout: 2_000 }).then((r) => r.ok(), () => false);
      if (ok) break;
      await new Promise((r) => setTimeout(r, 250));
    }
    const page = await world.page.context().newPage();
    await page.goto(url);
    await expect(page.getByLabel("Shared token").or(page.getByRole("heading", { name: "Requests", level: 1 }))).toBeVisible({ timeout: 30_000 });
    world.remembered.set("usable", String(Date.now() - started));
    await page.close();
  } finally {
    stop();
  }
});

Then("the interface is usable within one minute", async ({ world }) => {
  const ms = Number(world.remembered.get("usable"));
  console.log(`the interface was usable ${ms} ms after it was started`);
  expect(ms).toBeLessThan(60_000);
});
