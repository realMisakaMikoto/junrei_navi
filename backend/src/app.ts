import Fastify, { type FastifyInstance, type FastifyRequest } from "fastify";
import type {
  MatrixRequest,
  NavigationReservationRequest,
  RouteRequest,
  V2NavigationReservationRequest,
  V2Policy,
} from "./contract.js";
import { ApiError, errorBody } from "./errors.js";
import type { FirebaseTokenVerifier } from "./auth/firebase.js";
import type { RoutesProvider } from "./google/routes.js";
import { latencyBucket, type SafeLogger } from "./logging.js";
import type { QuotaLedger } from "./quota/ledger.js";
import { TokenBucketLimiter } from "./rate-limit.js";
import type { V2RoutingService } from "./routing/service.js";
import {
  matrixBodySchema,
  navigationReservationBodySchema,
  routeBodySchema,
  v2NavigationReservationBodySchema,
} from "./schemas.js";

export type V2AppDependencies = Readonly<{
  routing?: V2RoutingService;
  minimumAppVersion: string;
  v1SunsetAt: string;
  googleAvailable: boolean;
  amapAvailable: boolean;
}>;

export type AppDependencies = Readonly<{
  auth: FirebaseTokenVerifier;
  routes: RoutesProvider;
  quota: QuotaLedger;
  rateLimiter: TokenBucketLimiter;
  logger: SafeLogger;
  allowInsecureForTests?: boolean;
  nowMillis?: () => number;
  v2?: V2AppDependencies;
}>;

