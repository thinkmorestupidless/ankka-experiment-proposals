// A scientific question: its text, the facets it investigates, its status and what it needs.
import type { QuestionView } from "../backend/types.ts";
import { tag } from "../backend/types.ts";
import { Card } from "./Card.tsx";
import { Facts, Field, List } from "./Field.tsx";
import type { CardProps } from "./GenericCard.tsx";

export function QuestionCard({ id, record, onOpen, onShowStage }: CardProps) {
  const view = record as QuestionView;
  const q = view.question ?? { id };
  const investigation = view.investigation as { status?: string } | null | undefined;
  return (
    <Card kind="question" id={id} version={q.version} record={record} onOpen={onOpen} onShowStage={onShowStage}>
      <p className="edp-question-text">{q.question}</p>
      <Facts>
        <Field label="Facets investigated">
          <List items={q.facetNames} />
        </Field>
        <Field label="Status">{tag(q.status)}</Field>
        <Field label="Investigation">{investigation?.status ? tag(investigation.status) : null}</Field>
        <Field label="Evidence needed">
          <List items={q.evidenceNeeded} />
        </Field>
        <Field label="Sufficiency criteria">
          <List items={q.sufficiencyCriteria} />
        </Field>
        <Field label="Missing information">
          <List items={q.missingInformation} />
        </Field>
        <Field label="Knowledge gaps">
          <List items={view.gaps?.map((g) => `${g.description ?? g.id} (${tag(g.status)})`)} />
        </Field>
      </Facts>
    </Card>
  );
}
