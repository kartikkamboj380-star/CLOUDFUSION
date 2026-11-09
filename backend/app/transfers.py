"""Resumable, bounded-memory uploads and backend-mediated account-to-account copies.

The current deployment runs one API worker; PostgreSQL row locks protect quota
reservations, while per-transfer locks serialize chunk/retry/cancel operations.
"""
import threading
import weakref
from contextlib import contextmanager
from datetime import timedelta

from fastapi import HTTPException
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import drive
from .accounts import owned_account, refresh_account
from .db import SessionLocal, utcnow
from .models import Account, Transfer
from .schemas import CopyRequest, UploadRequest
from .security import decrypt, encrypt

CHUNK_SIZE = 4 * 1024 * 1024  # A multiple of Drive's 256 KiB requirement.
_reservation_lock = threading.RLock()
_locks_guard = threading.Lock()
_locks: weakref.WeakValueDictionary = weakref.WeakValueDictionary()
ACTIVE = ("initializing", "pending", "in_progress", "cancel_requested", "paused")


@contextmanager
def account_mutation_lock():
    """Serialize account removal with reservations on the supported single worker."""
    with _reservation_lock:
        yield


@contextmanager
def transfer_lock(transfer_id: str):
    with _locks_guard:
        lock = _locks.setdefault(transfer_id, threading.RLock())
    with lock:
        yield


def owned_transfer(db: Session, user_id: str, transfer_id: str, lock: bool = False) -> Transfer:
    q = select(Transfer).where(Transfer.id == transfer_id, Transfer.user_id == user_id)
    if lock:
        q = q.with_for_update()
    transfer = db.scalar(q)
    if not transfer:
        raise HTTPException(404, "Transfer not found")
    return transfer


def _existing(db: Session, user_id: str, key: str) -> Transfer | None:
    return db.scalar(select(Transfer).where(Transfer.user_id == user_id, Transfer.idempotency_key == key))


def _refresh_candidates(db: Session, user_id: str) -> None:
    for account in db.scalars(select(Account).where(Account.user_id == user_id)).all():
        refresh_account(db, account)


def _reserve(db: Session, user_id: str, size: int, account_id: str | None,
             exclude_id: str | None = None) -> Account:
    # All candidate rows are locked in a consistent order, preventing over-reservation
    # between concurrent PostgreSQL requests. Google remains the authority for quota.
    accounts = db.scalars(select(Account).where(Account.user_id == user_id).order_by(Account.id).with_for_update()).all()
    if account_id and not any(a.id == account_id for a in accounts):
        raise HTTPException(404, "Account not found")
    candidates = []
    for account in accounts:
        if account.id == exclude_id or (account_id and account.id != account_id):
            continue
        if account.reauth_required or account.quota_error or account.quota_usage is None or account.quota_checked_at is None:
            continue
        if account.quota_limit is not None:
            free = max(0, account.quota_limit - account.quota_usage - account.reserved_bytes)
            if free < size or account.quota_limit <= 0:
                continue
            # Prefer the account with the lowest projected utilization.
            score = (account.quota_usage + account.reserved_bytes + size) / account.quota_limit
        else:
            score = -1.0  # Google explicitly omitted the limit (unlimited).
        candidates.append((score, account.id, account))
    if not candidates:
        raise HTTPException(409, "No eligible account has sufficient known storage; refresh or reconnect an account")
    account = min(candidates, key=lambda item: (item[0], item[1]))[2]
    account.reserved_bytes += size
    return account


def _release(db: Session, transfer: Transfer) -> None:
    if transfer.reserved_bytes and transfer.destination_account_id:
        account = db.scalar(select(Account).where(Account.id == transfer.destination_account_id).with_for_update())
        if account:
            account.reserved_bytes = max(0, account.reserved_bytes - transfer.reserved_bytes)
    transfer.reserved_bytes = 0


def _mark_failed(db: Session, transfer: Transfer, message: str) -> None:
    with _reservation_lock:
        if transfer.status not in ("completed", "cancelled") and not (transfer.status == "failed" and transfer.result_file_id):
            transfer.status = "failed"
            transfer.error = message[:500]
            _release(db, transfer)
            db.commit()


