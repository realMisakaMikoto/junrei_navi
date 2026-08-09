# v0.2.5 candidate switch and rollback

This runbook is deliberately host-neutral. Record the actual Compose project names, loopback ports, reverse-proxy implementation, and proxy configuration path in the change ticket before running it. Do not copy credentials into the ticket or shell history.

## Hard stops

Do not start a production switch until all of the following are true:

- The exact source commit and `anitabi-api:0.2.5` image ID are recorded.
- Backend typecheck/tests and the image build pass from that commit.
- The approved region document's version, checksum, source/license, and review approval are recorded outside Git; no geometry is copied into the ticket.
- Google and AMap written permissions, AMap console egress-IP binding, and every daily/monthly AMap limit are recorded.
- `ANITABI_V025_RELEASE_AT` is the immutable planned release UTC instant and produces the reviewed +14-day v1 sunset.
- The host-specific policy derived from `egress-hosts.json` is enforced and tested. Compose does not enforce it.
- The active service is healthy and a new integrity-checked SQLite backup succeeds.
- `/etc/anitabi-api/active-compose-project` is a root-owned, non-symlink regular file that is not group/world writable and contains only the active Compose project name plus one newline.

## Candidate start

Use two distinct Compose project names and loopback ports. Both containers intentionally use the same forward-moving SQLite bind mount; never copy or replace that ledger during an application rollback.

```sh
export ANITABI_ACTIVE_PROJECT=anitabi-blue
export ANITABI_CANDIDATE_PROJECT=anitabi-green
export ANITABI_ACTIVE_PORT=8787
export ANITABI_CANDIDATE_PORT=8788

docker compose -f compose.yaml -f compose.amap.yaml -p "$ANITABI_ACTIVE_PROJECT" exec -T api node dist/admin.js backup
ANITABI_HOST_PORT="$ANITABI_CANDIDATE_PORT" docker compose -f compose.yaml -f compose.amap.yaml -p "$ANITABI_CANDIDATE_PROJECT" up -d --build api
```

Wait for the candidate process, then require an HTTP 200 and the exact four-key readiness body:

```sh
ANITABI_HEALTH_PORT="$ANITABI_CANDIDATE_PORT" node -e "const p=process.env.ANITABI_HEALTH_PORT;fetch('http://127.0.0.1:'+p+'/v2/health').then(async r=>{const b=await r.json();const k=['amap','database','google','regionData'];if(r.status!==200||Object.keys(b).sort().join(',')!==k.sort().join(',')||Object.values(b).some(v=>v!=='ok'))process.exit(1)}).catch(()=>process.exit(1))"
```

Fetch `/v2/policy` locally and compare `apiVersion`, `regionDataVersion`, `minimumAppVersion`, `v1SunsetAt`, and both provider states with the reviewed release record. This bootstrap check sends no coordinates and consumes no routing quota.

Before switching traffic, run the bounded real-provider smoke from the candidate container. It reads the already-mounted AMap credentials, stays within the reviewed personal quota, rate-limits sequential calls, and logs only one pass/fail line per travel mode:

```sh
docker compose -f compose.yaml -f compose.amap.yaml \
  -p "$ANITABI_CANDIDATE_PROJECT" exec -T api \
  node scripts/amap-live-smoke.mjs
```

Require `AMap live provider smoke: PASS` and record only the candidate commit, UTC run time, and operator identity. Do not copy provider URLs, coordinates, credentials, signatures, or raw responses into the release record.

## Switch

1. Save a recoverable copy and checksum of the active reverse-proxy configuration.
2. Change only the API loopback upstream from `ANITABI_ACTIVE_PORT` to `ANITABI_CANDIDATE_PORT`.
3. Run the selected proxy's native configuration validation (`caddy validate` or `nginx -t`).
4. Reload, do not restart, the selected proxy.
5. Verify public `/v2/health` and `/v2/policy`, then verify a bounded authenticated request for each approved provider only after its quota reservation and privacy evidence are ready.
6. Atomically replace `/etc/anitabi-api/active-compose-project` with a root-owned mode `0644` file containing `$ANITABI_CANDIDATE_PROJECT` and one trailing newline. Run `deploy/backup.sh` immediately and require success before stopping the old container.
7. Observe only safe endpoint/status/error-code logs. Do not capture request bodies, coordinates, tokens, UIDs, upstream URLs, or upstream responses.

Keep the old container stopped only after the observation window succeeds. Record the candidate image ID, health/policy results, backup basename, proxy-config checksum, and switch time.

## Application rollback

If the candidate fails but SQLite integrity and quota state remain certain:

1. Restore the saved proxy upstream port and validate the proxy configuration.
2. Reload the proxy and verify the old container's health.
3. Atomically restore `/etc/anitabi-api/active-compose-project` to `$ANITABI_ACTIVE_PROJECT`, preserving root ownership, mode `0644`, and exactly one trailing newline.
4. Stop the candidate with `docker compose -f compose.yaml -f compose.amap.yaml -p "$ANITABI_CANDIDATE_PROJECT" stop api`.
5. Do **not** restore or replace SQLite. Upstream and Google reservations made while the candidate was active must remain charged.
6. Run `deploy/backup.sh`, require success, and record the failure without sensitive request data.

If database integrity, backup lineage, or quota state is uncertain, disable billing and stop routing instead of automatically rolling back. A database restore is a separate audited recovery action; the admin restore command deliberately leaves billing disabled until ledger comparison is complete.

## Offline database restore

Never run `admin.js restore` with `docker compose exec`. Every API process creates its own runtime marker beside `/data/anitabi.sqlite` before opening SQLite and removes it only after a graceful close. Multiple markers are expected during blue/green operation. Any remaining marker blocks restore, including a stale marker after a crash.

1. Remove the API from public traffic and record every Compose project or other process that mounts `/var/lib/anitabi-api`.
2. Gracefully stop every such API container, including both blue and green projects. Independently verify that no container or host process still has the database, WAL, or SHM file open.
3. Confirm that all runtime markers are gone. If a stale marker remains, do not delete it automatically: first prove its process is dead and record the exact marker and container evidence. A stale `.restore.lock` means a previous restore may have stopped mid-swap; inspect the live database, prepared file, and quarantine directory before any manual recovery.
4. Run restore only as a one-off container with no API process:

```sh
export ANITABI_RESTORE_PROJECT=anitabi-restore
export ANITABI_BACKUP_FILE=/data/backups/anitabi-quota-REPLACE.sqlite

docker compose -f compose.yaml -f compose.amap.yaml \
  -p "$ANITABI_RESTORE_PROJECT" run --rm --no-deps api \
  node dist/admin.js restore "$ANITABI_BACKUP_FILE" CONFIRM_RESTORE_AND_DISABLE_BILLING
```

The command verifies a private temporary copy, disables billing in that copy, checkpoints it without WAL/SHM sidecars, then quarantines the old database, WAL, SHM, and rollback journal together before installing the verified copy. Record the returned quarantine basename. An interrupted or unsafe rollback leaves the restore lock in place so API startup and later restores fail closed.

5. Verify SQLite integrity and compare the restored Google and AMap ledgers with both provider consoles. Do not delete the quarantine set during this audit.
6. Only after the comparison is approved, run the explicit audited-enable command and restart the selected active project. Require `/v2/health` and `/v2/policy` to match the reviewed state before restoring public traffic.
