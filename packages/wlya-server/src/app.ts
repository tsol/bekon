import fs from "node:fs";
import path from "node:path";
import Fastify from "fastify";
import websocket from "@fastify/websocket";
import { Redis } from "ioredis";
import { verifyAuth } from "./auth.js";
import { rateLimit, readMessages, storeMessages } from "./store.js";

const BEKON_PUBLIC_DIR = process.env.BEKON_PUBLIC_DIR || path.join(process.cwd(), "public");
const BEKON_APK = path.join(BEKON_PUBLIC_DIR, "bekon.apk");

const READ_RATE = Number(process.env.READ_RATE ?? 10);
const WRITE_RATE = Number(process.env.WRITE_RATE ?? 5);

type Metrics = {
  httpRequests: number;
  messagesStored: number;
  messagesRead: number;
  authFail: number;
  rateLimited: number;
};

export async function buildApp(redis: Redis) {
  const metrics: Metrics = {
    httpRequests: 0,
    messagesStored: 0,
    messagesRead: 0,
    authFail: 0,
    rateLimited: 0,
  };

  const app = Fastify({
    logger: true,
    // Default is 1 MiB — enough for current 12 KiB parts; hi-res PNG multipart still fits.
    bodyLimit: 4 * 1024 * 1024,
  });
  const rooms = new Map<
    string,
    Set<{ client: string; socket: { send: (d: Buffer | string) => void } }>
  >();

  app.addContentTypeParser("application/json", { parseAs: "string" }, (_req, body, done) => {
    done(null, body);
  });

  await app.register(websocket);

  app.addHook("onRequest", async () => {
    metrics.httpRequests++;
  });

  app.get("/health", async (_req, reply) => {
    try {
      await redis.ping();
      return { ok: true };
    } catch {
      return reply.code(503).send({ ok: false });
    }
  });

  /** Phone pulls the APK over HTTPS — not through the WLYA JSON tunnel. */
  app.get("/u", async (_req, reply) => {
    reply.header("Cache-Control", "no-store");
    const ready = fs.existsSync(BEKON_APK);
    const bust = ready ? `?t=${fs.statSync(BEKON_APK).mtimeMs}` : "";
    const href = `/bekon.apk${bust}`;
    const body = `<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width,initial-scale=1"/>
<title>Bekon update</title>
</head>
<body style="font-family:sans-serif;padding:28px;font-size:22px;line-height:1.4">
<h1 style="font-size:28px">Bekon update</h1>
${ready
    ? `<p><a href="${href}">Download bekon.apk</a></p>
<p>Then open the file and install. Same signing key as the app on the phone.</p>
<script>location.replace(${JSON.stringify(href)})</script>`
    : `<p>No APK on the server yet. Ground station: copy bekon.apk into public/.</p>`}
</body></html>`;
    return reply.type("text/html; charset=utf-8").send(body);
  });

  app.get("/bekon.apk", async (_req, reply) => {
    if (!fs.existsSync(BEKON_APK)) {
      return reply.code(404).type("text/plain").send("no bekon.apk in public/");
    }
    const stat = fs.statSync(BEKON_APK);
    return reply
      .type("application/vnd.android.package-archive")
      .header("Cache-Control", "no-store")
      .header("Content-Length", String(stat.size))
      .header("Content-Disposition", 'attachment; filename="bekon.apk"')
      .send(fs.createReadStream(BEKON_APK));
  });

  app.get("/metrics", async (_req, reply) => {
    const body = [
      `# TYPE wlya_http_requests_total counter`,
      `wlya_http_requests_total ${metrics.httpRequests}`,
      `# TYPE wlya_messages_stored_total counter`,
      `wlya_messages_stored_total ${metrics.messagesStored}`,
      `# TYPE wlya_messages_read_total counter`,
      `wlya_messages_read_total ${metrics.messagesRead}`,
      `# TYPE wlya_auth_fail_total counter`,
      `wlya_auth_fail_total ${metrics.authFail}`,
      `# TYPE wlya_rate_limited_total counter`,
      `wlya_rate_limited_total ${metrics.rateLimited}`,
    ].join("\n") + "\n";
    return reply.type("text/plain").send(body);
  });

  function authFrom(req: { headers: Record<string, unknown>; body?: unknown }, rawBody: string) {
    const result = verifyAuth(req.headers as Record<string, string | string[] | undefined>, rawBody);
    if ("error" in result) {
      metrics.authFail++;
    }
    return result;
  }

  app.get("/v1/messages", async (req, reply) => {
    const auth = authFrom(req, "");
    if ("error" in auth) return reply.code(auth.status).send({ error: auth.error });
    if (!(await rateLimit(redis, "r", auth.hash, auth.client, READ_RATE))) {
      metrics.rateLimited++;
      return reply.code(429).send({ error: "rate limit" });
    }
    const q = (req.query as { cursor?: string }).cursor;
    const cursor = q != null && q !== "" ? Number(q) : undefined;
    const result = await readMessages(redis, auth.hash, auth.client, cursor);
    metrics.messagesRead += result.messages.length;
    return result;
  });

  app.post("/v1/messages", async (req, reply) => {
    const raw = typeof req.body === "string" ? req.body : JSON.stringify(req.body ?? {});
    const auth = authFrom(req, raw);
    if ("error" in auth) return reply.code(auth.status).send({ error: auth.error });
    if (!(await rateLimit(redis, "w", auth.hash, auth.client, WRITE_RATE))) {
      metrics.rateLimited++;
      return reply.code(429).send({ error: "rate limit" });
    }
    let parsed: { messages?: { id?: string; data?: string; ts?: number }[] };
    try {
      parsed = typeof req.body === "string" ? JSON.parse(req.body) : (req.body as typeof parsed);
    } catch {
      return reply.code(400).send({ error: "invalid json" });
    }
    const items = (parsed.messages ?? [])
      .filter((m) => m.id && m.data)
      .map((m) => ({ id: String(m.id), data: String(m.data), ts: Number(m.ts) || Date.now() }));
    const stored = await storeMessages(redis, auth.hash, items);
    metrics.messagesStored += stored;
    return { stored };
  });

  app.get("/v1/stream", async (req, reply) => {
    const auth = authFrom(req, "");
    if ("error" in auth) return reply.code(auth.status).send({ error: auth.error });
    if (!(await rateLimit(redis, "r", auth.hash, auth.client, READ_RATE))) {
      metrics.rateLimited++;
      return reply.code(429).send({ error: "rate limit" });
    }

    reply.hijack();
    reply.raw.writeHead(200, {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
    });
    reply.raw.write(":\n\n");

    const sub = redis.duplicate();
    await sub.subscribe(`wlya:pub:${auth.hash}`);
    const onMessage = (_channel: string, message: string) => {
      reply.raw.write(`event: message\ndata: ${message}\n\n`);
    };
    sub.on("message", onMessage);
    req.raw.on("close", () => {
      sub.off("message", onMessage);
      void sub.quit();
    });
  });

  app.get("/v1/call", { websocket: true }, (socket, req) => {
    const q = req.query as Record<string, string | undefined>;
    const headers: Record<string, string | undefined> = {
      "x-wlya-seed": (req.headers["x-wlya-seed"] as string) ?? q.seed,
      "x-wlya-client": (req.headers["x-wlya-client"] as string) ?? q.client,
      "x-wlya-timestamp": (req.headers["x-wlya-timestamp"] as string) ?? q.ts,
      "x-wlya-sig": (req.headers["x-wlya-sig"] as string) ?? q.sig,
    };
    const auth = verifyAuth(headers, "");
    if ("error" in auth) {
      metrics.authFail++;
      socket.close();
      return;
    }
    let roomId: string | null = null;
    const peer = { client: auth.client, socket };

    function relay(data: Buffer | string) {
      if (!roomId) return;
      const members = rooms.get(roomId);
      if (!members) return;
      for (const m of members) {
        if (m.client !== peer.client) m.socket.send(data);
      }
    }

    socket.on("message", (raw: Buffer | ArrayBuffer | string) => {
      const buf = Buffer.isBuffer(raw) ? raw : Buffer.from(raw as string);
      if (buf.length > 0 && buf[0] !== 0x7b) {
        relay(buf);
        return;
      }
      const text = buf.toString("utf8");
      try {
        const msg = JSON.parse(text) as { type?: string; room?: string };
        if (msg.type === "join" && msg.room) {
          roomId = msg.room;
          let set = rooms.get(roomId);
          if (!set) {
            set = new Set();
            rooms.set(roomId, set);
          }
          set.add(peer);
          return;
        }
        if (roomId && msg.type && msg.type !== "join") relay(text);
      } catch {
        /* ignore */
      }
    });

    socket.on("close", () => {
      if (!roomId) return;
      const set = rooms.get(roomId);
      if (!set) return;
      set.delete(peer);
      if (set.size === 0) rooms.delete(roomId);
    });
  });

  return app;
}
