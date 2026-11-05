// The candidate approaches side by side: one column per approach, one row per comparison criterion,
// then what each rests on (supporting, contrary, indirect and missing evidence). No probability of
// success is shown anywhere, and the type below fails the build if an approach ever gains one.
import type { ApproachesView, ExperimentalApproach, Ref } from "../backend/types.ts";
import { tag } from "../backend/types.ts";
import { Card } from "./Card.tsx";
import type { CardProps } from "./GenericCard.tsx";
import { Decide } from "../decisions/Decide.tsx";

/** The declared fields of a type, without its index signature. */
type Declared<T> = keyof { [K in keyof T as string extends K ? never : number extends K ? never : K]: T[K] };
type Probabilistic = Extract<Declared<ExperimentalApproach>, `${string}robabilit${string}` | `${string}uccessChance${string}` | `${string}ikelihood${string}`>;
/** Fails to typecheck the day `ExperimentalApproach` declares a probability. */
export const noProbability: [Probabilistic] extends [never] ? true : never = true;

const hidden = /probabilit|successchance|likelihood/i;

/** The approach as shown: any field naming a probability is dropped before it reaches the page. */
export function withoutProbability<T extends Record<string, unknown>>(a: T): T {
  return Object.fromEntries(Object.entries(a).filter(([k]) => !hidden.test(k))) as T;
}

const refs = (a: ExperimentalApproach, field: string): Ref[] => (Array.isArray(a[field]) ? (a[field] as Ref[]) : []);

const rows: { label: string; field: keyof ExperimentalApproach | string }[] = [
  { label: "Hypothesis", field: "hypothesis" },
  { label: "Biological model", field: "biologicalModel" },
  { label: "Rationale", field: "rationale" },
  { label: "Status", field: "status" },
];

export function CandidatesCard({ id, record, onOpen, onShowStage, onChanged }: CardProps) {
  const view = record as ApproachesView;
  const approaches = (view.approaches ?? []).map((a) => withoutProbability(a));
  const criteria = view.comparison?.criteria ?? [];
  const shown = { ...view, approaches };
  return (
    <Card kind="candidates" id={id} record={shown} onOpen={onOpen} onShowStage={onShowStage}>
      <div className="edp-table-scroll" tabIndex={0} role="region" aria-label="The candidate approaches compared">
        <table className="edp-table edp-compare">
          <caption className="ui-visually-hidden">The candidate approaches compared on every criterion</caption>
          <thead>
            <tr>
              <th scope="col">Criterion</th>
              {approaches.map((a) => (
                <th key={a.id} scope="col" data-approach={a.id}>
                  {a.title}
                  <div className="ui-hint">
                    <code>{a.id}</code>
                  </div>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.label}>
                <th scope="row">{r.label}</th>
                {approaches.map((a) => (
                  <td key={a.id}>{tag(a[r.field as string])}</td>
                ))}
              </tr>
            ))}
            {criteria.map((c) => (
              <tr key={c.name} data-criterion={c.name}>
                <th scope="row">{c.name.replaceAll("_", " ")}</th>
                {approaches.map((a) => {
                  const cell = c.perApproach[a.id];
                  return (
                    <td key={a.id}>
                      {cell ? (
                        <>
                          <strong>{cell.rating}</strong>
                          <div className="edp-rationale">{cell.rationale}</div>
                        </>
                      ) : (
                        <span className="ui-hint">not rated</span>
                      )}
                    </td>
                  );
                })}
              </tr>
            ))}
            {(
              [
                ["Supporting evidence", "supportingClaimRefs"],
                ["Contrary evidence", "contraryClaimRefs"],
                ["Indirect evidence", "indirectClaimRefs"],
              ] as const
            ).map(([label, field]) => (
              <tr key={field} data-evidence={field}>
                <th scope="row">{label}</th>
                {approaches.map((a) => {
                  const list = refs(a, field);
                  return (
                    <td key={a.id}>
                      {list.length === 0 ? (
                        <span className="ui-hint">none</span>
                      ) : (
                        <ul className="edp-list">
                          {list.map((r) => (
                            <li key={r.id}>
                              {tag(r.kind)} {r.id}
                            </li>
                          ))}
                        </ul>
                      )}
                    </td>
                  );
                })}
              </tr>
            ))}
            <tr data-evidence="missingEvidence">
              <th scope="row">Missing evidence</th>
              {approaches.map((a) => (
                <td key={a.id}>
                  {(a.missingEvidence ?? []).length === 0 ? (
                    <span className="ui-hint">none</span>
                  ) : (
                    <ul className="edp-list">
                      {(a.missingEvidence ?? []).map((m, n) => (
                        <li key={n}>{m}</li>
                      ))}
                    </ul>
                  )}
                </td>
              ))}
            </tr>
          </tbody>
        </table>
      </div>
      {view.decision ? (
        <p className="ui-hint">
          Decided by {view.decision.decidedBy}: {view.decision.selectedApproachRef?.id} selected.
        </p>
      ) : (
        <Decide requestId={id} approaches={approaches} onDone={onChanged} />
      )}
    </Card>
  );
}