def _init_session(db: Session, transfer: Transfer, account: Account) -> None:
    try:
        url = drive.client_for(db, account).start_upload(transfer.name, transfer.size, transfer.mime_type, transfer.destination_parent_id)
        transfer.session_url_enc = encrypt(url)
        transfer.status = "pending"
        db.commit()
    except drive.DriveFailure as exc:
        _mark_failed(db, transfer, exc.message)
        raise HTTPException(exc.status, exc.message) from exc


def create_upload(db: Session, user_id: str, req: UploadRequest) -> Transfer:
    with _reservation_lock:
        existing = _existing(db, user_id, req.idempotency_key)
        if existing:
            if (existing.kind != "upload" or existing.name != req.name or existing.size != req.size or
                    existing.mime_type != req.mime_type or (req.account_id and existing.destination_account_id != req.account_id) or
                    existing.destination_parent_id != req.parent_id):
                raise HTTPException(409, "Idempotency key is already used for another transfer")
            return existing
        if req.parent_id:
            drive.valid_id(req.parent_id)
            if not req.account_id:
                raise HTTPException(400, "Choose an account when uploading into a specific folder")
        _refresh_candidates(db, user_id)
        account = _reserve(db, user_id, req.size, req.account_id)
        transfer = Transfer(user_id=user_id, kind="upload", status="initializing", name=req.name,
                            mime_type=req.mime_type, size=req.size, destination_account_id=account.id,
                            destination_parent_id=req.parent_id, idempotency_key=req.idempotency_key,
                            reserved_bytes=req.size)
        db.add(transfer)
        db.commit()
        _init_session(db, transfer, account)
        return transfer


def create_copy(db: Session, user_id: str, req: CopyRequest) -> tuple[Transfer, bool]:
    with _reservation_lock:
        existing = _existing(db, user_id, req.idempotency_key)
        if existing:
            if (existing.kind != "copy" or existing.source_account_id != req.source_account_id or
                    existing.source_file_id != req.source_file_id or
                    (req.destination_account_id and existing.destination_account_id != req.destination_account_id) or
                    existing.destination_parent_id != req.destination_parent_id):
                raise HTTPException(409, "Idempotency key is already used for another transfer")
            return existing, False
        source = owned_account(db, user_id, req.source_account_id)
        drive.valid_id(req.source_file_id)
        if req.destination_parent_id:
            drive.valid_id(req.destination_parent_id)
            if not req.destination_account_id:
                raise HTTPException(400, "Choose an account when copying into a specific folder")
        try:
            meta = drive.client_for(db, source).metadata(req.source_file_id)
        except drive.DriveFailure as exc:
            raise HTTPException(exc.status, exc.message) from exc
        mime = meta.get("mimeType", "application/octet-stream")
        if mime.startswith("application/vnd.google-apps."):
            raise HTTPException(400, "Google Workspace documents and folders cannot be copied as binary files")
        try:
            size = int(meta["size"])
        except (KeyError, TypeError, ValueError):
            raise HTTPException(400, "Google did not provide a file size")
        if size <= 0 or size > 5 * 1024**4:
            raise HTTPException(400, "Only non-empty files up to 5 TiB are currently supported")
        _refresh_candidates(db, user_id)
        account = _reserve(db, user_id, size, req.destination_account_id, exclude_id=source.id)
        transfer = Transfer(user_id=user_id, kind="copy", status="initializing", name=meta.get("name") or "file",
                            mime_type=mime, size=size, destination_account_id=account.id,
                            source_account_id=source.id, source_file_id=req.source_file_id,
                            destination_parent_id=req.destination_parent_id, idempotency_key=req.idempotency_key,
                            reserved_bytes=size, expected_md5=meta.get("md5Checksum"))
        db.add(transfer)
        db.commit()
        _init_session(db, transfer, account)
        return transfer, True


