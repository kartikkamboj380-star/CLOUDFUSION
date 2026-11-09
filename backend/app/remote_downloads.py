"""Single-worker bounded-memory URL to Drive transfers; no phone or disk staging."""
import hashlib
import threading
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import unquote, urlsplit

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field, ValidationError
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from . import drive, transfers
from .accounts import transfer_view, owned_account
from .db import SessionLocal, get_db
from .models import RemoteSource, Transfer, User
from .remote_http import RemoteError, open_source
from .schemas import UploadRequest
from .security import current_user, decrypt, encrypt

router = APIRouter(prefix="/api/remote-downloads", tags=["remote downloads"])
_pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="remote-download")
_guard = threading.RLock()
_scheduled = set()


class PreviewRequest(BaseModel):
    url: str = Field(min_length=1, max_length=8192)


class CreateRequest(PreviewRequest):
    account_id: str
    idempotency_key: str = Field(min_length=1, max_length=128)


def metadata(url):
    with open_source(url, "HEAD") as response:
        if response.status != 200:
            raise RemoteError("Source rejected metadata request; use a public direct download link (HEAD support required)")
        mime = response.getheader("Content-Type", "application/octet-stream").split(";", 1)[0].lower()
        if mime in ("text/html", "application/xhtml+xml"):
            raise RemoteError("This is a web page, not a supported direct file link")
        if response.getheader("Content-Encoding", "identity") != "identity":
            raise RemoteError("Compressed HTTP representations are not supported")
        try:
            size = int(response.getheader("Content-Length", ""))
        except ValueError as exc:
            raise RemoteError("Source must provide a known nonzero file size") from exc
        if not 0 < size <= 5 * 1024**4:
            raise RemoteError("Supported size is 1 byte to 5 TiB")
        name = unquote(urlsplit(url).path.rsplit("/", 1)[-1]) or "download"
        name = name.replace("/", "_").replace("\\", "_")[:240]
        name = "".join(c for c in name if ord(c) >= 32 and ord(c) != 127).strip(". ") or "download"
        try:
            UploadRequest(name=name, size=size, mime_type=mime, idempotency_key="preview-validation")
        except ValidationError as exc:
            raise RemoteError("Source returned an unsupported file name or MIME type") from exc
        etag = response.getheader("ETag")
        if etag and (etag.startswith("W/") or len(etag) > 1024 or not etag.startswith('"') or not etag.endswith('"')):
            etag = None
        return {"name": name, "size": size, "mime_type": mime, "etag": etag,
                "resume_possible": bool(etag and response.getheader("Accept-Ranges", "").lower() == "bytes")}


@router.post("/preview")
def preview(body: PreviewRequest, request: Request, user: User = Depends(current_user)):
    from .main import limit_auth
    limit_auth(request, "remote_preview")
    try:
        info = metadata(body.url)
        return {k: v for k, v in info.items() if k != "etag"}
    except RemoteError as exc:
        raise HTTPException(400, str(exc)) from exc


def schedule(user_id, transfer_id):
    with _guard:
        if transfer_id in _scheduled:
            return
        _scheduled.add(transfer_id)
        _pool.submit(worker, user_id, transfer_id)


@router.post("", status_code=201)
def create(body: CreateRequest, request: Request, db: Session = Depends(get_db), user: User = Depends(current_user)):
    from .main import limit_auth
    limit_auth(request, "remote_create")
    with _guard:
        owned_account(db, user.id, body.account_id)
        existing = transfers._existing(db, user.id, body.idempotency_key)
        if existing:
            source = db.get(RemoteSource, existing.id)
            if not source or decrypt(source.url_enc) != body.url or existing.destination_account_id != body.account_id:
                raise HTTPException(409, "Idempotency key already used")
            return transfer_view(existing)
        if db.scalar(select(func.count()).select_from(Transfer).where(Transfer.kind == "remote", Transfer.status.in_(("pending", "in_progress", "paused", "failed")))) >= 20:
            raise HTTPException(429, "Server download queue is full; retry later")
        try:
            info = metadata(body.url)
        except RemoteError as exc:
            raise HTTPException(400, str(exc)) from exc
        t = transfers.create_upload(db, user.id, UploadRequest(name=info["name"], size=info["size"], mime_type=info["mime_type"], account_id=body.account_id, idempotency_key=body.idempotency_key))
        t.kind = "remote"
        db.add(RemoteSource(transfer_id=t.id, url_enc=encrypt(body.url), etag=info["etag"]))
        db.commit()
        schedule(user.id, t.id)
        return transfer_view(t)


@router.post("/{transfer_id}/{action}")
def control(transfer_id: str, action: str, db: Session = Depends(get_db), user: User = Depends(current_user)):
    with transfers.transfer_lock(transfer_id), _guard:
        t = transfers.owned_transfer(db, user.id, transfer_id)
        if t.kind != "remote":
            raise HTTPException(404, "Remote download not found")
        if action == "pause" and t.status in ("pending", "in_progress"):
            t.status = "paused"
            db.commit()
        elif action in ("resume", "retry") and t.status in ("paused", "failed"):
            if t.result_file_id or not t.session_url_enc:
                raise HTTPException(409, "Session cannot safely resume; inspect destination before creating a replacement")
            if transfer_id in _scheduled:
                raise HTTPException(409, "Worker is stopping; try again shortly")
            if not t.reserved_bytes:
                from .accounts import refresh_account
                account = owned_account(db, user.id, t.destination_account_id)
                refresh_account(db, account)
                with transfers.account_mutation_lock():
                    transfers._reserve(db, user.id, t.size, t.destination_account_id)
                    t.reserved_bytes = t.size
            t.status = "pending"
            t.error = None
            db.commit()
            schedule(user.id, t.id)
        elif action == "cancel" and t.status in ("pending", "in_progress", "paused", "failed"):
            t.cancel_requested = True
            t.status = "cancelled"
            with transfers.account_mutation_lock():
                transfers._release(db, t)
                db.commit()
        else:
            raise HTTPException(409, "Action is not available in the current state")
        return transfer_view(t)


