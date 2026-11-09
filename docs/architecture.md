# Architecture and transfer behavior

## Direct Android client

The Android app uses Google Play services `AuthorizationClient` with a user-selected on-device Google account and the restricted Drive scope. It does not send data or credentials to the optional CloudFusion backend. When Google returns an access token, the client checks `about.user.emailAddress` against the selected account before associating or using the token. Tokens are short-lived and kept in app memory; on expiry, the app clears Google's cached token and attempts silent authorization once, or asks the user to reconnect when consent is needed. Google Play services manages its own authorization cache. The app does not ask for a Google password, request an offline server authorization code or store a refresh token.

Account identifiers and local transfer records, including resumable session URLs and document URIs, are encrypted with an Android Keystore AES-GCM key in app-private storage. Backup is disabled. The old backend URL and encrypted local CloudFusion session are removed on first launch of the new app; this does not delete server data or Drive files. Disconnect clears the local account and token cache but does not revoke Google's grant. Users can revoke access from their Google Account.

### Quotas and files

Google accounts remain separate. `about.storageQuota.usage` and `limit` are fetched per account; usage can include Gmail, Photos and Drive. Missing usage, a failed refresh, or an invalid limit makes that account unavailable for allocation. Google omits the limit for unlimited storage, which is represented as unlimited, not zero. Aggregate capacity/free is unavailable when a component is unavailable or unlimited. **For organization-pooled storage, different accounts can report the same pool; adding their values may double-count it.** Values are snapshots and Google makes the final quota decision.

The app queries Drive for each account with independent page tokens; search and type predicates are sent to Google. It combines the pages actually loaded, and sorts that loaded set locally. It does not claim a globally sorted result across pages not yet loaded. Drive's `name contains` search behavior applies; size-ordered paging uses `quotaBytesUsed`, which can differ from file size. Failed accounts are reported rather than fabricated, and a refresh starts new pages. Owner, size and checksum fields may be unavailable. Move accepts a folder ID (or `root`); the app is not a hierarchical folder browser. Trash is not permanent deletion. Listing and changing permissions obey Google policy; making a file public requires an explicit confirmation, and revocation targets `anyone` permissions only.

### Allocation and phone-mediated transfers

Before an upload or copy, the app refreshes quotas and chooses an eligible whole-file destination with the lowest projected utilization, or verifies the manually selected account. Unlimited accounts are eligible; unknown accounts are not. The upload dialog shows the destination before any bytes are sent. The app allows one device transfer at a time and has no server-side or cross-device reservation. External writes or stale Google quotas can still cause a quota failure. Nothing merges accounts or splits a file across them.

Uploads use a Google Drive v3 resumable session, saved encrypted before sending a chunk. The app reads the chosen document to calculate MD5 before upload and again before resume; the same name, size and hash are required. It sends at most 4 MiB per chunk (a multiple of Drive's 256 KiB rule), queries the session on resume, and trusts Google's `Range` acknowledgement rather than a locally guessed offset. A lost response, unexpected offset, expired session or ambiguous completion is not silently restarted: resume/reconcile where possible and inspect the destination before creating a replacement.

A cross-account copy downloads **one bounded range to the phone** and uploads that chunk to the destination resumable session. Thus the phone uses both download and upload bandwidth, time and battery. It is not a server-side transfer. The source must have a known nonzero size and Google MD5; source metadata is checked on resume. Destination MD5 is compared with the saved source checksum when Google returns it. A mismatch is flagged for manual review; if Google returns no destination checksum, completion is explicitly unverified. Uploads compare the precomputed document MD5 similarly. MD5 catches common corruption but is not a cryptographic authenticity guarantee.

Downloads stream directly from Google to a Storage Access Framework destination in 64 KiB buffers and compare size/MD5 where available. They cannot resume. A cancelled or interrupted download may leave a partial file. All transfers depend on the app process and connection staying active; there is no background service or durable worker. Cancellation stops local work, but an already in-flight Google request may still complete. Reopening marks interrupted work accordingly. Resumable session URLs can expire, and cancellation/disconnect does not promise Google-side deletion. Review the destination when uncertain.

Current Android restrictions: one transfer at a time, known nonzero upload/copy sizes up to 5 TiB, source MD5 required for copy, no Workspace-native export or recursive folder copy, and no full shared-drive management guarantee. OAuth and real Google API behavior require device-level verification with configured credentials.

## Optional legacy backend and web

This separate architecture is retained for the web companion; the new Android client does not use it. A CloudFusion user authenticates to the backend with local email/password. The backend stores a salted scrypt password hash and only a SHA-256 digest of each random session token. Google credentials are encrypted using a server-provided Fernet key. Requests and data are scoped to the authenticated CloudFusion user.

The server indexes Drive metadata on `POST /api/files/sync` in bounded pages. Search/filter/sort/offset pagination operate on the last index, and a failed account refresh retains prior indexed rows. It refreshes quotas, locks candidate account rows and subtracts reservations for active server uploads. Reservations are not shared with the direct Android client or other external Drive activity.

The optional backend mediates web uploads and downloads and streams cross-account copies in 4 MiB chunks through the server, not through the web browser. Session URLs are encrypted server-side. Native Workspace files/folders and empty files are not supported for server-side copies; when both sides supply MD5 the checksums are compared. Web downloads require the File System Access API and check byte counts, not MD5. The service uses a single API worker and in-process copy tasks, not a durable queue: a restart marks interrupted copies failed and releases reservations. Retry while the Google session remains valid; check the destination before starting a replacement if completion is ambiguous. See [deployment](deployment.md).


## Optional URL-to-cloud server workspace (0.3.0)

Android JSON client → authenticated FastAPI remote-download API → pinned public HTTP source
→ bounded 4 MiB buffer → existing Google resumable upload adapter. File bytes do not transit
Android or a backend staging file. Encrypted source URL/ETag/source digest reference the
existing transfer record via additive `remote_sources` migration. One in-process remote worker,
20 retained queued/paused/failed task limit, explicitly paused on restart; not a distributed
job system. Existing direct account/token/session state is separate from server authorization.
See [the implementation/status document](server-downloads.md) for exact limits and missing work.
