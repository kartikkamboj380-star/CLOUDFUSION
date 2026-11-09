import hashlib
import os
import tempfile
import uuid
from contextlib import contextmanager
from datetime import timedelta

from cryptography.fernet import Fernet

# Test settings must be installed before importing the application modules.
_db_path = os.path.join(tempfile.gettempdir(), f"cloudfusion-test-{uuid.uuid4()}.db")
os.environ["DATABASE_URL"] = "sqlite:///" + _db_path
os.environ["FERNET_KEY"] = Fernet.generate_key().decode()
os.environ["GOOGLE_CLIENT_ID"] = "test-client"
os.environ["GOOGLE_CLIENT_SECRET"] = "test-secret"
os.environ["GOOGLE_REDIRECT_URI"] = "http://localhost:8000/api/oauth/callback"

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import select

from app import drive
from app.db import Base, SessionLocal, engine, utcnow
from app.main import app, _attempts
from app.models import Account, LoginSession, User
from app.security import encrypt, token_hash


class Stream:
    def __init__(self, data):
        self.data = data

    def iter_bytes(self, chunk_size):
        for pos in range(0, len(self.data), chunk_size):
            yield self.data[pos:pos + chunk_size]


class FakeDrive:
    def __init__(self):
        self.quota_map = {}
        self.items = {}
        self.sessions = {}
        self.permissions_map = {}
        self.fail_quota = set()
        self.fail_upload_once = False
        self.lost_upload_response_once = False
        self.fail_page = set()

    def client(self, db, account):
        fake = self
        aid = account.id

        class Client:
            def quota(self):
                if aid in fake.fail_quota:
                    raise drive.DriveFailure(502, "Drive temporarily unavailable")
                return fake.quota_map.get(aid, {"limit": 10000000, "usage": 0}).copy()

            def list_page(self, page_token=None):
                items = list(fake.items.get(aid, {}).values())
                if aid in fake.fail_page and page_token:
                    raise drive.DriveFailure(502, "Page failed")
                start = int(page_token or 0)
                subset = items[start:start + 2]
                return {"files": [dict(x["meta"]) for x in subset],
                        **({"nextPageToken": str(start + 2)} if start + 2 < len(items) else {})}

            def metadata(self, file_id):
                drive.valid_id(file_id)
                item = fake.items.get(aid, {}).get(file_id)
                if not item:
                    raise drive.DriveFailure(404, "File not found")
                return dict(item["meta"])

            def start_upload(self, name, size, mime_type, parent_id=None):
                if parent_id:
                    drive.valid_id(parent_id)
                key = str(uuid.uuid4())
                fake.sessions[key] = {"account_id": aid, "name": name, "size": size, "mime": mime_type, "data": bytearray(), "file": None}
                return "https://www.googleapis.com/upload/" + key

            def upload_status(self, url, total):
                session = fake.sessions[url.rsplit("/", 1)[1]]
                if session["file"]:
                    return {"offset": total, "done": True, "file": session["file"]}
                return {"offset": len(session["data"]), "done": False}

            def upload_chunk(self, url, total, offset, chunk):
                session = fake.sessions[url.rsplit("/", 1)[1]]
                if fake.fail_upload_once:
                    fake.fail_upload_once = False
                    raise drive.DriveFailure(403, "Permission denied")
                assert len(session["data"]) == offset
                session["data"].extend(chunk)
                if len(session["data"]) == total:
                    file_id = "file_" + str(uuid.uuid4()).replace("-", "")
                    digest = hashlib.md5(session["data"]).hexdigest()
                    meta = {"id": file_id, "name": session["name"], "mimeType": session["mime"],
                            "size": str(total), "md5Checksum": digest}
                    fake.items.setdefault(aid, {})[file_id] = {"meta": meta, "data": bytes(session["data"])}
                    session["file"] = meta
                    fake.quota_map[aid]["usage"] += total
                if fake.lost_upload_response_once:
                    fake.lost_upload_response_once = False
                    raise drive.DriveFailure(503, "Response lost")
                return self.upload_status(url, total)

            @contextmanager
            def stream_download(self, file_id, offset=0):
                item = fake.items.get(aid, {}).get(file_id)
                if not item:
                    raise drive.DriveFailure(404, "File not found")
                yield Stream(item["data"][offset:])

            def create_folder(self, name, parent_id):
                file_id = "folder_" + uuid.uuid4().hex
                meta = {"id": file_id, "name": name, "mimeType": drive.FOLDER, "parents": [parent_id] if parent_id else []}
                fake.items.setdefault(aid, {})[file_id] = {"meta": meta, "data": b""}
                return dict(meta)

            def rename(self, file_id, name):
                self.metadata(file_id)
                fake.items[aid][file_id]["meta"]["name"] = name
                return self.metadata(file_id)

            def trash(self, file_id):
                self.metadata(file_id)
                del fake.items[aid][file_id]

            def move(self, file_id, parent_id):
                self.metadata(file_id)
                drive.valid_id(parent_id)
                fake.items[aid][file_id]["meta"]["parents"] = [parent_id]
                return self.metadata(file_id)

            def permissions(self, file_id):
                self.metadata(file_id)
                return list(fake.permissions_map.get((aid, file_id), []))

            def public_link(self, file_id):
                p = {"id": "anyoneid", "type": "anyone", "role": "reader"}
                fake.permissions_map.setdefault((aid, file_id), []).append(p)
                return p

            def revoke_permission(self, file_id, permission_id):
                fake.permissions_map[(aid, file_id)] = [p for p in self.permissions(file_id) if p["id"] != permission_id]

        return Client()

    def add_file(self, account, file_id, name, data=b"hello", mime="text/plain", modified="2026-01-01T00:00:00Z"):
        meta = {"id": file_id, "name": name, "mimeType": mime, "size": str(len(data)), "modifiedTime": modified,
                "owners": [{"emailAddress": account.email}], "md5Checksum": hashlib.md5(data).hexdigest(),
                "webViewLink": "https://drive.google.com/file/d/" + file_id}
        self.items.setdefault(account.id, {})[file_id] = {"meta": meta, "data": data}


@pytest.fixture
def fake(monkeypatch):
    obj = FakeDrive()
    monkeypatch.setattr(drive, "client_for", obj.client)
    return obj


@pytest.fixture
def client(fake):
    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)
    _attempts.clear()
    with TestClient(app) as test_client:
        yield test_client
    Base.metadata.drop_all(engine)


@pytest.fixture
def signup(client):
    def create(email="user@example.com", password="a-strong-password"):
        response = client.post("/api/auth/register", json={"email": email, "password": password})
        assert response.status_code == 201, response.text
        return {"Authorization": "Bearer " + response.json()["token"]}
    return create


@pytest.fixture
def add_account(fake):
    def create(headers, email="drive@example.com", limit=10000000, usage=0):
        with SessionLocal() as db:
            login = db.get(LoginSession, token_hash(headers["Authorization"].split()[1]))
            user = db.get(User, login.user_id)
            a = Account(user_id=user.id, google_sub=uuid.uuid4().hex, email=email, refresh_token_enc=encrypt("secret-refresh"),
                        access_token_enc=encrypt("secret-access"), access_expires_at=utcnow() + timedelta(hours=1),
                        quota_limit=limit, quota_usage=usage, quota_checked_at=utcnow(), reserved_bytes=0)
            db.add(a)
            db.commit()
            fake.quota_map[a.id] = {"limit": limit, "usage": usage}
            return a
    return create
