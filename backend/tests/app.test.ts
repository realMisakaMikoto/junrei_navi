import assert from "node:assert/strict";
import { test } from "node:test";
import { buildApp } from "../src/app.js";
import type { FirebaseTokenVerifier } from "../src/auth/firebase.js";
import type { MatrixRequest, RouteRequest } from "../src/contract.js";
import { ApiError } from "../src/errors.js";
import type { RoutesProvider } from "../src/google/routes.js";
import type { SafeLogEvent } from "../src/logging.js";
import type {
  LedgerHealth,
  QuotaLedger,
  QuotaReservation,
  QuotaReservationResult,
  UpstreamReservation,
} from "../src/quota/ledger.js";
import { TokenBucketLimiter } from "../src/rate-limit.js";
import { V2RoutingService, type AmapRoutesProvider } from "../src/routing/service.js";
import { syntheticRegionClassifier } from "./region-fixture.js";

test("POST endpoints require authentication, JSON, valid bounds, and transit pairs", async () => {
  const fixture = createApp();
  try {
    const unauthenticated = await fixture.app.inject({
      method: "POST",
      url: "/v1/matrix",
      payload: validMatrix(),
    });
    assert.equal(unauthenticated.statusCode, 401);
    assert.equal(unauthenticated.json().error.code, "UNAUTHENTICATED");

    const invalidCoordinate = await fixture.app.inject(authenticated({
      method: "POST",
      url: "/v1/matrix",
      payload: { ...validMatrix(), coordinates: [{ latitude: 91, longitude: 0 }, { latitude: 0, longitude: 0 }] },
    }));
    assert.equal(invalidCoordinate.statusCode, 400);
    assert.equal(invalidCoordinate.json().error.code, "INVALID_ARGUMENT");

    const tooManyTransitLocations = await fixture.app.inject(authenticated({
      method: "POST",
      url: "/v1/route",
      payload: {
        mode: "TRANSIT",
        locations: [
          { latitude: 35, longitude: 139 },
          { latitude: 35.1, longitude: 139.1 },
          { latitude: 35.2, longitude: 139.2 },
        ],
      },
    }));
    assert.equal(tooManyTransitLocations.statusCode, 400);
    assert.equal(tooManyTransitLocations.json().error.code, "INVALID_ARGUMENT");

    const nonJson = await fixture.app.inject({
      method: "POST",
      url: "/v1/navigation/reserve",
      headers: { authorization: "Bearer valid-token", "content-type": "text/plain" },
      payload: "plain text",
    });
    assert.equal(nonJson.statusCode, 400);
    assert.equal(nonJson.json().error.code, "INVALID_ARGUMENT");
  } finally {
    await fixture.app.close();
  }
});

test("route-only transit fields fail validation before quota and provider calls", async () => {
  const fixture = createApp();
  const locations = [
    { latitude: 35, longitude: 139 },
    { latitude: 35.1, longitude: 139.1 },
  ];
  const invalidPayloads = [
    {
      mode: "TRANSIT",
      locations,
      departureTime: "2026-07-24T00:00:00Z",
      arrivalTime: "2026-07-30T02:00:00Z",
    },
    { mode: "WALK", locations, departureTime: "2026-07-30T01:00:00Z" },
    { mode: "DRIVE", locations, arrivalTime: "2026-07-30T02:00:00Z" },
    { mode: "BICYCLE", locations, transitRoutingPreference: "LESS_WALKING" },
    { mode: "WALK", locations, transitTravelModes: ["BUS"] },
    { mode: "TRANSIT", locations, transitRoutingPreference: "RECOMMENDED" },
    { mode: "TRANSIT", locations, transitTravelModes: [] },
    { mode: "TRANSIT", locations, transitTravelModes: ["BUS", "BUS"] },
    { mode: "TRANSIT", locations, transitTravelModes: ["BUS", "SUBWAY", "TRAIN", "LIGHT_RAIL", "RAIL", "BUS"] },
    { mode: "TRANSIT", locations, transitTravelModes: ["FERRY"] },
  ];

  try {
    for (const payload of invalidPayloads) {
      const response = await fixture.app.inject(authenticated({
        method: "POST",
        url: "/v1/route",
        payload,
      }));
      assert.equal(response.statusCode, 400);
      assert.equal(response.json().error.code, "INVALID_ARGUMENT");
    }
    assert.deepEqual(fixture.ledger.reservations, []);
    assert.equal(fixture.routes.routeCalls, 0);
  } finally {
    await fixture.app.close();
  }
});

