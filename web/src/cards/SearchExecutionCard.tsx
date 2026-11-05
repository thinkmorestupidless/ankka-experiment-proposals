// A search execution: every result in rank order with its source, every exclusion with the rule that
// removed it, and the re-run, which says whether it returned the same identifiers.
import { useState } from "react";
import type { Hit, RerunView, SearchExecution } from "../backend/types.ts";
import { tag } from "../backend/types.ts";
import { rerunExecution } from "../backend/records.ts";
import { BackendError } from "../backend/client.ts";
import { Card } from "./Card.tsx";
import { Facts, Field } from "./Field.tsx";
import type { CardProps } from "./GenericCard.tsx";

function Results({ title, hits, name }: { title: string; hits: Hit[]; name: string }) {
  const sorted = [...hits].sort((a, b) => a.rank - b.rank);
  return (
    <div className="edp-results" data-results={name}>
      <h4>{title}</h4>
      {sorted.length === 0 ? (
        <p className="ui-hint">none</p>
      ) : (
        <div className="edp-table-scroll" tabIndex={0} role="region" aria-label={title}>
          <table className="edp-table">
            <thead>
              <tr>
                <th scope="col">Rank</th>
                <th scope="col">Source</th>
                <th scope="col">Section</th>
                <th scope="col">Score</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((h) => (
                <tr key={`${h.rank}-${h.chunkId ?? h.sourceId}`} data-rank={h.rank} data-source={h.sourceId}>
                  <td>{h.rank}</td>
                  <td>
                    {h.title ?? h.sourceId}
                    <div className="ui-hint">
                      {h.sourceId} version {h.sourceVersion}
                      {h.identifier ? `, ${h.identifier}` : ""}
                    </div>
                  </td>
                  <td>{h.sectionId ?? h.chunkId ?? ""}</td>
                  <td>{h.score}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

export function SearchExecutionCard({ id, record, onOpen, onShowStage }: CardProps) {
  const e = record as SearchExecution;
  const [rerun, setRerun] = useState<{ view?: RerunView; error?: string; busy?: boolean }>({});
  const exclusions = e.exclusions ?? [];
  return (
    <Card kind="search-execution" id={id} version={e.version} time={e.executedAt} record={record} onOpen={onOpen} onShowStage={onShowStage}>
      <Facts>
        <Field label="Query">{e.query ? `${tag(e.query.kind)}: ${e.query.text}` : e.fused ? "the plan's queries, fused" : null}</Field>
        <Field label="Fused">{e.fused ? "yes" : "no"}</Field>
        <Field label="Source">{e.source}</Field>
        <Field label="Eligibility applied">{e.eligibilityApplied ? "yes" : "no"}</Field>
      </Facts>
      <Results title="Results" name="results" hits={e.results ?? []} />
      {e.eligibilityApplied ? <Results title="Eligible results" name="eligible" hits={e.eligibleResults ?? []} /> : null}
      <div className="edp-exclusions">
        <h4>Exclusions</h4>
        {exclusions.length === 0 ? (
          <p className="ui-hint">none</p>
        ) : (
          <ul className="edp-list">
            {exclusions.map((x, n) => (
              <li key={n} data-exclusion={x.sourceId} data-rule={x.ruleId}>
                {x.sourceId} version {x.sourceVersion} {tag(x.action)} by the rule <code>{x.ruleId}</code>: {x.reason}
              </li>
            ))}
          </ul>
        )}
      </div>
      <div className="edp-rerun">
        <button
          type="button"
          className="ui-button ui-button-small"
          disabled={rerun.busy}
          onClick={async () => {
            setRerun({ busy: true });
            try {
              setRerun({ view: await rerunExecution(id) });
            } catch (err) {
              setRerun({ error: err instanceof BackendError || err instanceof Error ? err.message : String(err) });
            }
          }}
        >
          Re-run the search
        </button>
        <p role="status" className="edp-rerun-result">
          {rerun.view
            ? rerun.view.matches
              ? `The re-run ${rerun.view.execution.id} returned the same identifiers.`
              : `The re-run ${rerun.view.execution.id} returned different identifiers.`
            : rerun.error
              ? `The re-run was refused: ${rerun.error}`
              : e.rerunOf
                ? `This is a re-run of ${e.rerunOf}.`
                : ""}
        </p>
      </div>
    </Card>
  );
}
