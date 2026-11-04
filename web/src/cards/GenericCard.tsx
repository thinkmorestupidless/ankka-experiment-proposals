// The card of a kind whose own card is not written yet: the frame and every field.
import { Card, type CardActions } from "./Card.tsx";

export interface CardProps extends CardActions {
  kind: string;
  id: string;
  record: unknown;
  /** Called after a decision on the card is recorded, so the card is fetched again. */
  onChanged?: () => void;
  /** Changes on each journey event, for a card that reads more than its record. */
  revision?: number;
  /** The node the card is shown for, when it is shown for one. */
  stages?: string[];
}

function pick(record: unknown, field: string): unknown {
  return record && typeof record === "object" ? (record as Record<string, unknown>)[field] : undefined;
}

export function GenericCard({ kind, id, record, onOpen, onShowStage }: CardProps) {
  const actions = { onOpen, onShowStage };
  const version = pick(record, "version");
  return <Card kind={kind} id={id} version={typeof version === "number" ? version : null} record={record} {...actions} />;
}
