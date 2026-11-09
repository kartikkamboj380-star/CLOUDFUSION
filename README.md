# CloudFusion

CloudFusion is an Android-first, self-hosted application for managing several Google Drive accounts from one interface. It presents a **virtual unified view**: Google accounts are not merged, their quotas are not pooled by Google, and each file remains a normal file in its destination account. The dashboard uses the real quotas returned by Google Drive; it does not assume 15 GB per account.

The repository includes a Kotlin/Jetpack Compose Android app, a FastAPI/PostgreSQL service, and an optional React/TypeScript web companion. **This is an initial implementation, not a claim of production readiness.** Review the security and operational limitations below before deploying it for others.

## What is implemented

- Local CloudFusion login, multiple Google account connections and reauthorization via Google's OAuth 2.0 authorization-code flow with PKCE, offline access and one-time state.
- Real per-account and aggregate Drive quota, account status, recent indexed files, recent uploads/transfers, and active/failed transfer counts. Unavailable or unlimited quota is shown as such, not counted as zero.
- A paginated, searchable, filterable and sortable index of actual Drive file IDs, with source account and owner where Google provides it. Refresh the index to see external changes. Create folders, rename, move within a Drive, and move files to trash.
- Automatic allocation based on refreshed quota and projected utilization, or manual account selection. Capacity is reserved for active uploads, and the destination is shown before bytes are sent. Files are **not** split between accounts.
- Bounded-memory resumable uploads through the service, progress, cancellation, idempotency keys, retry/reconciliation and transfer history. Android uses the Storage Access Framework, 4 MiB upload chunks, streaming downloads and optional MD5 comparison when Google supplies a checksum.
- Backend-mediated streaming copies between accounts, with progress, cancellation, retry and MD5 comparison when both sides provide a checksum. The backend streams chunks rather than silently downloading the file to the phone or writing an entire temporary file.
- Permission listing, explicitly confirmed public-link creation, link copying and public-access revocation. A file is never made public automatically.
- Dark Material 3 Android navigation for Home, Files, Transfers, Accounts and Settings; an optional responsive web companion; Alembic migrations; Docker Compose; and separate Android, backend and web CI workflows.

## Architecture

```text
Android (Compose + SAF) ─┐
                         ├── HTTPS /api ── FastAPI ── Google OAuth / Drive API v3
Web (React + Vite) ──────┘                    │
                                          PostgreSQL
                          (encrypted Google tokens, metadata, transfer state)
```

The web app uses relative `/api` URLs; its dev server and container proxy those requests to the backend. Android connects to a user-configured backend origin. Google OAuth happens in the system browser; the backend is the confidential OAuth client. See [architecture and transfer details](docs/architecture.md).

## Requirements

- Python 3.11+ for local backend development, or Docker and Docker Compose.
- Node.js 22 for the web companion.
- JDK 17 and Android SDK platform/build tools 35 for Android builds. The Gradle wrapper downloads Gradle 8.10.2 and verifies its distribution checksum.
- A Google Cloud project with Drive API enabled and a configured **Web application** OAuth client. Real Google integration requires your own credentials and test users or approved OAuth verification.

## 1. Configure Google OAuth

Follow the complete [Google Cloud setup guide](docs/google-oauth.md). In brief:

1. Enable **Google Drive API** in a Google Cloud project and configure the OAuth consent screen.
2. Add every Google account you want to connect as a test user while the application is in Testing.
3. Create a **Web application** OAuth client and add the exact redirect URI, for example `https://cloud.example.com/api/oauth/callback`.
4. Set `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` and `GOOGLE_REDIRECT_URI` in your server environment. Do not put the client secret in the Android app.

CloudFusion requests `openid`, `userinfo.email`, and the restricted `https://www.googleapis.com/auth/drive` scope. Full Drive access is necessary for the requested cross-account file listing and management; `drive.file` alone only covers files opened or created by the app. Google may require verification and an independent security assessment before a public app can use restricted scopes. External apps in Testing with Drive scopes generally receive refresh tokens that expire after seven days; reconnect affected accounts. Workspace administrators may impose additional restrictions.

## 2. Run with Docker Compose

```bash
cp .env.example .env
# Edit .env: set a strong PostgreSQL password, a unique Fernet key,
# Google OAuth credentials, and the exact redirect URI.
python3 -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
docker compose up --build -d
```

The key-generation command requires the Python `cryptography` package; alternatively generate the key in a trusted environment with that package installed. Keep the key stable across restarts and back it up securely. Set `DATABASE_URL` in `.env` to match `POSTGRES_USER`, `POSTGRES_PASSWORD`, and `POSTGRES_DB`; URL-encode special characters in the password in the URL.

Compose binds the web app to `127.0.0.1:8080` and API to `127.0.0.1:8000` on the Docker host. For a real phone or external OAuth callback, put an HTTPS reverse proxy in front of the web container at your configured domain. It proxies `/api` to the service. Do not expose the API or database directly to the internet. See [deployment and security](docs/deployment.md).

On the Docker host, `http://localhost:8080` is useful for local web development only. The health endpoint is `/api/health` and interactive API documentation is at `/docs` on the backend (or `/api` routes through the web proxy).

## 3. Local backend development

```bash
python3 -m venv .venv
.venv/bin/pip install -r backend/requirements-dev.txt
cd backend
cp ../.env.example .env
# In backend/.env set DATABASE_URL=sqlite:///./cloudfusion.db,
# a generated FERNET_KEY, and your Google OAuth values.
../.venv/bin/alembic upgrade head
../.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000 --no-access-log
```

