// The browser suites (contracts/interface.md, Testing). `features` runs every scenario of
// features/interface/ through playwright-bdd except the one tagged @no-model, which `no-model` runs
// against a stack started without an assistant script. The rest are plain Playwright specs.
// The interface is started beside the service with `ankka local web`, as the operator starts it.
import { defineConfig, devices } from "@playwright/test";
import { defineBddProject } from "playwright-bdd";

const service = process.env.EDP_SERVICE_URL ?? "http://127.0.0.1:9000";
const baseURL = process.env.EDP_WEB_URL ?? "http://127.0.0.1:3000";

const bdd = {
  features: "../features/interface/*.feature",
  featuresRoot: "../features",
  steps: ["test/features/steps/**/*.ts", "test/features/support/world.ts"],
  // Generation runs before every step is written; a scenario without its steps fails when it runs.
  missingSteps: "fail-on-run" as const,
};

export default defineConfig({
  timeout: 120_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    ...devices["Desktop Chrome"],
    baseURL,
    trace: "retain-on-failure",
    extraHTTPHeaders: {},
  },
  projects: [
    { ...defineBddProject({ ...bdd, name: "features", tags: "not @no-model", outputDir: ".features-gen/features" }) },
    { ...defineBddProject({ ...bdd, name: "no-model", tags: "@no-model", outputDir: ".features-gen/no-model" }) },
    { name: "walkthrough", testDir: "test/specs", testMatch: "walkthrough.spec.ts", use: { trace: "on" } },
    { name: "cards", testDir: "test/specs", testMatch: "cards.spec.ts" },
    { name: "accessibility", testDir: "test/specs", testMatch: "accessibility.spec.ts" },
  ],
  webServer: {
    command: `ankka local web --service ankka-experiment-proposals=${service} -- npm run dev`,
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
});
