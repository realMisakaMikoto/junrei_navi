export const API_ERROR_CODES = [
  "UNAUTHENTICATED",
  "INVALID_ARGUMENT",
  "NO_ROUTE",
  "QUOTA_EXHAUSTED",
  "RATE_LIMITED",
  "UPSTREAM_UNAVAILABLE",
  "BACKEND_UNAVAILABLE",
  "MIXED_MAP_PROVIDERS",
  "MIXED_TRANSIT_REGIONS",
  "REGION_UNRESOLVED",
  "REGION_DATA_OUTDATED",
  "CLIENT_UPGRADE_REQUIRED",
] as const;

export type ApiErrorCode = (typeof API_ERROR_CODES)[number];

const STATUS_BY_CODE: Record<ApiErrorCode, number> = {
  UNAUTHENTICATED: 401,
  INVALID_ARGUMENT: 400,
  NO_ROUTE: 404,
  QUOTA_EXHAUSTED: 429,
  RATE_LIMITED: 429,
  UPSTREAM_UNAVAILABLE: 503,
  BACKEND_UNAVAILABLE: 503,
  MIXED_MAP_PROVIDERS: 422,
  MIXED_TRANSIT_REGIONS: 422,
  REGION_UNRESOLVED: 422,
  REGION_DATA_OUTDATED: 409,
  CLIENT_UPGRADE_REQUIRED: 426,
};

const SAFE_MESSAGES: Record<ApiErrorCode, string> = {
  UNAUTHENTICATED: "Authentication is required.",
  INVALID_ARGUMENT: "The request is invalid.",
  NO_ROUTE: "No route is available.",
  QUOTA_EXHAUSTED: "The routing quota is exhausted.",
  RATE_LIMITED: "Too many requests.",
  UPSTREAM_UNAVAILABLE: "The routing provider is temporarily unavailable.",
  BACKEND_UNAVAILABLE: "The routing service is temporarily unavailable.",
  MIXED_MAP_PROVIDERS: "All journey locations must use the same map provider.",
  MIXED_TRANSIT_REGIONS: "Japanese and non-Japanese transit locations cannot be mixed.",
  REGION_UNRESOLVED: "The map region could not be resolved safely.",
  REGION_DATA_OUTDATED: "The app region data must be updated.",
  CLIENT_UPGRADE_REQUIRED: "A newer app version is required.",
};

export class ApiError extends Error {
  readonly statusCode: number;
  readonly retryAfterSeconds: number | undefined;

  constructor(readonly code: ApiErrorCode, options?: { cause?: unknown; retryAfterSeconds?: number }) {
    super(SAFE_MESSAGES[code], options);
    this.name = "ApiError";
    this.statusCode = STATUS_BY_CODE[code];
    this.retryAfterSeconds = options?.retryAfterSeconds;
  }
}

export function errorBody(error: ApiError): {
  error: { code: ApiErrorCode; message: string };
} {
  return {
    error: {
      code: error.code,
      message: error.message,
    },
  };
}
