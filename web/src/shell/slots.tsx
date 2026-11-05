// What a page adds to the shell: the crumbs past the area and the request, the bar's title, and the
// bar's actions. A page sets them while it is shown; leaving it clears them.
import { createContext, useContext, useEffect, useState, type ReactNode } from "react";

export interface Crumb {
  label: string;
  to?: string;
}

interface State {
  crumbs: Crumb[];
  primary: ReactNode;
  title?: ReactNode;
}

interface Slots extends State {
  set: (crumbs: Crumb[], primary: ReactNode) => void;
  setTitle: (title?: ReactNode) => void;
}

const SlotsContext = createContext<Slots>({ crumbs: [], primary: null, set: () => {}, setTitle: () => {} });

export function SlotsProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<State>({ crumbs: [], primary: null });
  return (
    <SlotsContext.Provider
      value={{
        ...state,
        set: (crumbs, primary) => setState((s) => ({ ...s, crumbs, primary })),
        setTitle: (title) => setState((s) => ({ ...s, title })),
      }}
    >
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

/** Called by a page: the title centred in the bar, kept while the page is shown. */
export function useBarTitle(title: ReactNode, deps: unknown[] = []): void {
  const { setTitle } = useSlots();
  useEffect(() => {
    setTitle(title);
    return () => setTitle(undefined);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}