def _apply_result(db: Session, transfer: Transfer, result: dict) -> None:
    transfer.bytes_transferred = result["offset"]
    transfer.error = None
    if not result.get("done"):
        transfer.status = "in_progress"
        db.commit()
        return
    file = result.get("file") or {}
    if not file.get("id"):
        raise drive.DriveFailure(502, "Google did not return the uploaded file ID")
    transfer.result_file_id = file["id"]
    transfer.result_md5 = file.get("md5Checksum")
    if transfer.expected_md5 and transfer.result_md5:
        transfer.checksum_verified = transfer.expected_md5.lower() == transfer.result_md5.lower()
    transfer.status = "completed" if transfer.checksum_verified is not False else "failed"
    if transfer.checksum_verified is False:
        transfer.error = "Checksum mismatch; the destination file exists and must be reviewed manually"
    with _reservation_lock:
        destination = db.get(Account, transfer.destination_account_id)
        _release(db, transfer)
        db.commit()
        if destination:
            refresh_account(db, destination)


def put_chunk(db: Session, user_id: str, transfer_id: str, offset: int, chunk: bytes, *, allow_copy: bool = False, allow_remote: bool = False) -> Transfer:
    with transfer_lock(transfer_id):
        transfer = owned_transfer(db, user_id, transfer_id, lock=True)
        if transfer.kind not in (("remote",) if allow_remote else (("upload", "copy") if allow_copy else ("upload",))):
            raise HTTPException(400, "Transfer does not accept upload chunks")
        if transfer.status == "completed":
            return transfer
        if transfer.status not in ("pending", "in_progress") or transfer.cancel_requested:
            raise HTTPException(409, "Transfer is not active; retry a failed transfer if possible")
        if not chunk or len(chunk) > 8 * 1024 * 1024:
            raise HTTPException(400, "Chunk must contain between 1 byte and 8 MiB")
        if offset < 0 or offset + len(chunk) > transfer.size:
            raise HTTPException(400, "Invalid chunk range")
        if offset + len(chunk) < transfer.size and len(chunk) % (256 * 1024):
            raise HTTPException(400, "Non-final chunks must be a multiple of 256 KiB")
        account = owned_account(db, user_id, transfer.destination_account_id)
        client = drive.client_for(db, account)
        url = decrypt(transfer.session_url_enc)
        try:
            # Reconcile before sending; this avoids replaying bytes after a lost response or restart.
            remote = client.upload_status(url, transfer.size)
            _apply_result(db, transfer, remote)
            if transfer.status in ("completed", "failed"):
                return transfer
            if remote["offset"] != offset:
                raise HTTPException(409, detail={"message": "Upload offset changed; resume at the reported offset", "offset": remote["offset"]})
            try:
                result = client.upload_chunk(url, transfer.size, offset, chunk)
            except drive.DriveFailure as exc:
                if exc.status != 503:
                    raise
                # It may have succeeded despite the timeout; never blindly resend.
                result = client.upload_status(url, transfer.size)
            _apply_result(db, transfer, result)
            return transfer
        except drive.DriveFailure as exc:
            if exc.status not in (502, 503, 429):
                _mark_failed(db, transfer, exc.message)
            raise HTTPException(exc.status, exc.message) from exc


def cancel(db: Session, user_id: str, transfer_id: str) -> Transfer:
    with transfer_lock(transfer_id):
        t = owned_transfer(db, user_id, transfer_id, lock=True)
        if t.status not in ACTIVE:
            raise HTTPException(409, "Transfer is not active")
        t.cancel_requested = True
        # Copies stop at the next chunk; uploads/downloads are marked immediately.
        if t.kind == "copy" and t.status == "in_progress":
            t.status = "cancel_requested"
        else:
            t.status = "cancelled"
            with _reservation_lock:
                _release(db, t)
        db.commit()
        return t


def retry(db: Session, user_id: str, transfer_id: str) -> tuple[Transfer, bool]:
    with transfer_lock(transfer_id), _reservation_lock:
        t = owned_transfer(db, user_id, transfer_id, lock=True)
        if t.status != "failed" or t.kind not in ("upload", "copy"):
            raise HTTPException(409, "Only failed uploads and copies can be retried")
        if t.result_file_id:
            raise HTTPException(409, "The destination file already exists; review it instead of retrying")
        if not t.destination_account_id or not t.session_url_enc:
            raise HTTPException(409, "This upload session cannot be resumed; start a new transfer")
        account = owned_account(db, user_id, t.destination_account_id)
        refresh_account(db, account)
        if account.reauth_required or account.quota_error:
            raise HTTPException(409, "Reconnect or refresh the destination account before retrying")
        try:
            remote = drive.client_for(db, account).upload_status(decrypt(t.session_url_enc), t.size)
        except drive.DriveFailure as exc:
            raise HTTPException(exc.status, exc.message) from exc
        if remote.get("done"):
            _apply_result(db, t, remote)
            return t, False
        account = _reserve(db, user_id, t.size, t.destination_account_id,
                           exclude_id=t.source_account_id if t.kind == "copy" else None)
        t.reserved_bytes = t.size
        t.bytes_transferred = remote["offset"]
        t.status = "pending"
        t.error = None
        t.cancel_requested = False
        db.commit()
        return t, t.kind == "copy"


