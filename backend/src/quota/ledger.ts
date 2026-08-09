import Database from "better-sqlite3";
import { ApiError } from "../errors.js";

export const QUOTA_LIMITS = {
  matrix: { monthly: 9_000 },
  route: { monthly: 9_000 },
  navigation: { monthly: 900 },
} as const;

export type QuotaBucket = keyof typeof QUOTA_LIMITS;

export type QuotaReservation = Readonly<{
  bucket: QuotaBucket;
  units: number;
  now?: Date;
}>;

export type QuotaReservationResult = Readonly<{
  monthlyUsed: number;
  monthlyRemaining: number;
}>;

export type LedgerHealth = Readonly<{
  healthy: boolean;
  billingEnabled: boolean;
}>;

export interface QuotaLedger {
  reserve(reservation: QuotaReservation): QuotaReservationResult;
  health(): LedgerHealth;
  close(): void;
}

export const AMAP_UPSTREAM_BUCKETS = ["conversion", "geocode", "distance", "route"] as const;
export type AmapUpstreamBucket = (typeof AMAP_UPSTREAM_BUCKETS)[number];

export type UpstreamLimit = Readonly<{ daily: number; monthly: number }>;
export type AmapUpstreamLimits = Readonly<Record<AmapUpstreamBucket, UpstreamLimit>>;
export type UpstreamReservation = Readonly<{ bucket: AmapUpstreamBucket; units: number }>;

export interface UpstreamUsageLedger {
  reserveAmap(reservations: readonly UpstreamReservation[], now?: Date): void;
  amapHealth(): Readonly<{ healthy: boolean; configured: boolean; billingEnabled: boolean }>;
}

type UsageRow = { used: unknown };
type MetadataRow = { value: string };

export class SqliteQuotaLedger implements QuotaLedger {
  private healthy = true;
  private readonly reserveTransaction: (
    reservation: Required<QuotaReservation>,
  ) => QuotaReservationResult;
  private readonly reserveAmapTransaction: (
    reservations: readonly UpstreamReservation[],
    now: Date,
  ) => void;

  constructor(
    private readonly database: Database.Database,
    private readonly amapLimits?: AmapUpstreamLimits,
  ) {
    try {
      database.pragma("journal_mode = WAL");
      database.pragma("synchronous = FULL");
      database.pragma("busy_timeout = 5000");
      database.exec(`
        CREATE TABLE IF NOT EXISTS quota_usage (
          -- The uid dimension remains schema-compatible with existing ledgers.
          -- New reservations only read and write the global dimension.
          dimension TEXT NOT NULL CHECK (dimension IN ('global', 'uid')),
          bucket TEXT NOT NULL CHECK (bucket IN ('matrix', 'route', 'navigation')),
          subject TEXT NOT NULL,
          period TEXT NOT NULL,
          used INTEGER NOT NULL CHECK (used >= 0),
          PRIMARY KEY (dimension, bucket, subject, period)
        ) STRICT;

        CREATE TABLE IF NOT EXISTS quota_metadata (
          key TEXT PRIMARY KEY,
          value TEXT NOT NULL
        ) STRICT;

        INSERT OR IGNORE INTO quota_metadata(key, value)
        VALUES ('billing_enabled', '1');

        CREATE TABLE IF NOT EXISTS upstream_usage (
          provider TEXT NOT NULL CHECK (provider IN ('amap')),
          bucket TEXT NOT NULL CHECK (bucket IN ('conversion', 'geocode', 'distance', 'route')),
          period_kind TEXT NOT NULL CHECK (period_kind IN ('day', 'month')),
          period TEXT NOT NULL,
          used INTEGER NOT NULL CHECK (used >= 0),
          PRIMARY KEY (provider, bucket, period_kind, period)
        ) STRICT;
      `);
      if (amapLimits !== undefined) validateAmapLimits(amapLimits);
      const integrityRows = database.pragma("integrity_check") as Array<Record<string, string>>;
      const integrityValues = integrityRows.flatMap((row) => Object.values(row));
      if (integrityValues.length !== 1 || integrityValues[0] !== "ok") {
        throw new Error("SQLite integrity check failed");
      }
    } catch (error) {
      this.healthy = false;
      try {
        database.close();
      } catch {}
      throw new ApiError("BACKEND_UNAVAILABLE", { cause: error });
    }

    this.reserveTransaction = database.transaction(
      (reservation: Required<QuotaReservation>): QuotaReservationResult => {
        if (!this.billingEnabled()) throw new ApiError("BACKEND_UNAVAILABLE");

        const limits = QUOTA_LIMITS[reservation.bucket];
        const month = reservation.now.toISOString().slice(0, 7);
        const monthlyUsed = this.readGlobalUsage(reservation.bucket, month);

        if (monthlyUsed + reservation.units > limits.monthly) {
          throw new ApiError("QUOTA_EXHAUSTED");
        }

        this.addGlobalUsage(reservation.bucket, month, reservation.units);

        const nextMonthly = monthlyUsed + reservation.units;
        return {
          monthlyUsed: nextMonthly,
          monthlyRemaining: limits.monthly - nextMonthly,
        };
      },
    ).immediate;

    this.reserveAmapTransaction = database.transaction(
      (reservations: readonly UpstreamReservation[], now: Date): void => {
        if (!this.billingEnabled()) throw new ApiError("BACKEND_UNAVAILABLE");
        if (this.amapLimits === undefined) throw new ApiError("BACKEND_UNAVAILABLE");
        const aggregated = aggregateUpstreamReservations(reservations);
        const day = now.toISOString().slice(0, 10);
        const month = now.toISOString().slice(0, 7);

        for (const reservation of aggregated) {
          const limits = this.amapLimits[reservation.bucket];
          const dailyUsed = this.readAmapUsage(reservation.bucket, "day", day);
          const monthlyUsed = this.readAmapUsage(reservation.bucket, "month", month);
          if (
            dailyUsed + reservation.units > limits.daily ||
            monthlyUsed + reservation.units > limits.monthly
          ) {
            throw new ApiError("QUOTA_EXHAUSTED");
          }
        }
        for (const reservation of aggregated) {
          this.addAmapUsage(reservation.bucket, "day", day, reservation.units);
          this.addAmapUsage(reservation.bucket, "month", month, reservation.units);
        }
      },
    ).immediate;
  }

