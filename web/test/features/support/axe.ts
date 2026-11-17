// The accessibility check every view must pass: axe with WCAG 2.1 A and AA, no violation at all.
import AxeBuilder from "@axe-core/playwright";
import { expect, type Page } from "@playwright/test";

export const wcag = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"];

export async function expectAccessible(page: Page, what: string): Promise<void> {
  const result = await new AxeBuilder({ page }).withTags(wcag).analyze();
  const summary = result.violations.map((v) => `${v.id} (${v.impact}): ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`);
  expect(summary, `axe violations on ${what}`).toEqual([]);
}
