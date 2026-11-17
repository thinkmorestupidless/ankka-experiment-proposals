// A parser of `text/event-stream`, fed chunks as they arrive. An event is its lines up to a blank
// one, in any order: the service sends `data:` before `event:`. Several `data:` lines are joined
// with a newline, as the protocol says. A chunk may end anywhere, even inside a line or a CRLF.
export interface StreamEvent {
  event: string;
  data: string;
}

export class EventParser {
  private buffer = "";
  private name = "";
  private data: string[] = [];
  private hasData = false;

  private readonly onEvent: (e: StreamEvent) => void;

  constructor(onEvent: (e: StreamEvent) => void) {
    this.onEvent = onEvent;
  }

  push(chunk: string): void {
    this.buffer += chunk;
    for (;;) {
      const nl = this.buffer.search(/\r\n|\r|\n/);
      if (nl < 0) return;
      // A lone CR at the very end may be the first half of a CRLF still to come.
      if (this.buffer[nl] === "\r" && nl === this.buffer.length - 1) return;
      const width = this.buffer.startsWith("\r\n", nl) ? 2 : 1;
      const line = this.buffer.slice(0, nl);
      this.buffer = this.buffer.slice(nl + width);
      this.line(line);
    }
  }

  /** The end of the stream: an event not yet closed by a blank line is dropped, as the protocol says. */
  end(): void {
    this.buffer = "";
    this.reset();
  }

  private line(line: string): void {
    if (line === "") {
      if (this.hasData) this.onEvent({ event: this.name || "message", data: this.data.join("\n") });
      this.reset();
      return;
    }
    if (line.startsWith(":")) return;
    const colon = line.indexOf(":");
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) value = value.slice(1);
    if (field === "event") this.name = value;
    else if (field === "data") {
      this.data.push(value);
      this.hasData = true;
    }
  }

  private reset(): void {
    this.name = "";
    this.data = [];
    this.hasData = false;
  }
}
