// The canvas: the journey as a constellation. The spine runs left to right; at "questions" it fans
// out into one lane per scientific question and gathers again at "packages". The chosen question's
// lane is lit and labelled; the others are faint clusters below it, and choosing one (by the
// selector, or by pointing at it) brings it forward. The selection is the parent's, so it survives
// a redraw. The canvas pans by dragging and zooms with the wheel or the toolbar; a stage that takes
// the keyboard's focus off screen is brought into view.
import { useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { Crosshair, Maximize, Minus, Plus } from "lucide-react";
import type { Journey, StageNode } from "../backend/types.ts";
import { Node } from "./Node.tsx";

export interface Selection {
  stage: string;
  /** The question whose lane the stage is in; absent for the spine. */
  question?: string;
}

const COL = 124; // between two stages of the spine
const LANE_DX = 152; // between two questions' lanes
const STEP = 66; // between two stages of a lane
const DROP = 120; // from the spine to the first stage of a lane
const PAD = 90;

/** Where a stage's label sits: above or below its light on a row, beside it in a column. */
export type Side = "above" | "below" | "right";

interface Placed {
  node: StageNode;
  x: number;
  y: number;
  side: Side;
}

interface Layout {
  spine: Placed[];
  lane?: { questionId: string; nodes: Placed[] };
  faint: { questionId: string; text: string; nodes: Placed[] }[];
  edges: { d: string; lit: boolean; faint?: boolean }[];
  width: number;
  height: number;
  focus?: { x: number; y: number; kind: "current" | "waits" };
}

const curve = (a: { x: number; y: number }, b: { x: number; y: number }) => {
  const my = (a.y + b.y) / 2;
  return Math.abs(a.y - b.y) < 1
    ? `M${a.x} ${a.y} L${b.x} ${b.y}`
    : `M${a.x} ${a.y} C${a.x} ${my} ${b.x} ${my} ${b.x} ${b.y}`;
};

/**
 * The journey as a diamond: the spine up to "questions" along the top, the questions' lanes hanging
 * from it side by side, and the rest of the spine along the bottom where the lanes gather. A spine
 * without questions yet is one row.
 */
function layout(journey: Journey, chosen?: string): Layout {
  const qi = journey.spine.findIndex((n) => n.stage === "questions");
  const lanes = journey.questions;
  const pre = qi < 0 ? journey.spine : journey.spine.slice(0, qi + 1);
  const post = qi < 0 ? [] : journey.spine.slice(qi + 1);
  const laneLen = lanes.length > 0 ? Math.max(...lanes.map((l) => l.nodes.length)) : 0;
  // The spine's top row ends over the middle, where the lanes fork; its bottom row starts there,
  // where they join.
  const left = Math.max((pre.length - 1) * COL, ((lanes.length - 1) * LANE_DX) / 2 + 80);
  const right = Math.max((post.length - 1) * COL, ((lanes.length - 1) * LANE_DX) / 2 + 160);
  const width = left + right + PAD * 2;
  const mid = PAD + left;
  const row = (ns: StageNode[], y: number, from: "end" | "start"): Placed[] =>
    ns.map((node, i) => {
      const j = from === "end" ? ns.length - 1 - i : i;
      return {
        node,
        x: from === "end" ? mid - j * COL : mid + j * COL,
        y,
        side: (j % 2 === 0) === (from === "end") ? "above" : "below",
      };
    });
  const top = PAD + 30;
  const lanesTop = top + DROP;
  const bottom = lanes.length > 0 ? lanesTop + (laneLen - 1) * STEP + DROP : top + DROP;
  const preP = qi < 0 ? row(pre, top, "start").map((p, i) => ({ ...p, x: p.x - (pre.length - 1) * COL / 2 + 0 * i })) : row(pre, top, "end");
  const postP = row(post, bottom, "start");
  const spine = [...preP, ...postP];
  const at = (q: (typeof lanes)[number], c: number): Placed[] =>
    q.nodes.map((node, k) => ({ node, x: mid + (c - (lanes.length - 1) / 2) * LANE_DX, y: lanesTop + k * STEP, side: "right" }));
  const chosenLane = lanes.find((l) => l.questionId === chosen) ?? lanes[0];
  const placedLanes = lanes.map((l, c) => ({ questionId: l.questionId, text: l.text, nodes: at(l, c) }));
  const lit = placedLanes.find((l) => l.questionId === chosenLane?.questionId);
  const faint = placedLanes.filter((l) => l !== lit);

  const edges: Layout["edges"] = [];
  const reached = (n: StageNode) => n.state !== "unreached";
  const chain = (ps: Placed[], faintly?: boolean) =>
    ps.slice(1).forEach((p, i) => edges.push({ d: curve(ps[i], p), lit: reached(p.node), faint: faintly }));
  chain(preP);
  chain(postP);
  const fork = qi >= 0 ? preP[preP.length - 1] : undefined;
  const join = postP[0];
  for (const l of [...faint.map((f) => ({ nodes: f.nodes, faint: true })), ...(lit ? [{ nodes: lit.nodes, faint: false }] : [])]) {
    if (l.nodes.length === 0) continue;
    if (fork) edges.push({ d: curve(fork, l.nodes[0]), lit: reached(l.nodes[0].node), faint: l.faint });
    chain(l.nodes, l.faint);
    if (join) edges.push({ d: curve(l.nodes[l.nodes.length - 1], join), lit: reached(join.node), faint: l.faint });
  }

  const all = [...spine, ...(lit?.nodes ?? [])];
  const current = all.find((p) => p.node.state === "current");
  const waiting = all.find((p) => p.node.state === "paused" || p.node.state === "failed");
  const light = current ?? waiting;
  return {
    spine,
    lane: lit ? { questionId: lit.questionId, nodes: lit.nodes } : undefined,
    faint,
    edges,
    width,
    height: bottom + PAD + 30,
    focus: light ? { x: light.x, y: light.y, kind: current ? "current" : "waits" } : undefined,
  };
}

const clampK = (k: number) => Math.min(1.8, Math.max(0.35, k));
/** Below this the labels are too small to read; the canvas centres on what matters instead. */
const READABLE = 0.82;

export function Canvas({
  journey,
  selection,
  question,
  onSelect,
  onQuestion,
}: {
  journey: Journey;
  selection?: Selection;
  question?: string;
  onSelect: (s: Selection) => void;
  onQuestion: (q: string) => void;
}) {
  const selectId = useId();
  const glowId = useId().replace(/:/g, "");
  const shape = useMemo(() => layout(journey, question), [journey, question]);
  const lane = journey.questions.find((q) => q.questionId === shape.lane?.questionId);
  const viewport = useRef<HTMLDivElement>(null);
  const [k, setK] = useState(1);
  const [size, setSize] = useState({ w: 0, h: 0 });
  const fitted = useRef(false);
  const drag = useRef<{ x: number; y: number; left: number; top: number } | null>(null);
  /** Where to scroll once the zoom has been drawn: a point of the journey, at a point of the viewport. */
  const pending = useRef<{ wx: number; wy: number; px?: number; py?: number } | null>(null);

  // The journey at scale k, centred while it is smaller than the viewport, scrolling when larger.
  const sw = shape.width * k;
  const sh = shape.height * k;
  const ox = Math.max(0, (size.w - sw) / 2);
  const oy = Math.max(0, (size.h - sh) / 2);

  // Where to look when the whole journey does not fit at a readable size: the chosen stage, else
  // where the investigation is, else the middle.
  const anchor = useRef<{ x: number; y: number } | undefined>(undefined);
  const chosenAt = [...shape.spine, ...(shape.lane?.nodes ?? [])].find(
    (p) => p.node.stage === selection?.stage && (selection?.question ? shape.lane?.questionId === selection.question : !shape.lane?.nodes.includes(p)),
  );
  anchor.current = chosenAt ?? shape.focus;

  const fit = useCallback(
    (whole = false) => {
      const el = viewport.current;
      if (!el) return;
      const w = el.clientWidth;
      const h = el.clientHeight;
      const best = Math.min(1, (w - 24) / shape.width, (h - 70) / shape.height);
      const centre = { wx: shape.width / 2, wy: shape.height / 2 };
      if (whole || best >= READABLE) {
        setK(clampK(best));
        pending.current = centre;
        return;
      }
      const a = anchor.current ?? { x: centre.wx, y: centre.wy };
      const kk = Math.max(best, READABLE);
      setK(kk);
      pending.current = { wx: a.x, wy: shape.height * kk <= h - 70 ? centre.wy : a.y };
    },
    [shape.width, shape.height],
  );

  useLayoutEffect(() => {
    const el = viewport.current;
    const p = pending.current;
    if (!el || !p) return;
    pending.current = null;
    const px = p.px ?? el.clientWidth / 2;
    const py = p.py ?? el.clientHeight / 2;
    el.scrollLeft = ox + p.wx * k - px;
    el.scrollTop = oy + p.wy * k - py;
  });

  useEffect(() => {
    const el = viewport.current;
    if (!el) return;
    const measure = () => setSize({ w: el.clientWidth, h: el.clientHeight });
    measure();
    if (!fitted.current) {
      fitted.current = true;
      fit();
    }
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(() => {
      measure();
      fit();
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, [fit]);

  const zoomAt = (factor: number, px?: number, py?: number) => {
    const el = viewport.current;
    if (!el) return;
    const vx = px ?? el.clientWidth / 2;
    const vy = py ?? el.clientHeight / 2;
    pending.current = { wx: (el.scrollLeft + vx - ox) / k, wy: (el.scrollTop + vy - oy) / k, px: vx, py: vy };
    setK((v) => clampK(v * factor));
  };

  const centreOn = (x: number, y: number) => {
    pending.current = { wx: x, wy: y };
    setK((v) => v + 0); // draw again so the scroll is applied
    setSize((s) => ({ ...s }));
  };

  useEffect(() => {
    const el = viewport.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      if (!(e.ctrlKey || e.metaKey)) return; // a plain wheel scrolls
      e.preventDefault();
      const r = el.getBoundingClientRect();
      zoomAt(Math.exp(-e.deltaY * 0.004), e.clientX - r.left, e.clientY - r.top);
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  });

  const onPointerDown = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (e.button !== 0 || e.pointerType === "touch" || (e.target as HTMLElement).closest("button, select, a, .edp-faint-hit")) return;
    drag.current = { x: e.clientX, y: e.clientY, left: e.currentTarget.scrollLeft, top: e.currentTarget.scrollTop };
    e.currentTarget.setPointerCapture(e.pointerId);
  };
  const onPointerMove = (e: ReactPointerEvent<HTMLDivElement>) => {
    const d = drag.current;
    if (!d) return;
    e.currentTarget.scrollLeft = d.left - (e.clientX - d.x);
    e.currentTarget.scrollTop = d.top - (e.clientY - d.y);
  };
  const onPointerUp = () => {
    drag.current = null;
  };

  const spineSelected = selection && !selection.question ? selection.stage : undefined;
  const laneSelected = selection?.question === lane?.questionId ? selection?.stage : undefined;

  return (
    <div className="edp-canvas" data-request={journey.requestId}>
      <h2 className="ui-visually-hidden">The journey</h2>
      <div
        className="edp-viewport"
        ref={viewport}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
      >
        <div className="edp-sizer" style={{ width: Math.max(size.w, sw), height: Math.max(size.h, sh) }}>
        <div className="edp-world" style={{ width: shape.width, height: shape.height, left: ox, top: oy, transform: `scale(${k})` }}>
          <svg className="edp-edges" width={shape.width} height={shape.height} aria-hidden="true" focusable="false">
            <defs>
              <radialGradient id={`${glowId}-glow`}>
                <stop offset="0%" stopColor={shape.focus?.kind === "waits" ? "var(--ui-signal)" : "#ffffff"} stopOpacity="0.22" />
                <stop offset="45%" stopColor={shape.focus?.kind === "waits" ? "var(--ui-signal)" : "#ffffff"} stopOpacity="0.06" />
                <stop offset="100%" stopColor="#000" stopOpacity="0" />
              </radialGradient>
            </defs>
            {shape.focus ? <circle cx={shape.focus.x} cy={shape.focus.y} r={260} fill={`url(#${glowId}-glow)`} /> : null}
            {shape.edges.map((e, i) => (
              <path key={i} d={e.d} className={`edp-edge${e.lit ? " edp-edge-lit" : ""}${e.faint ? " edp-edge-faint" : ""}`} />
            ))}
          </svg>
          <div className="edp-faint" aria-hidden="true">
            {shape.faint.map((f) => (
              <div key={f.questionId} className="edp-faint-lane" data-question={f.questionId}>
                <span
                  className="edp-faint-hit"
                  title={f.text}
                  style={{ left: (f.nodes[0]?.x ?? 0) - 18, top: (f.nodes[0]?.y ?? 0) - 18, height: (f.nodes.length - 1) * STEP + 36 }}
                  onClick={() => onQuestion(f.questionId)}
                />
                {f.nodes.map((p) => (
                  <span key={p.node.stage} className="edp-faint-dot" data-state={p.node.state} style={{ left: p.x, top: p.y }} />
                ))}
              </div>
            ))}
          </div>
          <ol className="edp-lane" role="list" aria-label="The journey" data-lane="spine">
            {shape.spine.map((p) => (
              <Node
                key={p.node.stage}
                node={p.node}
                x={p.x}
                y={p.y}
                side={p.side}
                selected={spineSelected === p.node.stage}
                onSelect={() => onSelect({ stage: p.node.stage })}
              />
            ))}
          </ol>
          {lane && shape.lane ? (
            <section className="edp-question-lane" aria-label="A scientific question's lane">
              <ol className="edp-lane" role="list" aria-label={`The lane of: ${lane.text}`} data-lane={lane.questionId}>
                {shape.lane.nodes.map((p) => (
                  <Node
                    key={p.node.stage}
                    node={p.node}
                    lane={lane.questionId}
                    x={p.x}
                    y={p.y}
                    side={p.side}
                    selected={laneSelected === p.node.stage}
                    onSelect={() => onSelect({ stage: p.node.stage, question: lane.questionId })}
                  />
                ))}
              </ol>
            </section>
          ) : null}
        </div>
        </div>
      </div>
      {lane ? (
        <div className="edp-canvas-top">
          <label htmlFor={selectId}>Scientific question</label>
          <select id={selectId} className="ui-input edp-question-select" value={lane.questionId} onChange={(e) => onQuestion(e.target.value)}>
            {journey.questions.map((q) => (
              <option key={q.questionId} value={q.questionId}>
                {q.text}
              </option>
            ))}
          </select>
        </div>
      ) : null}
      <div className="edp-toolbar" role="toolbar" aria-label="The canvas">
        <button type="button" onClick={() => fit(true)} aria-label="Fit the journey" title="Fit the journey">
          <Maximize aria-hidden="true" />
        </button>
        <button type="button" onClick={() => zoomAt(1.2)} aria-label="Zoom in" title="Zoom in">
          <Plus aria-hidden="true" />
        </button>
        <span className="edp-zoom" aria-live="polite">
          {Math.round(k * 100)}%
        </span>
        <button type="button" onClick={() => zoomAt(1 / 1.2)} aria-label="Zoom out" title="Zoom out">
          <Minus aria-hidden="true" />
        </button>
        <button
          type="button"
          onClick={() => shape.focus && centreOn(shape.focus.x, shape.focus.y)}
          disabled={!shape.focus}
          aria-label="Show where the investigation is"
          title="Show where the investigation is"
        >
          <Crosshair aria-hidden="true" />
        </button>
      </div>
    </div>
  );
}
