// A stage run: each stage execution with its stage, attempt, status, actor, the inputs it read, the
// output it produced, the checks it ran and its defect. A node shows only its own stages' executions.
import type { StageExecution, StageRun } from "../backend/types.ts";
import { tag } from "../backend/types.ts";
import { Card } from "./Card.tsx";
import { Facts, Field, List } from "./Field.tsx";
import type { CardProps } from "./GenericCard.tsx";

export function StageRunCard({ id, record, onOpen, onShowStage, stages }: CardProps) {
  const run = record as StageRun;
  const shown = (run.stages ?? []).filter((s) => !stages || stages.includes(s.stage)) as (StageExecution & { actor?: string })[];
  return (
    <Card kind="stage-run" id={id} record={record} onOpen={onOpen} onShowStage={onShowStage}>
      <p className="ui-hint">
        {run.workflow}: {shown.length} execution{shown.length === 1 ? "" : "s"}
        {stages ? ` of ${stages.join(", ")}` : ""}
      </p>
      <ol className="edp-executions">
        {[...shown].reverse().map((s) => (
          <li key={`${s.stage}-${s.attempt}-${s.startedAt}`} data-run-stage={s.stage} data-attempt={s.attempt} data-status={tag(s.status)}>
            <h4>
              {s.stage}, attempt {s.attempt}: {tag(s.status)}
            </h4>
            <Facts>
              <Field label="Actor">{s.actor ?? null}</Field>
              <Field label="Started">{s.startedAt}</Field>
              <Field label="Ended">{s.endedAt ?? null}</Field>
              <Field label="Inputs read">
                <List items={s.inputRefs.map((r) => `${tag(r.kind)} ${r.id} version ${r.version}`)} />
              </Field>
              <Field label="Output">{s.outputRef ? `${tag(s.outputRef.kind)} ${s.outputRef.id} version ${s.outputRef.version}` : null}</Field>
              <Field label="Checks">
                <List items={s.validations.map((v) => `${v.gate}: ${v.passed ? "passed" : "failed"}${v.defects.length ? `, ${v.defects.map((d) => tag(d.kind)).join(", ")}` : ""}`)} />
              </Field>
              <Field label="Model calls">
                <List items={s.modelCalls.map((m) => `${m.promptVersion} on ${m.model}: ${m.inputTokens} in, ${m.outputTokens} out${m.refused ? ", refused" : ""}`)} />
              </Field>
              <Field label="Defect">{s.defect ? `${tag(s.defect.kind)} (${tag(s.defect.severity)}): ${s.defect.detail}` : null}</Field>
            </Facts>
          </li>
        ))}
      </ol>
    </Card>
  );
}