test("route schema accepts supported transit preferences and travel-mode bounds", async () => {
  const fixture = createApp();
  const locations = [
    { latitude: 35, longitude: 139 },
    { latitude: 35.1, longitude: 139.1 },
  ];
  const validPayloads = [
    {
      mode: "TRANSIT",
      locations,
      departureTime: "2026-07-24T00:00:00Z",
      transitRoutingPreference: "LESS_WALKING",
      transitTravelModes: ["BUS"],
    },
    {
      mode: "TRANSIT",
      locations,
      arrivalTime: "2026-11-08T00:00:00Z",
      transitRoutingPreference: "FEWER_TRANSFERS",
      transitTravelModes: ["BUS", "SUBWAY", "TRAIN", "LIGHT_RAIL", "RAIL"],
    },
  ];

  try {
    for (const payload of validPayloads) {
      const response = await fixture.app.inject(authenticated({
        method: "POST",
        url: "/v1/route",
        payload,
      }));
      assert.equal(response.statusCode, 200);
    }
    assert.deepEqual(fixture.ledger.reservations, [
      { bucket: "route", units: 1 },
      { bucket: "route", units: 1 },
    ]);
    assert.equal(fixture.routes.routeCalls, 2);
  } finally {
    await fixture.app.close();
  }
});

test("transit time outside the Google window fails before quota and provider calls", async () => {
  const fixture = createApp();
  const locations = [
    { latitude: 35, longitude: 139 },
    { latitude: 35.1, longitude: 139.1 },
  ];
  try {
    for (const time of ["2026-07-23T23:59:59Z", "2026-11-08T00:00:01Z"]) {
      const response = await fixture.app.inject(authenticated({
        method: "POST",
        url: "/v1/route",
        payload: { mode: "TRANSIT", locations, departureTime: time },
      }));
      assert.equal(response.statusCode, 400);
      assert.equal(response.json().error.code, "INVALID_ARGUMENT");
    }
    assert.deepEqual(fixture.ledger.reservations, []);
    assert.equal(fixture.routes.routeCalls, 0);
  } finally {
    await fixture.app.close();
  }
});

test("matrix reserves billable elements and navigation reserves destinations", async () => {
  const fixture = createApp();
  try {
    const matrix = await fixture.app.inject(authenticated({
      method: "POST",
      url: "/v1/matrix",
      payload: validMatrix(),
    }));
    assert.equal(matrix.statusCode, 200);
    assert.deepEqual(fixture.ledger.reservations[0], {
      bucket: "matrix",
      units: 4,
    });

    const navigation = await fixture.app.inject(authenticated({
      method: "POST",
      url: "/v1/navigation/reserve",
      payload: { destinationCount: 25 },
    }));
    assert.equal(navigation.statusCode, 200);
    assert.deepEqual(navigation.json(), {
      reservedDestinations: 25,
      remainingToday: 2_147_483_647,
    });
  } finally {
    await fixture.app.close();
  }
});

test("health exposes only service/database state and logs never contain sensitive request data", async () => {
  const fixture = createApp();
  try {
    const secretToken = "private-token-value";
    const coordinate = "35.123456";
    const response = await fixture.app.inject({
      method: "POST",
      url: "/v1/matrix",
      remoteAddress: "192.0.2.123",
      headers: { authorization: `Bearer ${secretToken}`, "content-type": "application/json" },
      payload: {
        mode: "DRIVE",
        coordinates: [
          { latitude: Number(coordinate), longitude: 139 },
          { latitude: 35.2, longitude: 139.2 },
        ],
        objective: "FASTEST",
      },
    });
    assert.equal(response.statusCode, 200);

    const health = await fixture.app.inject({ method: "GET", url: "/v1/health" });
    assert.deepEqual(health.json(), { service: "ok", database: "ok" });
    const logs = JSON.stringify(fixture.logs);
    assert.equal(logs.includes(secretToken), false);
    assert.equal(logs.includes("anonymous-uid"), false);
    assert.equal(logs.includes("192.0.2.123"), false);
    assert.equal(logs.includes(coordinate), false);
  } finally {
    await fixture.app.close();
  }
});

