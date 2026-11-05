// An evidence package: the records it includes, what it excluded and why, the gaps it carries, its
// checks and its quality status, and the passages it carries.
import type { PackageView } from "../backend/types.ts";
import { tag } from "../backend/types.ts";
import { Card } from "./Card.tsx";
import { Facts, Field, List } from "./Field.tsx";
import type { CardProps } from "./GenericCard.tsx";
import { Passage } from "./Passage.tsx";

export function PackageCard({ id, record, onOpen, onShowStage }: CardProps) {
  const view = record as PackageView;
  const p = view.package ?? { id };
  return (
    <Card kind="package" id={id} version={p.version} record={record} onOpen={onOpen} onShowStage={onShowStage}>
      <Facts>
        <Field label="Quality">{tag(p.qualityStatus)}</Field>
        <Field label="Records included">
          <List items={(p.recordRefs ?? []).map((r) => `${tag(r.kind)} ${r.id} version ${r.version}`)} />
        </Field>
        <Field label="Assessments">
          <List items={(p.assessmentRefs ?? []).map((r) => r.id)} />
        </Field>
        <Field label="Contradicting">
          <List items={(p.contradictoryAssessmentRefs ?? []).map((r) => r.id)} />
        </Field>
        <Field label="Excluded">
          <List items={(p.excluded ?? []).map((x) => `${x.ref.id}: ${x.reason}`)} />
        </Field>
        <Field label="Gaps carried">
          <List items={p.gapIds} />
        </Field>
        <Field label="Open questions">
          <List items={p.openQuestionIds} />
        </Field>
        <Field label="Checks">
          <List items={(p.checks ?? []).map((c) => `${c.name}: ${c.passed ? "passed" : "failed"}, ${c.detail}`)} />
        </Field>
        <Field label="Built by">{p.builtBy}</Field>
      </Facts>
      {(view.passages ?? []).length > 0 ? (
        <details className="ui-more">
          <summary>Passages ({view.passages.length})</summary>
          {view.passages.map((s, n) => (
            <Passage key={n} view={s} />
          ))}
        </details>
      ) : null}
    </Card>
  );
}
