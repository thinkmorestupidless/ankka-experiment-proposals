import { test } from "node:test";
import assert from "node:assert/strict";
import { parse, segments } from "../src/conversation/markers.ts";
import { cardKinds } from "../src/backend/types.ts";

test("a marker names a record by kind and id, and a version when it carries one", () => {
  const { references, prefill } = parse("Indirect by the species rule [[assessment:asm-1]], see [[design:dsg-1@2]].");
  assert.deepEqual(references, [
    { kind: "assessment", id: "asm-1", version: null },
    { kind: "design", id: "dsg-1", version: 2 },
  ]);
  assert.equal(prefill, null);
});

test("every one of the sixteen kinds is a marker; an unknown kind is text", () => {
  assert.deepEqual(parse(cardKinds.map((k) => `[[${k}:x-1]]`).join(" ")).references.map((r) => r.kind), [...cardKinds]);
  const s = segments("[[recipe:r-1]] and [[finding:fnd-1]]").segments;
  assert.deepEqual(s[0], { type: "text", text: "[[recipe:r-1]] and " });
  assert.equal(s[1].type, "reference");
});

test("a marker inside a code span is text", () => {
  const { references, prefill } = parse("Cite with `[[finding:fnd-9]]`; real: [[claim:clm-1]]. `[[prefill:approval:d|x=y]]`");
  assert.deepEqual(references, [{ kind: "claim", id: "clm-1", version: null }]);
  assert.equal(prefill, null);
});

test("a record cited twice is one reference, in order of first appearance", () => {
  assert.deepEqual(parse("[[finding:b]] [[finding:a]] [[finding:b]]").references.map((r) => r.id), ["b", "a"]);
});

test("a prefill names the form, its target and its fields; an empty field stays empty", () => {
  assert.deepEqual(parse("Must approve. [[prefill:approval:dsg-1|outcome=approve|version=1|rationale=]]").prefill, {
    decision: "approval",
    target: "dsg-1",
    fields: { outcome: "approve", version: "1", rationale: "" },
  });
});

test("a prefill of an unknown form is neither a prefill nor a reference", () => {
  const p = parse("[[prefill:delete:req-1|now=yes]]");
  assert.equal(p.prefill, null);
  assert.deepEqual(p.references, []);
});

test("while streaming, a marker split across chunks is held back, then whole", () => {
  const chunks = ["See [[fin", "ding:fnd", "-1]] now"];
  let text = "";
  const shown: string[] = [];
  for (const c of chunks) {
    text += c;
    const { segments: s, pending } = segments(text, true);
    shown.push(s.map((x) => (x.type === "reference" ? `<${x.reference.id}>` : "text" in x ? x.text : "")).join("") + `|${pending}`);
  }
  assert.deepEqual(shown, ["See |[[fin", "See |[[finding:fnd", "See <fnd-1> now|"]);
});

test("a lone bracket at the end of a chunk is held back only while streaming", () => {
  assert.equal(segments("a [", true).pending, "[");
  assert.equal(segments("a [").pending, "");
  assert.equal(segments("a [not a marker] b", true).pending, "");
});
