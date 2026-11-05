# R2A — Conditional catalog refresh and offline-first cache

**Base:** `Mikeyphw/MMRL-LSPosed` main `9caef214d4f6770e1cc64d367cc3092633c5c0ae`.

## Scope

- Keeps the Vector 2.2 public catalog URL: `https://backup.modules.lsposed.org/modules.json`.
- Introduces `If-None-Match` only when the locally cached JSON is valid, and its sidecar contains a safe strong/weak ETag and a SHA-256 digest matching that exact body.
- `200 OK`: apply the existing 6 MiB limit and JSON/module validity checks, then update cache and metadata with temporary-file renames.
- `304 Not Modified`: reuse the parsed cache only when a validated ETag was actually sent; never claim online freshness from an unsolicited 304.
- Other HTTP status, timeout, DNS, invalid response or stale metadata: retain a parseable saved catalog and clearly label the refresh failure. If no usable saved catalog exists, fail with the existing connection/Private DNS guidance.
- No credentials are stored in the cache metadata. SHA-256 binds metadata to cache bytes; **it does not authenticate module publishers**, prove APK signing or verify runtime hooking.
- Refresh remains user initiated; sorting/search/filtering never issue network requests.

## Test matrix

| Case | Expected |
|---|---|
| First network 200 with safe ETag | Parse and save JSON plus bound receipt |
| Second network 304 with valid receipt | Reuse saved entries, no redownload |
| 200 without ETag | Replace catalog, clear validator |
| Tampered cache or altered sidecar | No conditional validator sent |
| Truncated, CRLF or quoted-header injection | Reject validator |
| Network exception / invalid 200 | Use last valid cache with warning |
| 304 without prior valid parsed cache | Refuse online freshness |
| Offline with no valid cache | Show connection error |
| Sort/search cached catalog | No network call |

## Qualification

Run full hosted native Devtool debug DAG, JVM tests (including `R2CatalogCacheTest`), lint, API 36 Compose tests and the PR artifact gate. A passing PR workflow does not establish trusted-main release provenance or physical network acceptance. After merge, separately inspect trusted-main signing and provenance before any install. No Gradle execution is required in Termux.

Manual Android acceptance: with catalog populated, toggle connectivity off, reopen Discover, confirm cached entries, verify all six sorts; reconnect and tap Refresh twice; confirm UI stays populated and no repeated root requests. Do not clear app data or change unrelated LSPosed modules.
