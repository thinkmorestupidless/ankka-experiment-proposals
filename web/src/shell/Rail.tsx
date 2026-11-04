// The rail: the four areas with line icons, the current one marked, and "forget the token" at the
// foot. Below 720 px it is a bar along the bottom of the screen.
import { NavLink } from "react-router";
import { Inbox, KeyRound, Library, MessagesSquare, Workflow } from "lucide-react";
import type { ComponentType, SVGProps } from "react";

export interface Area {
  area: "requests" | "queue" | "sources" | "conversation";
  label: string;
  to: string;
  icon: ComponentType<SVGProps<SVGSVGElement>>;
}

export const areas: Area[] = [
  { area: "requests", label: "Requests", to: "/", icon: Workflow },
  { area: "queue", label: "Review queue", to: "/queue", icon: Inbox },
  { area: "sources", label: "Sources", to: "/sources", icon: Library },
  { area: "conversation", label: "Conversation", to: "/chat", icon: MessagesSquare },
];

export function Rail({ current, onForget }: { current: Area["area"]; onForget: () => void }) {
  return (
    <nav className="ac-rail edp-rail" aria-label="Areas">
      <NavLink to="/" className="ac-mark" aria-label="Experiment proposals" title="Experiment proposals">
        <span className="ac-mark-image" aria-hidden="true" />
      </NavLink>
      <ul className="ac-rail-areas">
        {areas.map((a) => {
          const Icon = a.icon;
          return (
            <li key={a.area} data-area={a.area}>
              <NavLink
                to={a.to}
                className="ac-rail-item"
                aria-label={a.label}
                title={a.label}
                aria-current={current === a.area ? "page" : undefined}
              >
                <Icon aria-hidden="true" />
              </NavLink>
            </li>
          );
        })}
      </ul>
      <div className="ac-rail-foot">
        <button type="button" className="ac-rail-item" aria-label="Forget the token" title="Forget the token" onClick={onForget}>
          <KeyRound aria-hidden="true" />
        </button>
      </div>
    </nav>
  );
}
