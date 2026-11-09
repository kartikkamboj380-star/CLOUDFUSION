import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator


class Credentials(BaseModel):
    email: EmailStr
    password: str = Field(min_length=12, max_length=256)


class LoginCredentials(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=256)


class UploadRequest(BaseModel):
    name: str = Field(min_length=1, max_length=1024)
    size: int = Field(gt=0, le=5 * 1024**4)
    mime_type: str = Field(default="application/octet-stream", min_length=1, max_length=255)
    account_id: str | None = None
    parent_id: str | None = None
    idempotency_key: str = Field(min_length=1, max_length=128)

    @field_validator("name")
    @classmethod
    def no_control(cls, value: str) -> str:
        if any(ord(c) < 32 or ord(c) == 127 for c in value):
            raise ValueError("Control characters are not allowed")
        return value

    @field_validator("mime_type")
    @classmethod
    def binary_mime(cls, value: str) -> str:
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9!#$&^_.+\-]*/[A-Za-z0-9][A-Za-z0-9!#$&^_.+\-]*", value):
            raise ValueError("Invalid MIME type")
        if value.lower().startswith("application/vnd.google-apps."):
            raise ValueError("Google Workspace conversion is not supported")
        return value


class FolderRequest(BaseModel):
    account_id: str
    name: str = Field(min_length=1, max_length=1024)
    parent_id: str | None = None

    @field_validator("name")
    @classmethod
    def safe_name(cls, value: str) -> str:
        if any(ord(c) < 32 or ord(c) == 127 for c in value):
            raise ValueError("Control characters are not allowed")
        return value


class RenameRequest(BaseModel):
    name: str = Field(min_length=1, max_length=1024)

    @field_validator("name")
    @classmethod
    def safe_name(cls, value: str) -> str:
        return FolderRequest.safe_name(value)


class MoveRequest(BaseModel):
    parent_id: str = Field(min_length=1, max_length=255)


class CopyRequest(BaseModel):
    source_account_id: str
    source_file_id: str = Field(min_length=1, max_length=255)
    destination_account_id: str | None = None
    destination_parent_id: str | None = None
    idempotency_key: str = Field(min_length=1, max_length=128)


class SharingRequest(BaseModel):
    # Requiring explicit confirmation helps prevent accidental publication.
    confirm_public: Literal[True]
