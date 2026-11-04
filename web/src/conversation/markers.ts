// The marker grammar an answer cites records with, the same as the service's ReferenceMarkers
// (contracts/assistant.md): `[[kind:id]]`, `[[kind:id@version]]` and `[[prefill:decision:target|f=v|…]]`.
// A marker inside a backtick code span is text; an unknown kind is text. While an answer streams,
// a marker not yet closed is held back rather than shown as text.
import { cardKinds, type DecisionKind, type Prefill, type Reference } from "../backend/types.ts";

export type Segment =
  | { type: "text"; text: string }
  | { type: "code"; text: string }
  | { type: "reference"; reference: Reference; raw: string }
  | { type: "prefill"; prefill: Prefill; raw: string };

const decisions: readonly DecisionKind[] = ["facet-review", "facets-confirmed", "record-review", "decide", "approval"];
const marker = /^\[\[([a-z-]+):([^\]\s|@]+)(?:@(\d+))?\]\]/;
const fill = /^\[\[prefill:([a-z-]+):([^\]\s|]+)((?:\|[^\]|]*)*)\]\]/;

function asPrefill(m: RegExpExecArray): Prefill | null {
  if (!decisions.includes(m[1] as DecisionKind)) return null;
  const fields: Record<string, string> = {};
  for (const pair of m[3].split("|").filter((p) => p.length > 0)) {
    const at = pair.indexOf("=");
    const k = (at < 0 ? pair : pair.slice(0, at)).trim();
    if (k) fields[k] = at < 0 ? "" : pair.slice(at + 1).trim();
  }
  return { decision: m[1], target: m[2], fields };
}

/** Could `rest` still grow into a marker? Then, while streaming, it is held back. */
function couldBeMarker(rest: string): boolean {
  if (rest === "[") return true;
  if (!rest.startsWith("[[")) return false;
  return !rest.includes("]]") && !/\s/.test(rest.replace(/^\[\[prefill:[^\]]*/, "[["));
}

/**
 * The text as segments. With `streaming`, a marker cut off at the end is answered as `pending`, to be
 * prepended to the next chunk's parse (or, simpler, to parse the whole text again with the next chunk).
 */
export function segments(text: string, streaming = false): { segments: Segment[]; pending: string } {
  const out: Segment[] = [];
  let plain = "";
  const flush = () => {
    if (plain) out.push({ type: "text", text: plain });
    plain = "";
  };
  let i = 0;
  while (i < text.length) {
    const c = text[i];
    if (c === "`") {
      const close = text.indexOf("`", i + 1);
      if (close < 0) {
        // An unclosed code span: while streaming it may close later, so it is text in a code span.
        flush();
        out.push({ type: "code", text: text.slice(i + 1) });
        return { segments: out, pending: "" };
      }
      flush();
      out.push({ type: "code", text: text.slice(i + 1, close) });
      i = close + 1;
      continue;
    }
    if (c === "[" ) {
      const rest = text.slice(i);
      const f = fill.exec(rest);
      if (f) {
        const prefill = asPrefill(f);
        if (prefill) {
          flush();
          out.push({ type: "prefill", prefill, raw: f[0] });
        } else plain += f[0];
        i += f[0].length;
        continue;
      }
      const m = marker.exec(rest);
      if (m && m[1] !== "prefill") {
        if ((cardKinds as readonly string[]).includes(m[1])) {
          flush();
          out.push({
            type: "reference",
            reference: { kind: m[1], id: m[2], version: m[3] === undefined ? null : Number(m[3]) },
            raw: m[0],
          });
        } else plain += m[0];
        i += m[0].length;
        continue;
      }
      if (streaming && couldBeMarker(rest)) {
        flush();
        return { segments: out, pending: rest };
      }
    }
    plain += c;
    i += 1;
  }
  flush();
  return { segments: out, pending: "" };
}

/** The references in order of first appearance, each once, and the first prefill, as the service records them. */
export function parse(text: string): { references: Reference[]; prefill: Prefill | null } {
  const seen = new Set<string>();
  const references: Reference[] = [];
  let prefill: Prefill | null = null;
  for (const s of segments(text).segments) {
    if (s.type === "reference") {
      const key = `${s.reference.kind}:${s.reference.id}@${s.reference.version ?? ""}`;
      if (!seen.has(key)) {
        seen.add(key);
        references.push(s.reference);
      }
    } else if (s.type === "prefill" && !prefill) prefill = s.prefill;
  }
  return { references, prefill };
}
