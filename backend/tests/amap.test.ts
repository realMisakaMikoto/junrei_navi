import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { setTimeout as delay } from "node:timers/promises";
import { test } from "node:test";
import type { Coordinate } from "../src/contract.js";
import {
  AMAP_BICYCLING_URL,
  AMAP_CONVERT_URL,
  AMAP_DISTANCE_URL,
  AMAP_DRIVING_URL,
  AMAP_REVERSE_GEOCODE_URL,
  AMAP_TRANSIT_URL,
  AMAP_WALKING_URL,
  AmapRoutesClient,
  usageForMatrix,
  usageForRoute,
} from "../src/amap/routes.js";

const TEST_KEY = "00000000000000000000000000000000";
const TEST_SECRET = "11111111111111111111111111111111";

test("coordinate conversion signs canonical fixed-endpoint queries and batches at forty", async () => {
  const coordinates = syntheticCoordinates(41);
  let calls = 0;
  let maximumBatchSize = 0;
  let active = 0;
  let maximumActive = 0;
  const client = new AmapRoutesClient({
    apiKey: TEST_KEY,
    signingSecret: TEST_SECRET,
    fetch: async (input) => {
      const url = checkedUrl(input, AMAP_CONVERT_URL);
      verifySignature(url);
      assert.equal(url.toString().includes(TEST_SECRET), false);
      assert.equal(url.searchParams.get("coordsys"), "gps");
      const locations = requireQuery(url, "locations").split("|");
      maximumBatchSize = Math.max(maximumBatchSize, locations.length);
      calls += 1;
      active += 1;
      maximumActive = Math.max(maximumActive, active);
      await delay(2);
      active -= 1;
      return amapJson({ locations: locations.join(";") });
    },
  });

  assert.deepEqual(await client.convertCoordinates(coordinates), coordinates);
  assert.equal(calls, 2);
  assert.equal(maximumBatchSize, 40);
  assert.equal(maximumActive, 2);
});

test("every outbound coordinate is rounded to six decimals and negative zero is normalized", async () => {
  let locations: string | undefined;
  const client = new AmapRoutesClient({
    apiKey: TEST_KEY,
    signingSecret: TEST_SECRET,
    fetch: async (input) => {
      const url = checkedUrl(input, AMAP_CONVERT_URL);
      locations = requireQuery(url, "locations");
      return conversionResponse(url);
    },
  });
  const converted = await client.convertCoordinates([
    { latitude: 30.12345644, longitude: 110.12345678 },
    { latitude: 0, longitude: -0 },
  ]);
  assert.equal(locations, "110.123457,30.123456|0,0");
  assert.deepEqual(converted, [
    { latitude: 30.123456, longitude: 110.123457 },
    { latitude: 0, longitude: 0 },
  ]);
});

test("the client-wide semaphore caps simultaneous independent calls at two", async () => {
  let active = 0;
  let maximumActive = 0;
  const client = new AmapRoutesClient({
    apiKey: TEST_KEY,
    signingSecret: TEST_SECRET,
    requestStartsPerSecond: 1_000,
    fetch: async (input) => {
      const url = checkedUrl(input, AMAP_CONVERT_URL);
      active += 1;
      maximumActive = Math.max(maximumActive, active);
      await delay(3);
      active -= 1;
      return conversionResponse(url);
    },
  });
  await Promise.all(
    syntheticCoordinates(6).map((coordinate) => client.convertCoordinates([coordinate])),
  );
  assert.equal(maximumActive, 2);
});

test("the client starts at most three upstream requests in any rolling second", async () => {
  let now = 0;
  const starts: number[] = [];
  const waits: number[] = [];
  const client = new AmapRoutesClient({
    apiKey: TEST_KEY,
    signingSecret: TEST_SECRET,
    requestStartClock: () => now,
    requestStartSleep: async (milliseconds) => {
      waits.push(milliseconds);
      now += milliseconds;
    },
    fetch: async (input) => {
      const url = checkedUrl(input, AMAP_CONVERT_URL);
      starts.push(now);
      return conversionResponse(url);
    },
  });

  await Promise.all(
    syntheticCoordinates(6).map((coordinate) => client.convertCoordinates([coordinate])),
  );

  assert.deepEqual(starts, [0, 0, 0, 1_000, 1_000, 1_000]);
  assert.deepEqual(waits, [1_000]);
});

