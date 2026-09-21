# Discovery data contract

Verified against public primary sources on 2026-09-15, 2026-09-16 and 2026-09-20. Samples were inspected in memory. This document records structure and aggregate checks only; it contains no source titles, coordinates, search terms, or response bodies.

## Authorized endpoints

The approved frontend redesign permits a narrow exception to the previous API-only rule:

- `https://www.anitabi.cn/d/g.json`: discovery index.
- `https://www.anitabi.cn/d/g{n}.json`: nonnegative detail page numbers calculated from the index.
- `https://api.anitabi.cn/bangumi/{subjectId}/points/detail`: the existing subject details endpoint, prioritized after a user opens an incomplete subject.

The dedicated public client uses the app's versioned technical User-Agent. It has no Firebase token, map key, cookie jar or HTTP authenticator. Redirects are disabled. It does not use the Swift client's alternate origins. Static requests carry only a cache query parameter, shared by every request in an update round; that parameter is not the dataset version.

Images accept HTTPS on `image.anitabi.cn` only, without userinfo or a nonstandard port. A source root-relative image path is resolved against that host. External absolute image URLs, protocol-relative URLs and unsupported image values are omitted. A missing or rejected image never removes a point. Images are not downloaded by the discovery repository.

## Primary source evidence

- [Pinned Swift loader](https://github.com/anitabi/anitabi-swift-app/blob/51e597120c32932d5f9af129a2e2f9b08a74d3d6/anitabi/Home/AnitabiDataLoader.swift).
- [Current web application](https://www.anitabi.cn/map), whose observed loader was [`/_/b10d4589.js`](https://www.anitabi.cn/_/b10d4589.js). The deployed asset name is an observation, not a runtime dependency.
- [Official API documentation](https://github.com/anitabi/anitabi.cn-document/blob/main/api.md).
- Live index, first detail page and selected existing API responses, inspected without writing samples to the workspace.

The index sample contained 1,527 subjects and 50,579 point tuples, with a page size of 250. A subsequent same-round probe contained 1,531 subjects, still with page size 250. This is changing public data, not a fixed expected test count. The first detail page's subject IDs matched the corresponding index slice exactly; every index point in that slice had a detail row. The implementation derives page count with ceiling division, never the current number of pages.

## Index mapping

Root: `[subjectRows, pageSize, modified]`. Subject rows have 18 fields. The adapter consumes:

| Index | Meaning |
| --- | --- |
| 0 | Numeric subject ID |
| 1 | Chinese name |
| 2 | English name |
| 3 | Original name |
| 4 | City label supplied by Anitabi |
| 5 | Subject color |
| 6 | Cover image |
| 12 | Flat point tuples: `[pointId, latitude, longitude, priority]` repeated |

Remaining subject fields are not needed to place discovery markers. Coordinates are parsed as `Double`, explicitly latitude before longitude. Optional zero/null sentinels are not presented as text. Structural corruption, conflicting duplicate identities, non-finite/out-of-range coordinates, missing required fields and truncated point tuples reject the new index. An invalid update never replaces the old valid index.

Point identity is always `subjectId::pointId`. Identical repeated point tuples are deduplicated within a subject; the same raw ID in different subjects remains two independent points. Duplicate subject rows and conflicting duplicate point tuples fail validation.

The dataset version combines the source `modified` value with a SHA-256 digest of the parsed index document. A changed payload with an unchanged source timestamp is therefore a new generation too.

## Detail mapping

Page root: a list of `[subjectId, theme, pointRows, modified]` cells. Point rows have 15 fields:

| Index | Meaning |
| --- | --- |
| 0 | Point ID |
| 1 | Name |
| 2 | Chinese name |
| 3 | Folder flag |
| 4 | Source map ID, not consumed |
| 5 | Contributor ID, not consumed |
| 6 | Image |
| 7 | Folder ID |
| 8 | Episode, string or number |
| 9 | Timecode in seconds |
| 10 | Description (`mark` in the web parser) |
| 11 | Source attribution |
| 12 | Source URL |
| 13 | Folder label |
| 14 | Density, not consumed |

The pinned Swift loader labels slots 2 and 13 differently. Android follows the verified current web parser: slot 2 is `cn`, slot 13 is `folder`. It does not translate the Swift offsets blindly. A referenced folder row can supply a group name; folder rows themselves never become navigation points. Unknown optional fields remain absent. Ungrouped points remain available to the UI.

Detail merging updates display metadata only. It never reads API coordinates into the discovery model, creates a new marker without an index coordinate, or modifies a selected/saved journey. A refreshed row also clears optional fields removed by the new data, instead of retaining old metadata under a fresh label.

## Coordinate evidence boundary

The 2026-09-20 follow-up selected three subjects from the current index itself (first, middle and last eligible rows with at least three indexed points), rather than relying on remembered subject IDs. The index returned HTTP 200 with 1,533 subjects and page size 250. All six subject requests returned HTTP 200. Their detail arrays contained 26, 10 and 3 point objects; recursively inspecting every response found no point-level `geo` values. Two lightweight responses contained a root `geo`, but this describes the subject center and cannot establish any individual point's coordinate.

A full field/type inspection of the middle sample confirmed that the lightweight point collection is named `litePoints`. Its 10 entries contained only `id`, `cn`, `name`, `image`, `s`, `origin` and `originURL` across optional field variants. The matching detail response's 10 entries used `id`, `name`, `image`, `ep`, `s`, `origin` and `originURL`. Neither collection hid coordinates in a wrapper, alternative field name or numeric tuple. Responses stayed in process memory; only structure, counts and HTTP status were emitted, with serial requests spaced by two seconds and the app's versioned technical User-Agent. No credentials, cookies or alternate origins were used.

Consequently **a live new-index versus old-API coordinate equality check remains unproven**: there were zero comparable point-coordinate pairs, not a successful equality result. Current official API documentation still includes `geo` in its lightweight-point and detail examples, so the observed metadata-only samples differ from the documented examples. The verified evidence does not establish whether this behavior applies to every subject or why the service omits those fields. The old adapter's empty-coordinate default cannot supply a coordinate baseline; subject centers must not be substituted. The user approved the replacement acceptance basis below on 2026-09-21; this does not change the original comparison's unproven result.

A September 21 independent follow-up used the two subjects explicitly linked by the official API document, rather than another arbitrary index sample. The document revision was `61c62f78ad15699e612065f06646c76af8e3db8f`; both subjects existed in the 1,533-subject current index. Their two lightweight responses contained 10 rows each; the detail responses contained 84 and 63 rows. None had the documented point-level `geo` field, so all four comparisons again had zero comparable pairs. Requests were serial with two-second spacing and the versioned technical User-Agent; responses and comparison values remained in memory. No new endpoint, credential, cookie or mirror was used. This strengthens the observed absence of a usable old-API baseline without claiming that every subject or future response behaves identically.

What is established: the current web index parser explicitly maps the tuple to `[latitude, longitude]`; the previously observed deployed script remained available on 2026-09-20 and still contained the point mapping `geo:[b[I+1],b[I+2]]`. Synthetic regression tests verify exact Double preservation, no coordinate overwrite during API/page merging, and subject-scoped identity. Device projection, Google/Amap conversion and old saved-journey coordinate checks belong to the separate integration acceptance work.

### Approved replacement baseline (2026-09-21)

The user explicitly approved replacing the unavailable live old-API same-ID comparison with three required evidence layers: **the current primary web parsing contract, native Google and AMap projection/anchor checks, and saved-coordinate regression checks**. All three layers now have recorded evidence at the [requirement matrix](FRONTEND_REQUIREMENT_MATRIX.md)'s stated scopes, including actual API 37 ARM AMap projection on the repaired signed artifact. This establishes the replacement coordinate basis at those scopes; the separate API 26 native-AMap coverage decision remains open. The original live old/new comparison stays unproven and must never be relabeled as passed. No coordinate correction, new data origin or wider network access is authorized by this baseline decision.

Execution update: `cb89265` passed exact-head CI `35556295376` and signed build `35557984317`; artifact hashes/fixed signer were independently verified. API 37 passed all three AMap native cases and seven Google native cases, including coordinate anchors across presentation changes; local 417 JVM and saved-coordinate results retain their recorded scope. The first AMap suite had one initial tap-callback timeout after projection/pixel checks had passed; the same APK then passed the single case, full suite and visual-capture case. Its cause is unproven and the initial failure is retained. API 26 passed unsupported-ABI safe fallback, which does not establish native AMap projection. Compatible ARM coverage or an explicit substitute still awaits the user; see the [signed test plan](FRONTEND_SIGNED_TEST_PLAN.md).

## State, lifecycle and cache

- `indexAvailable`: a valid coordinate index exists, independently of detail loading.
- `detailsComplete`: every remaining index point has some detail content, which may be from the previous generation.
- `detailsCurrent`: every dynamic page validated, every point's detail version matches the index, and the final index recheck matched.
- API-prioritized content is tagged `api:<indexVersion>`, because that endpoint does not establish index-generation consistency. It cannot substitute for a validated current detail page.
- Missing/failed/malformed pages remain retryable even when the initial index check happened less than 24 hours ago. Old details are an explicit stale fallback.
- A successful, complete cache is checked again after 24 hours; manual refresh checks immediately. Cache state appears before network updates.
- One network request runs at a time, with a one-second interval. An already queued subject request gets the next network slot before subsequent bulk pages. Duplicate in-flight requests for the same subject share one deferred result.
- Backgrounding cancels the update and subject work; foregrounding resumes from validated pages. There is no polling triggered by camera movement, local search, clustering or filtering.
- At the end of a round, the index is fetched again using the same cache token. A generation mismatch leaves detail freshness unverified and permits another update.

`FileDiscoveryCache` writes typed snapshots under the app cache directory, separately from Room schema 2 and `StoredTourV2`. A pending file is flushed and atomically moved over `current.json`; a new generation preserves the previous valid generation. Corrupted current data falls back to `previous.json` and is never promoted over that backup. Only the current and previous generations are retained, plus the transient pending write. No route response or provider content enters this cache.

## Validation and encountered issues

JVM tests use synthetic data only and cover mapping, precision, ID collisions, malformed data, folder handling, optional values, image restrictions, stale carry-over, dynamic pages, the 24-hour interval, manual refresh, missing/wrong pages, final-version mismatch, subject request deduplication, lifecycle cancellation and atomic-cache recovery. HTTP tests inspect constructed requests, plus a local MockWebServer redirect test; they do not contact a public service.

At authoring time these tests were prepared but had not yet been run; the root task coordinates Gradle execution and records the actual outcome in the frontend acceptance document.

Read-only research initially received HTTP 403 using Python's default User-Agent. Retrying the same authorized public data endpoint with the app's technical User-Agent through curl succeeded. The webpage's minified script tags also omitted quoted `src` values, so a quoted-only extraction initially found no scripts; extraction of the actual deployed asset paths located and verified the current parser. Neither issue required credentials, mirrors or production changes.
