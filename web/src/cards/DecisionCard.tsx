// The decision: the approach selected, each rejected one with its reason, who decided, when and why.
import type { DecisionRecord } from "../backend/types.ts";
import { Card } from "./Card.tsx";
import { Facts, Field } from "./Field.tsx";
import type { CardProps } from "./GenericCard.tsx";

export function DecisionCard({ id, record, onOpen, onShowStage }: CardProps) {
  const d = record as DecisionRecord;
  return (
    <Card kind="decision" id={d.id ?? id} version={d.version} time={d.decidedAt} record={record} onOpen={onOpen} onShowStage={onShowStage}>
      <Facts>
        <Field label="Selected">{d.selectedApproachRef?.id}</Field>
        <Field label="Rejected">
          {(d.rejected ?? []).length === 0 ? null : (
            <ul className="edp-list">
              {(d.rejected ?? []).map((r) => (
                <li key={r.approachRef.id} data-rejected={r.approachRef.id}>
                  {r.approachRef.id}: {r.reason}
                </li>
              ))}
            </ul>
          )}
        </Field>
        <Field label="Decided by">{d.decidedBy}</Field>
        <Field label="Decided at">{d.decidedAt}</Field>
        <Field label="Rationale">{d.rationale}</Field>
      </Facts>
    </Card>
  );
}
