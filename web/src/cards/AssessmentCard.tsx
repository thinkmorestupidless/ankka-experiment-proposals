// An assessment: the applicability (direct, or indirect by a rule), the relationship, the rationale,
// the rules that decided it, and the record and question it links; the review form.
import type { EvidenceAssessment } from "../backend/types.ts";
import { tag } from "../backend/types.ts";
import { Card } from "./Card.tsx";
import { Facts, Field, List } from "./Field.tsx";
import type { CardProps } from "./GenericCard.tsx";
import { RecordReview } from "../decisions/RecordReview.tsx";

export function AssessmentCard({ id, record, onOpen, onShowStage, onChanged }: CardProps) {
  const a = record as EvidenceAssessment;
  return (
    <Card kind="assessment" id={id} version={a.version} record={record} onOpen={onOpen} onShowStage={onShowStage}>
      <Facts>
        <Field label="Applicability">{tag(a.applicability)}</Field>
        <Field label="Relationship">{tag(a.relationship)}</Field>
        <Field label="Record">{a.recordRef ? `${tag(a.recordRef.kind)} ${a.recordRef.id} version ${a.recordRef.version}` : null}</Field>
        <Field label="Question">{a.questionRef?.id}</Field>
        <Field label="Rationale">
          <span className="edp-rationale">{a.rationale}</span>
        </Field>
        <Field label="Dimensions">
          <List items={Object.entries(a.dimensions ?? {}).map(([k, v]) => `${k}: ${tag(v)}`)} />
        </Field>
        <Field label="Rules">
          <List items={(a.ruleResults ?? []).map((r) => `${r.ruleId}: ${tag(r.outcome)}, ${r.detail}`)} />
        </Field>
        <Field label="Limitations">
          <List items={a.limitations} />
        </Field>
        <Field label="Assessor">{a.assessor}</Field>
        <Field label="Review status">{tag(a.reviewStatus)}</Field>
      </Facts>
      <RecordReview kind="assessment" recordId={id} open={false} onDone={onChanged} />
    </Card>
  );
}