  static open(path: string, amapLimits?: AmapUpstreamLimits): QuotaLedger & UpstreamUsageLedger {
    try {
      return new SqliteQuotaLedger(new Database(path), amapLimits);
    } catch {
      return new UnavailableQuotaLedger();
    }
  }

  reserve(reservation: QuotaReservation): QuotaReservationResult {
    if (!this.healthy) throw new ApiError("BACKEND_UNAVAILABLE");
    if (!Number.isSafeInteger(reservation.units) || reservation.units <= 0) {
      throw new ApiError("INVALID_ARGUMENT");
    }

    try {
      return this.reserveTransaction({
        ...reservation,
        now: reservation.now ?? new Date(),
      });
    } catch (error) {
      if (error instanceof ApiError) throw error;
      this.healthy = false;
      throw new ApiError("BACKEND_UNAVAILABLE", { cause: error });
    }
  }

  health(): LedgerHealth {
    if (!this.healthy) return { healthy: false, billingEnabled: false };
    try {
      this.database.prepare("SELECT 1").get();
      return { healthy: true, billingEnabled: this.billingEnabled() };
    } catch {
      this.healthy = false;
      return { healthy: false, billingEnabled: false };
    }
  }

  reserveAmap(reservations: readonly UpstreamReservation[], now = new Date()): void {
    if (!this.healthy) throw new ApiError("BACKEND_UNAVAILABLE");
    try {
      this.reserveAmapTransaction(reservations, now);
    } catch (error) {
      if (error instanceof ApiError) throw error;
      this.healthy = false;
      throw new ApiError("BACKEND_UNAVAILABLE", { cause: error });
    }
  }

  amapHealth(): Readonly<{ healthy: boolean; configured: boolean; billingEnabled: boolean }> {
    const health = this.health();
    return {
      healthy: health.healthy,
      configured: this.amapLimits !== undefined,
      billingEnabled: health.billingEnabled,
    };
  }

  setBillingEnabled(enabled: boolean): void {
    if (!this.healthy) throw new ApiError("BACKEND_UNAVAILABLE");
    try {
      this.database
        .prepare("UPDATE quota_metadata SET value = ? WHERE key = 'billing_enabled'")
        .run(enabled ? "1" : "0");
    } catch (error) {
      this.healthy = false;
      throw new ApiError("BACKEND_UNAVAILABLE", { cause: error });
    }
  }