test("quota uncertainty fails closed before the Google provider is called", async () => {
  const fixture = createApp();
  fixture.ledger.reserveError = new ApiError("BACKEND_UNAVAILABLE");
  try {
    const response = await fixture.app.inject(authenticated({
      method: "POST",
      url: "/v1/route",
      payload: {
        mode: "WALK",
        locations: [
          { latitude: 35, longitude: 139 },
          { latitude: 35.1, longitude: 139.1 },
        ],
      },
    }));
    assert.equal(response.statusCode, 503);
    assert.equal(response.json().error.code, "BACKEND_UNAVAILABLE");
    assert.equal(fixture.routes.routeCalls, 0);
  } finally {
    await fixture.app.close();
  }
});

test("local IP rate limiting provides retry timing before quota or Google calls", async () => {
  let now = 0;
  const fixture = createApp(new TokenBucketLimiter({
    ipCapacity: 10,
    ipRefillPerSecond: 1,
    ipHmacKey: new Uint8Array(32).fill(4),
    now: () => now,
  }));
  const request = authenticated({
    method: "POST",
    url: "/v1/route",
    payload: {
      mode: "WALK",
      locations: [
        { latitude: 35, longitude: 139 },
        { latitude: 35.1, longitude: 139.1 },
      ],
    },
  });
  try {
    for (let attempt = 0; attempt < 10; attempt += 1) {
      assert.equal((await fixture.app.inject(request)).statusCode, 200);
    }
    const limited = await fixture.app.inject(request);
    assert.equal(limited.statusCode, 429);
    assert.equal(limited.json().error.code, "RATE_LIMITED");
    assert.equal(limited.headers["retry-after"], "1");
    assert.equal(fixture.ledger.reservations.length, 10);
    assert.equal(fixture.routes.routeCalls, 10);

    now += 999;
    assert.equal((await fixture.app.inject(request)).statusCode, 429);
    assert.equal(fixture.ledger.reservations.length, 10);
    now += 1;
    assert.equal((await fixture.app.inject(request)).statusCode, 200);
    assert.equal(fixture.ledger.reservations.length, 11);
    assert.equal(fixture.routes.routeCalls, 11);
  } finally {
    await fixture.app.close();
  }
});

test("downstream rate errors never advertise a safe local retry", async () => {
  const fixture = createApp();
  fixture.routes.routeError = new ApiError("RATE_LIMITED");
  try {
    const response = await fixture.app.inject(authenticated({
      method: "POST",
      url: "/v1/route",
      payload: {
        mode: "WALK",
        locations: [
          { latitude: 35, longitude: 139 },
          { latitude: 35.1, longitude: 139.1 },
        ],
      },
    }));
    assert.equal(response.statusCode, 429);
    assert.equal(response.headers["retry-after"], undefined);
    assert.equal(fixture.ledger.reservations.length, 1);
    assert.equal(fixture.routes.routeCalls, 1);
  } finally {
    await fixture.app.close();
  }
});

test("route provider failures keep one reservation and redact transit request data", async () => {
  const cases = [
    { code: "NO_ROUTE" as const, status: 404 },
    { code: "UPSTREAM_UNAVAILABLE" as const, status: 503 },
  ];
  for (const failure of cases) {
    const fixture = createApp();
    const departureTime = "2026-07-31T01:23:45Z";
    const upstreamBody = "private-upstream-response";
    fixture.routes.routeError = new ApiError(failure.code, { cause: new Error(upstreamBody) });
    try {
      const response = await fixture.app.inject(authenticated({
        method: "POST",
        url: "/v1/route",
        payload: {
          mode: "TRANSIT",
          locations: [
            { latitude: 35, longitude: 139 },
            { latitude: 35.1, longitude: 139.1 },
          ],
          departureTime,
          transitRoutingPreference: "LESS_WALKING",
          transitTravelModes: ["BUS"],
        },
      }));
      assert.equal(response.statusCode, failure.status);
      assert.equal(response.json().error.code, failure.code);
      assert.deepEqual(fixture.ledger.reservations, [
        { bucket: "route", units: 1 },
      ]);
      assert.equal(fixture.routes.routeCalls, 1);
      const logs = JSON.stringify(fixture.logs);
      assert.equal(logs.includes(departureTime), false);
      assert.equal(logs.includes("LESS_WALKING"), false);
      assert.equal(logs.includes("BUS"), false);
      assert.equal(logs.includes(upstreamBody), false);
    } finally {
      await fixture.app.close();
    }
  }
});

