import { readFileSync } from "node:fs";
import { ApiError } from "./errors.js";
import {
  AMAP_UPSTREAM_BUCKETS,
  type AmapUpstreamBucket,
  type AmapUpstreamLimits,
} from "./quota/ledger.js";

export type AmapRuntimeConfig = Readonly<{
  apiKeyFile: string;
  signatureSecretFile: string;
  limits: AmapUpstreamLimits;
}>;

export type RuntimeConfig = Readonly<{
  projectId: string;
  serviceAccountFile: string;
  ipHmacKey: Uint8Array;
  databasePath: string;
  host: string;
  port: number;
  regionDataFile: string;
  minimumAppVersion: string;
  v1SunsetAt: string;
  amap?: AmapRuntimeConfig;
}>;

export function loadConfig(environment: NodeJS.ProcessEnv = process.env): RuntimeConfig {
  const firebaseProjectId = required(environment, "ANITABI_FIREBASE_PROJECT_ID");
  const googleProjectId = required(environment, "ANITABI_GOOGLE_PROJECT_ID");
  if (firebaseProjectId !== googleProjectId) {
    throw new ApiError("BACKEND_UNAVAILABLE");
  }
  const hmacFile = environment["ANITABI_IP_HMAC_KEY_FILE"] ?? "/run/secrets/ip_hmac_key";
  let ipHmacKey: Uint8Array;
  try {
    ipHmacKey = readFileSync(hmacFile);
  } catch (error) {
    throw new ApiError("BACKEND_UNAVAILABLE", { cause: error });
  }
  if (ipHmacKey.byteLength < 32) throw new ApiError("BACKEND_UNAVAILABLE");

  const port = Number(environment["ANITABI_PORT"] ?? "8787");
  if (!Number.isSafeInteger(port) || port < 1 || port > 65_535) {
    throw new ApiError("BACKEND_UNAVAILABLE");
  }
  const minimumAppVersion = environment["ANITABI_MINIMUM_APP_VERSION"] ?? "0.2.5";
  if (!/^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)$/.test(minimumAppVersion)) {
    throw new ApiError("BACKEND_UNAVAILABLE");
  }
  const releaseAt = required(environment, "ANITABI_V025_RELEASE_AT");
  const releaseAtMillis = Date.parse(releaseAt);
  if (!isCanonicalUtcInstant(releaseAt, releaseAtMillis)) {
    throw new ApiError("BACKEND_UNAVAILABLE");
  }
  const v1SunsetAt = new Date(releaseAtMillis + 14 * 24 * 60 * 60 * 1_000).toISOString();
  const amap = loadAmapConfig(environment);
  return {
    projectId: googleProjectId,
    serviceAccountFile:
      environment["ANITABI_SERVICE_ACCOUNT_FILE"] ?? "/run/secrets/google_service_account.json",
    ipHmacKey,
    databasePath: environment["ANITABI_DATABASE_PATH"] ?? "/data/anitabi.sqlite",
    host: environment["ANITABI_HOST"] ?? "0.0.0.0",
    port,
    regionDataFile:
      environment["ANITABI_REGION_DATA_FILE"] ?? "/run/region-data/territories.json",
    minimumAppVersion,
    v1SunsetAt,
    ...(amap === undefined ? {} : { amap }),
  };
}

function required(environment: NodeJS.ProcessEnv, name: string): string {
  const value = environment[name];
  if (value === undefined || value.length === 0 || value.length > 128) {
    throw new ApiError("BACKEND_UNAVAILABLE");
  }
  return value;
}

function loadAmapConfig(environment: NodeJS.ProcessEnv): AmapRuntimeConfig | undefined {
  if (environment["ANITABI_AMAP_ENABLED"] !== "1") return undefined;
  const limits = {} as Record<AmapUpstreamBucket, { daily: number; monthly: number }>;
  for (const bucket of AMAP_UPSTREAM_BUCKETS) {
    const prefix = `ANITABI_AMAP_${bucket.toUpperCase()}`;
    const daily = positiveInteger(environment[`${prefix}_DAILY_LIMIT`]);
    const monthly = positiveInteger(environment[`${prefix}_MONTHLY_LIMIT`]);
    if (daily === undefined || monthly === undefined || daily > monthly) return undefined;
    limits[bucket] = { daily, monthly };
  }
  return {
    apiKeyFile: environment["ANITABI_AMAP_API_KEY_FILE"] ?? "/run/secrets/amap_web_key",
    signatureSecretFile:
      environment["ANITABI_AMAP_SIGNATURE_SECRET_FILE"] ?? "/run/secrets/amap_signature_secret",
    limits,
  };
}

function positiveInteger(value: string | undefined): number | undefined {
  if (value === undefined || !/^\d+$/.test(value)) return undefined;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined;
}

function isCanonicalUtcInstant(value: string, milliseconds: number): boolean {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(value)) return false;
  if (!Number.isFinite(milliseconds)) return false;
  const canonical = value.includes(".") ? value : value.replace("Z", ".000Z");
  return new Date(milliseconds).toISOString() === canonical;
}
