// The card of a kind whose own card is not written yet: the frame and every field.
import { Card, type CardActions } from "./Card.tsx";

export interface CardProps extends CardActions {
  kind: string;
  id: string;
  record: unknown;
}

function pick(record: unknown, field: string): unknown {
  return record && typeof record === "object" ? (record as Record<string, unknown>)[field] : undefined;
}

export function GenericCard({ kind, id, record, ...actions }: CardProps) {
  const version = pick(record, "version");
  return <Card kind={kind} id={id} version={typeof version === "number" ? version : null} record={record} {...actions} />;
}
