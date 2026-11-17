// SC-008 of feature 002: axe with WCAG 2.0 and 2.1, A and AA, finds no violation on any view: the
// admission screen, the requests, the canvas with each kind of card in the inspector, the queue, the
// sources, the conversation with an answer, a proposal and a prefill, and the narrow layout.
import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { admitted, approved, at, get, journey, q } from "./helpers.ts";

const wcag = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"];

async function accessible(page: Page, what: string): Promise<void> {
  const result = await new AxeBuilder({ page }).withTags(wcag).analyze();
  const summary = result.violations.map((v) => `${v.id} (${v.impact}): ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`);
  expect(summary, `axe violations on ${what}`).toEqual([]);
}

async function ask(page: Page, question: string): Promise<void> {
  const section = page.locator("section.edp-conversation");
  const before = await section.locator("li.edp-turn").count();
  await page.getByLabel("Your question").fill(question);
  await page.getByLabel("Your question").press("Enter");
  await expect(section.locator("li.edp-turn").nth(before)).not.toHaveAttribute("data-state", "writing", { timeout: 60_000 });
}

test.describe("SC-008: no axe violation at WCAG 2.1 AA", () => {
  test.setTimeout(900_000);

  test("the admission screen", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByLabel("Shared token")).toBeVisible();
    await accessible(page, "the admission screen");
    await page.getByLabel("Shared token").fill("not the token");
    await page.getByRole("button", { name: "Enter" }).click();
    await expect(page.getByRole("alert")).toBeVisible();
    await accessible(page, "the admission screen with its refusal");
  });

  test("the requests, the canvas with each card, the queue and the sources", async ({ page, request }) => {
    const rid = await approved(request);
    await admitted(page);
    await page.goto("/");
    await expect(page.locator("table.edp-requests")).toBeVisible({ timeout: 30_000 });
    await accessible(page, "the requests");

    await page.goto(`/requests/${q(rid)}`);
    await expect(page.locator(`.edp-canvas[data-request="${rid}"]`)).toBeVisible({ timeout: 30_000 });
    await accessible(page, "the canvas");
    const j = await journey(request, rid);
    const lane = j.questions.find((x) => x.text.startsWith("Has relevant lipid"))!;
    const nodes = [
      ...j.spine.filter((n) => n.state !== "unreached").map((n) => ({ stage: n.stage, question: undefined as string | undefined })),
      ...lane.nodes.filter((n) => n.state !== "unreached").map((n) => ({ stage: n.stage, question: lane.questionId })),
    ];
    const kinds = new Set<string>();
    for (const n of nodes) {
      if (n.question) await page.getByLabel("Scientific question", { exact: true }).selectOption(n.question);
      await page.locator(`.edp-lane[data-lane="${n.question ?? "spine"}"] button[data-stage="${n.stage}"]`).click();
      const stage = page.locator(`.ui-inspector .edp-stage[data-stage="${n.stage}"]`);
      await expect(stage.locator("section.edp-card").first()).toBeVisible({ timeout: 30_000 });
      await expect(page.locator(".ui-inspector .ui-hint", { hasText: "Reading" })).toHaveCount(0, { timeout: 30_000 });
      for (const k of await stage.locator("section.edp-card").evaluateAll((all) => all.map((c) => c.getAttribute("data-kind") ?? ""))) kinds.add(k);
      // Each card's "every field" open as well, since a reader may open it.
      for (const d of await stage.locator("details.edp-every-field").all()) await d.locator("summary").click();
      await accessible(page, `the canvas with the stage ${n.stage}${n.question ? ` of ${n.question}` : ""}`);
    }
    expect([...kinds].sort()).toEqual(
      expect.arrayContaining(["request", "facets", "question", "search-plan", "search-execution", "finding", "assessment", "sufficiency", "package", "decision", "design", "stage-run"]),
    );

    await page.goto("/queue");
    await expect(page.getByRole("heading", { name: "Review queue", level: 1 })).toBeVisible();
    await expect(page.locator(".ui-hint", { hasText: "Reading the queue" })).toHaveCount(0, { timeout: 30_000 });
    await accessible(page, "the review queue");

    await page.goto("/sources");
    await expect(page.locator("li[data-source]").first()).toBeVisible({ timeout: 30_000 });
    await page.locator('li[data-source="src:file:paper-b"] button').click();
    await expect(page.locator('.ui-inspector section.edp-card[data-kind="source"]')).toBeVisible({ timeout: 30_000 });
    await expect(page.locator(".ui-inspector li[data-record]").first()).toBeVisible({ timeout: 30_000 });
    await accessible(page, "the sources, with a source in the inspector");
  });

  test("the conversation with an answer, a proposal and a prefill", async ({ page, request }) => {
    const rid = await approved(request);
    await admitted(page);
    await page.goto(`/requests/${q(rid)}?discuss=1`);
    await expect(page.locator("section.edp-conversation")).toBeVisible({ timeout: 30_000 });
    await expect(page.getByLabel("Your question")).toBeEnabled({ timeout: 30_000 });
    await ask(page, "Why is the finding from Paper B indirect?");
    await expect(page.locator('li.edp-turn section.edp-card[data-kind="assessment"]').last()).toBeVisible({ timeout: 30_000 });
    await ask(page, "Cite the finding that does not exist.");
    await accessible(page, "the request's conversation beside its journey, with answers, cards and an unresolved reference");

    // A prefill, on a design still awaiting its approval when there is one; otherwise on the approved one.
    let awaiting = rid;
    for (const row of await get<{ id: string }[]>(request, "/requests")) {
      if (at(await journey(request, row.id), "approval") === "paused") awaiting = row.id;
    }
    await page.goto(`/requests/${q(awaiting)}/chat`);
    await expect(page.getByLabel("Your question")).toBeEnabled({ timeout: 30_000 });
    await ask(page, "Approve it.");
    await expect(page.locator(".edp-prefill").last()).toBeVisible({ timeout: 30_000 });
    await accessible(page, "the conversation with a prefilled decision");

    await page.goto("/chat");
    await page.getByRole("button", { name: "New conversation" }).click();
    await expect(page.getByLabel("Your question")).toBeEnabled({ timeout: 30_000 });
    await ask(page, "Mark Paper C retracted.");
    await expect(page.locator('section.edp-action-proposal[data-status="proposed"]')).toBeVisible({ timeout: 30_000 });
    await accessible(page, "the conversation with a proposal");
    await page.locator('section.edp-action-proposal[data-status="proposed"]').getByRole("button", { name: "Decline" }).click();
    await expect(page.locator('section.edp-action-proposal[data-status="declined"]')).toBeVisible();
    await accessible(page, "the conversation with a declined proposal");
  });

  test("the narrow layout at 390 px", async ({ page, request }) => {
    const rid = await approved(request);
    await page.setViewportSize({ width: 390, height: 844 });
    await admitted(page);
    await page.goto("/");
    await expect(page.locator("table.edp-requests")).toBeVisible({ timeout: 30_000 });
    await accessible(page, "the requests at 390 px");
    await page.goto(`/requests/${q(rid)}?stage=facet-review`);
    await expect(page.locator('.ui-inspector section.edp-card[data-kind="facets"]')).toBeVisible({ timeout: 30_000 });
    await accessible(page, "the canvas and its inspector at 390 px");
    // At this width "discuss" opens the conversation as the whole page.
    await page.goto(`/requests/${q(rid)}`);
    await page.getByRole("button", { name: "Discuss this request" }).click();
    await expect(page).toHaveURL(new RegExp(`/requests/${q(rid)}/chat$`));
    await expect(page.getByLabel("Your question")).toBeEnabled({ timeout: 30_000 });
    await ask(page, "Show me the facets");
    await accessible(page, "the conversation at 390 px");
    const width = await page.evaluate(() => document.documentElement.scrollWidth);
    expect(width, "no sideways scroll").toBeLessThanOrEqual(390);
  });
});
