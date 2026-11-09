import uuid

from sqlalchemy import BigInteger, Boolean, DateTime, ForeignKey, Index, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base, utcnow


def new_id() -> str:
    return str(uuid.uuid4())


class User(Base):
    __tablename__ = "users"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    email: Mapped[str] = mapped_column(String(320), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(256))
    created_at: Mapped[object] = mapped_column(DateTime, default=utcnow)


class LoginSession(Base):
    __tablename__ = "login_sessions"
    token_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    expires_at: Mapped[object] = mapped_column(DateTime)


class OAuthState(Base):
    __tablename__ = "oauth_states"
    state_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    expires_at: Mapped[object] = mapped_column(DateTime)
    verifier_enc: Mapped[str] = mapped_column(Text)


class Account(Base):
    __tablename__ = "accounts"
    __table_args__ = (UniqueConstraint("user_id", "google_sub", name="uq_account_user_google"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    google_sub: Mapped[str] = mapped_column(String(255))
    email: Mapped[str] = mapped_column(String(320))
    refresh_token_enc: Mapped[str] = mapped_column(Text)
    access_token_enc: Mapped[str | None] = mapped_column(Text, nullable=True)
    access_expires_at: Mapped[object | None] = mapped_column(DateTime, nullable=True)
    reauth_required: Mapped[bool] = mapped_column(Boolean, default=False)
    quota_limit: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    quota_usage: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    quota_checked_at: Mapped[object | None] = mapped_column(DateTime, nullable=True)
    quota_error: Mapped[str | None] = mapped_column(String(500), nullable=True)
    reserved_bytes: Mapped[int] = mapped_column(BigInteger, default=0)
    created_at: Mapped[object] = mapped_column(DateTime, default=utcnow)


class DriveFile(Base):
    __tablename__ = "drive_files"
    __table_args__ = (
        UniqueConstraint("account_id", "file_id", name="uq_file_account_file"),
        Index("ix_files_account_name", "account_id", "name"),
    )
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    account_id: Mapped[str] = mapped_column(ForeignKey("accounts.id", ondelete="CASCADE"), index=True)
    file_id: Mapped[str] = mapped_column(String(255))
    name: Mapped[str] = mapped_column(String(1024))
    mime_type: Mapped[str] = mapped_column(String(255))
    size: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    modified_time: Mapped[str | None] = mapped_column(String(40), nullable=True)
    owner_email: Mapped[str | None] = mapped_column(String(320), nullable=True)
    md5_checksum: Mapped[str | None] = mapped_column(String(32), nullable=True)
    web_view_link: Mapped[str | None] = mapped_column(Text, nullable=True)
    parents_json: Mapped[str] = mapped_column(Text, default="[]")
    sync_generation: Mapped[str] = mapped_column(String(36))


class Transfer(Base):
    __tablename__ = "transfers"
    __table_args__ = (UniqueConstraint("user_id", "idempotency_key", name="uq_transfer_idempotency"),)
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_id)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    kind: Mapped[str] = mapped_column(String(16))
    status: Mapped[str] = mapped_column(String(24), default="pending")
    name: Mapped[str] = mapped_column(String(1024))
    mime_type: Mapped[str] = mapped_column(String(255), default="application/octet-stream")
    size: Mapped[int] = mapped_column(BigInteger)
    bytes_transferred: Mapped[int] = mapped_column(BigInteger, default=0)
    destination_account_id: Mapped[str | None] = mapped_column(ForeignKey("accounts.id", ondelete="SET NULL"), nullable=True)
    source_account_id: Mapped[str | None] = mapped_column(ForeignKey("accounts.id", ondelete="SET NULL"), nullable=True)
    source_file_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
    destination_parent_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
    idempotency_key: Mapped[str | None] = mapped_column(String(128), nullable=True)
    session_url_enc: Mapped[str | None] = mapped_column(Text, nullable=True)
    reserved_bytes: Mapped[int] = mapped_column(BigInteger, default=0)
    cancel_requested: Mapped[bool] = mapped_column(Boolean, default=False)
    result_file_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
    expected_md5: Mapped[str | None] = mapped_column(String(32), nullable=True)
    result_md5: Mapped[str | None] = mapped_column(String(32), nullable=True)
    checksum_verified: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    error: Mapped[str | None] = mapped_column(String(500), nullable=True)
    created_at: Mapped[object] = mapped_column(DateTime, default=utcnow)
    updated_at: Mapped[object] = mapped_column(DateTime, default=utcnow, onupdate=utcnow)
