// The steps both interface features share: the request they start from and the configured reviewer.
import { expect } from "@playwright/test";
import { Given } from "../support/world.ts";
import { ensureCorpus, submitWaiting } from "../support/drive.ts";

Given("the customer request {string}", async ({ world }, text: string) => {
  await ensureCorpus(world);
  await submitWaiting(world, text);
});

Given("the reviewer {string} is configured", async ({ world }, name: string) => {
  const reviewers = await world.get<{ name: string }[]>("/reviewers");
  expect(reviewers.map((r) => r.name)).toContain(name);
});
