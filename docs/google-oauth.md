# Google Cloud and OAuth setup

CloudFusion has two **separate** paths. The current Android app connects directly to Drive with an **Android OAuth client**. The optional legacy backend/web app uses a confidential **Web application client**. Connecting an account in one path does not connect it in the other.

## Android: direct Google Drive

### Before connecting accounts

1. On a device running Android 8.0+ with up-to-date Google Play services, add the desired Google accounts under Android Settings. The app uses Google's account picker and asks consent for each account.
2. Create/select a Google Cloud project you control and enable **Google Drive API** under APIs & Services → Library.
3. Configure Google Auth platform / OAuth consent screen: app name, support/contact information and audience. If the audience is External and the app is in **Testing**, add **every account you intend to connect** as a test user. Workspace administrators may need to allow the app and its scope.
4. Under Google Auth platform → Clients, create an OAuth client of type **Android**. Set package name **`com.cloudfusion.app`** and the **SHA-1 signing certificate fingerprint for the exact installed APK**. Register separate Android clients/fingerprints for different signing keys (including the Play App Signing certificate if applicable). Do not use the source repository's commit hash or an old build's fingerprint.
5. Install that APK and choose **Accounts → Connect Google account**. Pick the account and approve the Google consent screen. Repeat for the other accounts. The app checks the account email returned by Drive against the selection and refuses a mismatch. Reconnect an account when Google requires renewed consent.

The app requests `https://www.googleapis.com/auth/drive`. This restricted scope is needed to view/manage pre-existing Drive files across accounts; the narrower `drive.file` scope would only cover files the user opens or creates through the app. Public use can require Google's restricted-scope verification, privacy disclosures, and potentially an independent security assessment; Workspace and policy restrictions still apply. This repository does **not** claim Google verification. Google's general authorization guide also describes a Web client for server-side authorization; the direct Android app does not request a server authorization code or use a backend. No client secret or `google-services.json` is embedded in this Android implementation.

### Find the SHA-1 for a GitHub Actions APK

A successful Android run uploads `CloudFusion-debug-apk`, containing `app-debug.apk` and `signing-certificate.txt`. The certificate file is produced **from that APK** using `apksigner verify --print-certs`; find `Signer #1 certificate SHA-1 digest`. The Actions run summary and the installed app's **Settings → Google developer setup** also show the SHA-1 (with a copy button). This fingerprint is public certificate information, not a private key. If Google Cloud's entry expects colon-separated uppercase hex, insert a colon between every two characters. For a local build:

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs app/build/outputs/apk/debug/app-debug.apk
```

GitHub-hosted jobs generally generate a fresh debug key in each runner. **A later artifact can have a different SHA-1** and cannot update a differently signed installed app. Register each exact build's fingerprint or build/sign with a stable key you securely control. If Android refuses installation due to a different signing certificate, uninstalling the older debug app removes its local account and transfer state (not Drive files), then install the new one. Never commit a keystore or its passwords; a real release should use protected signing material and appropriate Play App Signing setup.

### Troubleshooting

- A Google authorization/developer error can mean the Drive API is disabled, the package or certificate SHA-1 does not match the APK, the wrong Google Cloud project owns the Android client, the account is not an OAuth test user, or Google/Workspace policy denied access. Check Play services and internet connectivity too.
- An account must be present on the device and explicitly selected. This app never asks for a Google password and does not store a Google refresh token. It holds short-lived access tokens in memory; Google Play services handles its own cache. If consent is required again, tap **Reconnect** in Accounts.
- Disconnecting removes the local account and invalidates local resumability for affected unfinished transfers. It does not remove Drive files or revoke Google's grant. To revoke the grant, use [Google Account permissions](https://myaccount.google.com/permissions).
- Android direct accounts and transfer state do not migrate from the old backend-based app. On upgrade, the old local backend URL/session is removed; any server-side data and Google Drive files remain untouched. A fresh connection is required.

## Optional legacy backend and web

The optional backend is a confidential **Web application** OAuth client. It stores encrypted Google refresh tokens on the server. This flow is not used by the current Android app.

1. Enable Google Drive API and configure the OAuth consent screen/test users as above.
2. Create a **Web application** OAuth client. Register the exact redirect URI, such as `http://localhost:8000/api/oauth/callback` for a local desktop test or `https://your-domain.example/api/oauth/callback` for a deployed service.
3. Set the backend's `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` and `GOOGLE_REDIRECT_URI` privately and restart it. Never put the secret in the repository or Android app.
4. Sign in to the optional web companion's CloudFusion account, choose **Connect Google account**, complete consent in a browser that can reach the exact callback, and refresh. Repeat for each account.

