import logging
import secrets
import threading
import time
from collections import defaultdict, deque
from contextlib import asynccontextmanager
from typing import Literal
from urllib.parse import quote

from fastapi import BackgroundTasks, Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse, StreamingResponse
from sqlalchemy import delete, func, select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from starlette.concurrency import run_in_threadpool

from . import drive, files, oauth, transfers
from .accounts import account_view, dashboard, owned_account, refresh_account, transfer_view
from .config import get_settings
from .db import SessionLocal, get_db
from .models import Account, DriveFile, LoginSession, Transfer, User
from .schemas import CopyRequest, Credentials, FolderRequest, LoginCredentials, MoveRequest, RenameRequest, SharingRequest, UploadRequest
from .security import bearer, current_user, hash_password, make_session, token_hash, verify_password


@asynccontextmanager
async def lifespan(_app: FastAPI):
    transfers.recover_interrupted()
    yield


# HTTP clients can otherwise log resumable session URLs (which are credentials).
logging.getLogger("httpx").setLevel(logging.WARNING)
logging.getLogger("httpcore").setLevel(logging.WARNING)
app = FastAPI(title="CloudFusion API", version="0.1.0", lifespan=lifespan)
settings = get_settings()
origins = [origin.strip() for origin in settings.cors_origins.split(",") if origin.strip()]
if origins:
    app.add_middleware(CORSMiddleware, allow_origins=origins, allow_credentials=False,
                       allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE"], allow_headers=["Authorization", "Content-Type", "Upload-Offset"], expose_headers=["X-Transfer-Id", "Content-Disposition"])


@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Cache-Control"] = "no-store"
    return response


@app.exception_handler(drive.DriveFailure)
async def drive_error(_request: Request, exc: drive.DriveFailure):
    return JSONResponse({"detail": exc.message}, status_code=exc.status)


# Per-process defense in depth, not a substitute for a distributed reverse-proxy limiter.
_attempts: dict[str, deque] = defaultdict(deque)
_attempts_lock = threading.Lock()
_dummy_hash = hash_password(secrets.token_urlsafe(24))


def limit_auth(request: Request, action: str) -> None:
    now = time.monotonic()
    key = action + ":" + (request.client.host if request.client else "unknown")
    with _attempts_lock:
        if len(_attempts) > 10000:
            for old in list(_attempts):
                if not _attempts[old] or _attempts[old][-1] < now - 60:
                    del _attempts[old]
        bucket = _attempts[key]
        while bucket and bucket[0] < now - 60:
            bucket.popleft()
        if len(bucket) >= (5 if action == "register" else 12):
            raise HTTPException(429, "Too many attempts; try again later")
        bucket.append(now)


@app.get("/api/health")
def health(db: Session = Depends(get_db)):
    try:
        db.execute(text("SELECT 1"))
    except Exception as exc:
        raise HTTPException(503, "Database unavailable") from exc
    return {"status": "ok"}


@app.post("/api/auth/register", status_code=201)
def register(body: Credentials, request: Request, db: Session = Depends(get_db)):
    limit_auth(request, "register")
    user = User(email=str(body.email).lower(), password_hash=hash_password(body.password))
    db.add(user)
    try:
        db.commit()
    except IntegrityError as exc:
        db.rollback()
        raise HTTPException(409, "An account with this email already exists") from exc
    return {"token": make_session(db, user), "email": user.email}


@app.post("/api/auth/login")
def login(body: LoginCredentials, request: Request, db: Session = Depends(get_db)):
    limit_auth(request, "login")
    user = db.scalar(select(User).where(User.email == str(body.email).lower()))
    valid = verify_password(body.password, user.password_hash if user else _dummy_hash)
    if not user or not valid:
        raise HTTPException(401, "Incorrect email or password")
    return {"token": make_session(db, user), "email": user.email}


@app.get("/api/auth/me")
def me(user: User = Depends(current_user)):
    return {"id": user.id, "email": user.email}


@app.post("/api/auth/logout", status_code=204)
def logout(credentials=Depends(bearer), user: User = Depends(current_user), db: Session = Depends(get_db)):
    db.execute(delete(LoginSession).where(LoginSession.token_hash == token_hash(credentials.credentials), LoginSession.user_id == user.id))
    db.commit()
    return None


@app.post("/api/oauth/start")
def oauth_start(user: User = Depends(current_user), db: Session = Depends(get_db)):
    return {"authorization_url": oauth.start_oauth(db, user)}


@app.get("/api/oauth/callback", response_class=HTMLResponse)
def oauth_callback(state: str = Query(min_length=1, max_length=512), code: str | None = Query(default=None, max_length=4096),
                   error: str | None = None, db: Session = Depends(get_db)):
    if error or not code:
        raise HTTPException(400, "Google authorization was not completed; return to CloudFusion and try again")
    oauth.finish_oauth(db, state, code)
    return HTMLResponse("""<!doctype html><html lang="en"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>CloudFusion connected</title><body style="font-family:system-ui;background:#10121b;color:#f5f4ff;padding:2rem">
<h1>Account connected</h1><p>Your Google Drive account was connected. Return to CloudFusion and refresh Accounts.</p>
<p><a style="color:#ad9bff" href="cloudfusion://oauth">Return to the Android app</a></p></body></html>""",
                        headers={"Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'"})


@app.get("/api/accounts")
def list_accounts(user: User = Depends(current_user), db: Session = Depends(get_db)):
    return [account_view(a) for a in db.scalars(select(Account).where(Account.user_id == user.id).order_by(Account.created_at)).all()]


@app.post("/api/accounts/refresh")
def refresh_accounts(user: User = Depends(current_user), db: Session = Depends(get_db)):
    accounts = db.scalars(select(Account).where(Account.user_id == user.id)).all()
    for account in accounts:
        refresh_account(db, account)
    return [account_view(a) for a in accounts]


@app.delete("/api/accounts/{account_id}", status_code=204)
def disconnect_account(account_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    with transfers.account_mutation_lock():
        account = owned_account(db, user.id, account_id, lock=True)
        active = db.scalar(select(func.count()).select_from(Transfer).where(
            Transfer.user_id == user.id, Transfer.status.in_(transfers.ACTIVE),
            (Transfer.destination_account_id == account_id) | (Transfer.source_account_id == account_id)))
        if active:
            raise HTTPException(409, "Cancel active transfers involving this account before disconnecting it")
        db.delete(account)
        db.commit()
    return None


@app.get("/api/dashboard")
def get_dashboard(user: User = Depends(current_user), db: Session = Depends(get_db)):
    result = dashboard(db, user.id)
    result["recent_files"] = files.list_files(db, user.id, "", "modified", "desc", "all", None, 8, 0)["items"]
    return result


@app.post("/api/files/sync")
def sync_files(account_id: str | None = None, user: User = Depends(current_user), db: Session = Depends(get_db)):
    if account_id:
        accounts = [owned_account(db, user.id, account_id)]
    else:
        accounts = list(db.scalars(select(Account).where(Account.user_id == user.id)).all())
    return {"accounts": files.sync_accounts(db, accounts)}


@app.get("/api/files")
def get_files(search: str = Query(default="", max_length=200), sort: Literal["name", "size", "modified"] = "modified",
              direction: Literal["asc", "desc"] = "desc", type: Literal["all", "images", "videos", "audio", "documents", "folders"] = "all",
              account_id: str | None = None, limit: int = Query(default=30, ge=1, le=100), offset: int = Query(default=0, ge=0, le=1_000_000),
              user: User = Depends(current_user), db: Session = Depends(get_db)):
    if account_id:
        owned_account(db, user.id, account_id)
    return files.list_files(db, user.id, search, sort, direction, type, account_id, limit, offset)


@app.post("/api/files/folders", status_code=201)
def create_folder(body: FolderRequest, user: User = Depends(current_user), db: Session = Depends(get_db)):
    account = owned_account(db, user.id, body.account_id)
    if body.parent_id:
        drive.valid_id(body.parent_id)
    data = drive.client_for(db, account).create_folder(body.name, body.parent_id)
    row = files.upsert_file(db, account, data)
    db.commit()
    return files.file_view(row, account.email)


@app.patch("/api/files/{account_id}/{file_id}")
def rename_file(account_id: str, file_id: str, body: RenameRequest, user: User = Depends(current_user), db: Session = Depends(get_db)):
    account = owned_account(db, user.id, account_id)
    data = drive.client_for(db, account).rename(file_id, body.name)
    row = files.upsert_file(db, account, data)
    db.commit()
    return files.file_view(row, account.email)


@app.post("/api/files/{account_id}/{file_id}/move")
def move_file(account_id: str, file_id: str, body: MoveRequest, user: User = Depends(current_user), db: Session = Depends(get_db)):
    account = owned_account(db, user.id, account_id)
    data = drive.client_for(db, account).move(file_id, body.parent_id)
    row = files.upsert_file(db, account, data)
    db.commit()
    return files.file_view(row, account.email)


@app.delete("/api/files/{account_id}/{file_id}", status_code=204)
def delete_file(account_id: str, file_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    account = owned_account(db, user.id, account_id)
    drive.client_for(db, account).trash(file_id)
    db.execute(delete(DriveFile).where(DriveFile.account_id == account.id, DriveFile.file_id == file_id))
    db.commit()
    return None


@app.get("/api/files/{account_id}/{file_id}/permissions")
def permissions(account_id: str, file_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    account = owned_account(db, user.id, account_id)
    client = drive.client_for(db, account)
    return {"permissions": client.permissions(file_id), "file": client.metadata(file_id)}


@app.post("/api/files/{account_id}/{file_id}/public")
def make_public(account_id: str, file_id: str, body: SharingRequest, user: User = Depends(current_user), db: Session = Depends(get_db)):
    account = owned_account(db, user.id, account_id)
    client = drive.client_for(db, account)
    current = client.permissions(file_id)
    if not any(p.get("type") == "anyone" for p in current):
        client.public_link(file_id)
    return {"permissions": client.permissions(file_id), "file": client.metadata(file_id)}


@app.delete("/api/files/{account_id}/{file_id}/public")
def revoke_public(account_id: str, file_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    account = owned_account(db, user.id, account_id)
    client = drive.client_for(db, account)
    for permission in client.permissions(file_id):
        if permission.get("type") == "anyone":
            client.revoke_permission(file_id, permission["id"])
    return {"permissions": client.permissions(file_id), "file": client.metadata(file_id)}


@app.post("/api/uploads", status_code=201)
def create_upload(body: UploadRequest, user: User = Depends(current_user), db: Session = Depends(get_db)):
    return transfer_view(transfers.create_upload(db, user.id, body))


@app.put("/api/uploads/{transfer_id}/chunk")
async def upload_chunk(transfer_id: str, request: Request, upload_offset: int = Header(alias="Upload-Offset", ge=0),
                       user: User = Depends(current_user), db: Session = Depends(get_db)):
    length = request.headers.get("content-length")
    try:
        if length and (int(length) < 0 or int(length) > settings.max_chunk_bytes):
            raise HTTPException(413, "Upload chunk is too large")
    except ValueError as exc:
        raise HTTPException(400, "Invalid Content-Length") from exc
    data = bytearray()
    async for part in request.stream():
        data.extend(part)
        if len(data) > settings.max_chunk_bytes:
            raise HTTPException(413, "Upload chunk is too large")
    result = await run_in_threadpool(transfers.put_chunk, db, user.id, transfer_id, upload_offset, bytes(data))
    return transfer_view(result)


@app.post("/api/transfers/copy", status_code=201)
def copy_file(body: CopyRequest, background: BackgroundTasks, user: User = Depends(current_user), db: Session = Depends(get_db)):
    transfer, created = transfers.create_copy(db, user.id, body)
    if created:
        background.add_task(transfers.copy_worker, user.id, transfer.id)
    return transfer_view(transfer)


@app.get("/api/transfers")
def list_transfers(limit: int = Query(default=50, ge=1, le=100), offset: int = Query(default=0, ge=0),
                   user: User = Depends(current_user), db: Session = Depends(get_db)):
    rows = db.scalars(select(Transfer).where(Transfer.user_id == user.id).order_by(Transfer.created_at.desc(), Transfer.id).limit(limit + 1).offset(offset)).all()
    return {"items": [transfer_view(t) for t in rows[:limit]], "next_offset": offset + limit if len(rows) > limit else None}


@app.get("/api/transfers/{transfer_id}")
def get_transfer(transfer_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    return transfer_view(transfers.owned_transfer(db, user.id, transfer_id))


@app.post("/api/transfers/{transfer_id}/cancel")
def cancel_transfer(transfer_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    return transfer_view(transfers.cancel(db, user.id, transfer_id))


@app.post("/api/transfers/{transfer_id}/retry")
def retry_transfer(transfer_id: str, background: BackgroundTasks, user: User = Depends(current_user), db: Session = Depends(get_db)):
    transfer, launch = transfers.retry(db, user.id, transfer_id)
    if launch:
        background.add_task(transfers.copy_worker, user.id, transfer.id)
    return transfer_view(transfer)


def _download_stream(user_id: str, transfer_id: str):
    with SessionLocal() as db:
        t = transfers.owned_transfer(db, user_id, transfer_id)
        account = owned_account(db, user_id, t.source_account_id)
        sent = 0
        last_check = last_save = time.monotonic()
        saved_bytes = 0
        checked_bytes = -1024 * 1024
        try:
            with drive.client_for(db, account).stream_download(t.source_file_id) as response:
                for part in response.iter_bytes(chunk_size=256 * 1024):
                    now = time.monotonic()
                    if sent - checked_bytes >= 1024 * 1024 or now - last_check >= 1:
                        db.refresh(t)
                        if t.cancel_requested or t.status == "cancelled":
                            return
                        checked_bytes, last_check = sent, now
                    yield part
                    sent += len(part)
                    if sent - saved_bytes >= 4 * 1024 * 1024 or now - last_save >= 2:
                        t.bytes_transferred = sent
                        db.commit()
                        saved_bytes, last_save = sent, now
            db.refresh(t)
            if not t.cancel_requested:
                if sent == t.size:
                    t.status = "completed"
                else:
                    t.status, t.error = "failed", "Download ended before the expected file size"
                t.bytes_transferred = sent
                db.commit()
        except GeneratorExit:
            t.status, t.error = "cancelled", "Download disconnected"
            db.commit()
            raise
        except Exception:
            t.status, t.error = "failed", "Download interrupted; start it again"
            db.commit()
            raise


@app.get("/api/files/{account_id}/{file_id}/download")
def download_file(account_id: str, file_id: str, user: User = Depends(current_user), db: Session = Depends(get_db)):
    account = owned_account(db, user.id, account_id)
    meta = drive.client_for(db, account).metadata(file_id)
    mime = meta.get("mimeType", "application/octet-stream")
    if mime.startswith("application/vnd.google-apps."):
        raise HTTPException(400, "Google Workspace documents and folders cannot be downloaded as binary files")
    try:
        size = int(meta["size"])
    except (KeyError, TypeError, ValueError):
        raise HTTPException(400, "Google did not provide a file size")
    t = Transfer(user_id=user.id, kind="download", status="in_progress", name=meta.get("name") or "download",
                 mime_type=mime, size=size, source_account_id=account.id, source_file_id=file_id)
    db.add(t)
    db.commit()
    headers = {"Content-Length": str(size), "Content-Disposition": "attachment; filename*=UTF-8''" + quote(t.name, safe=""),
               "X-Transfer-Id": t.id}
    return StreamingResponse(_download_stream(user.id, t.id), media_type="application/octet-stream", headers=headers)
