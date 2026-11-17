// A record a card reads beside its own, fetched again when `deps` change; the last answer is kept
// while the next one is fetched, so a card does not blank on a refetch.
import { useEffect, useState } from "react";

export function useLoad<T>(load: () => Promise<T>, deps: unknown[]): { value?: T; error?: string } {
  const [state, setState] = useState<{ value?: T; error?: string }>({});
  useEffect(() => {
    let alive = true;
    load().then(
      (value) => alive && setState({ value }),
      (e: unknown) => alive && setState((s) => ({ ...s, error: e instanceof Error ? e.message : String(e) })),
    );
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return state;
}