test("the client rejects an unsafe request-start limit", () => {
  assert.throws(
    () => new AmapRoutesClient({
      apiKey: TEST_KEY,
      signingSecret: TEST_SECRET,
      requestStartsPerSecond: 0,
    }),
    /AMap request-start limit is invalid/,
  );
});

test("the client-wide semaphore rejects safely when its bounded queue is saturated", async () => {
  let releaseActive: (() => void) | undefined;
  const activeGate = new Promise<void>((resolve) => {
    releaseActive = resolve;
  });
  let fetchCalls = 0;
  const client = new AmapRoutesClient({
    apiKey: TEST_KEY,
    signingSecret: TEST_SECRET,
    timeoutMillis: 1_000,
    requestStartsPerSecond: 1_000,
    fetch: async (input) => {
      const url = checkedUrl(input, AMAP_CONVERT_URL);
      fetchCalls += 1;
      if (fetchCalls <= 2) await activeGate;
      return conversionResponse(url);
    },
  });
  const admitted = Array.from({ length: 66 }, () =>
    client.convertCoordinates(syntheticCoordinates(1)));
  await delay(0);
  assert.equal(fetchCalls, 2);
  await assert.rejects(
    client.convertCoordinates(syntheticCoordinates(1)),
    hasCode("UPSTREAM_UNAVAILABLE"),
  );
  releaseActive?.();
  await Promise.all(admitted);
  assert.equal(fetchCalls, 66);
});

test("semaphore acquisition times out and removes the waiter without consuming a permit", async () => {
  const held: Array<Readonly<{ url: URL; resolve: (response: Response) => void }>> = [];
  const client = new AmapRoutesClient({
    apiKey: TEST_KEY,
    signingSecret: TEST_SECRET,
    timeoutMillis: 10,
    fetch: async (input) => new Promise<Response>((resolve) => {
      held.push({ url: checkedUrl(input, AMAP_CONVERT_URL), resolve });
    }),
  });
  const first = client.convertCoordinates(syntheticCoordinates(1));
  const second = client.convertCoordinates(syntheticCoordinates(1));
  while (held.length < 2) await delay(0);
  await assert.rejects(
    client.convertCoordinates(syntheticCoordinates(1)),
    hasCode("UPSTREAM_UNAVAILABLE"),
  );
  assert.equal(held.length, 2);
  for (const pending of held) pending.resolve(conversionResponse(pending.url));
  await Promise.all([first, second]);
  assert.equal(held.length, 2);
});

test("usage estimators exactly account for conversion, distance, geocode, and route calls", () => {
  const three = syntheticCoordinates(3);
  const client = new AmapRoutesClient({ apiKey: TEST_KEY, signingSecret: TEST_SECRET });
  assert.deepEqual(
    usageForMatrix({ mode: "DRIVE", coordinates: three, objective: "FASTEST" }),
    { conversion: 1, geocode: 0, distance: 3, route: 0 },
  );
  assert.deepEqual(
    usageForMatrix({ mode: "BICYCLE", coordinates: three, objective: "SHORTEST" }),
    { conversion: 1, geocode: 0, distance: 0, route: 6 },
  );
  assert.deepEqual(
    usageForRoute({ mode: "DRIVE", locations: syntheticCoordinates(12) }),
    { conversion: 1, geocode: 0, distance: 0, route: 11 },
  );
  assert.deepEqual(
    usageForRoute({ mode: "TRANSIT", locations: three }),
    { conversion: 1, geocode: 3, distance: 0, route: 2 },
  );
  assert.deepEqual(
    client.usageForMatrix({ mode: "DRIVE", coordinates: three, objective: "FASTEST" }),
    [
      { bucket: "conversion", units: 1 },
      { bucket: "distance", units: 3 },
    ],
  );
  assert.deepEqual(
    client.usageForRoute({ mode: "TRANSIT", locations: three }),
    [
      { bucket: "conversion", units: 1 },
      { bucket: "geocode", units: 3 },
      { bucket: "route", units: 2 },
    ],
  );
});

