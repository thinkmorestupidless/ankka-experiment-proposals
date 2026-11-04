// Which records a selected node shows (contracts/http-api.md, the table by card), fetched; and
// whether the stage is running with nothing recorded yet. A pause names no stage run, so a pause
// node shows the card of the thing decided, with its form: the facets, the candidates or the design,
// and the decision's own record once it is made. Every node with runs also shows its stage run.
import type { Journey, QuestionView, StageNode, StageRunRef } from "../backend/types.ts";
import * as api from "../backend/records.ts";
import { fetchRecord } from "../cards/index.ts";
import type { Selection } from "./Canvas.tsx";
import { shownState } from "./Node.tsx";

export interface CardSpec {
  kind: string;
  id: string;
  record: unknown;
  /** For a stage-run card: the stages of the node, whose executions it shows. */
  stages?: string[];
}

export interface StageCards {
  node?: StageNode;
  cards: CardSpec[];
  /** The stage is running and has recorded nothing yet. */
  running: boolean;
  runs: StageRunRef[];
  /** The records are still being read. */
  loading?: boolean;
}

/** What the inspector shows at once for a node, before its records are read. */
export function pending(journey: Journey, sel: Selection): StageCards {
  const node = nodeOf(journey, sel);
  return { node, cards: [], running: false, runs: node?.runs ?? [], loading: true };
}

type Want = { kind: string; id: string };

export function nodeOf(journey: Journey, sel: Selection): StageNode | undefined {
  if (!sel.question) return journey.spine.find((n) => n.stage === sel.stage);
  return journey.questions.find((q) => q.questionId === sel.question)?.nodes.find((n) => n.stage === sel.stage);
}

const recordKind = (id: string) => (/:cl[^:]*$/.test(id) ? "claim" : "finding");

async function wanted(journey: Journey, sel: Selection): Promise<Want[]> {
  const rid = journey.requestId;
  const q = sel.question;
  if (!q) {
    switch (sel.stage) {
      case "request":
        return [{ kind: "request", id: rid }];
      case "facets":
      case "round-trip":
      case "facet-review":
        return [{ kind: "facets", id: rid }];
      case "questions":
        return journey.questions.map((x) => ({ kind: "question", id: x.questionId }));
      case "packages":
        return journey.questions.map((x) => ({ kind: "package", id: `${x.questionId}:pk1` }));
      case "candidates":
        return [{ kind: "candidates", id: rid }];
      case "decision": {
        const view = await api.getApproaches(rid).catch(() => undefined);
        return view?.decision ? [{ kind: "decision", id: rid }] : [{ kind: "candidates", id: rid }];
      }
      case "design":
      case "approval":
        return [{ kind: "design", id: `${rid}:design` }];
      case "impact":
        return [{ kind: "impact", id: rid }];
      default:
        return [];
    }
  }
  const plan = `${q}:plan`;
  switch (sel.stage) {
    case "search-plan":
      return [{ kind: "search-plan", id: plan }];
    case "executions":
    case "eligibility": {
      const p = await api.getPlan(plan).catch(() => undefined);
      const ids = p?.executionIds ?? [];
      // The search's card is its plan's queries and each execution's results.
      if (sel.stage === "executions") return [{ kind: "search-plan", id: plan }, ...ids.map((id) => ({ kind: "search-execution", id }))];
      const executions = await Promise.all(ids.map((id) => api.getExecution(id).catch(() => undefined)));
      return executions.filter((e) => e && (e.eligibilityApplied || e.fused)).map((e) => ({ kind: "search-execution", id: e!.id }));
    }
    case "verification":
    case "assessment":
    case "sufficiency": {
      const view: QuestionView | undefined = await api.getQuestion(q).catch(() => undefined);
      if (!view) return [];
      if (sel.stage === "assessment") return view.assessments.map((a) => ({ kind: "assessment", id: a.id }));
      if (sel.stage === "sufficiency") return view.sufficiency.map((s) => ({ kind: "sufficiency", id: s.id }));
      const inv = (view.investigation ?? {}) as { recordIds?: string[] };
      const ids = inv.recordIds ?? view.assessments.map((a) => a.recordRef?.id ?? "").filter(Boolean);
      return ids.map((id) => ({ kind: recordKind(id), id }));
    }
    default:
      return [];
  }
}

export async function cardsFor(journey: Journey, sel: Selection): Promise<StageCards> {
  const node = nodeOf(journey, sel);
  const runs = node?.runs ?? [];
  const wants = node && node.state !== "unreached" ? await wanted(journey, sel) : [];
  const fetched = await Promise.all(
    wants.map(async (w) => {
      try {
        return { ...w, record: await fetchRecord(w.kind, w.id) } as CardSpec;
      } catch {
        return undefined;
      }
    }),
  );
  // An empty answer (no facets yet) is nothing recorded, not a card.
  const cards = fetched.filter((c): c is CardSpec => c !== undefined && !(Array.isArray(c.record) && c.record.length === 0));
  const runIds = [...new Set(runs.map((r) => r.runId))];
  const stages = [...new Set(runs.map((r) => r.stage))];
  for (const runId of runIds) {
    try {
      cards.push({ kind: "stage-run", id: runId, record: await api.getRun(runId), stages });
    } catch {
      // a run not readable yet is shown by the node's runs below
    }
  }
  const running = node !== undefined && shownState(node).state === "current" && cards.every((c) => c.kind === "stage-run");
  return { node, cards, running, runs };
}
