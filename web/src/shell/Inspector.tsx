// The inspector: the console's column at the right; below 720 px a sheet over the page that can be
// closed. It holds the selected stage's card, or the selected record's.
import { useEffect, useState, type ReactNode } from "react";
import { Inspector as ConsoleInspector } from "ankka-console";
import { X } from "lucide-react";

const narrow = "(max-width: 720px)";

function useNarrow(): boolean {
  const [is, setIs] = useState(() => typeof window !== "undefined" && window.matchMedia(narrow).matches);
  useEffect(() => {
    const m = window.matchMedia(narrow);
    const on = () => setIs(m.matches);
    m.addEventListener("change", on);
    return () => m.removeEventListener("change", on);
  }, []);
  return is;
}

export function Inspector({ children, label = "Inspector", onClose }: { children: ReactNode; label?: string; onClose?: () => void }) {
  const isNarrow = useNarrow();
  return (
    <ConsoleInspector label={label}>
      {isNarrow && onClose ? (
        <button type="button" className="ac-button ac-button-quiet ac-button-small edp-sheet-close" onClick={onClose}>
          <X aria-hidden="true" /> Close
        </button>
      ) : null}
      {children}
    </ConsoleInspector>
  );
}
