import { test } from "node:test";
import assert from "node:assert/strict";
import { EventParser, type StreamEvent } from "../src/backend/events.ts";

function parse(...chunks: string[]): StreamEvent[] {
  const out: StreamEvent[] = [];
  const p = new EventParser((e) => out.push(e));
  chunks.forEach((c) => p.push(c));
  p.end();
  return out;
}

test("data before the event name is one named event, as the service sends it", () => {
  assert.deepEqual(parse('data:{"a":1}\nevent:journey\n\n'), [{ event: "journey", data: '{"a":1}' }]);
});

test("an event split anywhere across chunks, even inside a CRLF", () => {
  assert.deepEqual(parse("eve", "nt: tok", "en\r", "\ndata: \"he", "llo\"\r\n\r", "\n"), [
    { event: "token", data: '"hello"' },
  ]);
});

test("several data lines are joined with a newline; a comment is ignored", () => {
  assert.deepEqual(parse(": keep-alive\n\ndata: a\ndata: b\n\n"), [{ event: "message", data: "a\nb" }]);
});

test("an event the stream ended before closing is dropped", () => {
  assert.deepEqual(parse("event: done\ndata: {}\n"), []);
});

test("events in a row keep their own names", () => {
  assert.deepEqual(
    parse('event: token\ndata: "a"\n\nevent: done\ndata: {}\n\n').map((e) => e.event),
    ["token", "done"],
  );
});
