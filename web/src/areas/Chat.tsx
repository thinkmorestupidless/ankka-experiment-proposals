// The conversation as a whole page: the free-standing one at `/chat`, or a request's at
// `/requests/:id/chat` (what the request's panel becomes on a narrow screen). A card's "show its
// stage" opens the request's journey with that stage selected.
import { useState } from "react";
import { useNavigate, useParams } from "react-router";
import { Page } from "../shell/Page.tsx";
import { Conversation } from "../conversation/Conversation.tsx";
import { freeConversationId } from "../conversation/useConversation.ts";
import type { Selection } from "../journey/Canvas.tsx";

export function journeyPath(requestId: string, s: Selection): string {
  const p = new URLSearchParams({ stage: s.stage });
  if (s.question) p.set("question", s.question);
  return `/requests/${encodeURIComponent(requestId)}?${p.toString()}`;
}

export function Chat() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [free, setFree] = useState(() => (id ? "" : freeConversationId()));
  const show = (requestId: string, s: Selection) => navigate(journeyPath(requestId, s));
  return (
    <Page className="edp-chat-page">
      <h1>{id ? "The request's conversation" : "Conversation"}</h1>
      {id ? (
        <Conversation key={id} id={`request:${id}`} subject={id} onShowStage={show} />
      ) : (
        <Conversation key={free} id={free} onShowStage={show} onNew={() => setFree(freeConversationId(true))} />
      )}
    </Page>
  );
}
