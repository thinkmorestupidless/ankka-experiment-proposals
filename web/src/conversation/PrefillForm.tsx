// A wish the assistant turned into a decision's form, its fields set as the answer named them. The
// decision is the reviewer's: they choose who they are, may change any field, and submit it
// themselves; until then nothing is recorded.
import { useLoad } from "../cards/useLoad.ts";
import { getApproaches, getDesign, getFacets } from "../backend/records.ts";
import type { Prefill } from "../backend/types.ts";
import { tag } from "../backend/types.ts";
import { Approval, outcomeOf } from "../decisions/Approval.tsx";
import { RecordReview } from "../decisions/RecordReview.tsx";
import { FacetsConfirmed } from "../decisions/FacetsConfirmed.tsx";
import { FacetReview } from "../decisions/FacetReview.tsx";
import { Decide } from "../decisions/Decide.tsx";

function Body({ prefill }: { prefill: Prefill }) {
  const f = prefill.fields;
  const target = prefill.target;
  const design = useLoad(() => (prefill.decision === "approval" ? getDesign(target) : Promise.resolve(undefined)), [target]);
  const facets = useLoad(
    () => (prefill.decision === "facet-review" ? getFacets(target.slice(0, target.lastIndexOf(":"))) : Promise.resolve(undefined)),
    [target],
  );
  const approaches = useLoad(() => (prefill.decision === "decide" ? getApproaches(target) : Promise.resolve(undefined)), [target]);
  switch (prefill.decision) {
    case "approval": {
      const versions = design.value?.versions ?? [];
      const latest = versions[versions.length - 1];
      const version = Number(f.version) || latest?.version || 1;
      if (design.error) return <p className="edp-refusal" role="alert">{design.error}</p>;
      if (latest && tag(latest.status) !== "under_review")
        return <p className="ui-hint">Version {latest.version} of this design is {tag(latest.status).replace(/_/g, " ")}; there is nothing to decide.</p>;
      return <Approval designId={target} version={version} initial={{ decision: outcomeOf[f.outcome ?? f.decision ?? ""], rationale: f.rationale }} />;
    }
    case "record-review": {
      const kind = /^rq-/.test(target) ? "assessment" : /:cl[^:]*$/.test(target) ? "claim" : "finding";
      return <RecordReview kind={kind} recordId={target} open initial={{ action: f.action, rationale: f.rationale }} />;
    }
    case "facets-confirmed":
      return <FacetsConfirmed requestId={target} />;
    case "facet-review": {
      const facet = facets.value?.find((x) => x.id === target);
      return facet ? <FacetReview facet={facet} /> : <p className="ui-hint">Reading the facet…</p>;
    }
    case "decide":
      return approaches.value ? (
        <Decide requestId={target} approaches={approaches.value.approaches ?? []} />
      ) : (
        <p className="ui-hint">Reading the candidates…</p>
      );
    default:
      return <p className="ui-hint">The answer asked for a decision this interface does not know: {prefill.decision}.</p>;
  }
}

export function PrefillForm({ prefill }: { prefill: Prefill }) {
  return (
    <div className="ui-card edp-prefill" data-prefill={prefill.decision} data-target={prefill.target}>
      <p className="edp-prefill-note">
        <strong>A reviewer decides this.</strong> The form is filled in as asked; nothing is recorded until a configured reviewer submits it.
      </p>
      <Body prefill={prefill} />
    </div>
  );
}
