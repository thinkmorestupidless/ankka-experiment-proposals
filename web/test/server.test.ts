import { after, before, describe, test } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import type { AddressInfo } from "node:net";
import { mkdtemp, mkdir, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { createAppServer, stop } from "../server/server.ts";

/** Listens on a free loopback port and answers its address. */
async function listen(server: http.Server): Promise<string> {
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  return `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
}

async function close(server: http.Server): Promise<void> {
  server.closeAllConnections();
  await new Promise<void>((resolve) => server.close(() => resolve()));
}

describe("the built app", () => {
  let app: http.Server;
  let url = "";

  before(async () => {
    const dist = await mkdtemp(path.join(tmpdir(), "dist-"));
    await mkdir(path.join(dist, "assets"));
    await writeFile(path.join(dist, "index.html"), "<p>the app</p>");
    await writeFile(path.join(dist, "favicon.png"), "png");
    await writeFile(path.join(dist, "assets", "app.js"), "console.log(1)");
    app = createAppServer({ dist });
    url = await listen(app);
  });
  after(async () => close(app));

  test("an app's own route is the index page, never cached", async () => {
    const answer = await fetch(`${url}/carts/c1`);
    assert.equal(answer.status, 200);
    assert.equal(await answer.text(), "<p>the app</p>");
    assert.equal(answer.headers.get("cache-control"), "no-cache");
  });

  test("a built asset is served and may be cached for good", async () => {
    const answer = await fetch(`${url}/assets/app.js`);
    assert.equal(answer.status, 200);
    assert.equal(answer.headers.get("content-type"), "text/javascript");
    assert.match(answer.headers.get("cache-control") ?? "", /immutable/);
  });

  test("the index page by its own name is never cached either", async () => {
    const answer = await fetch(`${url}/index.html`);
    assert.equal(answer.status, 200);
    assert.equal(answer.headers.get("cache-control"), "no-cache");
  });

  test("a file outside assets/ is served but not cached for good", async () => {
    const answer = await fetch(`${url}/favicon.png`);
    assert.equal(answer.status, 200);
    assert.equal(answer.headers.get("cache-control"), "no-cache");
  });

  test("a path that climbs out of the app is not served", async () => {
    const answer = await fetch(`${url}/assets/..%2F..%2Fetc%2Fpasswd`);
    assert.equal(answer.status, 404);
  });

  test("a file that is not there is 404, not the index page", async () => {
    const answer = await fetch(`${url}/assets/missing.js`);
    assert.equal(answer.status, 404);
  });
});

describe("a request the app does not serve", () => {
  test("without a built app or Vite it is 404: the server has no route of its own", async () => {
    const app = createAppServer({});
    const url = await listen(app);
    try {
      for (const p of ["/summary", "/backend/api/v1/requests", "/"]) {
        assert.equal((await fetch(`${url}${p}`)).status, 404, p);
      }
    } finally {
      await close(app);
    }
  });
});

describe("stopping", () => {
  test("a stream left open is closed when the grace has passed, and the server stops", async () => {
    // A server holding a response open, as a watched journey does.
    const held = http.createServer((_req, res) => {
      res.writeHead(200, { "Content-Type": "text/event-stream" });
      res.write(": open\n\n");
    });
    const url = await listen(held);
    const response = await fetch(`${url}/watch`);
    const reader = response.body!.getReader();
    await reader.read();
    const started = Date.now();
    await stop(held, 300);
    const took = Date.now() - started;
    assert.ok(took >= 250 && took < 3_000, `stopped after ${took} ms`);
    await assert.rejects(fetch(`${url}/`));
  });

  test("with nothing open the server stops at once", async () => {
    const app = createAppServer({});
    await listen(app);
    const started = Date.now();
    await stop(app, 10_000);
    assert.ok(Date.now() - started < 1_000);
  });
});
