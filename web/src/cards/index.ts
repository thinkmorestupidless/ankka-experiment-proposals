// Each kind of record and the component that draws its card, and how its record is fetched.
// Kinds whose own card comes with a later story draw the generic card, which shows every field.
import type { ComponentType } from "react";
import type { CardKind } from "../backend/types.ts";
import * as api from "../backend/records.ts";
import { GenericCard, type CardProps } from "./GenericCard.tsx";

export type { CardProps };

export const cards: Record<CardKind, ComponentType<CardProps>> = {
  request: GenericCard,
  facets: GenericCard,
  question: GenericCard,
  "search-plan": GenericCard,
  "search-execution": GenericCard,
  source: GenericCard,
  finding: GenericCard,
  claim: GenericCard,
  assessment: GenericCard,
  sufficiency: GenericCard,
  package: GenericCard,
  candidates: GenericCard,
  decision: GenericCard,
  design: GenericCard,
  impact: GenericCard,
  "stage-run": GenericCard,
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
    case "impact": {
      // The request's impact: the flags on its design, each naming the changed source and version.
      const design = await api.getDesign(`${id}:design`);
      return { requestId: id, flags: design.flags ?? [] };
    }
    case "stage-run":
      return api.getRun(id);
    default:
      throw new Error(`no card for the kind '${kind}'`);
  }
}