test("driving matrix batches origins by destination and never exceeds two upstream calls", async () => {
  const coordinates = syntheticCoordinates(3);
  let distanceCalls = 0;
  let active = 0;
  let maximumActive = 0;
  const client = new AmapRoutesClient({
    apiKey: TEST_KEY,
    signingSecret: TEST_SECRET,
    fetch: async (input) => {
      const url = new URL(String(input));
      verifySignature(url);
      if (fixedEndpoint(url) === AMAP_CONVERT_URL) return conversionResponse(url);
      assert.equal(fixedEndpoint(url), AMAP_DISTANCE_URL);
      assert.equal(url.searchParams.get("type"), "1");
      const origins = requireQuery(url, "origins").split("|");
      assert.equal(origins.length, 2);
      distanceCalls += 1;
      active += 1;
      maximumActive = Math.max(maximumActive, active);
      await delay(2);
      active -= 1;
      return amapJson({
        results: origins.map((_, index) => ({
          distance: String(100 + index),
          duration: String(10 + index),
          ignored: "must-not-pass-through",
        })),
      });
    },
  });

  const matrix = await client.matrix({ mode: "DRIVE", coordinates, objective: "FASTEST" });
  assert.equal(distanceCalls, 3);
  assert.equal(maximumActive, 2);
  assert.equal(matrix.elements.length, 9);
  assert.deepEqual(matrix.elements[0], {
    originIndex: 0,
    destinationIndex: 0,
    status: "OK",
    distanceMeters: 0,
    durationSeconds: 0,
  });
  assert.equal(matrix.elements.filter((element) => element.status === "OK").length, 9);
  assert.equal(JSON.stringify(matrix).includes("must-not-pass-through"), false);
});

test("walking and bicycle matrices use directed v5 route pairs and preserve unreachable elements", async () => {
  for (const [mode, endpoint] of [
    ["WALK", AMAP_WALKING_URL],
    ["BICYCLE", AMAP_BICYCLING_URL],
  ] as const) {
    let routeCalls = 0;
    const client = new AmapRoutesClient({
      apiKey: TEST_KEY,
      signingSecret: TEST_SECRET,
      fetch: async (input) => {
        const url = new URL(String(input));
        if (fixedEndpoint(url) === AMAP_CONVERT_URL) return conversionResponse(url);
        assert.equal(fixedEndpoint(url), endpoint);
        routeCalls += 1;
        if (routeCalls === 2) return amapJson({ route: { paths: [] } });
        return roadResponse();
      },
    });
    const matrix = await client.matrix({
      mode,
      coordinates: syntheticCoordinates(3),
      objective: "SHORTEST",
    });
    assert.equal(routeCalls, 6);
    assert.equal(matrix.elements.length, 9);
    assert.equal(matrix.elements.some((element) => element.status === "UNREACHABLE"), true);
  }
});

