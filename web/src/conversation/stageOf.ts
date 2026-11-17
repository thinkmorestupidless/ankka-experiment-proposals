// The stage of the journey a cited record belongs to, so its card can select that node: the
// request's own records on the spine, a question's in its lane (the lane is the question's id, which
// a question's records' ids begin with). A record that is no request's (a source, a finding as
// extracted) has no stage.
import type { Selection } from "../journey/Canvas.tsx";

const questionOf = (id: string) => /^(rq-[^:]+:q\d+)/.exec(id)?.[1];

export function stageOf(kind: string, id: string): { requestId: string; selection: Selection } | undefined {
  const requestId = /^(rq-[^:]+)/.exec(id)?.[1];
  if (!requestId) return undefined;
  const q = questionOf(id);
  const spine = (stage: string) => ({ requestId, selection: { stage } });
  const lane = (stage: string) => (q ? { requestId, selection: { stage, question: q } } : undefined);
  switch (kind) {
    case "request":
      return spine("request");
    case "facets":
      return spine("facet-review");
    case "question":
      return spine("questions");
    case "search-plan":
      return lane("search-plan");
    case "search-execution":
      return lane("executions");
    case "assessment":
      return lane("assessment");
    case "sufficiency":
      return lane("sufficiency");
    case "package":
      return spine("packages");
    case "candidates":
      return spine("candidates");
    case "decision":
      return spine("decision");
    case "design":
      return spine("approval");
    case "impact":
      return spine("impact");
    default:
      return undefined;
  }
}
