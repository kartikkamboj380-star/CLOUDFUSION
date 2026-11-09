# Architecture and transfer behavior

## Trust boundaries

A CloudFusion user authenticates to the backend with a local email/password. The backend stores a salted scrypt password hash and only a SHA-256 digest of each random session token. Sessions expire after 30 days and can be revoked by signing out. All account, file, transfer and sharing operations are scoped to that CloudFusion user; Google enforces its own file permissions too. Google credentials are encrypted using a server-provided Fernet key and are never sent to Android or the web client.

Google accounts are independent. `about.storageQuota.usage` and `limit` are obtained from each account's Drive API response; usage can include Gmail, Photos and Drive. Missing usage or an error makes that account unavailable for allocation. Google omits the limit for an unlimited quota, which is represented as unlimited rather than zero. Aggregate capacity/free is unavailable when a component quota is unavailable or unlimited. Values are snapshots and Google makes the final quota decision.

## Files and indexing

`POST /api/files/sync` pages through Drive metadata per connected account and writes bounded batches to the database. On success it deletes stale rows for that account. If a later page fails, prior indexed rows are retained and the account error is returned. Listing, search, type filtering, name/size/modified sorting and `limit`/`offset` pagination operate on the indexed data. Google file IDs and account IDs identify files; equal names are not deduplicated. Run a refresh after changes made outside CloudFusion or after a transfer. Owner metadata and sizes may be missing for some Drive items.

Moves are within an account and currently accept a destination folder ID (or `root`); the client does not provide a hierarchical folder browser. Delete moves to Google Drive trash rather than permanently deleting. Sharing operations require the authenticated user's connected account and obey Google's permission/ownership rules. Public sharing requires an explicit confirmation; revoking public access removes `anyone` permissions, not individually granted permissions.

## Allocation and uploads

Before creating an upload, the backend refreshes account quotas, locks candidate account rows, subtracts active reservations and selects the eligible account with the lowest projected utilization. An explicitly selected account is checked in the same way. In-flight reservations prevent this service's concurrent uploads from allocating the same free space; activity outside the service and Google quota propagation can still cause quota errors. Reservations are released on completion, cancellation or failure. The destination is returned before the client sends content.

The service initializes a Drive v3 resumable session and stores its URL encrypted. The Android/web clients send bounded chunks (normally 4 MiB, maximum 8 MiB) to the backend; Google receives one normal file, not a collection of chunks. Each chunk is reconciled with Google before writing so a lost response is not blindly replayed. An idempotency key prevents duplicate transfer creation for the same request. Incomplete upload sessions can expire. If a session's final outcome is ambiguous or expired, review the destination Drive before creating a replacement.

Android reads from a Storage Access Framework URI, not a whole in-memory file, and computes MD5 while streaming. Resume re-reads the prefix to calculate the full checksum. A different file with the same name and size must not be selected; when Google supplies a checksum a mismatch is reported. Web uploads use the same bounded-chunk strategy and incremental MD5. No checksum guarantee is made when Google does not supply one.

## Downloads and copies

Downloads stream Drive bytes through the backend. Android writes directly to a user-selected SAF destination in 64 KiB buffers. Browser downloads require the File System Access API and stream to the chosen file; no whole-file `Blob` is created. A transfer's byte count measures backend output, not necessarily the final device flush; a disconnected client or partial local destination may require cleanup. Android compares the Google MD5 when available; the web download checks byte count.

A cross-account copy reads the source from Drive and streams 4 MiB chunks through the backend to a destination Drive resumable session. It does not traverse the phone or intentionally write a complete temporary backend file. The backend must have enough network bandwidth; the destination must have quota. Native Google Workspace files/folders and empty files are not supported for cross-account copies. When Google reports MD5 for source and destination, they are compared. A checksum mismatch leaves the destination file in place for manual review and is not automatically retried.

The service uses a single API worker and in-process copy tasks. Transfer state and encrypted session URLs persist in the database, but execution itself is not durable. On startup, interrupted copies/downloads are marked failed and reservations released; retry a copy while its Google session is still valid. Cancellation is checked between chunks. A production multi-worker deployment needs a durable queue, leases, idempotent recovery and distributed coordination rather than simply increasing Uvicorn workers.
