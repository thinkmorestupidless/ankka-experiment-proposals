// What a page adds to the shell: the crumbs past the area and the request, and the bar's primary
// action. A page sets them while it is shown; leaving it clears them.
import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

export interface Crumb {
  label: string;
  to?: string;
}

interface Slots {
  crumbs: Crumb[];
  primary: ReactNode;
  set: (crumbs: Crumb[], primary: ReactNode) => void;
}

const SlotsContext = createContext<Slots>({ crumbs: [], primary: null, set: () => {} });

export function SlotsProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<{ crumbs: Crumb[]; primary: ReactNode }>({ crumbs: [], primary: null });
  return (
    <SlotsContext.Provider value={{ ...state, set: (crumbs, primary) => setState({ crumbs, primary }) }}>
      {children}
    </SlotsContext.Provider>
  );
}

export const useSlots = () => useContext(SlotsContext);

/** Called by a page: its extra crumbs and its primary action, kept while the page is shown. */
export function usePageSlots(crumbs: Crumb[], primary: ReactNode = null, deps: unknown[] = []): void {
  const { set } = useSlots();
  useEffect(() => {
    set(crumbs, primary);
    return () => set([], null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}
