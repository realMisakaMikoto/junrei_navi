import { createHmac } from "node:crypto";

type Bucket = {
  tokens: number;
  updatedAtMillis: number;
};

const DEFAULT_MAX_IP_BUCKETS = 10_000;
const DEFAULT_IP_BUCKET_IDLE_TTL_MILLIS = 15 * 60 * 1_000;

export type TokenBucketOptions = Readonly<{
  ipCapacity: number;
  ipRefillPerSecond: number;
  ipHmacKey: Uint8Array;
  maxIpBuckets?: number;
  ipBucketIdleTtlMillis?: number;
  now?: () => number;
}>;

export class TokenBucketLimiter {
  private readonly ipBuckets = new Map<string, Bucket>();
  private readonly now: () => number;
  private readonly maxIpBuckets: number;
  private readonly ipBucketIdleTtlMillis: number;
  private lastObservedMillis = Number.NEGATIVE_INFINITY;

  constructor(private readonly options: TokenBucketOptions) {
    if (options.ipHmacKey.byteLength < 32) {
      throw new Error("The IP HMAC key must contain at least 32 bytes");
    }
    this.maxIpBuckets = options.maxIpBuckets ?? DEFAULT_MAX_IP_BUCKETS;
    this.ipBucketIdleTtlMillis =
      options.ipBucketIdleTtlMillis ?? DEFAULT_IP_BUCKET_IDLE_TTL_MILLIS;
    if (!Number.isSafeInteger(this.maxIpBuckets) || this.maxIpBuckets < 1) {
      throw new Error("The maximum IP bucket count must be a positive safe integer");
    }
    if (
      !Number.isSafeInteger(this.ipBucketIdleTtlMillis)
      || this.ipBucketIdleTtlMillis < 1
    ) {
      throw new Error("The IP bucket idle TTL must be a positive safe integer");
    }
    this.now = options.now ?? Date.now;
  }

  consume(rawIp: string): boolean {
    const now = Math.max(this.lastObservedMillis, this.now());
    this.lastObservedMillis = now;
    this.evictStale(now);
    const ipKey = createHmac("sha256", this.options.ipHmacKey).update(rawIp).digest("hex");
    if (!this.ipBuckets.has(ipKey) && this.ipBuckets.size >= this.maxIpBuckets) {
      const oldestKey = this.ipBuckets.keys().next().value as string | undefined;
      if (oldestKey !== undefined) this.ipBuckets.delete(oldestKey);
    }
    return this.take(
      this.ipBuckets,
      ipKey,
      this.options.ipCapacity,
      this.options.ipRefillPerSecond,
      now,
    );
  }

  private take(
    buckets: Map<string, Bucket>,
    key: string,
    capacity: number,
    refillPerSecond: number,
    now: number,
  ): boolean {
    const existing = buckets.get(key) ?? { tokens: capacity, updatedAtMillis: now };
    const elapsedSeconds = Math.max(0, now - existing.updatedAtMillis) / 1_000;
    const tokens = Math.min(capacity, existing.tokens + elapsedSeconds * refillPerSecond);
    if (tokens < 1) {
      this.touch(buckets, key, { tokens, updatedAtMillis: now });
      return false;
    }
    this.touch(buckets, key, { tokens: tokens - 1, updatedAtMillis: now });
    return true;
  }

  private evictStale(now: number): void {
    for (const [key, bucket] of this.ipBuckets) {
      if (Math.max(0, now - bucket.updatedAtMillis) < this.ipBucketIdleTtlMillis) break;
      this.ipBuckets.delete(key);
    }
  }

  private touch(buckets: Map<string, Bucket>, key: string, bucket: Bucket): void {
    buckets.delete(key);
    buckets.set(key, bucket);
  }
}