test("driving route preserves exact adjacent legs and re-encodes GCJ-02 geometry", async () => {
  const coordinates = syntheticCoordinates(3);
  const capturedDrivingUrls: URL[] = [];
  const client = new AmapRoutesClient({
    apiKey: TEST_KEY,
    signingSecret: TEST_SECRET,
    fetch: async (input) => {
      const url = new URL(String(input));
      if (fixedEndpoint(url) === AMAP_CONVERT_URL) return conversionResponse(url);
      assert.equal(fixedEndpoint(url), AMAP_DRIVING_URL);
      capturedDrivingUrls.push(url);
      return roadResponse({ unknownBody: "must-not-pass-through" });
    },
  });

  const route = await client.route({ mode: "DRIVE", locations: coordinates });
  assert.equal(capturedDrivingUrls.length, 2);
  assert.deepEqual(
    capturedDrivingUrls.map((url) => [url.searchParams.get("origin"), url.searchParams.get("destination")]),
    [
      [coordinatePair(coordinates[0]), coordinatePair(coordinates[1])],
      [coordinatePair(coordinates[1]), coordinatePair(coordinates[2])],
    ],
  );
  assert.equal(capturedDrivingUrls.every((url) => !url.searchParams.has("waypoints")), true);
  assert.equal(capturedDrivingUrls.every((url) => url.searchParams.get("show_fields") === "cost,polyline,navi"), true);
  assert.equal(route.legs.length, 2);
  assert.equal(route.legs[0]?.steps[0]?.travelMode, "DRIVE");
  assert.equal(route.encodedPolyline?.includes(";"), false);
  assert.equal(route.encodedPolyline?.includes(","), false);
  assert.equal(route.legs.every((leg) => leg.encodedPolyline === leg.steps[0]?.encodedPolyline), true);
  assert.equal(JSON.stringify(route).includes("must-not-pass-through"), false);
});

test("walking and bicycle multi-leg routes call only their fixed v5 endpoints", async () => {
  for (const [mode, endpoint] of [
    ["WALK", AMAP_WALKING_URL],
    ["BICYCLE", AMAP_BICYCLING_URL],
  ] as const) {
    let routeCalls = 0;
    const client = new AmapRoutesClient({
      apiKey: TEST_KEY,
      signingSecret: TEST_SECRET,
      fetch: async (input) => {
        const url = new URL(String(input));
        if (fixedEndpoint(url) === AMAP_CONVERT_URL) return conversionResponse(url);
        assert.equal(fixedEndpoint(url), endpoint);
        routeCalls += 1;
        return roadResponse();
      },
    });
    const route = await client.route({ mode, locations: syntheticCoordinates(3) });
    assert.equal(routeCalls, 2);
    assert.equal(route.legs.length, 2);
    assert.equal(route.distanceMeters, 2000);
    assert.equal(route.durationSeconds, 200);
  }
});

test("transit reverse-geocodes cities, maps departure strategy, and merges normalized legs", async () => {
  const coordinates = syntheticCoordinates(3);
  let geocodeCalls = 0;
  let transitCalls = 0;
  let active = 0;
  let maximumActive = 0;
  const transitUrls: URL[] = [];
  const client = new AmapRoutesClient({
    apiKey: TEST_KEY,
    signingSecret: TEST_SECRET,
    now: () => new Date("2026-08-09T03:04:00Z"),
    fetch: async (input) => {
      const url = new URL(String(input));
      const endpoint = fixedEndpoint(url);
      if (endpoint === AMAP_CONVERT_URL) return conversionResponse(url);
      active += 1;
      maximumActive = Math.max(maximumActive, active);
      await delay(2);
      active -= 1;
      if (endpoint === AMAP_REVERSE_GEOCODE_URL) {
        geocodeCalls += 1;
        return amapJson({
          regeocode: {
            addressComponent: {
              citycode: "010",
              adcode: "110000",
              ignoredAddress: "must-not-pass-through",
            },
          },
        });
      }
      assert.equal(endpoint, AMAP_TRANSIT_URL);
      transitCalls += 1;
      transitUrls.push(url);
      return transitResponse();
    },
  });

  const route = await client.route({
    mode: "TRANSIT",
    locations: coordinates,
    departureTime: "2026-08-09T00:30:00Z",
    transitRoutingPreference: "LESS_WALKING",
  });

  assert.equal(geocodeCalls, 3);
  assert.equal(transitCalls, 2);
  assert.equal(maximumActive, 2);
  assert.equal(route.legs.length, 2);
  assert.equal(route.distanceMeters, 2000);
  assert.equal(route.durationSeconds, 1200);
  assert.equal(route.legs.every((leg) => leg.steps.some((step) => step.travelMode === "TRANSIT")), true);
  for (const url of transitUrls) {
    assert.equal(url.searchParams.get("city1"), "010");
    assert.equal(url.searchParams.get("city2"), "010");
    assert.equal(url.searchParams.get("date"), "2026-08-09");
    assert.equal(url.searchParams.get("time"), "08:30");
    assert.equal(url.searchParams.get("strategy"), "3");
  }
  assert.deepEqual(route.legs[0]?.steps[1]?.transit, {
    departureStop: "Synthetic Start",
    arrivalStop: "Synthetic End",
    lineName: "Synthetic Line",
    vehicleType: "BUS",
  });
  assert.equal(JSON.stringify(route).includes("must-not-pass-through"), false);
});

