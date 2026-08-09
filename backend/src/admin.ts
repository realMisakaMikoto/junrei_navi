import Database from "better-sqlite3";
import { randomUUID } from "node:crypto";
import {
  chmodSync,
  constants,
  copyFileSync,
  existsSync,
  lstatSync,
  mkdirSync,
  readdirSync,
  renameSync,
  rmSync,
  statSync,
} from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import {
  acquireDatabaseRestoreLock,
  listApiRuntimeMarkers,
  releaseDatabaseRestoreLock,
} from "./database-lifecycle.js";
import { SqliteQuotaLedger } from "./quota/ledger.js";

const databasePath = process.env["ANITABI_DATABASE_PATH"] ?? "/data/anitabi.sqlite";
const command = process.argv[2];

switch (command) {
  case "backup":
    await backup();
    break;
  case "disable-billing":
    setBilling(false);
    break;
  case "enable-billing-after-audit":
    if (process.argv[3] !== "CONFIRM_QUOTA_LEDGER_AUDITED") process.exitCode = 2;
    else setBilling(true);
    break;
  case "restore":
    restore(process.argv[3], process.argv[4]);
    break;
  default:
    process.exitCode = 2;
}

async function backup(): Promise<void> {
  const source = new Database(databasePath);
  requireIntegrity(source);
  const backupDirectory = join(dirname(databasePath), "backups");
  mkdirSync(backupDirectory, { recursive: true, mode: 0o700 });
  const timestamp = new Date().toISOString().replaceAll(":", "-").replaceAll(".", "-");
  const destination = join(backupDirectory, `anitabi-quota-${timestamp}.sqlite`);
  await source.backup(destination);
  source.close();

  const copy = new Database(destination, { readonly: true });
  requireIntegrity(copy);
  copy.close();
  pruneBackups(backupDirectory, Date.now() - 7 * 24 * 60 * 60 * 1_000);
  process.stdout.write(`${JSON.stringify({ status: "ok", backup: basename(destination) })}\n`);
}

function restore(sourceArgument: string | undefined, confirmation: string | undefined): void {
  if (sourceArgument === undefined || confirmation !== "CONFIRM_RESTORE_AND_DISABLE_BILLING") {
    process.exitCode = 2;
    return;
  }
  const sourcePath = resolve(sourceArgument);
  const allowedDirectory = resolve(dirname(databasePath), "backups");
  if (dirname(sourcePath) !== allowedDirectory || !basename(sourcePath).startsWith("anitabi-quota-")) {
    process.exitCode = 2;
    return;
  }
  requireRegularFile(sourcePath, "Backup source");
  const source = new Database(sourcePath, { readonly: true });
  requireIntegrity(source);
  source.close();

  const restoreLock = acquireDatabaseRestoreLock(databasePath);
  let releaseRestoreLock = true;
  const restoreId = `${Date.now()}-${randomUUID()}`;
  const preparedPath = `${databasePath}.restore-prepared-${restoreId}`;
  const quarantineDirectory = `${databasePath}.pre-restore-${restoreId}`;
  try {
    const runtimeMarkers = listApiRuntimeMarkers(databasePath);
    if (runtimeMarkers.length > 0) {
      throw new Error("API runtime marker is present; stop every API container before restore");
    }
    prepareRestoredDatabase(sourcePath, preparedPath);
    try {
      quarantineAndInstall(databasePath, preparedPath, quarantineDirectory);
    } catch (error) {
      if (error instanceof ManualRestoreRecoveryRequired) releaseRestoreLock = false;
      throw error;
    }
  } catch (error) {
    throw error;
  } finally {
    removeDatabaseSet(preparedPath);
    if (releaseRestoreLock) releaseDatabaseRestoreLock(restoreLock);
  }
  process.stdout.write(`${JSON.stringify({
    status: "restored",
    billing: "disabled",
    quarantine: basename(quarantineDirectory),
  })}\n`);
}

