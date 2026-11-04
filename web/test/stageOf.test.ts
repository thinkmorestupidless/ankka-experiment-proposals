// Which stage of the journey a cited record's card selects (src/conversation/stageOf.ts).
import { test } from "node:test";
import assert from "node:assert/strict";
import { stageOf } from "../src/conversation/stageOf.ts";

const rid = "rq-01abc";

test("a question's records select their stage in its lane", () => {
  assert.deepEqual(stageOf("assessment", `${rid}:q4:src:file:paper-b:v1:fd1`), { requestId: rid, selection: { stage: "assessment", question: `${rid}:q4` } });
  assert.deepEqual(stageOf("sufficiency", `${rid}:q4:recommend-production-feasibility`), { requestId: rid, selection: { stage: "sufficiency", question: `${rid}:q4` } });
  assert.deepEqual(stageOf("search-execution", `${rid}:q4:plan:e4`), { requestId: rid, selection: { stage: "executions", question: `${rid}:q4` } });
  assert.deepEqual(stageOf("search-plan", `${rid}:q4:plan`), { requestId: rid, selection: { stage: "search-plan", question: `${rid}:q4` } });
});

test("the request's own records select their stage on the spine", () => {
  assert.deepEqual(stageOf("request", rid), { requestId: rid, selection: { stage: "request" } });
  assert.deepEqual(stageOf("facets", rid), { requestId: rid, selection: { stage: "facet-review" } });
  assert.deepEqual(stageOf("design", `${rid}:design`), { requestId: rid, selection: { stage: "approval" } });
  assert.deepEqual(stageOf("package", `${rid}:q4:pk1`), { requestId: rid, selection: { stage: "packages" } });
});

test("a record that is no request's has no stage", () => {
  assert.equal(stageOf("finding", "src:file:paper-b:v1:fd1"), undefined);
  assert.equal(stageOf("source", "src:file:paper-b"), undefined);
});
