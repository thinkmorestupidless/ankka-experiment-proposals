// The journey of a request: fetched once, then followed on `journey:watch`, each event replacing it.
// The stream closes when the request is left; if the service ends it, it is opened again.
import { useEffect, useState } from "react";
import type { Journey } from "../backend/types.ts";
import { getJourney, watchJourney } from "../backend/records.ts";

export function useJourney(requestId: string): { journey?: Journey; error?: string } {
  const [journey, setJourney] = useState<Journey>();
  const [error, setError] = useState<string>();
  useEffect(() => {
    const abort = new AbortController();
    let alive = true;
    setJourney(undefined);
    setError(undefined);
    (async () => {
      try {
        const first = await getJourney(requestId);
        if (alive) setJourney(first);
      } catch (e) {
        if (alive) setError(e instanceof Error ? e.message : String(e));
        return;
      }
      let pause = 500;
      while (alive) {
        try {
          await watchJourney(requestId, abort.signal, (j) => {
            if (alive) setJourney(j);
          });
          pause = 500;
        } catch {
          // a dropped stream is opened again below
        }
        if (!alive) break;
        await new Promise((r) => setTimeout(r, pause));
        pause = Math.min(pause * 2, 5000);
      }
    })();
    return () => {
      alive = false;
      abort.abort();
    };
  }, [requestId]);
  return { journey, error };
}
