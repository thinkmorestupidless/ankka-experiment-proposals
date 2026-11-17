// One labelled value in a card's facts. A list is written one item a line; nothing is "none".
import { isValidElement, type ReactNode } from "react";
import { tag } from "../backend/types.ts";

export function Facts({ children }: { children: ReactNode }) {
  return <dl className="ui-facts">{children}</dl>;
}

export function Field({ label, children: given }: { label: string; children: ReactNode }) {
  // An enumeration the service sent as `{"type": ...}` is written as its value.
  const children =
    given && typeof given === "object" && !Array.isArray(given) && !isValidElement(given) && "type" in (given as object) ? tag(given) : given;
  const empty = children === null || children === undefined || children === "" || (Array.isArray(children) && children.length === 0);
  return (
    <div>
      <dt>{label}</dt>
      <dd>{empty ? <span className="ui-hint">none</span> : children}</dd>
    </div>
  );
}

export function List({ items }: { items?: readonly string[] | null }) {
  if (!items || items.length === 0) return <span className="ui-hint">none</span>;
  return (
    <ul className="edp-list">
      {items.map((i, n) => (
        <li key={n}>{i}</li>
      ))}
    </ul>
  );
}
