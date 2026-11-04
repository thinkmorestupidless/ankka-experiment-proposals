// One labelled value in a card's facts. A list is written one item a line; nothing is "none".
import type { ReactNode } from "react";

export function Facts({ children }: { children: ReactNode }) {
  return <dl className="ac-facts">{children}</dl>;
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  const empty = children === null || children === undefined || children === "" || (Array.isArray(children) && children.length === 0);
  return (
    <div>
      <dt>{label}</dt>
      <dd>{empty ? <span className="ac-hint">none</span> : children}</dd>
    </div>
  );
}

export function List({ items }: { items?: readonly string[] | null }) {
  if (!items || items.length === 0) return <span className="ac-hint">none</span>;
  return (
    <ul className="edp-list">
      {items.map((i, n) => (
        <li key={n}>{i}</li>
      ))}
    </ul>
  );
}
