import assert from "node:assert/strict";
import {
  existsSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { test } from "node:test";
import Database from "better-sqlite3";
import {
  acquireDatabaseRestoreLock,
  apiRuntimeMarkerPrefix,
  createApiRuntimeMarker,
  databaseRestoreLockPath,
  listApiRuntimeMarkers,
  releaseDatabaseRestoreLock,
  removeApiRuntimeMarker,
} from "../src/database-lifecycle.js";
import { SqliteQuotaLedger, type AmapUpstreamLimits } from "../src/quota/ledger.js";

const LIMITS: AmapUpstreamLimits = {
  conversion: { daily: 10, monthly: 100 },
  geocode: { daily: 10, monthly: 100 },
  distance: { daily: 10, monthly: 100 },
  route: { daily: 10, monthly: 100 },
};

test("backup and restore preserve upstream usage and disable all billing", () => {
  const directory = mkdtempSync(join(tmpdir(), "anitabi-admin-"));
  const databasePath = join(directory, "anitabi.sqlite");
  try {
    const ledger = new SqliteQuotaLedger(new Database(databasePath), LIMITS);
    const now = new Date("2026-07-30T00:00:00Z");
    ledger.reserve({ bucket: "route", units: 1, now });
    ledger.reserveAmap([{ bucket: "conversion", units: 2 }], now);
    ledger.close();

    const backup = runAdmin(databasePath, "backup");
    assert.equal(backup.status, 0, backup.stderr);
    const backupName = readdirSync(join(directory, "backups")).find((name) => name.endsWith(".sqlite"));
    assert.ok(backupName);
    const backupPath = join(directory, "backups", backupName);

    const changed = new SqliteQuotaLedger(new Database(databasePath), LIMITS);
    changed.reserveAmap([{ bucket: "conversion", units: 1 }], now);
    changed.close();
    writeFileSync(`${databasePath}-wal`, "old wal sidecar", { mode: 0o600 });
    writeFileSync(`${databasePath}-shm`, "old shm sidecar", { mode: 0o600 });

    const restore = runAdmin(
      databasePath,
      "restore",
      backupPath,
      "CONFIRM_RESTORE_AND_DISABLE_BILLING",
    );
    assert.equal(restore.status, 0, restore.stderr);
    const restoreResult = JSON.parse(restore.stdout) as { quarantine: string };
    const quarantineDirectory = join(directory, restoreResult.quarantine);
    assert.deepEqual(
      readdirSync(quarantineDirectory).sort(),
      ["anitabi.sqlite", "anitabi.sqlite-shm", "anitabi.sqlite-wal"],
    );
    assert.equal(readFileSync(join(quarantineDirectory, "anitabi.sqlite-wal"), "utf8"), "old wal sidecar");
    assert.equal(readFileSync(join(quarantineDirectory, "anitabi.sqlite-shm"), "utf8"), "old shm sidecar");
    assert.equal(existsSync(`${databasePath}-wal`), false);
    assert.equal(existsSync(`${databasePath}-shm`), false);
    assert.equal(existsSync(databaseRestoreLockPath(databasePath)), false);
    assert.equal(
      readdirSync(directory).some((name) => name.includes(".restore-prepared-")),
      false,
    );
    const audit = new Database(databasePath, { readonly: true });
    const billing = audit.prepare(
      "SELECT value FROM quota_metadata WHERE key='billing_enabled'",
    ).get() as { value: string };
    const usage = audit.prepare(
      "SELECT used FROM upstream_usage WHERE bucket='conversion' AND period_kind='day'",
    ).get() as { used: number };
    const integrity = audit.pragma("integrity_check") as Array<Record<string, string>>;
    audit.close();
    assert.equal(billing.value, "0");
    assert.equal(usage.used, 2);
    assert.deepEqual(integrity.flatMap((row) => Object.values(row)), ["ok"]);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("restore refuses live or stale API runtime markers without touching the database", () => {
  const directory = mkdtempSync(join(tmpdir(), "anitabi-admin-marker-"));
  const databasePath = join(directory, "anitabi.sqlite");
  try {
    const original = new SqliteQuotaLedger(new Database(databasePath), LIMITS);
    const now = new Date("2026-07-30T00:00:00Z");
    original.reserve({ bucket: "route", units: 1, now });
    original.close();
    const backup = runAdmin(databasePath, "backup");
    assert.equal(backup.status, 0, backup.stderr);
    const backupName = readdirSync(join(directory, "backups")).find((name) => name.endsWith(".sqlite"));
    assert.ok(backupName);

    const changed = new SqliteQuotaLedger(new Database(databasePath), LIMITS);
    changed.reserve({ bucket: "route", units: 1, now });
    changed.close();
    const staleMarker = join(
      directory,
      `${apiRuntimeMarkerPrefix(databasePath)}stale.marker`,
    );
    writeFileSync(staleMarker, "stale\n", { mode: 0o600 });

    const restore = runAdmin(
      databasePath,
      "restore",
      join(directory, "backups", backupName),
      "CONFIRM_RESTORE_AND_DISABLE_BILLING",
    );
    assert.notEqual(restore.status, 0);
    assert.match(restore.stderr, /API runtime marker is present/);
    assert.equal(existsSync(staleMarker), true);
    assert.equal(existsSync(databaseRestoreLockPath(databasePath)), false);
    assert.equal(
      readdirSync(directory).some((name) => name.includes(".pre-restore-")),
      false,
    );

    const audit = new Database(databasePath, { readonly: true });
    const usage = audit.prepare(
      "SELECT used FROM quota_usage WHERE dimension='global' AND bucket='route'",
    ).get() as { used: number };
    audit.close();
    assert.equal(usage.used, 2);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("API runtime markers allow blue-green peers and stale restore locks block startup", () => {
  const directory = mkdtempSync(join(tmpdir(), "anitabi-runtime-marker-"));
  const databasePath = join(directory, "anitabi.sqlite");
  try {
    const first = createApiRuntimeMarker(databasePath);
    const second = createApiRuntimeMarker(databasePath);
    assert.deepEqual(listApiRuntimeMarkers(databasePath), [first, second].sort());
    removeApiRuntimeMarker(first);
    removeApiRuntimeMarker(second);

    const restoreLock = acquireDatabaseRestoreLock(databasePath);
    assert.throws(() => createApiRuntimeMarker(databasePath), /restore lock is present/);
    assert.throws(() => acquireDatabaseRestoreLock(databasePath), /already exists/);
    releaseDatabaseRestoreLock(restoreLock);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

function runAdmin(databasePath: string, ...arguments_: string[]) {
  return spawnSync(process.execPath, [join(process.cwd(), "dist", "admin.js"), ...arguments_], {
    encoding: "utf8",
    env: { ...process.env, ANITABI_DATABASE_PATH: databasePath },
  });
}
