// The steps of features/interface/decisions.feature. Each decision is made on its card in the page,
// as a reviewer chosen from the configured ones, and read back from the service, which records it
// through the same route the operator's API calls.
import { expect, type Locator } from "@playwright/test";
import { Given, Then, When, tagOf, type World } from "../support/world.ts";
import { card, inspector, rid, selectStage, submitWaiting, toApproval, toDecision } from "../support/drive.ts";

const q = encodeURIComponent;
/** The change the design cassettes were recorded with (DesignSuite), so replay writes version 2. */
const changes = "State the inconclusive criterion with a numeric threshold";

interface FacetJson {
  id: string;
  name: string;
  importance: unknown;
  status: unknown;
  version: number;
  reviews?: { reviewer: string; action: unknown; at: string; rationale: string; before?: string | null; after?: string | null }[];
}
interface DesignJson {
  versions: { version: number; status: unknown }[];
  approvals?: { version: number; decision: unknown; reviewer: string; at: string; rationale: string; requestedChanges?: string[] }[];
}

const facetsOf = (w: World, r = rid(w)) => w.get<FacetJson[]>(`/requests/${q(r)}/facets`);
const designOf = (w: World) => w.get<DesignJson>(`/designs/${q(rid(w))}:design`);

async function chooseReviewer(scope: Locator, name: string): Promise<void> {
  await scope.getByLabel("Reviewer", { exact: true }).first().selectOption(name);
}

/** Reviews one facet on the facets card: opens its form, picks the action, gives the rationale. */
async function reviewFacetOnCard(w: World, facet: FacetJson, action: string, rationale: string): Promise<Locator> {
  const li = card(w, "facets").locator(`li.edp-facet[data-facet="${facet.id}"]`);
  const details = li.locator("details.edp-facet-review");
  if (!(await details.evaluate((d) => (d as HTMLDetailsElement).open))) await details.locator("summary").click();
  const form = details.locator("form");
  await form.getByLabel("Action").selectOption(action);
  await form.getByLabel("Rationale").fill(rationale);
  await form.getByRole("button", { name: "Record the review" }).click();
  return form;
}

// ── the facets ────────────────────────────────────────────────────────────────

Given("the facets card shows eight facets", async ({ world }) => {
  await selectStage(world, rid(world), "facet-review");
  await expect(card(world, "facets").locator("li.edp-facet")).toHaveCount(8);
});

When("the reviewer confirms each mandatory facet and then the facets on the card", async ({ world }) => {
  const c = card(world, "facets");
  await chooseReviewer(c, "demo-reviewer");
  const mandatory = (await facetsOf(world)).filter((f) => tagOf(f.importance) === "mandatory");
  world.remembered.set("mandatory", mandatory.map((f) => f.id).join(" "));
  for (const f of mandatory.filter((f) => tagOf(f.status) !== "confirmed")) {
    await reviewFacetOnCard(world, f, "confirm", `confirmed on the card: ${f.name}`);
    await expect(c.locator(`li.edp-facet[data-facet="${f.id}"]`)).toHaveAttribute("data-status", "confirmed");
  }
  await c.getByRole("button", { name: "Confirm the facets" }).click();
});

Then("the history of each facet holds the reviewer, the time and the rationale", async ({ world }) => {
  const ids = (world.remembered.get("mandatory") ?? "").split(" ");
  const facets = (await facetsOf(world)).filter((f) => ids.includes(f.id));
  expect(facets.length).toBeGreaterThan(0);
  for (const f of facets) {
    const last = f.reviews?.[f.reviews.length - 1];
    expect(last?.reviewer).toBe("demo-reviewer");
    expect(last?.rationale).toBe(`confirmed on the card: ${f.name}`);
    expect(Number.isNaN(Date.parse(last?.at ?? ""))).toBe(false);
    const history = card(world, "facets").locator(`li.edp-facet[data-facet="${f.id}"] .edp-history`);
    await expect(history).toContainText(`confirm by demo-reviewer at ${last!.at}: ${last!.rationale}`);
  }
});

Then("the investigation proceeds to deriving the scientific questions", async ({ world }) => {
  const r = rid(world);
  await world.stageState(r, "facet-review", "reached", 30_000);
  await expect(world.page.locator('.edp-lane[data-lane="spine"] button[data-stage="facet-review"]')).toHaveAttribute("data-node-state", "reached");
  await expect(world.page.locator('.edp-lane[data-lane="spine"] button[data-stage="questions"]')).not.toHaveAttribute("data-node-state", "unreached", {
    timeout: 30_000,
  });
});

