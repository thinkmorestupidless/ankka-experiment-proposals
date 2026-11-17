// A span beside the passage it resolves to: the record's quotation, the source's own text, and
// where it is. A span that does not resolve says so.
import type { SpanView } from "../backend/types.ts";

export function Passage({ view }: { view: SpanView }) {
  const { span } = view;
  return (
    <figure className={`edp-passage ${view.resolves ? "" : "edp-passage-unresolved"}`}>
      <blockquote>
        <p>{span.quotation}</p>
      </blockquote>
      <figcaption className="ui-hint">
        {span.sourceId} version {span.sourceVersion}, section {span.sectionId}, characters {span.startOffset} to {span.endOffset}
        {view.resolves ? "" : ": does not resolve"}
      </figcaption>
      {view.resolves && view.text && view.text !== span.quotation ? (
        <p className="edp-passage-text">
          <span className="ui-visually-hidden">The source reads: </span>
          {view.text}
        </p>
      ) : null}
    </figure>
  );
}
