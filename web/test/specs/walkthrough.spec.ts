// SC-001 and SC-002 of feature 002: the reference walkthrough of feature 001, by one person in the
// browser, with the keyboard alone (Tab, Enter, Space, typing and the arrows; no click). The fixtures
// are in the store first, as SC-002 says. Each of feature 001's SC-001 to SC-010 is checked on the card
// that shows it, the reviewer makes exactly three decisions, and after each the open canvas moves on
// within five seconds without a reload.
import { expect, test, type APIRequestContext, type Locator, type Page } from "@playwright/test";
import { admitted, at, get, ingest, journey, lipid, post, press, q, tabTo, tagOf } from "./helpers.ts";

const reviewer = "demo-reviewer";

const conversation = (page: Page) => page.locator("section.edp-conversation");
const inspector = (page: Page) => page.locator(".ui-inspector");
const node = (page: Page, stage: string, lane = "spine") => page.locator(`.edp-lane[data-lane="${lane}"] button[data-stage="${stage}"]`);

async function ask(page: Page, question: string): Promise<Locator> {
  const before = await conversation(page).locator("li.edp-turn").count();
  await tabTo(page, page.getByLabel("Your question"));
  await page.keyboard.type(question);
  await page.keyboard.press("Enter");
  const t = conversation(page).locator("li.edp-turn").nth(before);
  await expect(t).toHaveAttribute("data-state", "answered", { timeout: 60_000 });
  return t;
}

async function confirmProposal(page: Page, action: string): Promise<Locator> {
  const p = conversation(page).locator(`section.edp-action-proposal[data-action="${action}"][data-status="proposed"]`).last();
  const n = (await p.getAttribute("data-proposal"))!;
  await press(page, p.getByRole("button", { name: /^Confirm/ }));
  const done = conversation(page).locator(`section.edp-action-proposal[data-proposal="${n}"]`);
  await expect(done).toHaveAttribute("data-status", "performed", { timeout: 60_000 });
  return done;
}

/** The node's state changes on the open canvas within five seconds, and the page was not reloaded. */
async function movesOn(page: Page, stage: string, from: string): Promise<void> {
  const started = Date.now();
  await expect(node(page, stage)).not.toHaveAttribute("data-state", from, { timeout: 5_000 });
  expect(Date.now() - started).toBeLessThan(5_000);
  expect(await page.evaluate(() => (window as unknown as { __edpNotReloaded?: boolean }).__edpNotReloaded)).toBe(true);
}

// The trace is recorded by the spec itself, to a fixed path (tasks.md T072), not by the project.
test.use({ trace: "off" });

test("the reference walkthrough, by keyboard alone", async ({ page, request, context }) => {
  test.setTimeout(1_200_000);
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
  try {
    await walkthrough(page, request);
  } finally {
    await context.tracing.stop({ path: "test-results/walkthrough.zip" });
  }
});

