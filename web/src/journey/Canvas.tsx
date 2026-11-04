// The canvas: the spine across the top, and below it the lane of the selected scientific question
// with a selector listing the questions. The selection is the parent's, so it survives a redraw.
import { useId } from "react";
import type { Journey } from "../backend/types.ts";
import { Lane } from "./Lane.tsx";

export interface Selection {
  stage: string;
  /** The question whose lane the stage is in; absent for the spine. */
  question?: string;
}

export function Canvas({
  journey,
  selection,
  question,
  onSelect,
  onQuestion,
}: {
  journey: Journey;
  selection?: Selection;
  question?: string;
  onSelect: (s: Selection) => void;
  onQuestion: (q: string) => void;
}) {
  const selectId = useId();
  const lane = journey.questions.find((q) => q.questionId === question) ?? journey.questions[0];
  return (
    <div className="edp-canvas" data-request={journey.requestId}>
      <h2 className="ac-visually-hidden">The journey</h2>
      <Lane
        label="The journey"
        nodes={journey.spine}
        selected={selection && !selection.question ? selection.stage : undefined}
        onSelect={(stage) => onSelect({ stage })}
      />
      {lane ? (
        <section className="edp-question-lane" aria-label="A scientific question's lane">
          <div className="edp-lane-head">
            <label htmlFor={selectId}>Scientific question</label>
            <select id={selectId} className="ac-input edp-question-select" value={lane.questionId} onChange={(e) => onQuestion(e.target.value)}>
              {journey.questions.map((q) => (
                <option key={q.questionId} value={q.questionId}>
                  {q.text}
                </option>
              ))}
            </select>
          </div>
          <Lane
            label={`The lane of: ${lane.text}`}
            lane={lane.questionId}
            nodes={lane.nodes}
            selected={selection?.question === lane.questionId ? selection.stage : undefined}
            onSelect={(stage) => onSelect({ stage, question: lane.questionId })}
          />
        </section>
      ) : null}
    </div>
  );
}
