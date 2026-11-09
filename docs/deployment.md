# Deployment, operations and security

## Configuration

Copy `.env.example` to `.env` and fill it with private values. `DATABASE_URL` must match the Compose PostgreSQL service; percent-encode URL-special characters in the password. Generate a distinct Fernet key with `cryptography.fernet.Fernet.generate_key()` and store it in your secret manager. Do not commit the real `.env`, OAuth client secret, key, database backup or user data. Back up the database and key separately; encrypted credentials are unrecoverable without the same key. Automatic key rotation is not implemented.

`CORS_ORIGINS` is a comma-separated list of explicit trusted origins. Leave it empty for the bundled same-origin web proxy and Android. Do not use wildcard origins in a production deployment. The API requires `FERNET_KEY` to start; Google connection requests return an error until OAuth is configured. Apply Alembic migrations before running the API; the backend container applies them on startup.

## HTTPS and networking

Compose binds the web container to `127.0.0.1:8080` and backend to `127.0.0.1:8000` on the Docker host. Keep PostgreSQL private. Put a trusted TLS reverse proxy in front of the web container so both `/` and `/api` share the same HTTPS origin. For example, a properly installed Caddy instance with a public DNS name can use:

```caddyfile
cloud.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Set `GOOGLE_REDIRECT_URI=https://cloud.example.com/api/oauth/callback` and register the exact URI with Google. Ensure the proxy supports long-lived streaming responses and uploads, does not buffer entire files, and sets appropriate request/time limits. The supplied nginx proxy accepts at most 9 MiB per chunk and disables proxy buffering; it is not a substitute for a hardened public edge. The Android app accepts trusted HTTPS origins; debug emulator access to `http://10.0.2.2` is the only cleartext exception.

## Operational constraints

- Run **one** backend worker/instance. PostgreSQL row locks protect capacity reservations, but copy tasks and per-transfer locks are in-process, not a distributed job system. Scale only after implementing durable jobs, leases and cross-instance transfer coordination.
- Monitor database health, quota failures, storage utilization, transfer failures, API rate limits and Google OAuth revocations. Do not log authorization headers, OAuth codes or states, tokens, upload session URLs or sensitive file contents. The supplied backend disables Uvicorn access logs and the web proxy disables nginx access logs because OAuth callback query strings contain codes; configure any upstream proxy and observability system to redact query strings and secrets as well.
- Google quota and file indexes are snapshots. Refresh accounts/files when accuracy matters. Allocation reservations cover this service's active uploads, not external Drive writes.
- When a backend restart interrupts a copy, the transfer is marked failed. Retry it if the Google resumable session still exists. If completion was ambiguous or the session expired, inspect the destination before creating a new transfer. Partial downloads may need to be deleted by the user.
- The local auth feature is a foundation, not a public identity service: no email verification, password reset, MFA, distributed rate limiter, account lockout management, abuse tooling or administrative UI is implemented. The process-local login limiter is defense in depth and may see a shared proxy IP. Put a trusted access gateway/VPN and robust per-user/IP rate limits in front, or add those capabilities before public deployment. Review OAuth, privacy policy, retention, incident response and Google's restricted-scope requirements.
- Restrict public registration and `/docs` at the edge as appropriate; API documentation is not an authorization boundary. Review dependencies and maintain backups. No formal security audit or live Google integration test has been performed by this repository's mocked test suite.

## Android CI and signing

The Android workflow installs JDK 17 and SDK 35, tests the app, assembles a debug APK and uploads it as `CloudFusion-debug-apk` only after a successful build. Artifacts expire after 14 days. The debug key is for testing only. There is currently **no signed release workflow**. If implementing one, generate and keep the release keystore outside git, store it and its passwords/alias as protected GitHub Actions secrets, decode only in a trusted release job, use Android's signing configuration, and delete temporary signing material afterward. Do not expose secrets to pull requests from untrusted forks.
