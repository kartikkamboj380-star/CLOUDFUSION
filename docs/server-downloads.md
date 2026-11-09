# Server URL downloads (0.3.0): implementation and deployment status

**This is a tested implementation increment, NOT a production-readiness certification.**
No hosted backend, real Google credentials, provider verification, original APK signing key,
or physical Android test device is supplied by this repository/session.

## What changed / repository audit

- Existing Kotlin/Compose Android package `com.cloudfusion.app` remains; no Flutter replacement.
- Direct Google Play services authorization, direct Drive browsing/quota, SAF uploads/downloads,
  encrypted local state and on-phone copies remain available. These do not use the optional backend.
- Existing FastAPI, SQLAlchemy, Alembic, PostgreSQL/SQLite, Docker, backend browser OAuth,
  scoped file APIs, permissions, quota reservations and streaming account copies are retained.
- Backend auth uses scrypt passwords and hashed expiring bearer sessions. Google credentials
  are Fernet-encrypted; browser OAuth uses state, PKCE, verified identity and offline refresh.
- Existing server copies run in process, not in a durable distributed queue. Existing Android
  copies travel through the phone. Neither is silently re-labelled as a server URL download.
- The previous Google consent handler masked non-OK activity results as cancellations.
  Returned error Intents are decoded now; common Google status codes have specific diagnostics.
  **Google login is not live-verified or declared fixed.**
- New additive `remote_sources` table stores encrypted source URL, strong ETag and optional
  source SHA-256, referencing existing transfers. No existing tables/data are dropped.
- Android version is 0.3.0 / code 3. Direct and server sessions use separate Keystore aliases;
  the existing direct-state alias is unchanged.

## Implemented server-download flow

Home → **Paste download link · Server to cloud** (also in Settings) → trusted HTTPS backend → CloudFusion
server sign-in/register → Connect server Google account (system browser) → return and Refresh
→ select destination/default account → paste direct link/clipboard → Preview → Download to Cloud.

This optional workspace **requires a real deployed backend**. It does not reintroduce a
backend requirement for the existing direct-Drive screens. Its CloudFusion password is not
your Google password. A direct Android connection does not grant a server offline access.

- Authenticated HEAD preview, safe filename, size, MIME and resume capability indication.
- HTTP/HTTPS standard ports only; public direct resources with known nonzero Content-Length,
  identity encoding and HEAD support. HTML pages, missing sizes, authenticated links, expired
  links, DRM and unsupported sources fail; no website scraping/security bypass.
- Every connection/redirect resolves and validates **all** DNS answers; public IP pinned to
  socket, original HTTPS hostname retained for certificate verification. No environment proxy,
  cookies, source authorization headers, private/local/metadata addresses or HTTPS downgrade.
- One remote worker, up to 20 retained nonterminal/failed tasks globally. Reads/upload blocks
  bounded at 4 MiB; no whole-file RAM buffer or disk staging. The phone exchanges JSON only.
- Server-side Drive resumable sessions, persisted acknowledged bytes, idempotent creation,
  history pagination, explicit pause/resume/retry/cancel and terminal-history removal.
- Pause/cancel apply between chunks; an in-flight source read/upload can finish first.
  Paused/failed tasks retain quota reservations until completion/cancel. Cancel failed tasks
  you no longer need. History removal does not delete a Drive file.
- Server restart pauses remote tasks; explicitly resume after restart. No automatic replay.
  Google offset is probed first. Resume beyond byte zero requires the same strong ETag,
  HTTP 206, exact Content-Range/length, and unchanged MIME. Refused/changed sources stop safely.
- Uninterrupted full streams compute source SHA-256 and MD5. MD5 is compared only when Drive
  returns its checksum. A resumed stream does not claim full-source digest verification.
  Source SHA-256 is **not** destination SHA-256 verification. Mock lifecycle tests compare
  complete source/destination SHA-256, but this is not a live Google integrity test.
- Android shows real acknowledged progress, sampled bytes/s and ETA when measurable, account
  label, errors and completion message. Polling is 15s only while this screen is STARTED and
  a visible task is pending/in-progress. Manual Refresh also works. No file-transfer foreground
  service is needed because the backend transfers bytes.

## API (bearer token required)

| Method | Path | Function |
|---|---|---|
| POST | `/api/remote-downloads/preview` | `{url}` metadata |
| POST | `/api/remote-downloads` | `{url, account_id, idempotency_key}` enqueue |
| GET | `/api/remote-downloads?limit=50&offset=0` | user-scoped paginated history |
| POST | `/api/remote-downloads/{id}/pause` | pause |
| POST | `/api/remote-downloads/{id}/resume` | resume paused/failed task |
| POST | `/api/remote-downloads/{id}/retry` | same safe reconciliation as resume |
| POST | `/api/remote-downloads/{id}/cancel` | cancel, release reservation |
| GET | `/api/remote-downloads/{id}/integrity` | available digests, verification limits |
| DELETE | `/api/remote-downloads/{id}` | completed/cancelled history only |

