import { test } from "node:test";
import assert from "node:assert/strict";
import { sign, verifyAuth } from "./auth.js";

test("verifyAuth accepts matching HMAC", () => {
  const seed = "channel-seed";
  const ts = "1700000000";
  const body = '{"messages":[]}';
  const sig = sign(seed, ts, body);
  const result = verifyAuth(
    {
      "x-wlya-seed": seed,
      "x-wlya-client": "client-1",
      "x-wlya-timestamp": ts,
      "x-wlya-sig": sig,
    },
    body,
    1700000000,
  );
  assert.equal("seed" in result, true);
  if ("seed" in result) {
    assert.equal(result.client, "client-1");
    assert.equal(result.hash.length, 64);
  }
});

test("verifyAuth rejects bad signature", () => {
  const result = verifyAuth(
    {
      "x-wlya-seed": "s",
      "x-wlya-client": "c",
      "x-wlya-timestamp": "1700000000",
      "x-wlya-sig": "00".repeat(32),
    },
    "",
    1700000000,
  );
  assert.equal("error" in result, true);
});

test("verifyAuth rejects skew", () => {
  const seed = "s";
  const ts = "1000";
  const sig = sign(seed, ts, "");
  const result = verifyAuth(
    {
      "x-wlya-seed": seed,
      "x-wlya-client": "c",
      "x-wlya-timestamp": ts,
      "x-wlya-sig": sig,
    },
    "",
    1700000000,
  );
  assert.equal("error" in result, true);
  if ("error" in result) assert.equal(result.error, "timestamp skew");
});
