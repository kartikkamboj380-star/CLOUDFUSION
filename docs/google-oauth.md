# Google Cloud and OAuth setup

CloudFusion's backend is a confidential **Web application** OAuth client. Android and web clients never receive the Google refresh token or client secret. Each Google account remains separate. You may connect four or more accounts, subject to Google's consent-screen and account policies.

## Configure the project

1. In [Google Cloud Console](https://console.cloud.google.com/), select or create a project you control.
2. Under **APIs & Services → Library**, enable **Google Drive API**.
3. Configure **Google Auth platform / OAuth consent screen** with the application name, support email, developer contact, audience and any requested authorized domains. Console labels can change.
4. For an external application in **Testing**, add the email address of **each** Google account you want to connect under test users. Accounts outside that list will be denied. Workspace accounts may additionally require administrator approval.
5. Create an OAuth client of type **Web application**. Add the exact redirect URI:
   - Local desktop development: `http://localhost:8000/api/oauth/callback`.
   - Deployed application: `https://your-domain.example/api/oauth/callback`.
6. In the backend's environment set `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` and `GOOGLE_REDIRECT_URI` to the values from this client. The URI must match exactly, including scheme, host and path. Never put the secret in the repository or Android app.
7. Restart the backend. Sign in to CloudFusion, choose **Connect Google account**, complete Google's consent flow in your browser and return to CloudFusion. Refresh accounts, then refresh files. Repeat for each account. Reconnecting an existing Google identity updates its saved credentials rather than adding a duplicate for that CloudFusion user.

The browser that handles consent must be able to reach the callback. `10.0.2.2` is the Android emulator's host alias, not a public callback. For a real phone, use a public HTTPS domain with a valid certificate, or perform the local callback from a desktop browser signed in to the same CloudFusion account.

## Requested scopes and verification

- `openid`: identify the Google account.
- `https://www.googleapis.com/auth/userinfo.email`: show and verify its email address.
- `https://www.googleapis.com/auth/drive`: list, read, create, modify and share the user's Drive files.

The full Drive scope is **restricted**. The narrower `drive.file` scope does not grant access to all pre-existing files, so it cannot satisfy the requested unified file manager. Google determines whether OAuth verification and an independent security assessment are required for production use of restricted scopes, particularly when a server stores or transmits the data. CloudFusion does not claim to be Google-verified. Follow current Google policies before making an app public and provide the privacy policy and disclosures Google requires.

In Testing, Google's offline refresh tokens for external applications with Drive scopes generally expire after seven days. Refresh tokens may also be revoked, invalidated, or affected by Google security policy. CloudFusion marks an account for reauthorization when Google reports a revoked/invalid grant; reconnect through the account screen. Google may refuse to issue a new refresh token in some circumstances. If so, revoke the existing app grant in [Google Account permissions](https://myaccount.google.com/permissions), then connect again and approve offline access.

## Security notes

The flow uses a random, ten-minute, single-use state and PKCE S256, requests offline access and verifies the Google userinfo email. The backend encrypts Google access/refresh tokens before storing them and keeps the key outside the database. Protect the OAuth client secret, database, encryption key and server. Lost keys make stored tokens unreadable; rotating the key requires a planned re-encryption migration, which is not included.

Disconnecting an account deletes CloudFusion's stored credentials and indexed metadata; it does not remove the Google grant or delete Drive files. Revoke the grant on Google's account-permissions page if you also want to stop Google's authorization.