test("transit NOW uses the injected clock and unsupported arrival/filter semantics fail before fetch", async () => {
  const coordinates = syntheticCoordinates(2);
  let transitUrl: URL | undefined;
  const client = new AmapRoutesClient({
    apiKey: TEST_KEY,
    signingSecret: TEST_SECRET,
    now: () => new Date("2026-08-09T16:05:00Z"),
    fetch: async (input) => {
      const url = new URL(String(input));
      const endpoint = fixedEndpoint(url);
      if (endpoint === AMAP_CONVERT_URL) return conversionResponse(url);
      if (endpoint === AMAP_REVERSE_GEOCODE_URL) {
        return amapJson({ regeocode: { addressComponent: { adcode: "110000" } } });
      }
      transitUrl = url;
      return transitResponse();
    },
  });
  await client.route({ mode: "TRANSIT", locations: coordinates });
  assert.equal(transitUrl?.searchParams.get("date"), "2026-08-10");
  assert.equal(transitUrl?.searchParams.get("time"), "00:05");

  let fetchCalls = 0;
  const rejectingClient = new AmapRoutesClient({
    apiKey: TEST_KEY,
    signingSecret: TEST_SECRET,
    fetch: async () => {
      fetchCalls += 1;
      throw new Error("must not be reached");
    },
  });
  await assert.rejects(
    rejectingClient.route({
      mode: "TRANSIT",
      locations: coordinates,
      arrivalTime: "2026-08-09T00:30:00Z",
    }),
    hasCode("INVALID_ARGUMENT"),
  );
  await assert.rejects(
    rejectingClient.route({
      mode: "TRANSIT",
      locations: coordinates,
      transitTravelModes: ["BUS"],
    }),
    hasCode("INVALID_ARGUMENT"),
  );
  assert.equal(fetchCalls, 0);
});

test("safe upstream mapping ignores non-success bodies and maps only bounded codes", async () => {
  let cancelled = false;
  const body = new ReadableStream<Uint8Array>({
    cancel() {
      cancelled = true;
    },
  });
  const httpClient = new AmapRoutesClient({
    apiKey: TEST_KEY,
    signingSecret: TEST_SECRET,
    fetch: async () => new Response(body, { status: 503 }),
  });
  await assert.rejects(httpClient.convertCoordinates(syntheticCoordinates(1)), hasCode("UPSTREAM_UNAVAILABLE"));
  assert.equal(cancelled, true);

  for (const [infocode, code] of [
    ["10003", "QUOTA_EXHAUSTED"],
    ["40003", "QUOTA_EXHAUSTED"],
    ["10014", "RATE_LIMITED"],
    ["10020", "RATE_LIMITED"],
    ["20800", "NO_ROUTE"],
    ["20803", "NO_ROUTE"],
    ["10010", "UPSTREAM_UNAVAILABLE"],
    ["10007", "UPSTREAM_UNAVAILABLE"],
  ] as const) {
    const client = new AmapRoutesClient({
      apiKey: TEST_KEY,
      signingSecret: TEST_SECRET,
      fetch: async () => Response.json({
        status: "0",
        infocode,
        info: "secret upstream explanation",
      }),
    });
    await assert.rejects(client.convertCoordinates(syntheticCoordinates(1)), (error: unknown) => {
      assert.equal(hasCode(code)(error), true);
      assert.equal(String(error).includes("secret upstream explanation"), false);
      return true;
    });
  }
});

