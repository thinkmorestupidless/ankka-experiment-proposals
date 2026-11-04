// The reviewer a decision is made in the name of: chosen from `GET /reviewers`, remembered for the
// session in component state and never in storage, and shared by every form on the page.
import { createContext, useContext, useEffect, useId, useState, type ReactNode } from "react";
import type { Reviewer } from "../backend/types.ts";
import { getReviewers } from "../backend/records.ts";

interface ReviewerState {
  reviewer: string;
  setReviewer: (name: string) => void;
  reviewers: Reviewer[];
  error?: string;
}

const ReviewerContext = createContext<ReviewerState>({ reviewer: "", setReviewer: () => {}, reviewers: [] });

export function ReviewerProvider({ children }: { children: ReactNode }) {
  const [reviewer, setReviewer] = useState("");
  const [reviewers, setReviewers] = useState<Reviewer[]>([]);
  const [error, setError] = useState<string>();
  useEffect(() => {
    getReviewers().then(setReviewers, (e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, []);
  return <ReviewerContext.Provider value={{ reviewer, setReviewer, reviewers, error }}>{children}</ReviewerContext.Provider>;
}

export const useReviewer = () => useContext(ReviewerContext);

/** The select naming the reviewer. `required` inside a form, so a decision cannot go without one. */
export function ReviewerSelect({ required = false }: { required?: boolean }) {
  const id = useId();
  const { reviewer, setReviewer, reviewers, error } = useReviewer();
  return (
    <div className="ac-field edp-reviewer">
      <label htmlFor={id}>Reviewer</label>
      <select id={id} className="ac-input" value={reviewer} required={required} onChange={(e) => setReviewer(e.target.value)} data-reviewer-select="">
        <option value="">Choose a configured reviewer</option>
        {reviewers.map((r) => (
          <option key={r.name} value={r.name}>
            {r.name}, {r.role}
          </option>
        ))}
      </select>
      {error ? <p className="ac-hint">The reviewers could not be read: {error}</p> : null}
    </div>
  );
}