Given("a mandatory facet that is neither confirmed, assumed nor unresolved and flagged", async ({ world }) => {
  const proposal = await world.get<{ stillProposed: string[] }>(`/requests/${q(rid(world))}/proposal`);
  const mandatory = (await facetsOf(world)).filter((f) => tagOf(f.importance) === "mandatory").map((f) => f.name);
  const unsettled = proposal.stillProposed.filter((n) => mandatory.includes(n));
  expect(unsettled.length).toBeGreaterThan(0);
  world.remembered.set("unsettled", unsettled.join("|"));
  await selectStage(world, rid(world), "facet-review");
});

When("the reviewer confirms the facets on the card", async ({ world }) => {
  const c = card(world, "facets");
  await chooseReviewer(c, "demo-reviewer");
  await c.getByRole("button", { name: "Confirm the facets" }).click();
});

Then("the confirmation is refused", async ({ world }) => {
  const refusal = card(world, "facets").locator("form.edp-facets-confirmed [role=alert]");
  await expect(refusal).toBeVisible();
  const j = await world.get<{ spine: { stage: string; state: string }[] }>(`/requests/${q(rid(world))}/journey`);
  expect(j.spine.find((n) => n.stage === "facet-review")?.state).toBe("paused");
});

Then("the refusal names the facet", async ({ world }) => {
  const refusal = card(world, "facets").locator("form.edp-facets-confirmed [role=alert]");
  for (const name of (world.remembered.get("unsettled") ?? "").split("|")) await expect(refusal).toContainText(name);
});

// ── the candidates ────────────────────────────────────────────────────────────

Given("the candidates card", async ({ world }) => {
  await toDecision(world, rid(world));
  await selectStage(world, rid(world), "decision");
  await expect(card(world, "candidates")).toBeVisible();
});

When("the reviewer selects one candidate approach on the card with a reason for the other", async ({ world }) => {
  const r = rid(world);
  const form = card(world, "candidates").locator("form.edp-decision");
  const view = await world.get<{ approaches: { id: string; title: string }[] }>(`/requests/${q(r)}/approaches`);
  const [chosen, other] = view.approaches;
  world.remembered.set("rejected", other.id);
  await form.getByLabel(chosen.title, { exact: true }).check();
  await form.getByLabel(`Reason for rejecting ${other.title}`).fill("a surrogate species does not answer the question");
  await chooseReviewer(form, "demo-reviewer");
  await form.getByLabel("Rationale").fill("the target species comes first");
  await form.getByRole("button", { name: "Record the decision" }).click();
  await expect(form.locator("[role=alert]")).toHaveCount(0);
});

Then("the decision record keeps the rejected candidate approach with that reason", async ({ world }) => {
  const decision = await world.eventually("the decision", async () => {
    const v = await world.get<{ decision?: { rejected: { approachRef: { id: string }; reason: string }[]; decidedBy: string } }>(
      `/requests/${q(rid(world))}/approaches`,
    );
    return v.decision;
  });
  expect(decision.decidedBy).toBe("demo-reviewer");
  expect(decision.rejected).toEqual([
    expect.objectContaining({ approachRef: expect.objectContaining({ id: world.remembered.get("rejected") }), reason: "a surrogate species does not answer the question" }),
  ]);
  const shown = inspector(world).locator(`section.edp-card[data-kind="decision"] li[data-rejected="${world.remembered.get("rejected")}"]`);
  await expect(shown).toContainText("a surrogate species does not answer the question", { timeout: 15_000 });
});

// ── the design ────────────────────────────────────────────────────────────────

Given("the design card", async ({ world }) => {
  await toApproval(world, rid(world));
  await selectStage(world, rid(world), "approval");
  await expect(card(world, "design").locator("form.edp-decision")).toBeVisible();
});

async function decideOnDesign(w: World, decision: "Approve" | "Request changes", rationale: string, version = "1"): Promise<void> {
  const form = card(w, "design").locator("form.edp-decision");
  await form.getByLabel("Version").fill(version);
  await form.getByLabel(decision, { exact: true }).check();
  if (decision === "Request changes") await form.getByLabel("The changes requested, one a line").fill(changes);
  await chooseReviewer(form, "demo-reviewer");
  await form.getByLabel("Rationale").fill(rationale);
  await form.getByRole("button", { name: "Record the decision on the design" }).click();
  await expect(form.locator("[role=alert]")).toHaveCount(0);
}

When("the reviewer approves version {string} on the card with a rationale", async ({ world }, version: string) => {
  await decideOnDesign(world, "Approve", "ready to run in the lab", version);
});

