# Android Direct Download — 0.4.0

## Phone workflow

Home → **Direct Download · Paste link** (also available in Settings) → paste public HTTPS
file URL → Preview → choose one of the existing connected Google accounts → Start Transfer.
No backend origin, CloudFusion workspace account or Web client secret is required. Reconnect
in Accounts only if Google requires new consent. Existing direct-Drive accounts and their
Keystore encryption alias are preserved; no new account migration or login is performed.

The old optional backend and server download implementation remain in the repository but
are **not used or presented in this workflow**. The Android screen no longer navigates to
its workspace login form. No backend APIs or Google tokens are used to fetch source URLs.

## Transfer engine

- Source HTTPS → bounded Android RAM block → **one** Google Drive resumable upload session.
- The complete file is never staged in internal/external storage, a temp file or one giant
  byte array. No DownloadManager, storage permission, disk cache or extra foreground service.
- Blocks are configurable: **256 KiB**, **1 MiB default**, or **4 MiB**. Non-final blocks respect
  Google's 256 KiB alignment. Ordinary app data and encrypted task metadata still use storage.
- Backpressure: wait for all ranges of one block, upload it, wait for Google's response,
  checkpoint the acknowledged offset, then fetch the next block. No unbounded queue/prefetch.
- Source and destination clients are separate: source requests never receive Google tokens,
  cookies or workspace credentials. Public DNS addresses are checked before connection;
  localhost/private/metadata/special addresses and unsafe redirect destinations are blocked.
- Only HTTPS on port 443. Cleartext HTTP is deliberately unsupported; Android's cleartext
  ban remains unchanged. Up to five redirects; original URL is encrypted in task state.
- Preview uses `GET Range: bytes=0-0`, not HEAD. If the server returns 200, close it after
  headers, record a known Content-Length and use a single streaming GET for the transfer.
  HTML pages, native documents, unknown/zero sizes and sizes over 5 TiB are rejected.
- Filename derives from final URL path, not Content-Disposition. No arbitrary cookies,
  authenticated-source headers, DRM bypass or website scraping.

## Parallel connections

Normal 1 (default), Fast 4, Advanced 8, Extreme 50; slider allows any count 1–50.
Parallel requests are used only with verified 206 range preview **and a strong ETag**.
A block is split into disjoint slices written directly into one shared bounded array; every
response must match expected Content-Range, Content-Length, ETag, MIME and Last-Modified
when originally supplied. There is only **one ordered Drive upload**, not many files to merge.

Without ranges/strong ETag, fall back to one persistent source stream, even if 50 was selected.
If a server advertises/initially supports ranges and later ignores them, stop rather than
blindly concatenate or replay. No automatic 50-request retry storm: reduce connections and
explicitly retry after rate limits/network errors. Fifty connections are not a speed guarantee
and cost sockets/TLS/battery. Use 1 connection + 256 KiB on low-memory phones.

## Pause, resume, cancellation, restart

- Pause cancels the current coroutine, cancels active source sockets (including body reads)
  and records `paused`; an in-flight Drive upload may take up to its timeout to unwind and
  may still complete. Source reads have an idle timeout, not a total-file time limit.
  Resume remains blocked while that worker is still stopping.
- Before sending bytes, persist the Google session URL with existing Android Keystore storage.
  On every resume, **probe Google** and use its committed offset, never merely the saved value.
- Byte-offset resume requires range support + the same strong ETag. Last-Modified is checked
  when supplied but is deliberately **not** sufficient for parallel/resume consistency.
- If the source has no safe range resume, a nonzero Google offset stops with a clear error.
  Cancel and inspect the destination before creating a new byte-zero transfer. No automatic
  replacement session or silent duplicate upload is created.
- Process death converts running tasks to `interrupted` using the existing local-state recovery.
  Open the app and explicitly Resume. No complete source file is persisted. Background Android
  execution is not guaranteed: keep CloudFusion open and online.
- Cancel discards local resumability and closes source work as the coroutine unwinds. It does
  not delete a Drive file that may already have completed. There is no claim that a partial
  Google session is remotely revoked; unused sessions expire under Google's policy.
- Expired Google sessions become `review_required`; inspect Drive before starting again.
  Expired/revoked Google authorization reuses existing token refresh/reconnect handling.

## Progress and integrity

Progress is Google's acknowledged byte count, not attempted download bytes. Download/upload
rates are measured separately per block (bytes / request duration), not invented speeds or
simultaneous instantaneous rates. Pause/network waits can make rates stale until another block.

For uninterrupted full streams, compute MD5 and SHA-256 in bounded memory; checkpoint digests
**before** the final upload request. Request SHA-256 from Google; compare it when available,
otherwise compare MD5 when available. Compare destination size when returned. Missing checksums
are reported as unverified, never a success-verification claim. Resuming midway without a
previously saved full-stream digest cannot claim whole-file checksum verification.
No real user's file is deleted/trash/publicized for verification.

## Testing and external prerequisites

`UrlTransferEngineTest` uses a controlled MockWebServer supporting exact ranges and a mock
Drive target. It exercises small/5-MiB-plus files, 50 disjoint source requests into one upload,
overlapping parallel requests, explicit source socket cancellation, redirects, HTTP 403/404/429/503, unsupported URLs/private addresses, no-range fallback, lost
acknowledgements/restart/retry, pause/cancellation, unsafe resume rejection, changed ETag,
wrong ranges, bounded buffers and complete source/destination SHA-256 equality. Existing
DriveClient tests cover real HTTP request construction with mocked Google responses.

These tests are **not** live OAuth, real Google Drive destination verification, physical
Android process-death instrumentation, low-memory profiling or real-device installation.
The handoff reports actual hosted test/build results after execution. Tests use HTTP loopback
only via an explicit injected test transport; production remains public HTTPS only.

Google project/package/SHA-1/scope/test users must already be configured for the installed APK.
Two existing connections are reused, not proof that a newly signed APK has the same identity.
Google Cloud configuration cannot be verified remotely here. See [OAuth guide](google-oauth.md).

## Build / install

VersionCode 4, versionName 0.4.0, package `com.cloudfusion.app`, Android API 26+.
Actions runs `testDebugUnitTest lintDebug assembleDebug`, verifies signature/package, and includes
`build-info.txt` + `signing-certificate.txt` in the actual successful APK artifact.

The original signing key is not available in this workspace. Existing optional stable-key
GitHub Secrets are respected. Without the original key, a newly debug-signed APK **cannot be
promised to update** your installed app or preserve its local account state. Do not uninstall
if you need that state. A deliberate fresh-install test removes local state, not Drive files,
and may require registering the new APK SHA-1 in the Android OAuth client. No key is committed.