SQLite is useful for local development/tests; use PostgreSQL for a deployment. The configured `GOOGLE_REDIRECT_URI` must be reachable from the browser performing consent and must exactly match the Cloud Console URI. `http://localhost:8000/api/oauth/callback` can be used for a local desktop OAuth test; use HTTPS for an external deployment.

Run backend tests and a migration check:

```bash
cd backend
../.venv/bin/alembic upgrade head
../.venv/bin/pytest -q
```

Tests use an isolated SQLite database and mocked Google responses. They do not contact real Google accounts or verify a deployed PostgreSQL instance.

## 4. Web companion

With the backend running:

```bash
cd web
npm ci
npm run dev            # port 5173; /api is proxied to the backend
npm test
npm run build
```

Set `API_PROXY_TARGET` **for the Vite server** if the backend is elsewhere. The browser still sends same-origin `/api` requests. For deployment the `web` container serves the compiled app and proxies `/api`. Browser downloads stream to a user-selected file only where the File System Access API is supported; otherwise use the Android app. Browser and Android uploads run while their client remains open.

## 5. Build and use Android

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
# android/app/build/outputs/apk/debug/app-debug.apk
```

Install the debug APK on Android 8.0+ (API 26+). Enter your **trusted HTTPS backend origin** on the sign-in screen, create/sign in to a CloudFusion account, then connect Google accounts in the browser. Refresh quotas and files after connecting. Debug builds additionally permit `http://10.0.2.2` for an Android emulator's host; this does not make a local OAuth callback reachable automatically from the emulator browser. A real device needs a reachable server and a trusted TLS certificate.

Android selects upload/download locations with the Storage Access Framework. To resume an interrupted upload after the app is closed, open Transfers and select the **same original file**. Downloading writes only to the location you select. No Android Google client secret or `google-services.json` is needed for this backend-driven OAuth design.

## GitHub Actions and APK artifacts

- **Android debug APK**: installs Java and Android SDK packages, runs `testDebugUnitTest` and `assembleDebug`, then uploads `CloudFusion-debug-apk` for 14 days if successful.
- **Backend tests**: installs Python dependencies, applies migrations to SQLite and runs pytest.
- **Web tests and build**: installs dependencies, runs Vitest and builds Vite.

See the repository's [Actions page](https://github.com/kartikkamboj380-star/CLOUDFUSION/actions) for actual results and artifacts. A debug APK is signed with the Android debug key; it is **not** a release build. No release-signing workflow is currently implemented. If one is added, store a base64-encoded release keystore, keystore password, alias and key password in GitHub Actions secrets and configure the workflow to consume them; never commit signing material. Detailed guidance is in [deployment](docs/deployment.md).

## Security and privacy

- CloudFusion passwords are salted and hashed with scrypt. Random bearer-session tokens are stored as hashes server-side; Google tokens and resumable-session URLs are encrypted at rest with the configured Fernet key. Android stores its CloudFusion session using Android Keystore; the web companion uses tab-scoped `sessionStorage`.
- All account and transfer queries are scoped to the authenticated CloudFusion user. The Google account password is never requested by CloudFusion. OAuth credentials and encryption keys stay on the backend.
- Use HTTPS, protected backups, a strong unique key and database password, restricted server access, and upstream rate limiting. Backend registration has no email verification, password reset, MFA, distributed rate limiter, or administrative controls. Do not expose this as an unrestricted public service without adding them and completing a security review.
- Disconnect removes credentials from CloudFusion; it does not delete Drive files or automatically revoke Google's grant. Revoke the grant in [Google Account permissions](https://myaccount.google.com/permissions) if desired.

## Known limitations

- Google Workspace-native documents and folders cannot currently be copied across accounts or downloaded as binary files; export and recursive folder copy are not implemented. A folder can still be created, renamed, moved or trashed where Google permits it.
- Empty-file uploads and cross-account copies are not yet supported. Uploads/copies require a known nonzero size and are capped at 5 TiB. Downloads of ordinary empty files are supported if Google reports their size.
- File metadata is indexed on explicit refresh, not continuously synchronized. Large Drive accounts can take time and API quota to index. Search/sort/pagination operate on the last successful indexed data; a failed account refresh retains previously indexed rows and reports an error.
- The backend currently runs **one API worker/instance**. Copies use in-process workers, not a durable queue: a restart marks interrupted copies failed and releases their reservations; retry can resume while Google still accepts the upload session. An expired or ambiguous session may need manual review before starting a new transfer to avoid duplicates. Cancellation takes effect between chunks.
- Google controls final quota enforcement, sharing permissions, API rates and refresh-token validity. Drive storage usage includes other Google services where Google's quota response does. External account changes can make a recently fetched quota stale.
- MD5 verification is used only when Google provides checksums; it detects common corruption, not malicious tampering. The web download path currently checks byte count but not MD5. Google-native files may not have binary size or MD5.
- The app does not implement background Android transfers, unattended server-side scheduling, file version synchronization, permanent deletion, Google Workspace export, release signing, or a durable multi-node job queue.

## Documentation

- [Google Cloud and OAuth setup](docs/google-oauth.md)
- [Architecture, allocation and transfers](docs/architecture.md)
- [Deployment, operations and security](docs/deployment.md)

No real tokens, OAuth client secrets, database passwords or encryption keys belong in the repository. `.env.example` contains placeholders only.
