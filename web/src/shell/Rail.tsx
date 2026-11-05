// The rail: the mark and the name, the areas in two groups with their line icons, the current
// one marked, and "forget the token" at the foot. Below 720 px it is a bar along the bottom.
import { NavLink } from "react-router";
import { FlaskConical, Inbox, KeyRound, Library, MessagesSquare, Workflow } from "lucide-react";
import type { ComponentType, SVGProps } from "react";

export interface Area {
  area: "requests" | "queue" | "sources" | "conversation";
  label: string;
  to: string;
  icon: ComponentType<SVGProps<SVGSVGElement>>;
  group: string;
}

export const areas: Area[] = [
  { area: "requests", label: "Requests", to: "/", icon: Workflow, group: "Investigate" },
  { area: "queue", label: "Review queue", to: "/queue", icon: Inbox, group: "Investigate" },
  { area: "sources", label: "Sources", to: "/sources", icon: Library, group: "Investigate" },
  { area: "conversation", label: "Conversation", to: "/chat", icon: MessagesSquare, group: "Assistant" },
];

const groups = [...new Set(areas.map((a) => a.group))];

export function Rail({ current, onForget }: { current: Area["area"]; onForget: () => void }) {
  return (
    <nav className="ui-rail" aria-label="Areas">
      <NavLink to="/" className="ui-mark">
        <span className="ui-mark-image" aria-hidden="true">
          <FlaskConical />
        </span>
        <span className="ui-wordmark">Experiment proposals</span>
      </NavLink>
      {groups.map((g) => (
        <div key={g} className="ui-rail-group">
          <p className="ui-rail-label" aria-hidden="true">
            {g}
          </p>
          <ul className="ui-rail-areas">
            {areas
              .filter((a) => a.group === g)
              .map((a) => {
                const Icon = a.icon;
                return (
                  <li key={a.area} data-area={a.area}>
                    <NavLink to={a.to} className="ui-rail-item" aria-current={current === a.area ? "page" : undefined}>
                      <Icon aria-hidden="true" />
                      <span>{a.label}</span>
                    </NavLink>
                  </li>
                );
              })}
          </ul>
        </div>
      ))}
      <div className="ui-rail-foot">
        <button type="button" className="ui-rail-item" onClick={onForget}>
          <KeyRound aria-hidden="true" />
          <span>Forget the token</span>
        </button>
      </div>
    </nav>
  );
}