async function walkthrough(page: Page, request: APIRequestContext): Promise<void> {
  await ingest(request, "paper-a.xml", "paper-b.xml", "paper-c.xml", "retracted.xml");
  await admitted(page);
  await page.goto("/");

  // 1. The request, typed in the conversation and submitted on confirmation (SC-001 of 001).
  await press(page, page.getByRole("navigation", { name: "Areas" }).getByRole("link", { name: "Conversation" }));
  await expect(conversation(page)).toBeVisible();
  await ask(page, "Submit the request: Can you make foie gras?");
  const submitted = await confirmProposal(page, "submit-request");
  const requestCard = submitted.locator('section.edp-card[data-kind="request"]');
  await expect(requestCard).toBeVisible({ timeout: 30_000 });
  const rid = (await requestCard.getAttribute("data-id"))!;
  await expect(requestCard).toContainText("Can you make foie gras?");
  await press(page, requestCard.getByRole("button", { name: "Show its stage" }));
  await expect(page.locator(`.edp-canvas[data-request="${rid}"]`)).toBeVisible({ timeout: 30_000 });
  await page.evaluate(() => ((window as unknown as { __edpNotReloaded: boolean }).__edpNotReloaded = true));
  const view = await get<{ request: { text: string } }>(request, `/requests/${q(rid)}`);
  expect(view.request.text).toBe("Can you make foie gras?");

  // 2. The facets: each mandatory one confirmed, then the facets, as the reviewer (decision one).
  const confirmFacets = page.locator('button[data-primary="facet-review"]');
  await expect(confirmFacets).toBeVisible({ timeout: 120_000 });
  await press(page, confirmFacets);
  const facets = inspector(page).locator('section.edp-card[data-kind="facets"]');
  await expect(facets).toBeVisible({ timeout: 30_000 });
  await tabTo(page, facets.getByLabel("Reviewer", { exact: true }).first());
  await page.keyboard.type(reviewer);
  const mandatory = (await get<{ id: string; importance: unknown; status: unknown }[]>(request, `/requests/${q(rid)}/facets`)).filter(
    (f) => tagOf(f.importance) === "mandatory" && tagOf(f.status) !== "confirmed",
  );
  for (const f of mandatory) {
    const li = facets.locator(`li.edp-facet[data-facet="${f.id}"]`);
    const details = li.locator("details.edp-facet-review");
    if (!(await details.evaluate((d) => (d as HTMLDetailsElement).open))) await press(page, details.locator("summary"));
    await press(page, details.getByRole("button", { name: "Record the review" }));
    await expect(li.locator("li[data-reviewer]").first()).toBeVisible({ timeout: 30_000 });
  }
  await press(page, facets.locator("form.edp-facets-confirmed").getByRole("button", { name: "Confirm the facets" }));
  await movesOn(page, "facet-review", "paused");
  // SC-001: every mandatory facet confirmed at proposal time, and the request's text unchanged.
  const proposal = await get<{ stillProposed: string[] }>(request, `/requests/${q(rid)}/proposal`);
  expect(proposal.stillProposed).toEqual([]);

  // 3. The investigation runs by itself to the candidates; the reviewer decides (decision two).
  const decide = page.locator('button[data-primary="decision"]');
  await expect(decide).toBeVisible({ timeout: 300_000 });
  // SC-002: the lipid question's search re-runs to the same identifiers, on its card.
  await tabTo(page, page.getByLabel("Scientific question", { exact: true }));
  await page.keyboard.type(lipid.slice(0, 24));
  const lane = (await page.getByLabel("Scientific question", { exact: true }).inputValue())!;
  expect(lane).toMatch(new RegExp(`^${rid}:q\\d+$`));
  await press(page, node(page, "eligibility", lane));
  const fused = inspector(page).locator('section.edp-card[data-kind="search-execution"]').first();
  await expect(fused).toContainText("eligibility.retracted", { timeout: 30_000 });
  await press(page, fused.getByRole("button", { name: "Re-run the search" }));
  await expect(fused.locator(".edp-rerun-result")).toContainText("returned the same identifiers", { timeout: 60_000 });
  // SC-003: a finding beside the text its spans resolve to.
  await press(page, node(page, "verification", lane));
  const finding = inspector(page).locator('section.edp-card[data-kind="finding"]').first();
  await expect(finding.locator("figure, blockquote").first()).toBeVisible({ timeout: 30_000 });
  // SC-004: Paper B indirect by the species rule.
  await press(page, node(page, "assessment", lane));
  const paperB = inspector(page).locator(`section.edp-card[data-kind="assessment"][data-id="${lane}:src:file:paper-b:v1:fd1"]`);
  await expect(paperB).toContainText("indirect", { timeout: 30_000 });
  await expect(paperB).toContainText("species-mismatch-blocks-direct");
  // SC-006 and SC-010: sufficiency for each decision, the abstention visible.
  await press(page, node(page, "sufficiency", lane));
  const sufficiency = inspector(page).locator('section.edp-card[data-kind="sufficiency"]');
  await expect(sufficiency).toHaveCount(2, { timeout: 30_000 });
  await expect(sufficiency.first()).toContainText("insufficient");
  // SC-007: two candidates, then the decision with the reason for the rejected one.
  await press(page, decide);
  const candidates = inspector(page).locator('section.edp-card[data-kind="candidates"]');
  await expect(candidates).toBeVisible({ timeout: 30_000 });
  const form = candidates.locator('form[data-decision="Decide between the candidate approaches"]');
  await tabTo(page, form.locator('input[type="radio"]').first());
  await page.keyboard.press("Space");
  await expect(form.locator('input[type="radio"]').first()).toBeChecked();
  const reason = form.locator("input[data-reason-for]").first();
  await tabTo(page, reason);
  await page.keyboard.type("not the target species");
  await tabTo(page, form.getByLabel("Rationale"));
  await page.keyboard.type("the target species comes first");
  await press(page, form.getByRole("button", { name: "Record the decision" }));
  await movesOn(page, "decision", "paused");
  const approaches = await get<{ approaches: unknown[]; decision?: { rejected: { reason: string }[] } }>(request, `/requests/${q(rid)}/approaches`);
  expect(approaches.approaches.length).toBeGreaterThanOrEqual(2);
  expect(approaches.decision?.rejected.map((r) => r.reason)).toEqual(["not the target species"]);

  // 4. The design, and its approval (decision three); SC-008.
  const approve = page.locator('button[data-primary="approval"]');
  await expect(approve).toBeVisible({ timeout: 300_000 });
  await press(page, approve);
  const design = inspector(page).locator('section.edp-card[data-kind="design"]');
  await expect(design).toContainText("Proposal awaiting review", { timeout: 30_000 });
  const approval = design.locator('form[data-decision^="Decide on version"]');
  await tabTo(page, approval.getByLabel("Rationale"));
  await page.keyboard.type("ready to run");
  await press(page, approval.getByRole("button", { name: "Record the decision on the design" }));
  await movesOn(page, "approval", "paused");
  await expect(design).not.toContainText("Proposal awaiting review", { timeout: 30_000 });
  await expect(design).toContainText("ready to run");

  // SC-002 of 002: exactly three decisions were made.
  const request0 = await get<{ request: { confirmedBy?: string } }>(request, `/requests/${q(rid)}`);
  const designJson = await get<{ approvals?: { decision: unknown; reviewer: string }[]; versions: { sections?: unknown }[] }>(request, `/designs/${q(rid)}:design`);
  expect(request0.request.confirmedBy).toBe(reviewer);
  expect(approaches.decision).toBeDefined();
  expect((designJson.approvals ?? []).map((a) => tagOf(a.decision))).toEqual(["approved"]);

  // 5. A fault, from the conversation (SC-005 of 001), on the copy of Paper A its cassettes were
  // recorded under. The proposal's target is checked before anything is done: a fault on the original
  // finding has no verification cassette, and would change what every later request assesses. The
  // copy is put in the store only now, as scripts/walkthrough.sh does, for the same reason.
  await press(page, page.getByRole("navigation", { name: "Areas" }).getByRole("link", { name: "Conversation" }));
  await expect(conversation(page)).toBeVisible();
  await ask(page, "Inject the fault endpoint swap on a finding.");
  const fault = conversation(page).locator('section.edp-action-proposal[data-action="inject-fault"][data-status="proposed"]').last();
  await expect(fault).toBeVisible({ timeout: 30_000 });
  const copy = "src:file:paper-a@endpoint_swap:v1:fd1";
  expect(await fault.locator("dd code").allTextContents(), "the script proposes the fault on the copy of Paper A (evals/assistant/walkthrough.json)").toContain(copy);
  await ingest(request, "paper-a.xml@endpoint_swap");
  const faulted = await confirmProposal(page, "inject-fault");
  const faultCard = faulted.locator(`section.edp-card[data-kind="finding"][data-id="${copy}"]`);
  await expect(faultCard).toContainText("endpoint_mismatch", { timeout: 120_000 });
  // The copy has served its purpose; superseded, it stays out of any later request's search, whose
  // recorded assessments do not include it (as scripts/walkthrough.sh does).
  await post(request, "/sources/src:file:paper-a@endpoint_swap/status", { status: "superseded", reason: "the fault demonstration is done", by: "operator" });

  // 6. Paper B retracted, from the sources, as the operator; SC-009 and SC-010 on the impact.
  try {
    await press(page, page.getByRole("navigation", { name: "Areas" }).getByRole("link", { name: "Sources" }));
    await press(page, page.locator('li[data-source="src:file:paper-b"] button'));
    const status = inspector(page).getByLabel("Status", { exact: true });
    await tabTo(page, status);
    await page.keyboard.type("retr");
    await expect(status).toHaveValue("retracted");
    await tabTo(page, inspector(page).getByLabel("Reason", { exact: true }));
    await page.keyboard.type("walkthrough");
    await press(page, inspector(page).getByRole("button", { name: "Set the status" }));
    await expect(inspector(page).getByRole("status")).toContainText("retracted", { timeout: 30_000 });
    await press(page, page.getByRole("navigation", { name: "Areas" }).getByRole("link", { name: "Requests" }));
    await press(page, page.locator(`tr[data-request="${rid}"] a`).first());
    await expect(page.locator(`.edp-canvas[data-request="${rid}"]`)).toBeVisible({ timeout: 30_000 });
    await expect(node(page, "impact")).not.toHaveAttribute("data-state", "unreached", { timeout: 120_000 });
    await press(page, node(page, "impact"));
    const impact = inspector(page).locator('section.edp-card[data-kind="impact"]');
    await expect(impact.locator('[data-changed="source:src:file:paper-b:v1"]')).toBeVisible({ timeout: 120_000 });
    await expect(impact.locator('li[data-flagged="false"]')).toHaveCount(0, { timeout: 180_000 });
    await press(page, node(page, "approval"));
    await expect(design).toContainText("src:file:paper-b", { timeout: 30_000 });
    await expect(design).toContainText("ready to run");
    expect((await get<{ approvals?: unknown[] }>(request, `/designs/${q(rid)}:design`)).approvals ?? []).toHaveLength(1);
  } finally {
    await post(request, "/sources/src:file:paper-b/status", { status: "active", reason: "walkthrough restored", by: "operator" });
  }
  expect(at(await journey(request, rid), "approval")).toBe("reached");
}
