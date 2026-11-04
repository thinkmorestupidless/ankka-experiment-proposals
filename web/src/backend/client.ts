// Every call to the service: under the mount /backend, with the shared token as a bearer header.
import { getToken } from "../token/token.ts";
import { EventParser, type StreamEvent } from "./events.ts";

export const base = "/backend/api/v1";

/** A refusal from the service: its status and the message it gave. */
export class BackendError extends Error {
  readonly status: number;
  readonly details: Record<string, string>;
  constructor(status: number, message: string, details: Record<string, string> = {}) {
    super(message);
    this.name = "BackendError";
    this.status = status;
    this.details = details;
  }
}

/** Sent when the service refuses the token, so the admission screen comes back. */
export const refusedEvent = "edp:token-refused";

function headers(init?: RequestInit): Headers {
  const h = new Headers(init?.headers);
  const token = getToken();
  if (token) h.set("Authorization", `Bearer ${token}`);
  if (init?.body !== undefined && !h.has("Content-Type")) h.set("Content-Type", "application/json");
  return h;
}

async function refusal(response: Response): Promise<BackendError> {
  const text = await response.text();
  let message = text || response.statusText;
  let details: Record<string, string> = {};
  try {
    const body = JSON.parse(text) as { error?: string; message?: string; details?: Record<string, string> };
    message = body.error ?? body.message ?? message;
    details = body.details ?? {};
  } catch {
    // not JSON: the text is the message
  }
  if (response.status === 401 && typeof window !== "undefined") window.dispatchEvent(new Event(refusedEvent));
  return new BackendError(response.status, message, details);
}

/** A call answering JSON, or text when the service sends text. */
export async function backend<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(base + path, { ...init, headers: headers(init) });
  if (!response.ok) throw await refusal(response);
  const type = response.headers.get("Content-Type") ?? "";
  if (response.status === 204) return undefined as T;
  return (type.includes("json") ? await response.json() : await response.text()) as T;
}

export const post = <T>(path: string, body: unknown = {}): Promise<T> =>
  backend<T>(path, { method: "POST", body: JSON.stringify(body) });

/**
 * Reads a stream of server-sent events from a `fetch` body (EventSource cannot carry the token).
 * Resolves when the stream ends; abort the signal to stop reading, as a page does when it is left.
 */
export async function readEvents(
  path: string,
  init: RequestInit & { signal?: AbortSignal },
  onEvent: (e: StreamEvent) => void,
): Promise<void> {
  const h = headers(init);
  h.set("Accept", "text/event-stream");
  const response = await fetch(base + path, { ...init, headers: h });
  if (!response.ok) throw await refusal(response);
  if (!response.body) return;
  const parser = new EventParser(onEvent);
  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      parser.push(value);
    }
  } catch (e) {
    if (init.signal?.aborted) return;
    throw e;
  } finally {
    parser.end();
    reader.releaseLock();
  }
}

export type { StreamEvent };
