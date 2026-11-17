// The first screen: the shared token, asked for once and kept by this browser. Nothing of the
// records is shown before the service has accepted it; a refused token later brings this back.
import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { BackendError, backend, refusedEvent } from "../backend/client.ts";
import { getToken, setToken } from "./token.ts";

type Standing = "checking" | "asking" | "admitted";

export function Admission({ children }: { children: ReactNode }) {
  const [standing, setStanding] = useState<Standing>(getToken() ? "checking" : "asking");
  const [refused, setRefused] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [value, setValue] = useState("");

  async function check(): Promise<void> {
    try {
      await backend("/reviewers");
      setRefused(false);
      setProblem(null);
      setStanding("admitted");
    } catch (e) {
      if (e instanceof BackendError && e.status === 401) setRefused(true);
      else setProblem(e instanceof Error ? e.message : String(e));
      setStanding("asking");
    }
  }

  useEffect(() => {
    if (standing === "checking") void check();
    const onRefused = () => {
      setRefused(true);
      setStanding("asking");
    };
    window.addEventListener(refusedEvent, onRefused);
    return () => window.removeEventListener(refusedEvent, onRefused);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (standing === "admitted") return <>{children}</>;

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!value.trim()) return;
    setToken(value.trim());
    setStanding("checking");
    void check();
  };

  return (
    <main className="ui-body edp-admission" id="main">
      <section className="ui-card" aria-labelledby="admission-title">
        <h1 id="admission-title">Experiment proposals</h1>
        <p className="ui-lede">Enter the shared token to see the requests and their records.</p>
        {refused ? (
          <p className="ui-refusal" role="alert">
            The token was refused.
          </p>
        ) : null}
        {problem ? (
          <p className="ui-refusal" role="alert">
            The service could not be reached: {problem}
          </p>
        ) : null}
        <form className="ui-form" onSubmit={submit}>
          <div className="ui-field">
            <label htmlFor="token">Shared token</label>
            <input
              id="token"
              name="token"
              type="password"
              autoComplete="off"
              value={value}
              onChange={(e) => setValue(e.target.value)}
              aria-invalid={refused ? "true" : undefined}
            />
          </div>
          <div className="ui-actions">
            <button type="submit" className="ui-button ui-button-primary" disabled={standing === "checking"}>
              {standing === "checking" ? "Checking" : "Enter"}
            </button>
          </div>
        </form>
      </section>
    </main>
  );
}
