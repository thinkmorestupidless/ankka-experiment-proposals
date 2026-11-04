// A page: its body, and the inspector beside it when something is selected.
import type { ReactNode } from "react";
import { Inspector } from "./Inspector.tsx";

export function Page({
  children,
  inspector,
  inspectorLabel,
  onCloseInspector,
  className,
}: {
  children: ReactNode;
  inspector?: ReactNode;
  inspectorLabel?: string;
  onCloseInspector?: () => void;
  className?: string;
}) {
  return (
    <div className="ac-page">
      <main className={`ac-body${className ? ` ${className}` : ""}`} id="main">
        {children}
      </main>
      {inspector ? (
        <Inspector label={inspectorLabel} onClose={onCloseInspector}>
          {inspector}
        </Inspector>
      ) : null}
    </div>
  );
}