This server flow requests `openid`, `userinfo.email` and the restricted full Drive scope. It uses random one-time state, PKCE and offline access; the server encrypts access/refresh tokens with the configured Fernet key. External apps in Testing with Drive scopes generally receive refresh tokens that expire after seven days. Revoked/expired credentials need reconnection; in some cases the existing grant must be revoked before Google issues a new refresh token. Protect the backend secret, database and encryption key. See [deployment](deployment.md).

### Consent closes without connecting

If the app says consent did not finish (older APKs always said “Google authorization was cancelled”), this alone does **not** prove the user cancelled or identify an OAuth configuration fault. Google can return a failure Intent with a non-OK activity result. The app now decodes any returned Intent through `AuthorizationClient` so its status code is shown; a missing Intent cannot supply a Google error code.

1. Note whether the account picker appeared and whether Google's consent screen appeared before it closed.
2. In the installed app, open **Settings → Google developer setup**. Compare its package and SHA-1 with the Android OAuth client in the same Cloud project that has Drive API enabled. Do not substitute another APK build's fingerprint.
3. Check the consent audience/test-user list and device Google Play services. Retry, and share the exact displayed status code or a redacted screenshot. Never share tokens or passwords.

This diagnostic change does not bypass consent or guarantee that a Google Cloud configuration is correct. Real-device verification is still required.

## 0.3.0 checklist: distinguish the two OAuth paths

| Check | Direct Android mode | Optional server downloads |
|---|---|---|
| Debug SHA-1 | actual installed APK Settings/artifact | not used by browser Web client |
| Release SHA-1 | actual release/Play signing certificate | not used by browser Web client |
| Client type | Android, `com.cloudfusion.app` + certificate | Web application |
| Client ID | Google Play services resolves Android configuration | `GOOGLE_CLIENT_ID` on backend |
| Client secret | none in APK | `GOOGLE_CLIENT_SECRET`, server only |
| Redirect | Play services resolution | exact HTTPS `GOOGLE_REDIRECT_URI` + registered Web callback |
| Drive API/project | enabled in client-owning project | enabled in Web-client-owning project |
| Audience | internal or external as appropriate | internal or external as appropriate |
| Testing | all connecting accounts listed as test users | all connecting accounts listed as test users |
| Refresh | Play services cache, short-lived token in app memory | encrypted offline refresh token, invalid grant → reconnect |

Owner console links (labels may change):
- [Enable Drive API](https://console.cloud.google.com/apis/library/drive.googleapis.com)
- [OAuth clients](https://console.cloud.google.com/auth/clients)
- [Audience and test users](https://console.cloud.google.com/auth/audience)
- [Consent branding](https://console.cloud.google.com/auth/branding)
- [Requested scopes](https://console.cloud.google.com/auth/scopes)

For `403 access_denied`, check test users, administrator policy and denied scope. An invalid
client/wrong package/SHA-1 needs the matching client configuration, not another secret in the
APK. `redirect_uri_mismatch` concerns the Web client's exact callback. API-disabled failures
require enabling Drive in the client-owning project. Expired/revoked server refresh tokens
require Reconnect; network failures require connectivity/retry, not new credentials.
The app cannot inspect private Google Cloud settings to verify them automatically.
Testing does not require publishing publicly; public restricted-scope distribution may need
Google review. None of this repository's mocked tests proves that review or live login.