def worker(user_id, transfer_id):
    try:
        with SessionLocal() as db:
            with transfers.transfer_lock(transfer_id):
                t = transfers.owned_transfer(db, user_id, transfer_id)
                if t.status != "pending":
                    return
                source = db.get(RemoteSource, t.id)
                url, etag = decrypt(source.url_enc), source.etag
                destination = owned_account(db, user_id, t.destination_account_id)
                remote = drive.client_for(db, destination).upload_status(decrypt(t.session_url_enc), t.size)
                transfers._apply_result(db, t, remote)
                if t.status in ("completed", "failed"):
                    return
                offset, total, expected_mime = t.bytes_transferred, t.size, t.mime_type
            if offset and not etag:
                raise RemoteError("Safe resume needs a stable strong source ETag; inspect destination before restarting")
            headers = {"If-Match": etag} if etag else {}
            if offset:
                headers.update({"Range": f"bytes={offset}-", "If-Range": etag})
            md5 = hashlib.md5(usedforsecurity=False) if offset == 0 else None
            sha256 = hashlib.sha256() if offset == 0 else None
            with open_source(url, headers=headers) as response:
                if response.status != (206 if offset else 200):
                    raise RemoteError("Source refused access or safe range resume")
                if response.getheader("Content-Type", "application/octet-stream").split(";", 1)[0].lower() != expected_mime:
                    raise RemoteError("Source file type changed")
                if etag and response.getheader("ETag") != etag:
                    raise RemoteError("Source file changed; unsafe to resume")
                if response.getheader("Content-Encoding", "identity") != "identity":
                    raise RemoteError("Source encoding changed")
                if offset and response.getheader("Content-Range") != f"bytes {offset}-{total-1}/{total}":
                    raise RemoteError("Source returned an unexpected byte range")
                if response.getheader("Content-Length") != str(total - offset):
                    raise RemoteError("Source size changed or is unknown")
                while offset < total:
                    block = response.read(min(transfers.CHUNK_SIZE, total - offset))
                    if not block:
                        raise RemoteError("Source stream ended early")
                    if md5 is not None:
                        md5.update(block)
                        sha256.update(block)
                    with SessionLocal() as progress:
                        with transfers.transfer_lock(transfer_id):
                            current = transfers.owned_transfer(progress, user_id, transfer_id)
                            if current.status in ("paused", "cancelled"):
                                return
                            if offset + len(block) == total and md5 is not None:
                                current.expected_md5 = md5.hexdigest()
                                progress.get(RemoteSource, transfer_id).source_sha256 = sha256.hexdigest()
                                progress.commit()
                            updated = transfers.put_chunk(progress, user_id, transfer_id, offset, block, allow_remote=True)
                            if updated.bytes_transferred != offset + len(block):
                                raise RemoteError("Upload acknowledgement uncertain; resume to reconcile")
                            offset = updated.bytes_transferred
    except Exception as exc:
        # No source URLs, query credentials, provider tokens or exception bodies in logs/API.
        with SessionLocal() as db, transfers.transfer_lock(transfer_id):
            t = transfers.owned_transfer(db, user_id, transfer_id)
            if t.status not in ("completed", "cancelled", "paused"):
                t.status = "failed"
                t.error = str(exc) if isinstance(exc, RemoteError) else "Remote transfer interrupted or source changed. Resume reconciles the Drive session; source must support stable ETag and ranges. Inspect destination if outcome is uncertain."
                db.commit()
    finally:
        with _guard:
            _scheduled.discard(transfer_id)


@router.get("/{transfer_id}/integrity")
def integrity(transfer_id: str, db: Session = Depends(get_db), user: User = Depends(current_user)):
    t = transfers.owned_transfer(db, user.id, transfer_id)
    source = db.get(RemoteSource, t.id)
    if source is None:
        raise HTTPException(404, "Remote download not found")
    return {"source_sha256": source.source_sha256, "destination_md5": t.result_md5,
            "md5_verified": t.checksum_verified,
            "note": "Source SHA-256 is recorded only for uninterrupted full streams; it is not destination SHA-256 verification."}


@router.delete("/{transfer_id}", status_code=204)
def remove_history(transfer_id: str, db: Session = Depends(get_db), user: User = Depends(current_user)):
    with transfers.transfer_lock(transfer_id):
        t = transfers.owned_transfer(db, user.id, transfer_id)
        source = db.get(RemoteSource, t.id)
        if source is None:
            raise HTTPException(404, "Remote download not found")
        if t.status not in ("completed", "cancelled"):
            raise HTTPException(409, "Only completed or cancelled history can be removed")
        db.delete(source)
        db.delete(t)
        db.commit()


@router.get("")
def history(limit: int = 50, offset: int = 0, db: Session = Depends(get_db), user: User = Depends(current_user)):
    if not 1 <= limit <= 100 or offset < 0:
        raise HTTPException(422, "Invalid pagination")
    rows = db.scalars(select(Transfer).where(Transfer.user_id == user.id, Transfer.kind == "remote")
        .order_by(Transfer.created_at.desc(), Transfer.id).limit(limit + 1).offset(offset)).all()
    return {"items": [transfer_view(t) for t in rows[:limit]], "next_offset": offset + limit if len(rows) > limit else None}
