// The request: its text verbatim, who submitted it and when, and its status.
import type { RequestView } from "../backend/types.ts";
import { tag } from "../backend/types.ts";
import { Card } from "./Card.tsx";
import { Facts, Field } from "./Field.tsx";
import type { CardProps } from "./GenericCard.tsx";

export function RequestCard({ id, record, onOpen, onShowStage }: CardProps) {
  const view = record as RequestView;
  const r = view.request ?? { id };
  return (
    <Card kind="request" id={id} time={r.submittedAt} record={record} onOpen={onOpen} onShowStage={onShowStage}>
      <blockquote className="edp-request-text">
        <p>{r.text}</p>
      </blockquote>
      <Facts>
        <Field label="Submitted by">{r.submittedBy}</Field>
        <Field label="Submitted at">{r.submittedAt}</Field>
        <Field label="Status">{tag(r.status)}</Field>
        <Field label="Facets">{String(r.facets?.length ?? 0)}</Field>
        <Field label="Scientific questions">{String(r.questionIds?.length ?? 0)}</Field>
        <Field label="Round-trip check">{r.roundTrip ? (r.roundTrip.passed ? "passed" : "failed") : null}</Field>
        <Field label="Facets confirmed by">{r.confirmedBy}</Field>
      </Facts>
    </Card>
  );
}
