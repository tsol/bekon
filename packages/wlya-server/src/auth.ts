import { createHash, createHmac, timingSafeEqual } from "node:crypto";

/**
 * HMAC for /v1/messages, /v1/stream, /v1/call.
 *
 * Header/query `X-WLYA-Seed` / `seed` is the **channel id** (Redis namespace + HMAC key),
 * not the client AES payload secret. The tunnel encrypts blobs before POST; this server
 * never sees that secret. Protocol field names stay `seed` for compatibility.
 */

export const HMAC_SKEW_SEC = Number(process.env.HMAC_SKEW_SEC ?? 120);

export function seedHash(seed: string): string {
  return createHash("sha256").update(seed, "utf8").digest("hex");
}

export function hmacKey(seed: string): Buffer {
  return createHash("sha256").update(seed, "utf8").digest();
}

export function sign(seed: string, timestamp: string, body = ""): string {
  return createHmac("sha256", hmacKey(seed))
    .update(seed + timestamp + body, "utf8")
    .digest("hex");
}

export function safeEqualHex(a: string, b: string): boolean {
  try {
    const ba = Buffer.from(a, "hex");
    const bb = Buffer.from(b, "hex");
    if (ba.length === 0 || ba.length !== bb.length) return false;
    return timingSafeEqual(ba, bb);
  } catch {
    return false;
  }
}

export type AuthOk = { seed: string; client: string; hash: string };
export type AuthErr = { error: string; status: number };

export function verifyAuth(
  headers: Record<string, string | string[] | undefined>,
  body: string,
  nowSec = Math.floor(Date.now() / 1000),
): AuthOk | AuthErr {
  const seed = header(headers, "x-wlya-seed");
  const client = header(headers, "x-wlya-client");
  const ts = header(headers, "x-wlya-timestamp");
  const sig = header(headers, "x-wlya-sig");
  if (!seed || !client || !ts || !sig) {
    return { error: "missing auth headers", status: 401 };
  }
  const tsNum = Number(ts);
  if (!Number.isFinite(tsNum) || Math.abs(nowSec - tsNum) > HMAC_SKEW_SEC) {
    return { error: "timestamp skew", status: 401 };
  }
  const expected = sign(seed, ts, body);
  if (!safeEqualHex(sig.toLowerCase(), expected)) {
    return { error: "invalid signature", status: 401 };
  }
  return { seed, client, hash: seedHash(seed) };
}

function header(
  headers: Record<string, string | string[] | undefined>,
  name: string,
): string {
  const v = headers[name] ?? headers[name.toLowerCase()];
  if (Array.isArray(v)) return v[0] ?? "";
  return v ?? "";
}
