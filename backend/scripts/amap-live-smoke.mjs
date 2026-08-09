import { readFileSync } from "node:fs";
import { AmapRoutesClient } from "../dist/amap/routes.js";
import { ApiError } from "../dist/errors.js";

const apiKey = readCredential(
  process.env.ANITABI_AMAP_API_KEY_FILE ?? "/run/anitabi-secrets/amap-web-key",
);
const signingSecret = readCredential(
  process.env.ANITABI_AMAP_SIGNATURE_SECRET_FILE ??
    "/run/anitabi-secrets/amap-signature-secret",
);
if (apiKey === signingSecret) fail("CREDENTIAL_REUSE");

const client = new AmapRoutesClient({ apiKey, signingSecret });
const fixtures = [
  {
    mode: "DRIVE",
    locations: [
      { latitude: 39.9087, longitude: 116.3975 },
      { latitude: 39.8822, longitude: 116.4066 },
    ],
  },
  {
    mode: "WALK",
    locations: [
      { latitude: 39.9087, longitude: 116.3975 },
      { latitude: 39.9251, longitude: 116.389 },
    ],
  },
  {
    mode: "BICYCLE",
    locations: [
      { latitude: 39.9087, longitude: 116.3975 },
      { latitude: 39.9262, longitude: 116.4173 },
    ],
  },
  {
    mode: "TRANSIT",
    locations: [
      { latitude: 39.9087, longitude: 116.3975 },
      { latitude: 39.8954, longitude: 116.3212 },
    ],
  },
];

try {
  for (const [index, fixture] of fixtures.entries()) {
    if (index > 0) await delay(1_500);
    const route = await client.route(fixture);
    verifyRoute(fixture.mode, route);
    console.log(`AMap ${fixture.mode} route: OK`);
  }
  console.log("AMap live provider smoke: PASS");
} catch (error) {
  if (error instanceof ApiError) fail(error.code);
  fail("UNEXPECTED_FAILURE");
}

function readCredential(path) {
  try {
    const bytes = readFileSync(path);
    if (bytes.byteLength === 0 || bytes.byteLength > 4_096) fail("INVALID_CREDENTIAL_FILE");
    const value = bytes.toString("utf8").trim();
    if (value.length === 0) fail("INVALID_CREDENTIAL_FILE");
    return value;
  } catch {
    fail("INVALID_CREDENTIAL_FILE");
  }
}

function verifyRoute(mode, route) {
  if (route.legs.length !== 1) fail("INVALID_LEG_COUNT");
  if (!Number.isFinite(route.distanceMeters) || route.distanceMeters <= 0) {
    fail("INVALID_DISTANCE");
  }
  if (!Number.isFinite(route.durationSeconds) || route.durationSeconds <= 0) {
    fail("INVALID_DURATION");
  }
  if (typeof route.encodedPolyline !== "string" || route.encodedPolyline.length === 0) {
    fail("MISSING_GEOMETRY");
  }
  const leg = route.legs[0];
  if (leg === undefined || leg.steps.length === 0) fail("MISSING_STEPS");
  const expectedMode = mode === "TRANSIT" ? "TRANSIT" : mode;
  if (!leg.steps.some((step) => step.travelMode === expectedMode)) {
    fail("MISSING_EXPECTED_MODE");
  }
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function fail(code) {
  console.error(`AMap live provider smoke: FAIL (${code})`);
  process.exit(1);
}
