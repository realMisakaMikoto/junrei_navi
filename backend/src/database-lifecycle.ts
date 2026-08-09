import { randomUUID } from "node:crypto";
import {
  closeSync,
  existsSync,
  fsyncSync,
  openSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { basename, dirname, join } from "node:path";

const RUNTIME_MARKER_SUFFIX = ".marker";

export function createApiRuntimeMarker(databasePath: string): string {
  const restoreLock = databaseRestoreLockPath(databasePath);
  if (existsSync(restoreLock)) {
    throw new Error("Database restore lock is present");
  }

  const markerPath = join(
    dirname(databasePath),
    `${apiRuntimeMarkerPrefix(databasePath)}${process.pid}.${randomUUID()}${RUNTIME_MARKER_SUFFIX}`,
  );
  writeExclusiveMarker(
    markerPath,
    `pid=${process.pid}\nstartedAt=${new Date().toISOString()}\n`,
  );

  if (existsSync(restoreLock)) {
    rmSync(markerPath, { force: true });
    throw new Error("Database restore started during API startup");
  }
  return markerPath;
}

export function removeApiRuntimeMarker(markerPath: string): void {
  rmSync(markerPath, { force: true });
}

export function listApiRuntimeMarkers(databasePath: string): string[] {
  const directory = dirname(databasePath);
  const prefix = apiRuntimeMarkerPrefix(databasePath);
  return readdirSync(directory)
    .filter((name) => name.startsWith(prefix) && name.endsWith(RUNTIME_MARKER_SUFFIX))
    .sort()
    .map((name) => join(directory, name));
}

export function apiRuntimeMarkerPrefix(databasePath: string): string {
  return `${basename(databasePath)}.runtime.`;
}

export function acquireDatabaseRestoreLock(databasePath: string): string {
  const path = databaseRestoreLockPath(databasePath);
  try {
    writeExclusiveMarker(
      path,
      `pid=${process.pid}\nstartedAt=${new Date().toISOString()}\n`,
    );
  } catch (error) {
    throw new Error("Database restore lock already exists", { cause: error });
  }
  return path;
}

export function releaseDatabaseRestoreLock(lockPath: string): void {
  rmSync(lockPath, { force: true });
}

export function databaseRestoreLockPath(databasePath: string): string {
  return `${databasePath}.restore.lock`;
}

function writeExclusiveMarker(path: string, content: string): void {
  let descriptor: number | undefined;
  let created = false;
  let failure: unknown;
  try {
    descriptor = openSync(path, "wx", 0o600);
    created = true;
    writeFileSync(descriptor, content, { encoding: "utf8" });
    fsyncSync(descriptor);
  } catch (error) {
    failure = error;
  } finally {
    if (descriptor !== undefined) closeSync(descriptor);
  }
  if (failure !== undefined) {
    if (created) rmSync(path, { force: true });
    throw failure;
  }
}
