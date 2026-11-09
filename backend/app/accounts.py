from fastapi import HTTPException
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from . import drive
from .db import utcnow
from .models import Account, Transfer


def owned_account(db: Session, user_id: str, account_id: str, lock: bool = False) -> Account:
    query = select(Account).where(Account.id == account_id, Account.user_id == user_id)
    if lock:
        query = query.with_for_update()
    account = db.scalar(query)
    if not account:
        raise HTTPException(404, "Account not found")
    return account


def refresh_account(db: Session, account: Account) -> bool:
    try:
        quota = drive.client_for(db, account).quota()
        usage, limit = quota.get("usage"), quota.get("limit")
        if usage is None or usage < 0 or (limit is not None and limit < 0):
            raise drive.DriveFailure(502, "Google did not return a usable storage quota")
        account.quota_usage, account.quota_limit = usage, limit
        account.quota_checked_at, account.quota_error = utcnow(), None
        db.commit()
        return True
    except drive.DriveFailure as exc:
        account.quota_error = exc.message
        db.commit()
        return False


def account_view(a: Account) -> dict:
    available = a.quota_usage is not None and a.quota_checked_at is not None and not a.quota_error and not a.reauth_required
    free = max(0, a.quota_limit - a.quota_usage) if available and a.quota_limit is not None else None
    return {"id": a.id, "email": a.email, "reauthorization_required": a.reauth_required,
            "quota_available": available, "quota_error": a.quota_error,
            "limit_bytes": a.quota_limit if available else None,
            "used_bytes": a.quota_usage if available else None, "free_bytes": free,
            "unlimited": available and a.quota_limit is None, "reserved_bytes": a.reserved_bytes,
            "quota_checked_at": a.quota_checked_at.isoformat() + "Z" if a.quota_checked_at else None}


def dashboard(db: Session, user_id: str) -> dict:
    accounts = db.scalars(select(Account).where(Account.user_id == user_id).order_by(Account.created_at)).all()
    views = [account_view(a) for a in accounts]
    all_known = all(a["quota_available"] for a in views)
    all_bounded = all_known and all(not a["unlimited"] for a in views)
    capacity = sum(a["limit_bytes"] for a in views) if all_bounded else None
    used = sum(a["used_bytes"] for a in views) if all_known else None
    remaining = sum(a["free_bytes"] for a in views) if all_bounded else None
    util = round(used / capacity * 100, 2) if capacity and used is not None else (0.0 if capacity == 0 and used == 0 else None)
    transfers = db.scalars(select(Transfer).where(Transfer.user_id == user_id).order_by(Transfer.created_at.desc()).limit(10)).all()
    return {"connected_accounts": len(views), "capacity_bytes": capacity, "used_bytes": used,
            "remaining_bytes": remaining, "utilization_percent": util, "accounts": views,
            "active_transfers": db.scalar(select(func.count()).select_from(Transfer).where(Transfer.user_id == user_id, Transfer.status.in_(("initializing", "pending", "in_progress", "cancel_requested")))),
            "failed_transfers": db.scalar(select(func.count()).select_from(Transfer).where(Transfer.user_id == user_id, Transfer.status == "failed")),
            "recent_transfers": [transfer_view(t) for t in transfers]}


def transfer_view(t: Transfer) -> dict:
    return {"id": t.id, "kind": t.kind, "status": t.status, "name": t.name, "mime_type": t.mime_type,
            "size": t.size, "bytes_transferred": t.bytes_transferred,
            "destination_account_id": t.destination_account_id, "source_account_id": t.source_account_id,
            "source_file_id": t.source_file_id, "result_file_id": t.result_file_id,
            "checksum_verified": t.checksum_verified, "result_md5": t.result_md5, "error": t.error,
            "created_at": t.created_at.isoformat() + "Z", "updated_at": t.updated_at.isoformat() + "Z"}
