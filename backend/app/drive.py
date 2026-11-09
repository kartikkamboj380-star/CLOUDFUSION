"""A narrow Google Drive v3 adapter. No Google credentials are returned to clients."""
import contextlib
import re
from datetime import timedelta
from urllib.parse import quote, urlsplit

import httpx
from sqlalchemy.orm import Session

from .config import get_settings
from .db import utcnow
from .models import Account
from .security import decrypt, encrypt

API = "https://www.googleapis.com/drive/v3"
UPLOAD_API = "https://www.googleapis.com/upload/drive/v3"
TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
FILE_FIELDS = "id,name,mimeType,size,modifiedTime,owners(emailAddress),md5Checksum,webViewLink,parents"
FOLDER = "application/vnd.google-apps.folder"


class DriveFailure(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


def valid_id(value: str) -> str:
    if not value or not re.fullmatch(r"[A-Za-z0-9_-]{1,255}", value):
        raise DriveFailure(400, "Invalid Google Drive file or folder ID")
    return value


def valid_session_url(url: str) -> str:
    try:
        parts = urlsplit(url)
        host = (parts.hostname or "").lower()
        valid = (parts.scheme == "https" and (host == "googleapis.com" or host.endswith(".googleapis.com"))
                 and not parts.username and not parts.password and parts.port in (None, 443))
    except ValueError:
        valid = False
    if not valid:
        raise DriveFailure(502, "Google returned an invalid upload session URL")
    return url


def _error(response: httpx.Response) -> DriveFailure:
    if response.status_code == 401:
        return DriveFailure(424, "Google authorization expired; reconnect this account")
    if response.status_code == 404:
        return DriveFailure(404, "Google Drive file or upload session was not found")
    if response.status_code in (403, 429):
        reason = ""
        try:
            reason = response.json().get("error", {}).get("errors", [{}])[0].get("reason", "")
        except (ValueError, AttributeError, IndexError, TypeError, httpx.ResponseNotRead):
            pass
        if reason in ("storageQuotaExceeded", "teamDriveFileLimitExceeded"):
            return DriveFailure(409, "The destination account has insufficient storage quota")
        return DriveFailure(403 if response.status_code == 403 else 429,
                            "Google Drive denied the request or its rate limit was reached")
    if response.status_code == 410:
        return DriveFailure(410, "The Google upload session has expired")
    return DriveFailure(502, "Google Drive request failed; retry later")


class GoogleDrive:
    def __init__(self, db: Session, account: Account):
        self.db = db
        self.account = account

    def access_token(self, force: bool = False) -> str:
        if self.account.reauth_required:
            raise DriveFailure(424, "Google authorization expired; reconnect this account")
        if (not force and self.account.access_token_enc and self.account.access_expires_at
                and self.account.access_expires_at > utcnow() + timedelta(seconds=60)):
            return decrypt(self.account.access_token_enc)
        settings = get_settings()
        try:
            response = httpx.post(TOKEN_ENDPOINT, data={
                "client_id": settings.google_client_id,
                "client_secret": settings.google_client_secret,
                "refresh_token": decrypt(self.account.refresh_token_enc),
                "grant_type": "refresh_token",
            }, timeout=30)
        except httpx.HTTPError as exc:
            raise DriveFailure(502, "Unable to reach Google while refreshing authorization") from exc
        if response.status_code != 200:
            try:
                revoked = response.json().get("error") in ("invalid_grant", "invalid_token")
            except (ValueError, AttributeError):
                revoked = False
            if revoked:
                self.account.reauth_required = True
                self.db.commit()
                raise DriveFailure(424, "Google authorization was revoked or expired; reconnect this account")
            raise DriveFailure(502, "Google authorization refresh failed")
        data = response.json()
        if not data.get("access_token"):
            raise DriveFailure(502, "Google returned an invalid authorization response")
        self.account.access_token_enc = encrypt(data["access_token"])
        self.account.access_expires_at = utcnow() + timedelta(seconds=int(data.get("expires_in", 3600)))
        self.db.commit()
        return data["access_token"]

    def request(self, method: str, path: str, **kwargs) -> httpx.Response:
        kwargs.setdefault("timeout", 60)
        headers = dict(kwargs.pop("headers", {}))
        for attempt in range(2):
            headers["Authorization"] = "Bearer " + self.access_token(force=attempt == 1)
            try:
                response = httpx.request(method, API + path, headers=headers, **kwargs)
            except httpx.HTTPError as exc:
                raise DriveFailure(502, "Unable to reach Google Drive") from exc
            if response.status_code != 401 or attempt:
                break
        if not response.is_success:
            if response.status_code == 401:
                self.account.reauth_required = True
                self.db.commit()
            raise _error(response)
        return response

    def quota(self) -> dict:
        data = self.request("GET", "/about", params={"fields": "storageQuota,user(emailAddress)"}).json()
        raw = data.get("storageQuota", {})
        # Drive omits limit for an unlimited quota. An omitted usage is unknown.
        return {"limit": int(raw["limit"]) if raw.get("limit") is not None else None,
                "usage": int(raw["usage"]) if raw.get("usage") is not None else None}

    def list_page(self, page_token: str | None = None) -> dict:
        params = {"q": "trashed = false", "pageSize": 200, "spaces": "drive",
                  "fields": f"nextPageToken,files({FILE_FIELDS})"}
        if page_token:
            params["pageToken"] = page_token
        return self.request("GET", "/files", params=params).json()

    def metadata(self, file_id: str) -> dict:
        return self.request("GET", "/files/" + valid_id(file_id), params={"fields": FILE_FIELDS, "supportsAllDrives": "true"}).json()

    def create_folder(self, name: str, parent_id: str | None) -> dict:
        data = {"name": name, "mimeType": FOLDER}
        if parent_id:
            data["parents"] = [valid_id(parent_id)]
        return self.request("POST", "/files", params={"fields": FILE_FIELDS, "supportsAllDrives": "true"}, json=data).json()

    def rename(self, file_id: str, name: str) -> dict:
        return self.request("PATCH", "/files/" + valid_id(file_id),
                            params={"fields": FILE_FIELDS, "supportsAllDrives": "true"}, json={"name": name}).json()

    def trash(self, file_id: str) -> None:
        self.request("PATCH", "/files/" + valid_id(file_id), params={"supportsAllDrives": "true"}, json={"trashed": True})

    def move(self, file_id: str, parent_id: str) -> dict:
        file_id = valid_id(file_id)
        parent_id = valid_id(parent_id)
        current = self.metadata(file_id)
        params = {"fields": FILE_FIELDS, "addParents": parent_id, "supportsAllDrives": "true"}
        old = current.get("parents", [])
        remove = [valid_id(p) for p in old if p != parent_id]
        if remove:
            params["removeParents"] = ",".join(remove)
        if parent_id in old:
            return current
        return self.request("PATCH", "/files/" + file_id, params=params, json={}).json()

    def permissions(self, file_id: str) -> list[dict]:
        result = []
        page = None
        while True:
            params = {"fields": "nextPageToken,permissions(id,type,role,emailAddress,allowFileDiscovery)",
                      "pageSize": 100, "supportsAllDrives": "true"}
            if page:
                params["pageToken"] = page
            data = self.request("GET", "/files/" + valid_id(file_id) + "/permissions", params=params).json()
            result.extend(data.get("permissions", []))
            page = data.get("nextPageToken")
            if not page:
                return result

    def public_link(self, file_id: str) -> dict:
        return self.request("POST", "/files/" + valid_id(file_id) + "/permissions",
                            params={"fields": "id,type,role,allowFileDiscovery", "supportsAllDrives": "true"},
                            json={"type": "anyone", "role": "reader", "allowFileDiscovery": False}).json()

    def revoke_permission(self, file_id: str, permission_id: str) -> None:
        self.request("DELETE", "/files/" + valid_id(file_id) + "/permissions/" + valid_id(permission_id),
                     params={"supportsAllDrives": "true"})

    def start_upload(self, name: str, size: int, mime_type: str, parent_id: str | None = None) -> str:
        metadata = {"name": name, "mimeType": mime_type}
        if parent_id:
            metadata["parents"] = [valid_id(parent_id)]
        headers = {"X-Upload-Content-Type": mime_type, "X-Upload-Content-Length": str(size),
                   "Content-Type": "application/json; charset=UTF-8"}
        for attempt in range(2):
            headers["Authorization"] = "Bearer " + self.access_token(force=attempt == 1)
            try:
                response = httpx.post(UPLOAD_API + "/files", params={"uploadType": "resumable", "fields": "id,name,size,md5Checksum,webViewLink", "supportsAllDrives": "true"},
                                      headers=headers, json=metadata, timeout=60)
            except httpx.HTTPError as exc:
                raise DriveFailure(502, "Could not initiate Google Drive upload") from exc
            if response.status_code != 401 or attempt:
                break
        if not response.is_success:
            if response.status_code == 401:
                self.account.reauth_required = True
                self.db.commit()
            raise _error(response)
        return valid_session_url(response.headers.get("Location", ""))

    def upload_chunk(self, url: str, total: int, offset: int, chunk: bytes) -> dict:
        url = valid_session_url(url)
        headers = {"Content-Length": str(len(chunk)), "Content-Range": f"bytes {offset}-{offset + len(chunk) - 1}/{total}"}
        try:
            response = httpx.put(url, headers=headers, content=chunk, timeout=120, follow_redirects=False)
        except httpx.HTTPError as exc:
            # The server may have accepted the bytes even if the response was lost.
            raise DriveFailure(503, "Upload outcome is uncertain; query the session before retrying") from exc
        return self._upload_result(response, total)

    def upload_status(self, url: str, total: int) -> dict:
        try:
            response = httpx.put(valid_session_url(url), headers={"Content-Length": "0", "Content-Range": f"bytes */{total}"},
                                 content=b"", timeout=60, follow_redirects=False)
        except httpx.HTTPError as exc:
            raise DriveFailure(503, "Could not check resumable upload status") from exc
        return self._upload_result(response, total)

    @staticmethod
    def _upload_result(response: httpx.Response, total: int) -> dict:
        if response.status_code == 308:
            range_header = response.headers.get("Range", "")
            match = re.fullmatch(r"bytes=0-(\d+)", range_header)
            if range_header and not match:
                raise DriveFailure(502, "Google returned an invalid upload range")
            offset = int(match.group(1)) + 1 if match else 0
            if offset > total:
                raise DriveFailure(502, "Google returned an invalid upload offset")
            return {"offset": offset, "done": False}
        if response.status_code in (200, 201):
            try:
                data = response.json()
            except ValueError as exc:
                raise DriveFailure(502, "Google returned an invalid upload result") from exc
            return {"offset": total, "done": True, "file": data}
        raise _error(response)

    @contextlib.contextmanager
    def stream_download(self, file_id: str, offset: int = 0):
        headers = {}
        if offset:
            headers["Range"] = f"bytes={offset}-"
        try:
            with httpx.Client(timeout=httpx.Timeout(120, connect=30), follow_redirects=False) as client:
                for attempt in range(2):
                    headers["Authorization"] = "Bearer " + self.access_token(force=attempt == 1)
                    with client.stream("GET", API + "/files/" + valid_id(file_id),
                                       params={"alt": "media", "supportsAllDrives": "true"}, headers=headers) as response:
                        if response.status_code == 401 and attempt == 0:
                            continue
                        if response.status_code not in ((206,) if offset else (200, 206)):
                            if response.status_code == 401:
                                self.account.reauth_required = True
                                self.db.commit()
                            raise _error(response)
                        if offset and not response.headers.get("Content-Range", "").startswith(f"bytes {offset}-"):
                            raise DriveFailure(502, "Google returned an unexpected download range; retry the transfer")
                        yield response
                        return
        except httpx.HTTPError as exc:
            raise DriveFailure(502, "Google Drive download stream failed") from exc


def client_for(db: Session, account: Account) -> GoogleDrive:
    return GoogleDrive(db, account)
