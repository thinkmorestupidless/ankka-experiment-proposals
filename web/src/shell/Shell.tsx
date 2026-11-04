// The interface inside the console's shell: dark, the backdrop behind everything, the rail, the bar
// and whichever area the route shows. The console package is used for what it gives a host outside
// the console (Shell, Backdrop, Inspector and the styles); the bar and the rail are drawn here with
// its classes (research.md, found during implementation).
import "ankka-console/styles.css";
import "../app.css";
import { Shell as ConsoleShell, Backdrop } from "ankka-console";
import { Outlet, useLocation, useNavigate } from "react-router";
import { Bar } from "./Bar.tsx";
import { Rail, type Area } from "./Rail.tsx";
import { SlotsProvider, useSlots, type Crumb } from "./slots.tsx";
import { forgetToken } from "../token/token.ts";
import { Admission } from "../token/Admission.tsx";
import { ReviewerProvider } from "../decisions/Reviewer.tsx";

/** The area a path belongs to and the crumbs it names, before the page adds its own. */
export function crumbsFor(pathname: string): { area: Area["area"]; crumbs: Crumb[] } {
  const parts = pathname.split("/").filter(Boolean).map(decodeURIComponent);
  if (parts[0] === "queue") return { area: "queue", crumbs: [{ label: "Review queue", to: "/queue" }] };
  if (parts[0] === "sources") return { area: "sources", crumbs: [{ label: "Sources", to: "/sources" }] };
  if (parts[0] === "chat") return { area: "conversation", crumbs: [{ label: "Conversation", to: "/chat" }] };
  const crumbs: Crumb[] = [{ label: "Requests", to: "/" }];
  if (parts[0] === "requests" && parts[1]) {
    crumbs.push({ label: parts[1], to: `/requests/${encodeURIComponent(parts[1])}` });
    if (parts[2] === "chat") crumbs.push({ label: "Conversation" });
  }
  return { area: "requests", crumbs };
}

function Frame() {
  const location = useLocation();
  const navigate = useNavigate();
  const slots = useSlots();
  const { area, crumbs } = crumbsFor(location.pathname);
  return (
    <>
      <Rail
        current={area}
        onForget={() => {
          forgetToken();
          navigate("/", { replace: true });
          window.location.reload();
        }}
      />
      <Bar crumbs={[...crumbs, ...slots.crumbs]} primary={slots.primary} />
      <Outlet />
    </>
  );
}

export function Shell() {
  return (
    <ConsoleShell theme="dark" className="edp-shell">
      <Backdrop />
      <Admission>
        <SlotsProvider>
          <ReviewerProvider>
            <Frame />
          </ReviewerProvider>
        </SlotsProvider>
      </Admission>
    </ConsoleShell>
  );
}
