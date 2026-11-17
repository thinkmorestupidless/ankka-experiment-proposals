// A sufficiency judgement for one intended decision: its status, its critical gaps, its assumptions
// and the rationale.
import type { EvidenceSufficiency } from "../backend/types.ts";
import { tag } from "../backend/types.ts";
import { Card } from "./Card.tsx";
import { Facts, Field, List } from "./Field.tsx";
import type { CardProps } from "./GenericCard.tsx";

export function SufficiencyCard({ id, record, onOpen, onShowStage }: CardProps) {
  const s = record as EvidenceSufficiency;
  return (
    <Card kind="sufficiency" id={id} version={s.version} record={record} onOpen={onOpen} onShowStage={onShowStage}>
      <Facts>
        <Field label="Intended decision">{s.intendedDecision}</Field>
        <Field label="Status">{tag(s.status)}</Field>
        <Field label="Proposition">{s.proposition}</Field>
        <Field label="Critical gaps">
          <List items={s.criticalGapIds} />
        </Field>
        <Field label="Assumptions">
          <List items={s.assumptions} />
        </Field>
        <Field label="Coverage">{s.coverageJudgement}</Field>
        <Field label="Rationale">
          <span className="edp-rationale">{s.rationale}</span>
        </Field>
        <Field label="Escalation">{tag(s.escalation)}</Field>
        <Field label="Judged by">{s.judgedBy}</Field>
      </Facts>
    </Card>
  );
}