test("response size and timeout bounds fail closed without exposing upstream data", async () => {
  const oversizedClient = new AmapRoutesClient({
    apiKey: TEST_KEY,
    signingSecret: TEST_SECRET,
    maxResponseBytes: 64,
    fetch: async () => new Response("x", {
      status: 200,
      headers: { "content-length": "65" },
    }),
  });
  await assert.rejects(
    oversizedClient.convertCoordinates(syntheticCoordinates(1)),
    hasCode("UPSTREAM_UNAVAILABLE"),
  );

  const timeoutClient = new AmapRoutesClient({
    apiKey: TEST_KEY,
    signingSecret: TEST_SECRET,
    timeoutMillis: 5,
    fetch: async (_input, init) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener("abort", () => reject(new Error("timed out")), { once: true });
    }),
  });
  await assert.rejects(
    timeoutClient.convertCoordinates(syntheticCoordinates(1)),
    hasCode("UPSTREAM_UNAVAILABLE"),
  );
});

function syntheticCoordinates(count: number): Coordinate[] {
  return Array.from({ length: count }, (_, index) => ({
    latitude: 30 + index / 10_000,
    longitude: 110 + index / 10_000,
  }));
}

function amapJson(body: Record<string, unknown>): Response {
  return Response.json({ status: "1", info: "OK", infocode: "10000", ...body });
}

function conversionResponse(url: URL): Response {
  return amapJson({ locations: requireQuery(url, "locations").replaceAll("|", ";") });
}

function roadResponse(extra: Record<string, unknown> = {}): Response {
  return amapJson({
    route: {
      paths: [{
        distance: "1000",
        cost: { duration: "100" },
        steps: [{
          instruction: "Continue",
          action: "straight",
          step_distance: "1000",
          cost: { duration: "100" },
          polyline: "110,30;110.001,30.001",
          ...extra,
        }],
      }],
    },
  });
}

function transitResponse(): Response {
  return amapJson({
    route: {
      transits: [{
        distance: "1000",
        cost: { duration: "600" },
        segments: [{
          walking: {
            steps: [{
              instruction: "Walk",
              step_distance: "100",
              cost: { duration: "120" },
              polyline: "110,30;110.0001,30.0001",
            }],
          },
          bus: {
            buslines: [{
              name: "Synthetic Line",
              type: "普通公交线路",
              distance: "900",
              duration: "480",
              polyline: "110.0001,30.0001;110.001,30.001",
              start_stop: { name: "Synthetic Start" },
              end_stop: { name: "Synthetic End" },
              ignored: "must-not-pass-through",
            }],
          },
          taxi: {
            distance: "50",
            duration: "30",
            polyline: "110.001,30.001;110.0011,30.0011",
          },
        }],
        ignored: "must-not-pass-through",
      }],
    },
  });
}

function checkedUrl(input: RequestInfo | URL, expectedEndpoint: string): URL {
  const url = new URL(String(input));
  assert.equal(fixedEndpoint(url), expectedEndpoint);
  return url;
}

function fixedEndpoint(url: URL): string {
  return `${url.origin}${url.pathname}`;
}

function requireQuery(url: URL, name: string): string {
  const value = url.searchParams.get(name);
  assert.notEqual(value, null);
  return value as string;
}

function verifySignature(url: URL): void {
  const signature = requireQuery(url, "sig");
  const entries = Array.from(url.searchParams.entries())
    .filter(([key]) => key !== "sig")
    .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0);
  const canonicalQuery = entries.map(([key, value]) => `${key}=${value}`).join("&");
  assert.equal(
    signature,
    createHash("md5").update(canonicalQuery + TEST_SECRET, "utf8").digest("hex"),
  );
}

function coordinatePair(coordinate: Coordinate | undefined): string | undefined {
  return coordinate === undefined ? undefined : `${coordinate.longitude},${coordinate.latitude}`;
}

function hasCode(code: string): (error: unknown) => boolean {
  return (error) => typeof error === "object" && error !== null && "code" in error && error.code === code;
}
