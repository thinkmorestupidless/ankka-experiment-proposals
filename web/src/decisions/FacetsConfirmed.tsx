// The confirmation of the facets as a set (`POST /requests/{id}/facets:confirmed`); the route refuses
// while a mandatory facet is unsettled and names it, and the refusal is shown as it was given.
import { confirmFacets } from "../backend/records.ts";
import { DecisionForm } from "./Form.tsx";

export function FacetsConfirmed({ requestId, onDone }: { requestId: string; onDone?: () => void }) {
  return (
    <DecisionForm
      title="Confirm the facets"
      submitLabel="Confirm the facets"
      rationale={false}
      ownReviewer={false}
      onDone={onDone}
      onSubmit={(reviewer) => confirmFacets(requestId, reviewer)}
      className="edp-facets-confirmed"
    />
  );
}