test("v2 policy is public while every v2 POST requires auth and both version headers", async () => {
  const fixture = createV2App();
  try {
    const policy = await fixture.app.inject({ method: "GET", url: "/v2/policy" });
    assert.equal(policy.statusCode, 200);
    assert.deepEqual(policy.json(), {
      apiVersion: "2",
      regionDataVersion: "synthetic-v1",
      minimumAppVersion: "0.2.5",
      v1SunsetAt: "2026-08-20T00:00:00.000Z",
      providers: { google: "enabled", amap: "enabled" },
    });

    const payload = validMatrix();
    const unauthenticated = await fixture.app.inject({ method: "POST", url: "/v2/matrix", payload });
    assert.equal(unauthenticated.statusCode, 401);
    assert.equal(unauthenticated.json().error.code, "UNAUTHENTICATED");

    for (const appVersion of [null, "0.2.4", "0.2.5-rc.1", "invalid"] as const) {
      const response = await fixture.app.inject(v2Authenticated({
        method: "POST",
        url: "/v2/matrix",
        payload,
      }, appVersion, "synthetic-v1"));
      assert.equal(response.statusCode, 426);
      assert.equal(response.json().error.code, "CLIENT_UPGRADE_REQUIRED");
    }
    const outdated = await fixture.app.inject(v2Authenticated({
      method: "POST",
      url: "/v2/matrix",
      payload,
    }, "0.2.5", "old-region-data"));
    assert.equal(outdated.statusCode, 409);
    assert.equal(outdated.json().error.code, "REGION_DATA_OUTDATED");
    const clientSelectedProvider = await fixture.app.inject(v2Authenticated({
      method: "POST",
      url: "/v2/matrix",
      payload: { ...payload, provider: "GOOGLE", coordinateSystem: "WGS84" },
    }));
    assert.equal(clientSelectedProvider.statusCode, 400);
    assert.equal(clientSelectedProvider.json().error.code, "INVALID_ARGUMENT");
    assert.deepEqual(fixture.ledger.reservations, []);
    assert.deepEqual(fixture.ledger.upstreamReservations, []);
  } finally {
    await fixture.app.close();
  }
});

test("v2 independently dispatches providers and rejects mixed or unresolved regions before quota", async () => {
  const fixture = createV2App();
  try {
    const amap = await fixture.app.inject(v2Authenticated({
      method: "POST",
      url: "/v2/matrix",
      payload: {
        mode: "DRIVE",
        coordinates: [
          { latitude: 15, longitude: -55 },
          { latitude: 16, longitude: -54 },
        ],
        objective: "FASTEST",
      },
    }));
    assert.equal(amap.statusCode, 200);
    assert.equal(amap.json().provider, "AMAP");
    assert.equal(amap.json().coordinateSystem, "GCJ02");
    assert.equal(amap.json().regionDataVersion, "synthetic-v1");
    assert.equal(fixture.amap.matrixCalls, 1);
    assert.deepEqual(fixture.ledger.upstreamReservations, [[
      { bucket: "conversion", units: 1 },
      { bucket: "distance", units: 2 },
    ]]);

    const google = await fixture.app.inject(v2Authenticated({
      method: "POST",
      url: "/v2/route",
      payload: {
        mode: "WALK",
        locations: [
          { latitude: -20, longitude: 100 },
          { latitude: -21, longitude: 101 },
        ],
      },
    }));
    assert.equal(google.statusCode, 200);
    assert.equal(google.json().provider, "GOOGLE");
    assert.equal(google.json().coordinateSystem, "WGS84");
    assert.deepEqual(fixture.ledger.reservations, [{ bucket: "route", units: 1 }]);
    assert.equal(fixture.google.routeCalls, 1);

    for (const locations of [
      [
        { latitude: 15, longitude: -55 },
        { latitude: -20, longitude: 100 },
      ],
      [
        { latitude: 15, longitude: -60 },
        { latitude: 15, longitude: -55 },
      ],
    ]) {
      const response = await fixture.app.inject(v2Authenticated({
        method: "POST",
        url: "/v2/route",
        payload: { mode: "WALK", locations },
      }));
      assert.equal(response.statusCode, 422);
    }
    assert.deepEqual(fixture.ledger.reservations, [{ bucket: "route", units: 1 }]);
    assert.equal(fixture.google.routeCalls, 1);
    assert.equal(fixture.amap.routeCalls, 0);
  } finally {
    await fixture.app.close();
  }
});

