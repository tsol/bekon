import { Redis } from "ioredis";

export const MSG_TTL_SEC = Number(process.env.MSG_TTL_SEC ?? 600);
export const CURSOR_TTL_SEC = Number(process.env.CURSOR_TTL_SEC ?? 900);
export const DEFAULT_TAIL = 10;
export const MAX_BATCH = 100;

export type StoredMessage = {
  id: string;
  offset: number;
  data: string;
  ts: number;
};

export async function storeMessages(
  redis: Redis,
  hash: string,
  items: { id: string; data: string; ts: number }[],
): Promise<number> {
  if (items.length === 0) return 0;
  const last = await redis.incrby(`wlya:seq:${hash}`, items.length);
  const first = last - items.length + 1;
  const pipe = redis.pipeline();
  items.forEach((item, i) => {
    const offset = first + i;
    const payload = JSON.stringify({ id: item.id, offset, data: item.data, ts: item.ts });
    pipe.set(`wlya:msg:${hash}:${offset}`, payload, "EX", MSG_TTL_SEC);
    pipe.zadd(`wlya:idx:${hash}`, offset, String(offset));
    pipe.publish(`wlya:pub:${hash}`, payload);
  });
  pipe.expire(`wlya:idx:${hash}`, MSG_TTL_SEC);
  await pipe.exec();
  return items.length;
}

export async function readMessages(
  redis: Redis,
  hash: string,
  client: string,
  queryCursor?: number,
): Promise<{ messages: StoredMessage[]; next_cursor: number }> {
  const seq = Number((await redis.get(`wlya:seq:${hash}`)) ?? 0);
  let cursor = queryCursor;
  if (cursor === undefined) {
    const stored = await redis.get(`wlya:cursor:${hash}:${client}`);
    cursor = stored != null ? Number(stored) : undefined;
  }

  let start: number;
  if (cursor === undefined || Number.isNaN(cursor)) {
    start = Math.max(1, seq - DEFAULT_TAIL + 1);
  } else {
    start = cursor + 1;
  }
  const end = Math.min(seq, start + MAX_BATCH - 1);
  const messages: StoredMessage[] = [];
  if (seq > 0 && start <= end) {
    const keys = [];
    for (let o = start; o <= end; o++) keys.push(`wlya:msg:${hash}:${o}`);
    const raw = await redis.mget(...keys);
    for (const row of raw) {
      if (!row) continue;
      try {
        messages.push(JSON.parse(row) as StoredMessage);
      } catch {
        /* skip corrupt */
      }
    }
  }
  const next = messages.length > 0 ? messages[messages.length - 1].offset : seq;
  await redis.set(`wlya:cursor:${hash}:${client}`, String(next), "EX", CURSOR_TTL_SEC);
  return { messages, next_cursor: next };
}

export async function rateLimit(
  redis: Redis,
  kind: "r" | "w",
  hash: string,
  client: string,
  limitPerSec: number,
): Promise<boolean> {
  const key = `wlya:ratelimit:${kind}:${hash}:${client}`;
  const n = await redis.incr(key);
  if (n === 1) await redis.expire(key, 1);
  return n <= limitPerSec;
}
