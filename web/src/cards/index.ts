// Each kind of record and the component that draws its card, and how its record is fetched.
// A kind without a card of its own draws the generic card, which shows every field.
import type { ComponentType } from "react";
import type { CardKind } from "../backend/types.ts";
import * as api from "../backend/records.ts";
import { GenericCard, type CardProps } from "./GenericCard.tsx";
import { RequestCard } from "./RequestCard.tsx";
import { FacetsCard } from "./FacetsCard.tsx";
import { QuestionCard } from "./QuestionCard.tsx";
import { SearchPlanCard } from "./SearchPlanCard.tsx";
import { SearchExecutionCard } from "./SearchExecutionCard.tsx";
import { SourceCard } from "./SourceCard.tsx";
import { RecordCard } from "./RecordCard.tsx";
import { AssessmentCard } from "./AssessmentCard.tsx";
import { SufficiencyCard } from "./SufficiencyCard.tsx";
import { PackageCard } from "./PackageCard.tsx";
import { CandidatesCard } from "./CandidatesCard.tsx";
import { DecisionCard } from "./DecisionCard.tsx";
import { DesignCard } from "./DesignCard.tsx";
import { ImpactCard, type RequestImpact } from "./ImpactCard.tsx";
import { StageRunCard } from "./StageRunCard.tsx";
import { tag } from "../backend/types.ts";

export type { CardProps };

export const cards: Record<CardKind, ComponentType<CardProps>> = {
  request: RequestCard,
  facets: FacetsCard,
  question: QuestionCard,
  "search-plan": SearchPlanCard,
  "search-execution": SearchExecutionCard,
  source: SourceCard,
  finding: RecordCard,
  claim: RecordCard,
  assessment: AssessmentCard,
  sufficiency: SufficiencyCard,
  package: PackageCard,
  candidates: CandidatesCard,
  decision: DecisionCard,
  design: DesignCard,
  impact: ImpactCard,
  "stage-run": StageRunCard,
};

export function cardFor(kind: string): ComponentType<CardProps> {
  return (cards as Record<string, ComponentType<CardProps>>)[kind] ?? GenericCard;
}

/**
 * The record a card of `kind` shows, fetched from the service. For `facets`, `candidates`,
 * `decision` and `impact` the id is the request's (contracts/assistant.md); a sufficiency is read
 * from its question, which its id begins with.
 */
export async function fetchRecord(kind: string, id: string): Promise<unknown> {
  switch (kind) {
    case "request":
      return api.getRequest(id);
    case "facets":
      return api.getFacets(id);
    case "question":
      return api.getQuestion(id);
    case "search-plan":
      return api.getPlan(id);
    case "search-execution":
      return api.getExecution(id);
    case "source":
      return api.getSource(id);
    case "finding":
      return api.getFinding(id);
    case "claim":
      return api.getClaim(id);
    case "assessment":
      return api.getAssessment(id);
    case "sufficiency": {
      // `<question id>:<decision slug>` (SufficiencyEntity.idFor).
      const question = id.slice(0, id.lastIndexOf(":"));
      const view = await api.getQuestion(question);
      const found = view.sufficiency.find((s) => s.id === id);
      if (!found) throw new Error(`no sufficiency judgement '${id}'`);
      return found;
    }
    case "package":
      return api.getPackage(id);
    case "candidates":
      return api.getApproaches(id);
    case "decision": {
      const view = await api.getApproaches(id);
      if (!view.decision) throw new Error(`no decision for '${id}' yet`);
      return view.decision;
    }
    case "design":
      return api.getDesign(id);
    case "impact":
      return requestImpact(id);
    case "stage-run":
      return api.getRun(id);
    default:
      throw new Error(`no card for the kind '${kind}'`);
  }
}

/** Whether a dependent of a change belongs to the request: its own records, or the evidence it used. */
const ofRequest = (requestId: string, dependentId: string, kind: string) =>
  dependentId === requestId || dependentId.startsWith(`${requestId}:`) || kind === "finding" || kind === "claim";

/**
 * The request's impact: the flags on its design, and for each source change they name, every
 * dependent of that change (`GET /impact`) that is the request's own or the evidence it rests on.
 */
export async function requestImpact(requestId: string): Promise<RequestImpact> {
  let flags: RequestImpact["flags"] = [];
  try {
    flags = (await api.getDesign(`${requestId}:design`)).flags ?? [];
  } catch {
    // no design yet: nothing of the request is flagged through it
  }
  const refs = [...new Set(flags.map((f) => `${tag(f.changedRef.kind)}:${f.changedRef.id}:v${f.changedRef.version}`))];
  const changes = await Promise.all(
    refs.map(async (ref) => {
      const view = await api.getImpact(ref);
      return { ref, dependents: view.dependents.filter((d) => ofRequest(requestId, d.id, d.kind)) };
    }),
  );
  return { requestId, flags, changes };
}