test("v2 navigation derives provider from origin and destinations without charging Google for AMap", async () => {
  const fixture = createV2App();
  try {
    const amap = await fixture.app.inject(v2Authenticated({
      method: "POST",
      url: "/v2/navigation/reserve",
      payload: {
        origin: { latitude: 15, longitude: -55 },
        destinations: [
          { latitude: 15, longitude: -54 },
          { latitude: 15, longitude: -53 },
        ],
      },
    }));
    assert.deepEqual(amap.json(), {
      reservedDestinations: 2,
      executionStrategy: "EXTERNAL_AMAP_MAINLAND",
      provider: "AMAP",
      coordinateSystem: "WGS84",
      regionDataVersion: "synthetic-v1",
    });
    assert.deepEqual(fixture.ledger.reservations, []);

    const google = await fixture.app.inject(v2Authenticated({
      method: "POST",
      url: "/v2/navigation/reserve",
      payload: {
        origin: { latitude: -20, longitude: 100 },
        destinations: [{ latitude: -21, longitude: 101 }],
      },
    }));
    assert.equal(google.json().executionStrategy, "GOOGLE_NAVIGATION_SDK");
    assert.deepEqual(fixture.ledger.reservations, [{ bucket: "navigation", units: 1 }]);
  } finally {
    await fixture.app.close();
  }
});

test("v1 rejects AMap regions before quota during the compatibility window and all POSTs after sunset", async () => {
  const fixture = createV2App();
  try {
    const china = await fixture.app.inject(authenticated({
      method: "POST",
      url: "/v1/route",
      payload: {
        mode: "WALK",
        locations: [
          { latitude: 15, longitude: -55 },
          { latitude: 15, longitude: -54 },
        ],
      },
    }));
    assert.equal(china.statusCode, 426);
    assert.equal(china.json().error.code, "CLIENT_UPGRADE_REQUIRED");
    assert.deepEqual(fixture.ledger.reservations, []);
    assert.equal(fixture.google.routeCalls, 0);
  } finally {
    await fixture.app.close();
  }

  const expired = createV2App({ nowMillis: () => Date.parse("2026-08-20T00:00:00Z") });
  try {
    for (const request of [
      authenticated({ method: "POST", url: "/v1/matrix", payload: validMatrix() }),
      authenticated({ method: "POST", url: "/v1/navigation/reserve", payload: { destinationCount: 1 } }),
    ]) {
      const response = await expired.app.inject(request);
      assert.equal(response.statusCode, 426);
      assert.equal(response.json().error.code, "CLIENT_UPGRADE_REQUIRED");
    }
    assert.deepEqual(expired.ledger.reservations, []);
  } finally {
    await expired.app.close();
  }
});

test("missing region data boots health-only and fails policy, v1 routing, and v2 routing closed", async () => {
  const fixture = createV2App({ regionUnavailable: true });
  try {
    const health = await fixture.app.inject({ method: "GET", url: "/v2/health" });
    assert.equal(health.statusCode, 503);
    assert.deepEqual(health.json(), {
      database: "ok",
      regionData: "unavailable",
      google: "ok",
      amap: "unavailable",
    });
    const policy = await fixture.app.inject({ method: "GET", url: "/v2/policy" });
    assert.equal(policy.statusCode, 503);
    assert.equal(policy.json().error.code, "BACKEND_UNAVAILABLE");
    const v1 = await fixture.app.inject(authenticated({
      method: "POST",
      url: "/v1/route",
      payload: {
        mode: "WALK",
        locations: [
          { latitude: -20, longitude: 100 },
          { latitude: -21, longitude: 101 },
        ],
      },
    }));
    assert.equal(v1.statusCode, 503);
    const v2 = await fixture.app.inject(v2Authenticated({
      method: "POST",
      url: "/v2/matrix",
      payload: validMatrix(),
    }));
    assert.equal(v2.statusCode, 503);
    assert.deepEqual(fixture.ledger.reservations, []);
  } finally {
    await fixture.app.close();
  }
});