Then("the approval is recorded against version {string}", async ({ world }, version: string) => {
  const d = await world.eventually("the approval", async () => {
    const d = await designOf(world);
    return (d.approvals ?? []).length > 0 ? d : undefined;
  });
  expect(d.approvals).toEqual([
    expect.objectContaining({ version: Number(version), decision: { type: "approved" }, reviewer: "demo-reviewer", rationale: "ready to run in the lab" }),
  ]);
});

Then("the experimental design is not marked as a proposal", async ({ world }) => {
  await expect(card(world, "design").locator("[data-proposal]")).toHaveCount(0, { timeout: 15_000 });
  await expect(card(world, "design").locator(".edp-approved")).toContainText("demo-reviewer");
});

When("the reviewer requests changes on the card with a rationale", async ({ world }) => {
  await decideOnDesign(world, "Request changes", "the inconclusive criterion has no threshold");
});

Then("a new version of the experimental design is written", async ({ world }) => {
  const d = await world.eventually(
    "version 2 of the design",
    async () => {
      const d = await designOf(world);
      return d.versions.some((v) => v.version === 2) ? d : undefined;
    },
    120_000,
  );
  expect(tagOf(d.versions.find((v) => v.version === 1)!.status)).toBe("superseded");
  await expect(card(world, "design").locator('li[data-design-version="2"]')).toBeVisible({ timeout: 15_000 });
});

Then("the earlier version and its decision remain visible in the history", async ({ world }) => {
  const v1 = card(world, "design").locator('li[data-design-version="1"]');
  await expect(v1).toHaveAttribute("data-status", "superseded");
  const decision = v1.locator('li[data-approval="changes_requested"]');
  await expect(decision).toContainText("demo-reviewer");
  await expect(decision).toContainText("the inconclusive criterion has no threshold");
  await expect(decision).toContainText(changes);
});

// ── a name that is not a reviewer ─────────────────────────────────────────────

Given("the name {string} is not a configured reviewer", async ({ world }, name: string) => {
  const reviewers = await world.get<{ name: string }[]>("/reviewers");
  expect(reviewers.map((r) => r.name)).not.toContain(name);
  world.remembered.set("impostor", name);
});

When("a decision is made on a card in that name", async ({ world }) => {
  const name = world.remembered.get("impostor")!;
  await selectStage(world, rid(world), "facet-review");
  const c = card(world, "facets");
  // The select offers only configured reviewers; the name is put in it as a tampered page would.
  const select = c.getByLabel("Reviewer", { exact: true }).first();
  await select.evaluate((el, n) => {
    const o = document.createElement("option");
    o.value = n;
    o.textContent = n;
    el.appendChild(o);
  }, name);
  await select.selectOption(name);
  const facet = (await facetsOf(world))[0];
  world.remembered.set("facet", JSON.stringify(facet));
  await reviewFacetOnCard(world, facet, "confirm", "in a name that is not configured");
});

Then("the decision is refused", async ({ world }) => {
  const facet = JSON.parse(world.remembered.get("facet")!) as FacetJson;
  const refusal = card(world, "facets").locator(`li.edp-facet[data-facet="${facet.id}"] form [role=alert]`);
  await expect(refusal).toContainText(`'${world.remembered.get("impostor")}' is not a configured reviewer`);
});

Then("nothing is recorded", async ({ world }) => {
  const before = JSON.parse(world.remembered.get("facet")!) as FacetJson;
  const after = (await facetsOf(world)).find((f) => f.id === before.id)!;
  expect(after.version).toBe(before.version);
  expect(after.reviews ?? []).toEqual(before.reviews ?? []);
  expect(after.status).toEqual(before.status);
});

// ── the review queue ──────────────────────────────────────────────────────────

Given("a finding in the review queue", async ({ world }) => {
  // A reviewer-authored record whose span does not support it is routed to review (FR-046). The
  // service takes reviewer-authored records only as claims, so the queued record is a claim on Paper A.
  const made = await world.post<{ id: string }>("/claims", {
    sourceId: "src:file:paper-a",
    version: 1,
    // Named for the scenario's request, so a second run against the same database makes a new record.
    proposition: `Duck hepatocytes accumulate lipid in a defined serum-free medium (${rid(world)})`,
    kind: "normalised_claim",
    spans: [{ sectionId: "sec-4", quotation: "Duck hepatocytes proliferated under the defined conditions" }],
    reviewer: "demo-reviewer",
  });
  world.remembered.set("queued", made.id);
  await world.eventually("the record is in the review queue", async () => {
    const rows = await world.get<{ id: string }[]>("/review-queue");
    return rows.some((r) => r.id === made.id) ? rows : undefined;
  });
});

