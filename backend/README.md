# 巡礼手帳 API 后端

The v0.2.5 backend is a single Node.js 24 LTS / Fastify process backed by SQLite WAL. It accepts only Firebase Anonymous ID tokens for POST requests, independently classifies WGS-84 coordinates from approved versioned region data, chooses Google or AMap server-side, and reserves quota before every billable operation.

## Endpoints

- `GET /v2/policy`: unauthenticated bootstrap metadata: API/region-data versions, minimum app version, v1 sunset, and provider status. It fails closed while region data is unavailable.
- `GET /v2/health`: only `ok` / `unavailable` status for database, region data, Google, and AMap.
- `POST /v2/matrix`: 2-10 WGS-84 road coordinates. The backend chooses the provider and returns `provider`, `coordinateSystem`, and `regionDataVersion`.
- `POST /v2/route`: 2-12 WGS-84 road locations or exactly two transit locations, with the same provider metadata. AMap `ARRIVE_BY` and transport-mode filters are rejected rather than silently ignored.
- `POST /v2/navigation/reserve`: one WGS-84 origin and 1-25 destinations. Google regions reserve Navigation SDK destinations; AMap regions return the external AMap execution strategy without consuming Google quota.
- `POST /v1/matrix`, `/v1/route`, and `/v1/navigation/reserve`: compatibility endpoints. Before sunset, matrix/route reject every AMap-region coordinate with `426 CLIENT_UPGRADE_REQUIRED`; after sunset every v1 POST returns 426. The legacy coordinate-free navigation reservation remains unchanged until sunset.
- `GET /v1/health`: legacy service/database health.

POST bodies are capped at 16 KiB and must arrive through HTTPS with Firebase auth. Every v2 POST also requires `X-Anitabi-App-Version` (stable `major.minor.patch`, at least the configured minimum) and `X-Anitabi-Region-Data-Version` (exact match). Clients never select a provider. Mixed providers, unresolved boundaries, and mixed Japanese/non-Japanese Google transit fail before quota or upstream calls.

Google and AMap upstream URLs, timeouts, concurrency, response-size limits, field selection, response normalization, and safe error mapping are fixed in source. The AMap client allows at most two concurrent upstream calls and starts at most three calls in any rolling one-second window, matching the verified personal-developer QPS allowance. AMap semicolon-delimited GCJ-02 geometry is validated and re-encoded using the common precision-5 encoded-polyline format; raw upstream geometry is never returned.

## Region data

No production geometry is committed. `/run/region-data/territories.json` must be an approved GeoJSON-derived document containing a version, HTTPS source and license metadata, approval authority/identifier/time, approval flag, SHA-256 checksum, and all required territory features. The loader validates metadata, checksum, coordinate bounds, ring closure/area, holes, total byte/vertex limits, and treats polygon boundaries or overlapping classifications as unresolved. Missing, unapproved, malformed, or mismatched data keeps policy and routing closed while `/v2/health` remains available.

## Quotas

Reservations use `BEGIN IMMEDIATE` SQLite transactions and UTC month boundaries:

| Bucket | Monthly global |
| --- | ---: |
| Matrix Essentials | 9,000 elements |
| Compute Routes Essentials | 9,000 requests |
| Navigation Request | 900 destinations |

There is no daily per-UID quota. Firebase Anonymous UID verification remains mandatory, but new reservations neither read nor write UID usage rows. Existing UID rows remain inert in legacy SQLite ledgers so the global billing ledger does not require a destructive migration. The in-process anti-abuse limiter uses only an HMAC of the request IP, with a capacity of 60 requests and a refill rate of 5 requests per second. It evicts digests after 15 idle minutes and retains at most 10,000 least-recently-used digests.

Reservations are deliberately not refunded after an upstream failure. Database errors and a disabled billing flag fail closed. Restores always disable billing until an operator compares the restored ledger with Google and AMap usage and runs the explicit audited-enable command. Every running API process holds a unique marker beside SQLite, so blue/green peers can coexist while any live or stale marker prevents an offline restore. Restore first verifies a temporary copy, then quarantines the prior database and its WAL/SHM sidecars as one recoverable set; it must never run through `docker compose exec` against a live API container.

AMap uses a separate STRICT `upstream_usage` table without changing the legacy `quota_usage` constraints. Coordinate conversion, reverse geocoding, distance, and route calls each have independently configured daily and monthly limits. All calls for one accepted request are reserved atomically before the first upstream request and are not refunded. Missing any AMap limit disables AMap.

## Local verification

```text
npm ci
npm test
docker build -t anitabi-api:0.2.5 .
```

Tests use generated keys and simulated Google responses. No Google credential is needed.

## VPS layout

- Application: `/opt/anitabi-api`
- Data and seven-day backups: `/var/lib/anitabi-api`
- Read-only secrets: `/etc/anitabi-api/secrets`

The Compose file publishes the container only on host `127.0.0.1:8787` by default. Set the non-secret `ANITABI_HOST_PORT` variable when needed. The container remains non-root, drops all capabilities, uses a read-only root filesystem, and mounts only data, read-only secrets, and read-only region data. `deploy/Caddyfile.api` and `deploy/nginx-api.conf` are additive TLS virtual hosts; use only the one matching the inventoried reverse proxy. The Nginx template expects the named Let's Encrypt certificate paths to exist and redirects port 80 rather than forwarding plaintext POST requests.

Install `deploy/anitabi-api-backup.service` and `deploy/anitabi-api-backup.timer` as a pair to run the existing integrity-checked backup script daily with a randomized delay. The script resolves the current blue/green Compose project from `/etc/anitabi-api/active-compose-project`; that file must be a root-owned, non-symlink regular file, must not be group/world writable, and must contain exactly one validated project name plus a newline.

Use [`deploy/DEPLOY_V0.2.5.md`](deploy/DEPLOY_V0.2.5.md) for the candidate switch and non-ledger rollback procedure. [`deploy/egress-hosts.json`](deploy/egress-hosts.json) is the explicit runtime host inventory; [`deploy/EGRESS_POLICY.md`](deploy/EGRESS_POLICY.md) defines the required host-specific enforcement evidence and makes clear that Compose itself is not an egress firewall.

Do not place credentials or licensed geometry in `.env`, Git, images, or logs. Keep the Google service account, HMAC key, AMap Web key, and AMap signing secret as distinct owner-only files. `ANITABI_V025_RELEASE_AT` is the exact UTC release instant; the service derives the v1 sunset as exactly 14 days later. The base `compose.yaml` deliberately has no AMap secret bind mounts, so it can boot health-only with AMap disabled when those host files do not exist. Production AMap startup must explicitly add `-f compose.amap.yaml`; that override enables AMap and requires both owner-only secret files. AMap is usable only when both files are readable and every daily/monthly limit is set. Production release additionally remains blocked on the required provider permissions, licensed/reviewed region data, actual console quotas, and network-level egress controls.