test("v2 health requires configured healthy billing for AMap", async () => {
  const fixture = createV2App({ amapBillingEnabled: false });
  try {
    const health = await fixture.app.inject({ method: "GET", url: "/v2/health" });
    assert.equal(health.statusCode, 503);
    assert.equal(health.json().amap, "unavailable");
    const policy = await fixture.app.inject({ method: "GET", url: "/v2/policy" });
    assert.equal(policy.json().providers.amap, "disabled");
  } finally {
    await fixture.app.close();
  }
});

test("global billing disable leaves database healthy and disables both providers", async () => {
  const fixture = createV2App({ globalBillingEnabled: false });
  try {
    const health = await fixture.app.inject({ method: "GET", url: "/v2/health" });
    assert.equal(health.statusCode, 503);
    assert.deepEqual(health.json(), {
      database: "ok",
      regionData: "ok",
      google: "unavailable",
      amap: "unavailable",
    });
    const policy = await fixture.app.inject({ method: "GET", url: "/v2/policy" });
    assert.equal(policy.statusCode, 200);
    assert.deepEqual(policy.json().providers, { google: "disabled", amap: "disabled" });
  } finally {
    await fixture.app.close();
  }
});

test("invalid v2 minimum or sunset configuration is rejected at startup", () => {
  const fixture = createV2Dependencies();
  assert.throws(() => buildApp({ ...fixture.dependencies, v2: {
    ...fixture.dependencies.v2!,
    minimumAppVersion: "0.2.5-rc.1",
  } }), /stable semantic version/);
  assert.throws(() => buildApp({ ...fixture.dependencies, v2: {
    ...fixture.dependencies.v2!,
    v1SunsetAt: "not-a-date",
  } }), /v1 sunset/);
  assert.throws(() => buildApp({ ...fixture.dependencies, v2: {
    ...fixture.dependencies.v2!,
    v1SunsetAt: "2026-08-20 00:00:00Z",
  } }), /v1 sunset/);
});

function authenticated(options: {
  method: "POST";
  url: string;
  payload: unknown;
}): typeof options & { headers: Record<string, string> } {
  return {
    ...options,
    headers: { authorization: "Bearer valid-token", "content-type": "application/json" },
  };
}

function v2Authenticated(
  options: { method: "POST"; url: string; payload: unknown },
  appVersion: string | null = "0.2.5",
  regionVersion = "synthetic-v1",
): typeof options & { headers: Record<string, string> } {
  return {
    ...options,
    headers: {
      authorization: "Bearer valid-token",
      "content-type": "application/json",
      ...(appVersion === null ? {} : { "x-anitabi-app-version": appVersion }),
      "x-anitabi-region-data-version": regionVersion,
    },
  };
}

function validMatrix(): MatrixRequest {
  return {
    mode: "DRIVE",
    coordinates: [
      { latitude: 35, longitude: 139 },
      { latitude: 35.1, longitude: 139.1 },
    ],
    objective: "FASTEST",
  };
}

function createApp(rateLimiter = new TokenBucketLimiter({
  ipCapacity: 100,
  ipRefillPerSecond: 100,
  ipHmacKey: new Uint8Array(32).fill(3),
})): {
  app: ReturnType<typeof buildApp>;
  ledger: FakeLedger;
  routes: FakeRoutes;
  logs: SafeLogEvent[];
} {
  const ledger = new FakeLedger();
  const routes = new FakeRoutes();
  const logs: SafeLogEvent[] = [];
  const auth: FirebaseTokenVerifier = {
    verify: async (token) => {
      if (token !== "valid-token" && token !== "private-token-value") {
        throw new ApiError("UNAUTHENTICATED");
      }
      return "anonymous-uid";
    },
  };
  return {
    ledger,
    routes,
    logs,
    app: buildApp({
      auth,
      routes,
      quota: ledger,
      rateLimiter,
      logger: { write: (event) => logs.push(event) },
      allowInsecureForTests: true,
      nowMillis: () => Date.parse("2026-07-31T00:00:00Z"),
    }),
  };
}

