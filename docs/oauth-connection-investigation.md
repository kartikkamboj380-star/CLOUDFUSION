# Google account connection investigation — code 8

Date: 2026-11-09. Baseline: `44f56a8` (Android 0.4.0).
This investigation does **not** certify restored/live Google authorization.

## Scope preserved

No UI, screens, navigation, Direct Download, URL engine, transfers, database, package,
version, dependency, signing configuration, consent scopes or token flow changes.
Only code-8 diagnostic wording and its unit test change. Do not interpret this as an
OAuth-flow fix or assume installing another APK will resolve the underlying error.

## History comparison

| Item | Earlier source | Current source / finding |
|---|---|---|
| applicationId / namespace | `d32f677` (0.1.0), `23d2877` (0.2.0): `com.cloudfusion.app` | `44f56a8` (0.4.0): identical |
| Alternative `com.cloudfusion` | no evidence in available Android history | do not rename the current package |
| 0.1.0 account connection | backend browser OAuth + CloudFusion session | a different architecture, not restored |
| 0.2.0 direct account picker | Google AccountPicker → explicit email | retained |
| Direct authorization request | AuthorizationClient + selected Android `Account` | unchanged |
| Requested scope | `https://www.googleapis.com/auth/drive` | unchanged |
| Identity check | Drive `about.user.emailAddress` must match selection | retained |
| Tokens | memory-only access tokens, Play services authorization cache | retained |
| Expired access token | clear invalid token, reacquire; consent → reconnect | retained |
| Stored direct account state | Keystore alias `cloudfusion.direct.state.v1` | same alias, no account-state reset |
| Consent result handling | previously labelled all non-OK results cancelled | now decodes returned Google error Intent |
| Client secret / Web client ID | not used for direct Android flow | none added or removed |
| Dependency | play-services-auth 21.5.0 in direct implementation | unchanged |

`google-services.json` or a hardcoded OAuth client ID is not needed to restore this direct
AuthorizationClient flow. The Google project resolves Android app identity from the package
and certificate configuration; a backend Web client is not interchangeable.

The successful older APK **used by this user** has not been identified. Available history
cannot prove its version, signing key or whether it was a separately built APK.

## Observed public certificate evidence

These are real GitHub check-run notices, not private signing keys:

| Build | SHA-1 |
|---|---|
| 0.2.0 PR run `42558037325`, job `128968053562` | `FB:06:C1:FD:5D:92:AF:C4:88:08:CA:66:77:51:68:DD:00:8A:30:34` |
| 0.4.0 run `42595511989`, job `129087055981` | `3C:23:7D:CF:A4:31:5C:E7:79:F7:1E:DB:4E:B9:3F:CD:DD:8F:DF:61` |

Thus signing certificates **did change between those two builds**. This does not prove which
one is installed, which build previously worked, or what the existing OAuth client accepts.
A fresh GitHub debug build can create another certificate. Existing optional stable-signing
Secrets support is preserved. GitHub secret metadata inspection returned HTTP 403 (integration
permission); no claim is made that stable signing Secrets exist or are absent. No secrets or
private keys are requested in chat. A public fingerprint cannot reconstruct its private key.

## What code 8 does and does not establish

Google Play services code 8 denotes an internal error, not a specific certificate/package,
API-disabled, missing-test-user or invalid-client diagnosis. The prior fallback message
incorrectly directed every unrecognised status to a single checklist without distinguishing
this uncertainty. The new wording identifies the internal error without claiming a fix.

The existing Cloud project, Android client registrations, Drive API state, audience, test users
and device Play services cannot be inspected from this checkout. No Google Cloud credentials,
connected project/session or real Android device were available. Nothing in those settings
was changed. Do not recreate a project or add the same test users again based only on code 8.

## Minimal next verification — use the existing project

1. Do **not** uninstall or disconnect accounts as a diagnostic step; preserve local state.
2. Open the currently installed app's **Settings → Google developer setup**. Record/copy its
   actual package + SHA-1. A redacted screenshot is enough; no passwords/tokens are needed.
3. Identify the previously working APK/version/run link. If still installed on another device,
   use its Settings fingerprint; otherwise use its exact Actions certificate file, not a
   different build from the same commit.
4. In the **existing** [Google Auth clients page](https://console.cloud.google.com/auth/clients),
   compare the existing Android client's package/certificate with the installed APK. If they
   already match, do not create another client; investigate device Play services/network and
   the precise stage where authorization fails. Retry once, check Play services updates and
   automatic date/time; avoid clearing app data or Google's grants just to test.
5. **Only if a mismatch is confirmed:** either sign with the original securely retained key,
   or register an additional Android client in the SAME project for `com.cloudfusion.app` and
   the actual installed certificate. No new Cloud project or duplicate test-user addition is
   implied. Keep Web-client settings unchanged. Never guess the signing SHA-1.
6. Verify existing [Drive API enablement](https://console.cloud.google.com/apis/library/drive.googleapis.com)
   and [audience/test-user configuration](https://console.cloud.google.com/auth/audience)
   in that same project; check first, change only if a real discrepancy is found.
7. Real authorization verification requires account selection → consent/authorization → verified
   Drive identity/quota returned for both intended accounts. A build/unit-test pass does not
   verify those steps. Use only disposable files with explicit permission for transfer tests.

## Build and regression verification

Use the existing `.github/workflows/android.yml`: `testDebugUnitTest lintDebug assembleDebug`,
then APK signature/package verification. The handoff gives the actual successful run and
artifact after execution. This diagnostic APK is a test build, NOT a verified account-connection
repair. A changed signing key can make it less useful than checking the currently installed
APK first. The workflow/signing setup and version 0.4.0/code 4 are deliberately unchanged.
