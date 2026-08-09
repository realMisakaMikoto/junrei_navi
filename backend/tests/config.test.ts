import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { loadConfig } from "../src/config.js";

test("runtime config derives the v1 sunset exactly fourteen days after release", () => {
  const fixture = environmentFixture();
  try {
    const config = loadConfig(fixture.environment);
    assert.equal(config.minimumAppVersion, "0.2.5");
    assert.equal(config.v1SunsetAt, "2026-08-24T12:34:56.000Z");
    assert.equal(config.regionDataFile, "/synthetic/region-data.json");
    assert.equal(config.amap, undefined);
  } finally {
    fixture.close();
  }
});

test("AMap stays disabled unless every daily and monthly quota is configured", () => {
  const fixture = environmentFixture();
  try {
    fixture.environment["ANITABI_AMAP_ENABLED"] = "1";
    assert.equal(loadConfig(fixture.environment).amap, undefined);
    for (const bucket of ["CONVERSION", "GEOCODE", "DISTANCE", "ROUTE"]) {
      fixture.environment[`ANITABI_AMAP_${bucket}_DAILY_LIMIT`] = "10";
      fixture.environment[`ANITABI_AMAP_${bucket}_MONTHLY_LIMIT`] = "100";
    }
    const amap = loadConfig(fixture.environment).amap;
    assert.ok(amap);
    assert.deepEqual(amap.limits.route, { daily: 10, monthly: 100 });
  } finally {
    fixture.close();
  }
});

test("invalid release time and prerelease minimum version fail closed", () => {
  const fixture = environmentFixture();
  try {
    fixture.environment["ANITABI_V025_RELEASE_AT"] = "invalid";
    assert.throws(() => loadConfig(fixture.environment), hasCode("BACKEND_UNAVAILABLE"));
    fixture.environment["ANITABI_V025_RELEASE_AT"] = "2026-08-10 12:34:56Z";
    assert.throws(() => loadConfig(fixture.environment), hasCode("BACKEND_UNAVAILABLE"));
    fixture.environment["ANITABI_V025_RELEASE_AT"] = "2026-08-10T12:34:56Z";
    fixture.environment["ANITABI_MINIMUM_APP_VERSION"] = "0.2.5-rc.1";
    assert.throws(() => loadConfig(fixture.environment), hasCode("BACKEND_UNAVAILABLE"));
  } finally {
    fixture.close();
  }
});

function environmentFixture(): {
  environment: NodeJS.ProcessEnv;
  close: () => void;
} {
  const directory = mkdtempSync(join(tmpdir(), "anitabi-config-"));
  const hmacFile = join(directory, "hmac-key");
  writeFileSync(hmacFile, Buffer.alloc(32, 7));
  return {
    environment: {
      ANITABI_FIREBASE_PROJECT_ID: "synthetic-project",
      ANITABI_GOOGLE_PROJECT_ID: "synthetic-project",
      ANITABI_IP_HMAC_KEY_FILE: hmacFile,
      ANITABI_REGION_DATA_FILE: "/synthetic/region-data.json",
      ANITABI_V025_RELEASE_AT: "2026-08-10T12:34:56Z",
    },
    close: () => rmSync(directory, { recursive: true, force: true }),
  };
}

function hasCode(code: string): (error: unknown) => boolean {
  return (error) => typeof error === "object" && error !== null && "code" in error && error.code === code;
}