def copy_worker(user_id: str, transfer_id: str) -> None:
    try:
        with SessionLocal() as db:
            with transfer_lock(transfer_id):
                t = owned_transfer(db, user_id, transfer_id, lock=True)
                if t.status != "pending":
                    return
                t.status = "in_progress"
                db.commit()
                source = owned_account(db, user_id, t.source_account_id)
                dest = owned_account(db, user_id, t.destination_account_id)
                remote = drive.client_for(db, dest).upload_status(decrypt(t.session_url_enc), t.size)
                _apply_result(db, t, remote)
                if t.status in ("completed", "failed"):
                    return
                offset = t.bytes_transferred
                source_id = t.source_file_id
            with drive.client_for(db, source).stream_download(source_id, offset=offset) as response:
                buffer = bytearray()
                for part in response.iter_bytes(chunk_size=256 * 1024):
                    buffer.extend(part)
                    while len(buffer) >= CHUNK_SIZE:
                        block = bytes(buffer[:CHUNK_SIZE])
                        del buffer[:CHUNK_SIZE]
                        with SessionLocal() as progress_db:
                            current = owned_transfer(progress_db, user_id, transfer_id)
                            if current.cancel_requested or current.status == "cancelled":
                                _finish_cancel(progress_db, current)
                                return
                            updated = put_chunk(progress_db, user_id, transfer_id, offset, block, allow_copy=True)
                            if updated.bytes_transferred != offset + len(block):
                                raise drive.DriveFailure(503, "Transfer interrupted; retry to resume")
                            offset = updated.bytes_transferred
                if buffer:
                    with SessionLocal() as progress_db:
                        current = owned_transfer(progress_db, user_id, transfer_id)
                        if current.cancel_requested or current.status == "cancelled":
                            _finish_cancel(progress_db, current)
                            return
                        updated = put_chunk(progress_db, user_id, transfer_id, offset, bytes(buffer), allow_copy=True)
                        offset = updated.bytes_transferred
                with SessionLocal() as progress_db:
                    current = owned_transfer(progress_db, user_id, transfer_id)
                    if current.cancel_requested:
                        _finish_cancel(progress_db, current)
                    elif current.status not in ("completed", "failed"):
                        _mark_failed(progress_db, current, "Source stream ended before the upload completed; retry to resume")
    except Exception as exc:
        message = exc.message if isinstance(exc, drive.DriveFailure) else "Transfer interrupted; retry to resume"
        with SessionLocal() as db:
            t = db.scalar(select(Transfer).where(Transfer.id == transfer_id, Transfer.user_id == user_id))
            if t:
                if t.cancel_requested:
                    _finish_cancel(db, t)
                else:
                    _mark_failed(db, t, message)


def _finish_cancel(db: Session, t: Transfer) -> None:
    with transfer_lock(t.id), _reservation_lock:
        db.refresh(t)
        if t.status != "completed":
            t.status = "cancelled"
            _release(db, t)
            db.commit()


def recover_interrupted() -> None:
    # Single worker only: running in-process copy/download tasks do not survive a restart.
    with SessionLocal() as db, _reservation_lock:
        cutoff = utcnow() - timedelta(days=7)
        transfers = db.scalars(select(Transfer).where(Transfer.status.in_(ACTIVE))).all()
        for t in transfers:
            if t.kind == "remote":
                t.status = "paused"
                t.error = "Server restarted; explicitly resume to reconcile the Drive session"
                continue
            if t.kind in ("copy", "download") or t.status == "initializing" or t.created_at < cutoff:
                t.status = "failed"
                t.error = "Server restarted or upload session expired; retry if the session is still valid"
                _release(db, t)
        db.commit()
