import json
import threading
import uuid

from fastapi import HTTPException
from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from . import drive
from .models import Account, DriveFile

# The backend currently runs a single API worker; this prevents overlapping index refreshes.
_sync_lock = threading.Lock()


def upsert_file(db: Session, account: Account, data: dict, generation: str | None = None) -> DriveFile:
    file_id = drive.valid_id(data.get("id", ""))
    row = db.scalar(select(DriveFile).where(DriveFile.account_id == account.id, DriveFile.file_id == file_id))
    if not row:
        row = DriveFile(account_id=account.id, file_id=file_id, sync_generation=generation or str(uuid.uuid4()))
        db.add(row)
    row.name = data.get("name") or "(unnamed)"
    row.mime_type = data.get("mimeType") or "application/octet-stream"
    try:
        row.size = int(data["size"]) if data.get("size") is not None else None
    except (ValueError, TypeError):
        row.size = None
    row.modified_time = data.get("modifiedTime")
    owners = data.get("owners") or []
    row.owner_email = owners[0].get("emailAddress") if owners else None
    row.md5_checksum = data.get("md5Checksum")
    row.web_view_link = data.get("webViewLink")
    row.parents_json = json.dumps(data.get("parents") or [])
    if generation:
        row.sync_generation = generation
    return row


def sync_accounts(db: Session, accounts: list[Account]) -> list[dict]:
    results = []
    if not _sync_lock.acquire(blocking=False):
        raise HTTPException(409, "A file refresh is already running")
    try:
        for account in accounts:
            generation = str(uuid.uuid4())
            page_token = None
            count = 0
            try:
                client = drive.client_for(db, account)
                while True:
                    page = client.list_page(page_token)
                    for item in page.get("files", []):
                        upsert_file(db, account, item, generation)
                        count += 1
                    db.commit()  # Bounded memory; retain older rows if a later page fails.
                    page_token = page.get("nextPageToken")
                    if not page_token:
                        break
                db.execute(delete(DriveFile).where(DriveFile.account_id == account.id, DriveFile.sync_generation != generation))
                db.commit()
                results.append({"account_id": account.id, "count": count, "error": None})
            except drive.DriveFailure as exc:
                db.rollback()
                results.append({"account_id": account.id, "count": count, "error": exc.message})
    finally:
        _sync_lock.release()
    return results


def file_view(row: DriveFile, email: str) -> dict:
    return {"id": row.file_id, "account_id": row.account_id, "account_email": email,
            "name": row.name, "mime_type": row.mime_type, "size": row.size,
            "modified_time": row.modified_time, "owner_email": row.owner_email,
            "md5_checksum": row.md5_checksum, "web_view_link": row.web_view_link,
            "parents": json.loads(row.parents_json)}


def list_files(db: Session, user_id: str, search: str, sort: str, direction: str,
               file_type: str, account_id: str | None, limit: int, offset: int) -> dict:
    query = select(DriveFile, Account.email).join(Account, Account.id == DriveFile.account_id).where(Account.user_id == user_id)
    if account_id:
        query = query.where(Account.id == account_id)
    if search:
        # Escape SQL LIKE wildcards so searches are literal, not patterns.
        escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        query = query.where(DriveFile.name.ilike("%" + escaped + "%", escape="\\"))
    if file_type == "folders":
        query = query.where(DriveFile.mime_type == drive.FOLDER)
    elif file_type in ("images", "videos", "audio"):
        prefix = {"images": "image/", "videos": "video/", "audio": "audio/"}[file_type]
        query = query.where(DriveFile.mime_type.startswith(prefix))
    elif file_type == "documents":
        query = query.where((DriveFile.mime_type.like("%document%")) | (DriveFile.mime_type == "application/pdf") |
                            (DriveFile.mime_type.like("text/%")) | (DriveFile.mime_type.like("%spreadsheet%")) |
                            (DriveFile.mime_type.like("%presentation%")))
    column = {"name": func.lower(DriveFile.name), "size": DriveFile.size, "modified": DriveFile.modified_time}[sort]
    ordering = column.asc() if direction == "asc" else column.desc()
    rows = db.execute(query.order_by(ordering.nulls_last(), DriveFile.id.asc()).limit(limit + 1).offset(offset)).all()
    return {"items": [file_view(row, email) for row, email in rows[:limit]],
            "next_offset": offset + limit if len(rows) > limit else None}