When("the reviewer rejects the finding on its card with a rationale", async ({ world }) => {
  const id = world.remembered.get("queued")!;
  await world.open("/queue");
  const item = world.page.locator(`li[data-queued="${id}"]`);
  await expect(item).toBeVisible({ timeout: 30_000 });
  const form = item.locator("form.edp-decision");
  await form.getByLabel("Action").selectOption("reject");
  await chooseReviewer(form, "demo-reviewer");
  await form.getByLabel("Rationale").fill("the span is about proliferation");
  await form.getByRole("button", { name: "Record the review" }).click();
});

Then("the status of the finding is {string}", async ({ world }, status: string) => {
  const id = world.remembered.get("queued")!;
  const view = await world.eventually("the review", async () => {
    const v = await world.get<{ record: { status: unknown } }>(`/claims/${q(id)}`);
    return tagOf(v.record.status) === status ? v : undefined;
  }, 15_000);
  expect(tagOf(view.record.status)).toBe(status);
});

Then("the finding is no longer in the review queue", async ({ world }) => {
  const id = world.remembered.get("queued")!;
  await expect(world.page.locator(`li[data-queued="${id}"]`)).toHaveCount(0, { timeout: 15_000 });
  // The queue is a view: it catches up with the review within moments.
  await world.eventually("the queue drops the record", async () => {
    const rows = await world.get<{ id: string }[]>("/review-queue");
    return rows.every((r) => r.id !== id) ? rows : undefined;
  }, 15_000);
});

// ── the card records what the operator's route records ────────────────────────

Given("any decision a reviewer can make", async ({ world }) => {
  // The same decision on two requests at the same pause: one on the card, one through the API.
  const onCard = rid(world);
  const byApi = await submitWaiting(world);
  world.remembered.set("request", onCard);
  world.remembered.set("by-api", byApi);
  await selectStage(world, onCard, "facet-review");
});

When("the decision is made on its card", async ({ world }) => {
  const c = card(world, "facets");
  await chooseReviewer(c, "demo-reviewer");
  const facet = (await facetsOf(world)).find((f) => f.id.endsWith(":f1"))!;
  await reviewFacetOnCard(world, facet, "confirm", "the same decision twice");
  await expect(c.locator(`li.edp-facet[data-facet="${facet.id}"]`)).toHaveAttribute("data-status", "confirmed");
  const byApi = world.remembered.get("by-api")!;
  await world.post(`/facets/${q(`${byApi}:f1`)}/review`, { action: "confirm", reviewer: "demo-reviewer", rationale: "the same decision twice" });
  // And one the route refuses: the facets confirmed while mandatory facets are unsettled.
  await c.getByRole("button", { name: "Confirm the facets" }).click();
});

Then("the record holds the same reviewer, time, rationale and version as the operator's route would record", async ({ world }) => {
  const a = (await facetsOf(world)).find((f) => f.id.endsWith(":f1"))!;
  const b = (await facetsOf(world, world.remembered.get("by-api")!)).find((f) => f.id.endsWith(":f1"))!;
  // Field by field, except the request's id and the times, which differ by construction.
  const strip = (f: FacetJson, r: string) =>
    JSON.parse(JSON.stringify(f).replaceAll(r, "<request>").replace(/"\d{4}-\d\d-\d\dT[^"]+Z"/g, '"<time>"')) as unknown;
  expect(strip(a, rid(world))).toEqual(strip(b, world.remembered.get("by-api")!));
  expect(a.version).toBe(b.version);
  expect(a.reviews?.at(-1)).toEqual(expect.objectContaining({ reviewer: "demo-reviewer", rationale: "the same decision twice" }));
});

Then("it is refused in exactly the cases the operator's route refuses", async ({ world }) => {
  const onCard = rid(world);
  const byApi = world.remembered.get("by-api")!;
  const api = await world.attempt("POST", `/requests/${q(byApi)}/facets:confirmed`, { reviewer: "demo-reviewer" });
  expect(api.status).toBe(409);
  const message = (JSON.parse(api.body) as { error: string }).error;
  await expect(card(world, "facets").locator("form.edp-facets-confirmed [role=alert]")).toHaveText(message.replaceAll(byApi, onCard));
  const unknown = await world.attempt("POST", `/facets/${q(`${byApi}:f2`)}/review`, { action: "confirm", reviewer: "nobody", rationale: "x" });
  expect(unknown.status).toBe(400);
});
