// The inspector: the console's column at the right; below 720 px a sheet over the page. It can be
// closed at every width, by its button or by Escape, which gives the canvas the whole width back. It holds the selected stage's card, or the selected record's.
import { useEffect, type ReactNode } from "react";
import { Inspector as ConsoleInspector } from "ankka-console";
import { X } from "lucide-react";

function useEscape(onClose?: () => void): void {
  useEffect(() => {
    if (!onClose) return;
    const on = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !e.defaultPrevented) onClose();
    };
    window.addEventListener("keydown", on);
    return () => window.removeEventListener("keydown", on);
  }, [onClose]);
}

export function Inspector({ children, label = "Inspector", onClose }: { children: ReactNode; label?: string; onClose?: () => void }) {
  useEscape(onClose);
  return (
    <ConsoleInspector label={label}>
      {onClose ? (
        <button type="button" className="ac-button ac-button-quiet ac-button-small edp-sheet-close" onClick={onClose}>
          <X aria-hidden="true" /> Close
        </button>
      ) : null}
      {children}
    </ConsoleInspector>
  );
}
