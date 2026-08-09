import { mkdirSync, readFileSync } from "node:fs";
import { dirname } from "node:path";
import { buildApp } from "./app.js";
import { AmapRoutesClient } from "./amap/routes.js";
import { FirebaseJwtVerifier } from "./auth/firebase.js";
import { loadConfig } from "./config.js";
import { createApiRuntimeMarker, removeApiRuntimeMarker } from "./database-lifecycle.js";
import { GoogleOAuthTokenProvider } from "./google/oauth.js";
import { GoogleRoutesClient } from "./google/routes.js";
import { JsonSafeLogger } from "./logging.js";
import { SqliteQuotaLedger } from "./quota/ledger.js";
import { TokenBucketLimiter } from "./rate-limit.js";
import { TerritoryRegionClassifier } from "./region/classifier.js";
import { V2RoutingService } from "./routing/service.js";

const logger = new JsonSafeLogger();
const config = loadConfig();
mkdirSync(dirname(config.databasePath), { recursive: true, mode: 0o700 });
const runtimeMarker = createApiRuntimeMarker(config.databasePath);
let runtimeMarkerReleased = false;
const releaseRuntimeMarker = (): void => {
  if (runtimeMarkerReleased) return;
  removeApiRuntimeMarker(runtimeMarker);
  runtimeMarkerReleased = true;
};

try {
  const quota = SqliteQuotaLedger.open(config.databasePath, config.amap?.limits);
  const oauth = GoogleOAuthTokenProvider.fromFile(config.serviceAccountFile);
  const google = new GoogleRoutesClient({ projectId: config.projectId, oauth });
  const regions = loadRegions(config.regionDataFile);
  const amap = config.amap === undefined ? undefined : loadAmap(
    config.amap.apiKeyFile,
    config.amap.signatureSecretFile,
  );
  const routing = regions === undefined ? undefined : new V2RoutingService({
    regions,
    google,
    ...(amap === undefined ? {} : { amap }),
    googleQuota: quota,
    upstreamQuota: quota,
  });
  const app = buildApp({
    auth: new FirebaseJwtVerifier(config.projectId),
    routes: google,
    quota,
    rateLimiter: new TokenBucketLimiter({
      ipCapacity: 60,
      ipRefillPerSecond: 5,
      ipHmacKey: config.ipHmacKey,
    }),
    logger,
    v2: {
      ...(routing === undefined ? {} : { routing }),
      minimumAppVersion: config.minimumAppVersion,
      v1SunsetAt: config.v1SunsetAt,
      googleAvailable: true,
      amapAvailable: amap !== undefined,
    },
  });
  await app.listen({ host: config.host, port: config.port });
  logger.write({ level: "info", event: "startup" });

  for (const signal of ["SIGINT", "SIGTERM"] as const) {
    process.once(signal, () => {
      void app.close()
        .then(() => {
          releaseRuntimeMarker();
        })
        .catch(() => {
          // Leave the marker behind when close is uncertain so restore stays blocked.
        })
        .finally(() => {
          logger.write({ level: "info", event: "shutdown" });
          process.exit(0);
        });
    });
  }
} catch (error) {
  // Startup may already hold SQLite. Keep the marker for explicit stale-marker recovery.
  throw error;
}

function loadRegions(path: string): TerritoryRegionClassifier | undefined {
  try {
    return TerritoryRegionClassifier.load(path);
  } catch {
    return undefined;
  }
}

function loadAmap(apiKeyFile: string, signatureSecretFile: string): AmapRoutesClient | undefined {
  try {
    return new AmapRoutesClient({
      apiKey: readSecret(apiKeyFile),
      signingSecret: readSecret(signatureSecretFile),
    });
  } catch {
    return undefined;
  }
}

function readSecret(path: string): string {
  const bytes = readFileSync(path);
  if (bytes.byteLength === 0 || bytes.byteLength > 4_096) throw new Error("Secret file is invalid");
  const value = bytes.toString("utf8").trim();
  if (value.length === 0) throw new Error("Secret file is invalid");
  return value;
}