class FakeLedger implements QuotaLedger {
  readonly reservations: QuotaReservation[] = [];
  reserveError?: ApiError;
  readonly upstreamReservations: UpstreamReservation[][] = [];
  amapBillingEnabled = true;
  globalBillingEnabled = true;

  reserve(reservation: QuotaReservation): QuotaReservationResult {
    if (this.reserveError !== undefined) throw this.reserveError;
    if (!this.globalBillingEnabled) throw new ApiError("BACKEND_UNAVAILABLE");
    this.reservations.push(reservation);
    return { monthlyUsed: reservation.units, monthlyRemaining: 100 };
  }

  health(): LedgerHealth {
    return { healthy: true, billingEnabled: this.globalBillingEnabled };
  }

  reserveAmap(reservations: readonly UpstreamReservation[]): void {
    if (!this.globalBillingEnabled || !this.amapBillingEnabled) throw new ApiError("BACKEND_UNAVAILABLE");
    this.upstreamReservations.push([...reservations]);
  }

  amapHealth() {
    return {
      healthy: true,
      configured: true,
      billingEnabled: this.globalBillingEnabled && this.amapBillingEnabled,
    };
  }

  close(): void {}
}

class FakeRoutes implements RoutesProvider {
  matrixCalls = 0;
  routeCalls = 0;
  routeError?: ApiError;

  async matrix(_request: MatrixRequest) {
    this.matrixCalls += 1;
    return { elements: [] };
  }

  async route(_request: RouteRequest) {
    this.routeCalls += 1;
    if (this.routeError !== undefined) throw this.routeError;
    return { distanceMeters: 1, durationSeconds: 1, legs: [] };
  }
}

class FakeAmapRoutes extends FakeRoutes implements AmapRoutesProvider {
  usageForMatrix(request: MatrixRequest): readonly UpstreamReservation[] {
    return [
      { bucket: "conversion", units: 1 },
      { bucket: request.mode === "DRIVE" ? "distance" : "route", units: request.coordinates.length },
    ];
  }

  usageForRoute(request: RouteRequest): readonly UpstreamReservation[] {
    return [
      { bucket: "conversion", units: 1 },
      { bucket: "route", units: request.locations.length - 1 },
    ];
  }
}

function createV2Dependencies(options: {
  nowMillis?: () => number;
  regionUnavailable?: boolean;
  amapBillingEnabled?: boolean;
  globalBillingEnabled?: boolean;
} = {}) {
  const ledger = new FakeLedger();
  ledger.amapBillingEnabled = options.amapBillingEnabled ?? true;
  ledger.globalBillingEnabled = options.globalBillingEnabled ?? true;
  const google = new FakeRoutes();
  const amap = new FakeAmapRoutes();
  const routing = options.regionUnavailable === true ? undefined : new V2RoutingService({
    regions: syntheticRegionClassifier(),
    google,
    amap,
    googleQuota: ledger,
    upstreamQuota: ledger,
  });
  const logs: SafeLogEvent[] = [];
  const auth: FirebaseTokenVerifier = { verify: async () => "anonymous-uid" };
  const dependencies = {
    auth,
    routes: google,
    quota: ledger,
    rateLimiter: new TokenBucketLimiter({
      ipCapacity: 100,
      ipRefillPerSecond: 100,
      ipHmacKey: new Uint8Array(32).fill(3),
    }),
    logger: { write: (event: SafeLogEvent) => logs.push(event) },
    allowInsecureForTests: true,
    nowMillis: options.nowMillis ?? (() => Date.parse("2026-08-10T00:00:00Z")),
    v2: {
      ...(routing === undefined ? {} : { routing }),
      minimumAppVersion: "0.2.5",
      v1SunsetAt: "2026-08-20T00:00:00.000Z",
      googleAvailable: true,
      amapAvailable: true,
    },
  } satisfies Parameters<typeof buildApp>[0];
  return { dependencies, ledger, google, amap, logs };
}

function createV2App(options: {
  nowMillis?: () => number;
  regionUnavailable?: boolean;
  amapBillingEnabled?: boolean;
  globalBillingEnabled?: boolean;
} = {}) {
  const fixture = createV2Dependencies(options);
  return { ...fixture, app: buildApp(fixture.dependencies) };
}
