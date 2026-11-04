// The facets of a request: each with its origin, importance, status, its quotation from the request
// and its history; the round-trip check's result and its differences; and, while the facets are not
// yet confirmed, the review form for each facet and the confirmation of the set.
import type { Facet, Issue, RequestView } from "../backend/types.ts";
import { tag } from "../backend/types.ts";
import { getRequest } from "../backend/records.ts";
import { Card } from "./Card.tsx";
import { Facts, Field } from "./Field.tsx";
import type { CardProps } from "./GenericCard.tsx";
import { useLoad } from "./useLoad.ts";
import { FacetReview } from "../decisions/FacetReview.tsx";
import { FacetsConfirmed } from "../decisions/FacetsConfirmed.tsx";
import { ReviewerSelect } from "../decisions/Reviewer.tsx";

function Issues({ title, items }: { title: string; items: Issue[] }) {
  return (
    <div className="edp-issues" data-issues={title}>
      <h5>{title}</h5>
      {items.length === 0 ? (
        <p className="ac-hint">none</p>
      ) : (
        <ul className="edp-list">
          {items.map((i, n) => (
            <li key={n}>
              {i.description}
              {i.facetName ? ` (facet ${i.facetName})` : ""}
              {i.span ? `: “${i.span}”` : ""}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function FacetsCard({ id, record, onOpen, onShowStage, onChanged, revision }: CardProps) {
  const facets = (record as Facet[]) ?? [];
  const { value: view } = useLoad<RequestView>(() => getRequest(id), [id, revision, record]);
  const request = view?.request;
  const text = request?.text ?? "";
  const rt = request?.roundTrip;
  const open = view !== undefined && !request?.confirmedBy;
  return (
    <Card kind="facets" id={id} record={record} onOpen={onOpen} onShowStage={onShowStage}>
      <p className="ac-hint">{facets.length} facets</p>
      {open ? <ReviewerSelect /> : null}
      <ol className="edp-facets">
        {facets.map((f) => {
          const origin = tag(f.origin);
          return (
            <li
              key={f.id}
              className="edp-facet"
              data-facet={f.id}
              data-name={f.name}
              data-origin={origin}
              data-importance={tag(f.importance)}
              data-status={tag(f.status)}
            >
              <h4>
                {f.name} <code className="edp-card-id">{f.id}</code>
              </h4>
              <p>{f.statement}</p>
              <Facts>
                <Field label="Origin">{origin}</Field>
                <Field label="Importance">{tag(f.importance)}</Field>
                <Field label="Status">{tag(f.status)}</Field>
                {f.assumedValue ? <Field label="Assumed value">{f.assumedValue}</Field> : null}
              </Facts>
              {(f.requestSpans ?? []).map((s, n) => (
                <figure key={n} className="edp-passage edp-facet-quote" data-span={`${s.start}-${s.end}`}>
                  <blockquote>
                    <p>{s.quotation}</p>
                  </blockquote>
                  <figcaption className="ac-hint">
                    the customer request, characters {s.start} to {s.end}
                    {text && text.slice(s.start, s.end) === s.quotation ? "" : text ? ": does not match the request's text" : ""}
                  </figcaption>
                </figure>
              ))}
              {(f.reviews ?? []).length > 0 ? (
                <div className="edp-history">
                  <h5>History</h5>
                  <ul className="edp-list">
                    {(f.reviews ?? []).map((r, n) => (
                      <li key={n} data-reviewer={r.reviewer}>
                        {tag(r.action)} by {r.reviewer} at <time dateTime={r.at}>{r.at}</time>: {r.rationale}
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}
              {open ? <FacetReview facet={f} onDone={onChanged} /> : null}
            </li>
          );
        })}
      </ol>
      <section className="edp-roundtrip" aria-label="The round-trip check">
        <h4>Round-trip check</h4>
        {rt ? (
          <>
            <Facts>
              <Field label="Result">{rt.passed ? "passed" : "failed"}</Field>
              <Field label="Reconstruction">{rt.check.reconstruction}</Field>
              <Field label="Run">{String(rt.run)}</Field>
            </Facts>
            <Issues title="Omissions" items={rt.check.omissions} />
            <Issues title="Unsupported additions" items={rt.check.unsupportedAdditions} />
            <Issues title="Contradictions" items={rt.check.contradictions} />
            <Issues title="Ambiguities" items={rt.check.ambiguities} />
          </>
        ) : (
          <p className="ac-hint">The round-trip check has not run.</p>
        )}
      </section>
      {open ? <FacetsConfirmed requestId={id} onDone={onChanged} /> : null}
    </Card>
  );
}