  close(): void {
    this.database.close();
  }

  private billingEnabled(): boolean {
    const row = this.database
      .prepare("SELECT value FROM quota_metadata WHERE key = 'billing_enabled'")
      .get() as MetadataRow | undefined;
    return row?.value === "1";
  }

  private readGlobalUsage(bucket: QuotaBucket, period: string): number {
    const row = this.database
      .prepare(
        "SELECT used FROM quota_usage WHERE dimension = 'global' AND bucket = ? AND subject = '*' AND period = ?",
      )
      .get(bucket, period) as UsageRow | undefined;
    return requireSafeStoredUsage(row?.used);
  }

  private addGlobalUsage(bucket: QuotaBucket, period: string, units: number): void {
    this.database
      .prepare(`
        INSERT INTO quota_usage(dimension, bucket, subject, period, used)
        VALUES ('global', ?, '*', ?, ?)
        ON CONFLICT(dimension, bucket, subject, period)
        DO UPDATE SET used = used + excluded.used
      `)
      .run(bucket, period, units);
  }

  private readAmapUsage(
    bucket: AmapUpstreamBucket,
    periodKind: "day" | "month",
    period: string,
  ): number {
    const row = this.database
      .prepare(
        "SELECT used FROM upstream_usage WHERE provider = 'amap' AND bucket = ? AND period_kind = ? AND period = ?",
      )
      .get(bucket, periodKind, period) as UsageRow | undefined;
    return requireSafeStoredUsage(row?.used);
  }

  private addAmapUsage(
    bucket: AmapUpstreamBucket,
    periodKind: "day" | "month",
    period: string,
    units: number,
  ): void {
    this.database
      .prepare(`
        INSERT INTO upstream_usage(provider, bucket, period_kind, period, used)
        VALUES ('amap', ?, ?, ?, ?)
        ON CONFLICT(provider, bucket, period_kind, period)
        DO UPDATE SET used = used + excluded.used
      `)
      .run(bucket, periodKind, period, units);
  }
}

export class UnavailableQuotaLedger implements QuotaLedger, UpstreamUsageLedger {
  reserve(_reservation: QuotaReservation): never {
    throw new ApiError("BACKEND_UNAVAILABLE");
  }

  health(): LedgerHealth {
    return { healthy: false, billingEnabled: false };
  }

  reserveAmap(_reservations: readonly UpstreamReservation[], _now?: Date): never {
    throw new ApiError("BACKEND_UNAVAILABLE");
  }

  amapHealth(): Readonly<{ healthy: boolean; configured: boolean; billingEnabled: boolean }> {
    return { healthy: false, configured: false, billingEnabled: false };
  }

  close(): void {}
}

function validateAmapLimits(limits: AmapUpstreamLimits): void {
  for (const bucket of AMAP_UPSTREAM_BUCKETS) {
    const value = limits[bucket];
    if (
      !Number.isSafeInteger(value.daily) || value.daily <= 0 ||
      !Number.isSafeInteger(value.monthly) || value.monthly <= 0 ||
      value.daily > value.monthly
    ) {
      throw new ApiError("BACKEND_UNAVAILABLE");
    }
  }
}

function aggregateUpstreamReservations(
  reservations: readonly UpstreamReservation[],
): readonly UpstreamReservation[] {
  if (reservations.length === 0) throw new ApiError("INVALID_ARGUMENT");
  const totals = new Map<AmapUpstreamBucket, number>();
  for (const reservation of reservations) {
    if (!AMAP_UPSTREAM_BUCKETS.includes(reservation.bucket)) throw new ApiError("INVALID_ARGUMENT");
    if (!Number.isSafeInteger(reservation.units) || reservation.units <= 0) {
      throw new ApiError("INVALID_ARGUMENT");
    }
    const total = (totals.get(reservation.bucket) ?? 0) + reservation.units;
    if (!Number.isSafeInteger(total)) throw new ApiError("INVALID_ARGUMENT");
    totals.set(reservation.bucket, total);
  }
  return [...totals].map(([bucket, units]) => ({ bucket, units }));
}

function requireSafeStoredUsage(value: unknown): number {
  if (value === undefined) return 0;
  if (typeof value !== "number" || !Number.isSafeInteger(value) || value < 0) {
    throw new Error("Stored quota usage is outside the safe integer range");
  }
  return value;
}