function setBilling(enabled: boolean): void {
  const ledger = new SqliteQuotaLedger(new Database(databasePath));
  ledger.setBillingEnabled(enabled);
  ledger.close();
  process.stdout.write(`${JSON.stringify({ status: "ok", billing: enabled ? "enabled" : "disabled" })}\n`);
}

function requireIntegrity(database: Database.Database): void {
  const rows = database.pragma("integrity_check") as Array<Record<string, string>>;
  const values = rows.flatMap((row) => Object.values(row));
  if (values.length !== 1 || values[0] !== "ok") throw new Error("SQLite integrity check failed");
}

function prepareRestoredDatabase(sourcePath: string, preparedPath: string): void {
  copyFileSync(sourcePath, preparedPath, constants.COPYFILE_EXCL);
  chmodSync(preparedPath, 0o600);
  const database = new Database(preparedPath);
  try {
    const ledger = new SqliteQuotaLedger(database);
    ledger.setBillingEnabled(false);
    database.pragma("wal_checkpoint(TRUNCATE)");
    database.pragma("journal_mode = DELETE");
  } finally {
    if (database.open) database.close();
  }

  for (const sidecar of databaseSidecars(preparedPath)) {
    if (existsSync(sidecar)) {
      throw new Error("Prepared restore database retained a journal sidecar");
    }
  }
  const verification = new Database(preparedPath, { readonly: true, fileMustExist: true });
  try {
    requireIntegrity(verification);
    const billing = verification.prepare(
      "SELECT value FROM quota_metadata WHERE key = 'billing_enabled'",
    ).get() as { value: string } | undefined;
    if (billing?.value !== "0") throw new Error("Prepared restore did not disable billing");
  } finally {
    verification.close();
  }
}

function quarantineAndInstall(
  liveDatabasePath: string,
  preparedPath: string,
  quarantineDirectory: string,
): void {
  const livePaths = [liveDatabasePath, ...databaseSidecars(liveDatabasePath)]
    .filter(existsSync);
  if (!livePaths.includes(liveDatabasePath)) {
    throw new Error("Live database is missing");
  }
  for (const path of livePaths) requireRegularFile(path, "Live database file");

  mkdirSync(quarantineDirectory, { mode: 0o700 });
  const moved: Array<Readonly<{ original: string; quarantined: string }>> = [];
  try {
    for (const original of livePaths) {
      const quarantined = join(quarantineDirectory, basename(original));
      renameSync(original, quarantined);
      moved.push({ original, quarantined });
    }
    renameSync(preparedPath, liveDatabasePath);
  } catch (error) {
    try {
      for (const entry of moved.toReversed()) {
        renameSync(entry.quarantined, entry.original);
      }
      rmSync(quarantineDirectory, { recursive: true, force: true });
    } catch (rollbackError) {
      throw new ManualRestoreRecoveryRequired(
        "Restore rollback failed; keep the restore lock and recover the quarantined database manually",
        { cause: rollbackError },
      );
    }
    throw error;
  }
}

function databaseSidecars(path: string): string[] {
  return [`${path}-wal`, `${path}-shm`, `${path}-journal`];
}

function removeDatabaseSet(path: string): void {
  for (const member of [path, ...databaseSidecars(path)]) {
    rmSync(member, { force: true });
  }
}

function requireRegularFile(path: string, label: string): void {
  const stats = lstatSync(path);
  if (stats.isSymbolicLink() || !stats.isFile()) throw new Error(`${label} is not a regular file`);
}

class ManualRestoreRecoveryRequired extends Error {}

function pruneBackups(directory: string, oldestAllowedMillis: number): void {
  for (const name of readdirSync(directory)) {
    if (!name.startsWith("anitabi-quota-") || !name.endsWith(".sqlite")) continue;
    const path = join(directory, name);
    if (statSync(path).mtimeMs < oldestAllowedMillis) rmSync(path, { force: true });
  }
}
