// The bar: a step back and the breadcrumb (area / request / stage) at the start, the page's title
// in the middle, and the page's actions at the end.
import type { ReactNode } from "react";
import { Link } from "react-router";
import { ArrowLeft } from "lucide-react";
import type { Crumb } from "./slots.tsx";

export function Bar({ crumbs, primary, title }: { crumbs: Crumb[]; primary?: ReactNode; title?: ReactNode }) {
  const back = [...crumbs].slice(0, -1).reverse().find((c) => c.to);
  return (
    <header className="ui-bar">
      <div className="ui-bar-start">
        {back?.to ? (
          <Link to={back.to} className="ui-bar-back" aria-label={`Back to ${back.label}`} title={`Back to ${back.label}`}>
            <ArrowLeft aria-hidden="true" />
          </Link>
        ) : null}
        {crumbs.length > 0 ? (
          <nav aria-label="Breadcrumb" className="ui-crumbs">
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
        ) : null}
      </div>
      <div className="ui-bar-title">{title}</div>
      <div className="ui-bar-end">{primary}</div>
    </header>
  );
}
