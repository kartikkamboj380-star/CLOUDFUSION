# CloudFusion

CloudFusion is an Android-first **virtual view** of multiple Google Drive accounts. Google accounts and their storage are not merged; each file stays in one ordinary Drive account. Quotas and file metadata come from Google rather than fixed or sample values.

## Android: no CloudFusion backend

The Android app connects **directly to Google Drive** using Google Play services. There is no backend URL, separate CloudFusion email/password, or self-hosted server to run for Android. **The APK still requires Google Cloud OAuth configuration before Google account access will work.** See [Android Google OAuth setup](docs/google-oauth.md#android-direct-google-drive) and the exact signing fingerprint instructions below.

The implementation provides:

- Google device-account selection and explicit Drive authorization for multiple accounts; reconnect and disconnect controls. Drive's returned identity is checked against the selected account before its token is used.
- Actual per-account quota and an aggregate of the returned values; unavailable and unlimited values are not assumed to be zero. Workspace organizations can expose a shared pool, so an aggregate can **double-count the same organizational pool**.
- Live, paginated Drive search and type filters, with sorting of loaded results; account and owner identification, folder creation, rename, within-account move, trash, download and explicitly confirmed public-link sharing/revocation where Google allows them.
- Whole-file destination selection from freshly fetched quotas, or a manual destination, shown before an upload. Google enforces the final quota; there is no cross-device reservation and files are not split across accounts.
- Bounded-memory, resumable uploads and account-to-account copies. **Copies are downloaded and uploaded through the phone**, using both directions of its network, battery and time. Transfers run while the app remains open and online, not in the background or on a server. Upload/copy sessions are encrypted locally, are reconciled with Google before resume, and can expire. Downloads stream to a user-selected document and are not resumable. Check Drive before starting a replacement when the outcome is uncertain.
- A dark Compose UI, Android unit tests, and an Actions workflow that builds a debug APK and publishes its public certificate fingerprint.

This is an initial implementation, not a claim of production readiness or a verified Google application. Automated tests use mocks; **real Google OAuth, multiple-account Drive access and transfers have not been verified on a real device** in this repository.

## Configure and use Android

1. Have a Google Play services-enabled Android device (Android 8.0/API 26 or newer) with the Google accounts you intend to use added to the device.
2. Download a successful **backend-free** branch/commit's `CloudFusion-debug-apk` Actions artifact. It contains `app-debug.apk` and `signing-certificate.txt`. Artifacts expire after 14 days. Earlier APKs from the original release still require a backend.
3. In a Google Cloud project you control, enable the **Google Drive API**, configure the consent screen and add each account as a test user while the app is in Testing. Create an **Android OAuth client** with package name `com.cloudfusion.app` and the exact **SHA-1 of the certificate that signed that APK**. The artifact's `signing-certificate.txt`, the Actions run summary, and the installed app's **Settings → Google developer setup** show the SHA-1. Google Cloud may expect the hex bytes separated by colons; format them as `AA:BB:...` if needed. See [the detailed guide](docs/google-oauth.md#android-direct-google-drive).
4. Install the APK, open **Accounts → Connect Google account**, choose an account and approve Google's consent screen. Repeat for each account; refresh accounts/files. No Google password is entered in CloudFusion.

Each GitHub-hosted debug build normally creates a **new debug signing key**, so its SHA-1 can differ from previous builds. Register the certificate of the *specific APK installed*, not the certificate from an older run. A differently signed APK cannot update an installed copy with the same package name: Android may require uninstalling the old debug app first, which removes its local app state but does not delete Google Drive files. Use a securely managed, stable signing key for a release. No signing key or OAuth secret is included in this repository.

Full Drive access is a **restricted Google scope**. While the app is in Testing, accounts must be test users; Workspace admins can impose additional restrictions. Public distribution can require OAuth verification, privacy disclosures and potentially a security assessment. Do not claim Google approval without completing its review.

### Build Android locally

Requires JDK 17, Android SDK platform/build tools 35, and network access to Gradle/Google Maven. The pinned Gradle 8.10.2 wrapper verifies its distribution checksum.

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```

Register the printed SHA-1 for this build's signing key. No Google client secret, `google-services.json`, Web client ID, or backend URL is embedded or entered into the Android app. See [setup and troubleshooting](docs/google-oauth.md).

## Architecture and optional legacy service

```text
Android (Compose + Google Play services + SAF) ── HTTPS ── Google Drive API v3
       └─ encrypted on-device account/transfer state; copies travel through the phone

Optional, separate legacy setup:
Web (React + Vite) ── HTTPS /api ── FastAPI ── Google OAuth / Drive API v3
                                       └──── PostgreSQL (encrypted server credentials/state)
```

The existing FastAPI backend and React web companion remain available as a **separate optional product path**. They still require a deployed service, a local CloudFusion login, and a Web OAuth client; they are not used by the new direct Android app. The two paths do not automatically share account connections or transfer history. See [architecture](docs/architecture.md) and [deployment](docs/deployment.md).

### Optional backend and web quick start

Requirements: Docker Compose, or Python 3.11+ and Node.js 22. Configure the optional backend's Web OAuth client as described in [Google OAuth setup](docs/google-oauth.md#optional-legacy-backend-and-web). Then:

```bash
cp .env.example .env
# Set a strong database password, a Fernet key, and Web OAuth credentials in .env.
python3 -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
docker compose up --build -d
```

The key-generation command needs Python `cryptography`; generate it in a trusted environment. Keep the key stable and backed up. `DATABASE_URL` must match the database credentials; URL-encode special characters. Compose binds the web app to `127.0.0.1:8080` and API to `127.0.0.1:8000` on the host. Use a trusted HTTPS reverse proxy for external access. The web app uses relative `/api` URLs; it still uses the backend and its transfers run under the backend's documented limitations.

For local development and tests:

```bash
python3 -m venv .venv
.venv/bin/pip install -r backend/requirements-dev.txt
cd backend
cp ../.env.example .env
# Set DATABASE_URL=sqlite:///./cloudfusion.db, a generated FERNET_KEY, and Web OAuth values.
../.venv/bin/alembic upgrade head
../.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000 --no-access-log
# In another terminal: cd backend && ../.venv/bin/pytest -q

cd ../web
npm ci
npm run dev       # /api is proxied to the backend
npm test
npm run build
```

## Android limitations and safety

- Only one transfer runs at a time on this device. No background service or scheduled unattended job is implemented. Android may stop the app when it is backgrounded. A cancelled in-flight request can still complete; inspect Drive. A cancelled/interrupted download may leave a partial local file.
- Uploads and copies require a known, nonzero size up to 5 TiB. Copies require a Google-provided source MD5; native Google Workspace documents and folders cannot be copied or downloaded as binary files. Export and recursive folder copy are not implemented. Upload resume re-hashes the original selected file before continuing; copies compare source and destination checksums when Google supplies both. A destination without a returned checksum is reported as unverified. MD5 detects common corruption, not malicious tampering.
- Google Drive queries run per account and pages are combined locally. Sorting is among **loaded** results, not a guaranteed global order across unrequested pages; size paging uses Google's `quotaBytesUsed` order, which can differ from file size. Refresh after external changes. Search follows Drive's `name contains` behavior. Shared-drive and organization policies may restrict results/actions.
- Account identifiers, transfer history and resumable-session URLs are encrypted with Android Keystore in app-private storage; the app keeps Google access tokens only in memory. Google Play services manages its own authorization cache. Android backup is disabled. Disconnect removes the account locally but does not delete Drive files or revoke the Google grant; revoke it in [Google Account permissions](https://myaccount.google.com/permissions).
- OAuth and Drive requests go to Google over HTTPS, not CloudFusion's backend. Google controls consent, quotas, rate limits, scope restrictions, shared-drive permissions and token validity. Do not commit OAuth secrets, private keystores, real tokens or private user data.

## CI and documentation

The [Actions page](https://github.com/kartikkamboj380-star/CLOUDFUSION/actions) shows actual build/test results. Android runs unit tests and builds a debug APK; backend and web have separate workflows. Debug APKs are not release builds; there is no release-signing workflow.

- [Google Cloud OAuth setup](docs/google-oauth.md)
- [Architecture, allocation and transfers](docs/architecture.md)
- [Optional backend deployment and Android signing](docs/deployment.md)
