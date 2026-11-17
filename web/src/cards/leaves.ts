// Every scalar leaf of a record, with the path that reaches it, so a card can show all of them
// (SC-003: every scalar the API returns appears in the card's text).
export type Scalar = string | number | boolean | null;

export interface Leaf {
  path: string;
  value: Scalar;
}

export function leaves(value: unknown, path = ""): Leaf[] {
  if (value === null || value === undefined) return path ? [{ path, value: null }] : [];
  if (typeof value !== "object") return [{ path, value: value as Scalar }];
  if (Array.isArray(value)) return value.flatMap((v, i) => leaves(v, `${path}[${i}]`));
  return Object.entries(value as Record<string, unknown>).flatMap(([k, v]) => leaves(v, path ? `${path}.${k}` : k));
}

/** A leaf's value as the card writes it. */
export function show(value: Scalar): string {
  if (value === null) return "none";
  if (typeof value === "boolean") return value ? "yes" : "no";
  return String(value);
}