export function buildApp(dependencies: AppDependencies): FastifyInstance {
  validateV2Configuration(dependencies.v2);
  const app = Fastify({
    logger: false,
    bodyLimit: 16 * 1_024,
    ajv: { customOptions: { removeAdditional: false } },
    // The container port is published on host loopback only. The configured
    // reverse proxy overwrites forwarded headers, so the container trusts that hop.
    trustProxy: true,
  });
  const requestStartedAt = new WeakMap<FastifyRequest, number>();
  const requestErrorCode = new WeakMap<FastifyRequest, string>();

  app.addHook("onRequest", async (request) => {
    requestStartedAt.set(request, performance.now());
  });

  app.addHook("preHandler", async (request) => {
    if (request.method !== "POST") return;
    const endpoint = routeTemplate(request);
    if (endpoint.startsWith("/v1/") && v1Expired(dependencies)) {
      throw new ApiError("CLIENT_UPGRADE_REQUIRED");
    }
    if (!dependencies.allowInsecureForTests && request.protocol !== "https") {
      throw new ApiError("INVALID_ARGUMENT");
    }
    if (!isJsonContentType(request.headers["content-type"])) {
      throw new ApiError("INVALID_ARGUMENT");
    }

    const authorization = request.headers.authorization;
    if (typeof authorization !== "string" || !authorization.startsWith("Bearer ")) {
      throw new ApiError("UNAUTHENTICATED");
    }
    const token = authorization.slice("Bearer ".length);
    if (token.length === 0 || token.length > 8_192) throw new ApiError("UNAUTHENTICATED");
    await dependencies.auth.verify(token);
    if (!dependencies.rateLimiter.consume(request.ip)) {
      throw new ApiError("RATE_LIMITED", { retryAfterSeconds: 1 });
    }
    if (endpoint.startsWith("/v2/")) requireV2Headers(request, dependencies.v2);
  });

  app.get("/v1/health", async (_request, reply) => {
    const health = dependencies.quota.health();
    const available = health.healthy && health.billingEnabled;
    return reply.status(available ? 200 : 503).send({
      service: available ? "ok" : "unavailable",
      database: health.healthy ? "ok" : "unavailable",
    });
  });

  app.get("/v2/health", async (_request, reply) => {
    const databaseHealth = dependencies.quota.health();
    const v2 = dependencies.v2;
    const health = {
      database: databaseHealth.healthy ? "ok" : "unavailable",
      regionData: v2?.routing === undefined ? "unavailable" : "ok",
      google: v2?.googleAvailable === true && databaseHealth.healthy && databaseHealth.billingEnabled
        ? "ok"
        : "unavailable",
      amap: v2?.amapAvailable === true && v2.routing?.amapEnabled === true ? "ok" : "unavailable",
    } as const;
    const available = Object.values(health).every((value) => value === "ok");
    return reply.status(available ? 200 : 503).send(health);
  });

  app.get("/v2/policy", async (_request, reply) => {
    const v2 = dependencies.v2;
    const routing = v2?.routing;
    if (v2 === undefined || routing === undefined) throw new ApiError("BACKEND_UNAVAILABLE");
    const databaseHealth = dependencies.quota.health();
    const policy: V2Policy = {
      apiVersion: "2",
      regionDataVersion: routing.regionDataVersion,
      minimumAppVersion: v2.minimumAppVersion,
      v1SunsetAt: v2.v1SunsetAt,
      providers: {
        google: v2.googleAvailable && databaseHealth.healthy && databaseHealth.billingEnabled
          ? "enabled"
          : "disabled",
        amap: v2.amapAvailable && routing.amapEnabled ? "enabled" : "disabled",
      },
    };
    return reply.send(policy);
  });

  app.post<{ Body: MatrixRequest }>(
    "/v1/matrix",
    { schema: { body: matrixBodySchema } },
    async (request) => {
      dependencies.v2?.routing?.assertV1GoogleCoordinates(request.body.coordinates);
      requireV1RegionSafety(dependencies);
      const elementCount = request.body.coordinates.length ** 2;
      dependencies.quota.reserve({ bucket: "matrix", units: elementCount });
      return dependencies.routes.matrix(request.body);
    },
  );

  app.post<{ Body: RouteRequest }>(
    "/v1/route",
    { schema: { body: routeBodySchema } },
    async (request) => {
      if (request.body.mode === "TRANSIT" && request.body.locations.length !== 2) {
        throw new ApiError("INVALID_ARGUMENT");
      }
      dependencies.v2?.routing?.assertV1GoogleCoordinates(request.body.locations);
      requireV1RegionSafety(dependencies);
      validateTransitTimeWindow(request.body, dependencies.nowMillis?.() ?? Date.now());
      dependencies.quota.reserve({ bucket: "route", units: 1 });
      return dependencies.routes.route(request.body);
    },
  );

  app.post<{ Body: MatrixRequest }>(
    "/v2/matrix",
    { schema: { body: matrixBodySchema } },
    async (request) => requireV2Routing(dependencies).matrix(request.body),
  );

  app.post<{ Body: RouteRequest }>(
    "/v2/route",
    { schema: { body: routeBodySchema } },
    async (request) => {
      if (request.body.mode === "TRANSIT" && request.body.locations.length !== 2) {
        throw new ApiError("INVALID_ARGUMENT");
      }
      const routing = requireV2Routing(dependencies);
      const selection = routingPreview(request.body.locations, request.body.mode, routing);
      if (selection === "GOOGLE") {
        validateTransitTimeWindow(request.body, dependencies.nowMillis?.() ?? Date.now());
      }
      return routing.route(request.body);
    },
  );

  app.post<{ Body: V2NavigationReservationRequest }>(
    "/v2/navigation/reserve",
    { schema: { body: v2NavigationReservationBodySchema } },
    async (request) => requireV2Routing(dependencies).reserveNavigation(request.body),
  );

  app.post<{ Body: NavigationReservationRequest }>(
    "/v1/navigation/reserve",
    { schema: { body: navigationReservationBodySchema } },
    async (request) => {
      dependencies.quota.reserve({
        bucket: "navigation",
        units: request.body.destinationCount,
      });
      return {
        reservedDestinations: request.body.destinationCount,
        // Public v0.2.3 clients require this legacy field while deserializing.
        remainingToday: LEGACY_UNBOUNDED_REMAINING_TODAY,
      };
    },
  );

  app.setErrorHandler((error, request, reply) => {
    const apiError = normalizeError(error);
    requestErrorCode.set(request, apiError.code);
    if (apiError.retryAfterSeconds !== undefined) {
      reply.header("Retry-After", String(apiError.retryAfterSeconds));
    }
    void reply.status(apiError.statusCode).send(errorBody(apiError));
  });

  app.addHook("onResponse", async (request, reply) => {
    const start = requestStartedAt.get(request) ?? performance.now();
    const errorCode = requestErrorCode.get(request);
    dependencies.logger.write({
      level: reply.statusCode >= 500 ? "error" : reply.statusCode >= 400 ? "warn" : "info",
      event: "request_complete",
      endpoint: routeTemplate(request),
      statusCode: reply.statusCode,
      latencyBucket: latencyBucket(performance.now() - start),
      ...(errorCode === undefined ? {} : { errorCode }),
    });
  });

  app.addHook("onClose", async () => {
    dependencies.quota.close();
  });

  return app;
}

const DAY_MILLIS = 24 * 60 * 60 * 1_000;
const LEGACY_UNBOUNDED_REMAINING_TODAY = 2_147_483_647;

