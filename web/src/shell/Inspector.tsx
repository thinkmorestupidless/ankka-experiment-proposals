// The inspector: a panel at the right of the page; below 900 px a sheet over the page. It can be
// closed at every width, by its button or by Escape, which gives the canvas the whole width back.
// It holds the selected stage's card, or the selected record's.
import { useEffect, type ReactNode } from "react";
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
    <aside className="ui-inspector" aria-label={label}>
      <div className="ui-inspector-head">
        <span className="ui-inspector-title">Inspector</span>
        {onClose ? (
          <button type="button" className="ui-icon-button edp-sheet-close" onClick={onClose} aria-label="Close" title="Close (Esc)">
            <X aria-hidden="true" />
          </button>
        ) : null}
      </div>
      <div className="ui-inspector-body">{children}</div>
    </aside>
  );
}
