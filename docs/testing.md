# Verification record

## 0.4.0 Android-side URL update

Adds controlled MockWebServer + fake upload-target tests in `UrlTransferEngineTest`. Final
hosted Actions results and artifact are given in the delivery response; a queued/failed run
is not counted as a pass. Tests cover bounded streaming, safe source ranges and 50 connections,
resume/reconciliation, cancellation, errors and full payload SHA-256 equality. Actual Google
OAuth/Drive/device/process-kill tests remain unexecuted. See [direct-download guide](android-direct-downloads.md).

## Prior 0.3.0 increment

## Executed in the workspace

- Backend pytest: **60 passed** (HTTP/Drive/DNS mocked, real SQLite + FastAPI TestClient).
- SQLite Alembic: upgrade initial→head, schema `check`, downgrade to previous revision,
  upgrade again, schema `check`: passed on a disposable DB, not production data.
- Web: **3 passed**, TypeScript/Vite production build passed. `npm ci` audit reported no vulnerabilities.
- `git diff --check`: passed.

## Hosted Android verification

CI uses JDK 17 / Android SDK 35 and executes `testDebugUnitTest lintDebug assembleDebug`.
A successful run then checks APK signature with `apksigner`, asserts package ID
`com.cloudfusion.app`, and writes source commit to the downloadable artifact's `build-info.txt`.
Use the run/artifact linked in the delivery message for the **final** source commit.
The workspace has no local Java/SDK; Android verification is hosted, not a local-device run.

## Not executed / not claimed

- Physical phone install/upgrade and UI instrumentation tests.
- Live Google OAuth/token refresh, real Drive uploads/sharing, external HTTP source transfers.
- Live PostgreSQL migration/load/concurrency testing, Docker deployment, production egress firewall.
- Low-RAM device profiling, release signing or Google OAuth verification.
- Full destination SHA-256 verification against a real Google file. Mocked lifecycle test
  does compare complete source/destination SHA-256; production full streams compare Drive MD5
  when available and record source SHA-256 separately.

See [server downloads](server-downloads.md) for exact API/feature limitations. Passing tests
are not proof of production readiness. Do not test delete/trash/public sharing on private user
files without explicit permission; use disposable authorized test accounts/files.