function validateTransitTimeWindow(request: RouteRequest, nowMillis: number): void {
  if (request.mode !== "TRANSIT") return;
  const value = request.departureTime ?? request.arrivalTime;
  if (value === undefined) return;
  const timeMillis = Date.parse(value);
  if (
    !Number.isFinite(timeMillis) ||
    timeMillis < nowMillis - 7 * DAY_MILLIS ||
    timeMillis > nowMillis + 100 * DAY_MILLIS
  ) {
    throw new ApiError("INVALID_ARGUMENT");
  }
}

function normalizeError(error: unknown): ApiError {
  if (error instanceof ApiError) return error;
  if (
    typeof error === "object" &&
    error !== null &&
    ("validation" in error || ("code" in error && error.code === "FST_ERR_CTP_BODY_TOO_LARGE"))
  ) {
    return new ApiError("INVALID_ARGUMENT", { cause: error });
  }
  return new ApiError("BACKEND_UNAVAILABLE", { cause: error });
}

function isJsonContentType(value: string | undefined): boolean {
  return value?.split(";", 1)[0]?.trim().toLowerCase() === "application/json";
}

function routeTemplate(request: FastifyRequest): string {
  return request.routeOptions.url ?? "unknown";
}

function v1Expired(dependencies: AppDependencies): boolean {
  const sunset = dependencies.v2?.v1SunsetAt;
  return sunset !== undefined && (dependencies.nowMillis?.() ?? Date.now()) >= Date.parse(sunset);
}

function requireV2Headers(
  request: FastifyRequest,
  v2: V2AppDependencies | undefined,
): void {
  const routing = v2?.routing;
  if (v2 === undefined || routing === undefined) throw new ApiError("BACKEND_UNAVAILABLE");
  const appVersion = request.headers["x-anitabi-app-version"];
  if (
    typeof appVersion !== "string" ||
    !versionAtLeast(appVersion, v2.minimumAppVersion)
  ) {
    throw new ApiError("CLIENT_UPGRADE_REQUIRED");
  }
  const header = request.headers["x-anitabi-region-data-version"];
  if (typeof header !== "string" || header !== routing.regionDataVersion) {
    throw new ApiError("REGION_DATA_OUTDATED");
  }
}

function versionAtLeast(candidate: string, minimum: string): boolean {
  const candidateParts = parseVersion(candidate);
  const minimumParts = parseVersion(minimum);
  if (candidateParts === undefined || minimumParts === undefined) return false;
  for (let index = 0; index < 3; index += 1) {
    const candidatePart = candidateParts[index] ?? 0;
    const minimumPart = minimumParts[index] ?? 0;
    if (candidatePart !== minimumPart) return candidatePart > minimumPart;
  }
  return true;
}

function parseVersion(value: string): readonly [number, number, number] | undefined {
  if (value.length > 64) return undefined;
  const match = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.exec(value);
  if (match === null) return undefined;
  const parts = match.slice(1, 4).map(Number);
  if (!parts.every(Number.isSafeInteger)) return undefined;
  return [parts[0] ?? 0, parts[1] ?? 0, parts[2] ?? 0];
}

function validateV2Configuration(v2: V2AppDependencies | undefined): void {
  if (v2 === undefined) return;
  if (parseVersion(v2.minimumAppVersion) === undefined) {
    throw new Error("The minimum app version must be a stable semantic version");
  }
  const sunsetMillis = Date.parse(v2.v1SunsetAt);
  if (!isCanonicalUtcInstant(v2.v1SunsetAt, sunsetMillis)) {
    throw new Error("The v1 sunset must be a UTC instant");
  }
}

function isCanonicalUtcInstant(value: string, milliseconds: number): boolean {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/.test(value)) return false;
  if (!Number.isFinite(milliseconds)) return false;
  const canonical = value.includes(".") ? value : value.replace("Z", ".000Z");
  return new Date(milliseconds).toISOString() === canonical;
}

function requireV2Routing(dependencies: AppDependencies): V2RoutingService {
  const routing = dependencies.v2?.routing;
  if (routing === undefined) throw new ApiError("BACKEND_UNAVAILABLE");
  return routing;
}

function requireV1RegionSafety(dependencies: AppDependencies): void {
  if (dependencies.v2 !== undefined && dependencies.v2.routing === undefined) {
    throw new ApiError("BACKEND_UNAVAILABLE");
  }
}

function routingPreview(
  coordinates: readonly { latitude: number; longitude: number }[],
  mode: string,
  routing: V2RoutingService,
): "GOOGLE" | "AMAP" {
  return routing.providerFor(coordinates, mode);
}
