import assert from "node:assert/strict";
import { test } from "node:test";
import { TokenBucketLimiter } from "../src/rate-limit.js";

test("IP token buckets limit bursts and refill over time", () => {
  let now = 1_000;
  const limiter = new TokenBucketLimiter({
    ipCapacity: 2,
    ipRefillPerSecond: 1,
    ipHmacKey: new Uint8Array(32).fill(7),
    now: () => now,
  });
  assert.equal(limiter.consume("192.0.2.10"), true);
  assert.equal(limiter.consume("192.0.2.10"), true);
  assert.equal(limiter.consume("192.0.2.10"), false);
  now += 1_000;
  assert.equal(limiter.consume("192.0.2.10"), true);
});

test("production token settings retain a 60-request burst and five-per-second refill", () => {
  let now = 1_000;
  const limiter = new TokenBucketLimiter({
    ipCapacity: 60,
    ipRefillPerSecond: 5,
    ipHmacKey: new Uint8Array(32).fill(8),
    now: () => now,
  });
  for (let request = 0; request < 60; request += 1) {
    assert.equal(limiter.consume("192.0.2.10"), true);
  }
  assert.equal(limiter.consume("192.0.2.10"), false);
  now += 199;
  assert.equal(limiter.consume("192.0.2.10"), false);
  now += 1;
  assert.equal(limiter.consume("192.0.2.10"), true);
});

test("different IP addresses have independent buckets", () => {
  const limiter = new TokenBucketLimiter({
    ipCapacity: 1,
    ipRefillPerSecond: 0,
    ipHmacKey: new Uint8Array(32).fill(9),
  });
  assert.equal(limiter.consume("192.0.2.10"), true);
  assert.equal(limiter.consume("192.0.2.10"), false);
  assert.equal(limiter.consume("192.0.2.11"), true);
});

test("short HMAC keys are rejected", () => {
  assert.throws(
    () =>
      new TokenBucketLimiter({
        ipCapacity: 1,
        ipRefillPerSecond: 1,
        ipHmacKey: new Uint8Array(31),
      }),
    /at least 32 bytes/,
  );
});

test("stale HMAC buckets expire after the configured idle TTL", () => {
  let now = 1_000;
  const limiter = new TokenBucketLimiter({
    ipCapacity: 1,
    ipRefillPerSecond: 1,
    ipHmacKey: new Uint8Array(32).fill(10),
    maxIpBuckets: 2,
    ipBucketIdleTtlMillis: 1_000,
    now: () => now,
  });
  limiter.consume("192.0.2.10");
  now += 999;
  limiter.consume("192.0.2.11");
  assert.equal(ipBuckets(limiter).size, 2);
  now += 1;
  limiter.consume("192.0.2.12");
  assert.equal(ipBuckets(limiter).size, 2);
});

test("unique-IP churn is bounded and retains only HMAC digests", () => {
  const limiter = new TokenBucketLimiter({
    ipCapacity: 60,
    ipRefillPerSecond: 5,
    ipHmacKey: new Uint8Array(32).fill(11),
    maxIpBuckets: 32,
    ipBucketIdleTtlMillis: 60_000,
    now: () => 1_000,
  });
  for (let index = 0; index < 10_000; index += 1) {
    limiter.consume(`192.0.2.${index}`);
  }
  const buckets = ipBuckets(limiter);
  assert.equal(buckets.size, 32);
  assert.equal([...buckets.keys()].every((key) => /^[0-9a-f]{64}$/.test(key)), true);
  assert.equal([...buckets.keys()].some((key) => key.includes("192.0.2.")), false);
});

function ipBuckets(limiter: TokenBucketLimiter): Map<string, unknown> {
  return (limiter as unknown as { ipBuckets: Map<string, unknown> }).ipBuckets;
}
