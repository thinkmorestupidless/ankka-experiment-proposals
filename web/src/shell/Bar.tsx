// The bar: the wordmark and name at the start, a centred breadcrumb (area › request › stage), and
// the page's primary action at the end. Drawn with the console's classes; the console's own Bar
// reads its router data, which this interface does not have.
import type { ReactNode } from "react";
import { Link } from "react-router";
import type { Crumb } from "./slots.tsx";

export function Bar({ crumbs, primary }: { crumbs: Crumb[]; primary?: ReactNode }) {
  return (
    <header className="ac-bar">
      <div className="ac-bar-start">
        <Link to="/" className="ac-wordmark">
          experiment <span>proposals</span>
        </Link>
      </div>
      {crumbs.length > 0 ? (
        <nav aria-label="Breadcrumb" className="ac-crumbs">
          <ol>
            {crumbs.map((c, i) => (
              <li key={`${i}-${c.label}`}>
                {c.to && i < crumbs.length - 1 ? (
                  <Link to={c.to}>{c.label}</Link>
                ) : (
                  <span aria-current={i === crumbs.length - 1 ? "page" : undefined}>{c.label}</span>
                )}
              </li>
            ))}
          </ol>
        </nav>
      ) : (
        <span />
      )}
      <div className="ac-bar-end">{primary}</div>
    </header>
  );
}