Existing `/api/auth/*`, `/api/oauth/start`, `/api/accounts`, `/api/transfers/{id}` and
file/permission APIs remain. `/docs` describes schemas. URLs and resumable credentials are
never returned in task history. Preview/create also use a process-local per-IP limiter;
production requires edge rate limiting and registration restrictions.

## Deployment

1. Back up the database and existing Fernet key **before** migration. Keep the same key.
2. Copy `.env.example` to private `.env`. Fill `DATABASE_URL`, `FERNET_KEY`, Web
   `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, exact HTTPS `GOOGLE_REDIRECT_URI` and CORS if needed.
   `TOKEN_ENCRYPTION_KEY` is an alias only; `FERNET_KEY` wins if both are set. Do not rotate
   encryption keys by renaming variables. `APP_BASE_URL` is not consumed; use the redirect URI.
3. Configure the Google Web client, consent/test users and Drive API using [OAuth guide](google-oauth.md).
4. `docker compose up --build -d` applies Alembic migration in the backend container. Without
   Docker: `cd backend && ../.venv/bin/alembic upgrade head`, then start one Uvicorn worker.
5. Keep DB private; expose only a trusted HTTPS reverse proxy. Verify `/api/health`.
6. Enforce an outbound firewall denying internal/private/metadata networks, including DNS,
   redirect and IPv6 routes. Limit CPU/RAM/egress and HTTP request concurrency. DNS resolution
   depends on the OS resolver; configure resolver deadlines. Never expose unrestricted
   registration or use this service as an anonymous downloader/proxy.
7. Add restricted-scope privacy disclosures, retention/cleanup policy, monitoring and operational
   review before serving other users. There is no hosted deployment supplied here.

## Phone-only installation / signing

Open the successful Android GitHub Actions run, sign in to GitHub if needed, download
`CloudFusion-debug-apk`, extract ZIP with the phone's Files app, and open `app-debug.apk`.
Temporarily allow install from the browser/Files app only if you trust this build.
The artifact includes `signing-certificate.txt` and `build-info.txt` with package/commit.

**Do not uninstall an existing app merely to force an update if you need its local data.**
A newer versionCode cannot overcome a different signing certificate. The old debug key is
not available here. Without that key, a same-package in-place update preserving local data
cannot be promised. A fresh-install test requires an explicit decision to remove the old
app's local state (Drive files remain). There is no encrypted-state export/import feature.

A maintainer can configure stable test signing with GitHub Secrets `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. This must be the
**original** key for an in-place update. Keys are decoded only in runner temporary storage;
not supplied to PR builds. Without these secrets, the artifact is a newly debug-signed test
build, not a release or guaranteed upgrade. No production signing identity is fabricated.

## What remains incomplete

- Live Google OAuth, live external-source/Drive transfers, physical-device installation and
  low-memory profiling are unverified. OAuth Cloud configuration and test-user approval must
  be performed by the project owner; no password/token needs to be shared in chat.
- Direct screens still call Drive, not backend file APIs. Server workspace does not yet
  provide a second full cloud file manager/dashboard or unified server file-manager screen. Server-account disconnect and quota refresh controls are implemented separately.
- No Android OS/background completion push notification (only visible-screen completion
  message), durable distributed worker/lease system, unattended restart recovery, abuse/report queue, public directory, or Dropbox/OneDrive connector.
- Sharing in existing file manager remains explicit Google permissions, not global discovery.
- Source HEAD/Content-Length/identity requirements exclude otherwise valid downloadable sites.
- URL filename uses path, not Content-Disposition. No arbitrary headers/cookies/login scraping.
- No release build or proven data-preserving update without original signing material.

## Tests and verification

```sh
python -m venv .venv
.venv/bin/pip install -r backend/requirements-dev.txt
cd backend && ../.venv/bin/pytest -q
# Use an isolated SQLite DB + temporary Fernet key for migration checks, not production data:
../.venv/bin/alembic upgrade head
../.venv/bin/alembic check
cd ../web && npm ci && npm test && npm run build
cd ../android && ./gradlew testDebugUnitTest lintDebug assembleDebug
```

Backend tests use mocked DNS/HTTP and mocked Drive, including SSRF, redirect/IP pinning,
auth/ownership, preview, queue creation, idempotency, pause after a chunk, restart/resume,
range refusal, changed source, failure/retry, integrity and history cleanup. Android Actions
verifies APK signatures and package, but cannot prove installation/login on your device.
