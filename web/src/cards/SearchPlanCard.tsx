// A search plan: its lexical and semantic queries, the escalation and the stopping criterion.
import type { Query, SearchPlan } from "../backend/types.ts";
import { tag } from "../backend/types.ts";
import { Card } from "./Card.tsx";
import { Facts, Field, List } from "./Field.tsx";
import type { CardProps } from "./GenericCard.tsx";

function Queries({ title, kind, queries }: { title: string; kind: string; queries: Query[] }) {
  const mine = queries.filter((q) => tag(q.kind) === kind);
  return (
    <div className="edp-queries" data-query-kind={kind}>
      <h4>{title}</h4>
      {mine.length === 0 ? (
        <p className="ui-hint">none</p>
      ) : (
        <ul className="edp-list">
          {mine.map((q, n) => (
            <li key={n} data-query={q.text}>
              {q.text}
              {q.expansionOf ? <span className="ui-hint"> (expands “{q.expansionOf}”)</span> : null}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function SearchPlanCard({ id, record, onOpen, onShowStage }: CardProps) {
  const p = record as SearchPlan;
  const queries = p.queries ?? [];
  return (
    <Card kind="search-plan" id={id} version={p.version} record={record} onOpen={onOpen} onShowStage={onShowStage}>
      <Queries title="Lexical queries" kind="lexical" queries={queries} />
      <Queries title="Semantic queries" kind="semantic" queries={queries} />
      <Facts>
        <Field label="Target sources">
          <List items={p.targetSources} />
        </Field>
        <Field label="Stopping criterion">{p.stoppingCriterion}</Field>
        <Field label="Escalation">{tag(p.escalation)}</Field>
        <Field label="Executions">
          <List items={p.executionIds} />
        </Field>
        <Field label="Planned by">{p.plannedBy}</Field>
      </Facts>
    </Card>
  );
}
