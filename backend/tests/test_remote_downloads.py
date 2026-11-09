import io
import socket
from contextlib import contextmanager
from unittest.mock import Mock

import pytest
from fastapi import HTTPException
from sqlalchemy import select

from app import remote_downloads as remote, remote_http, transfers
from app.db import SessionLocal
from app.models import RemoteSource, Transfer, User
from app.security import decrypt


@pytest.mark.parametrize("url", ["file:///etc/passwd", "ftp://example.org/a", "http://user:pass@example.org/x", "https://example.org:8443/x", "https://example.org/#fragment", "https://example.org/\nHost:x"])
def test_invalid_url_never_resolves(monkeypatch, url):
    resolver = Mock()
    monkeypatch.setattr(socket, "getaddrinfo", resolver)
    with pytest.raises(remote_http.RemoteError):
        remote_http.public_target(url)
    resolver.assert_not_called()


@pytest.mark.parametrize("address", ["127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.100.100.200", "0.0.0.0", "::1", "fe80::1", "fc00::1", "::ffff:8.8.8.8", "224.0.0.1"])
def test_private_and_metadata_dns_blocked(monkeypatch, address):
    monkeypatch.setattr(socket, "getaddrinfo", lambda *a, **k: [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (address, 80))])
    with pytest.raises(remote_http.RemoteError):
        remote_http.public_target("http://attacker.example/file")


def test_all_dns_answers_checked(monkeypatch):
    monkeypatch.setattr(socket, "getaddrinfo", lambda *a, **k: [(2, 1, 6, "", (ip, 443)) for ip in ("8.8.8.8", "127.0.0.1")])
    with pytest.raises(remote_http.RemoteError):
        remote_http.public_target("https://mixed.example/file")


def test_socket_pinned_and_redirect_revalidated(monkeypatch):
    monkeypatch.setattr(socket, "getaddrinfo", lambda host, *a, **k: [(2, 1, 6, "", (("127.0.0.1" if host == "internal.example" else "8.8.8.8"), 443))])
    connect = Mock(return_value=object())
    monkeypatch.setattr(socket, "create_connection", connect)
    class Connection:
        def __init__(self, host, port, timeout):
            assert host == "public.example"
        def request(self, method, path, headers):
            self._create_connection(("public.example", 443), 30)
        def getresponse(self):
            result = Mock(status=302)
            result.getheader = lambda key: "https://internal.example/secret"
            return result
        def close(self):
            pass
    monkeypatch.setattr(remote_http.http.client, "HTTPSConnection", Connection)
    with pytest.raises(remote_http.RemoteError):
        with remote_http.open_source("https://public.example/file"):
            pytest.fail("Internal redirect must not be opened")
    assert connect.call_args.args[0] == ("8.8.8.8", 443)


class Response(io.BytesIO):
    def __init__(self, data, status=200, headers=None):
        super().__init__(data)
        self.status = status
        self.headers = {"Content-Length": str(len(data)), "Content-Type": "application/octet-stream", "ETag": '"stable"', "Accept-Ranges": "bytes", **(headers or {})}
    def getheader(self, key, default=None):
        return self.headers.get(key, default)


@pytest.fixture
def source(monkeypatch):
    state = {"data": b"real test file" * 50000, "requests": [], "range": True, "etag": '"stable"'}
    @contextmanager
    def open_fake(url, method="GET", headers=None):
        state["requests"].append((method, headers))
        data = state["data"]
        values = {"ETag": state["etag"]}
        status = 200
        if headers and "Range" in headers and state["range"]:
            offset = int(headers["Range"].split("=")[1].split("-")[0])
            values["Content-Range"] = f"bytes {offset}-{len(data)-1}/{len(data)}"
            data = data[offset:]
            status = 206
        yield Response(data, status, values)
    monkeypatch.setattr(remote, "open_source", open_fake)
    monkeypatch.setattr(remote, "schedule", lambda *a: None)
    return state


def make_task(client, headers, account):
    result = client.post("/api/remote-downloads", headers=headers, json={"url": "https://source.example/file.bin?private=secret", "account_id": account.id, "idempotency_key": "remote-test"})
    assert result.status_code == 201, result.text
    return result.json()


def user_id(task_id):
    with SessionLocal() as db:
        return db.get(Transfer, task_id).user_id


def test_create_stream_complete_ownership_and_secret_storage(client, signup, add_account, source, fake):
    headers = signup()
    account = add_account(headers)
    task = make_task(client, headers, account)
    assert "secret" not in str(task)
    with SessionLocal() as db:
        saved = db.get(RemoteSource, task["id"])
        assert "secret" not in saved.url_enc
        assert "private=secret" in decrypt(saved.url_enc)
    remote.worker(user_id(task["id"]), task["id"])
    done = client.get("/api/transfers/" + task["id"], headers=headers).json()
    assert done["status"] == "completed"
    assert done["bytes_transferred"] == len(source["data"])
    assert fake.items[account.id][done["result_file_id"]]["data"] == source["data"]
    other = signup("other@example.com")
    assert client.post(f'/api/remote-downloads/{task["id"]}/pause', headers=other).status_code == 404
    assert client.get("/api/transfers/" + task["id"], headers=other).status_code == 404


def test_auth_validation_preview(client, signup, source):
    assert client.post("/api/remote-downloads/preview", json={"url": "https://source.example/x"}).status_code == 401
    headers = signup()
    response = client.post("/api/remote-downloads/preview", headers=headers, json={"url": "https://source.example/x"})
    assert response.status_code == 200
    assert response.json()["size"] == len(source["data"])
    assert "etag" not in response.json()


def test_pause_restart_resume_cancel_and_idempotency(client, signup, add_account, source):
    headers = signup()
    account = add_account(headers)
    task = make_task(client, headers, account)
    assert make_task(client, headers, account)["id"] == task["id"]
    assert client.post(f'/api/remote-downloads/{task["id"]}/pause', headers=headers).json()["status"] == "paused"
    remote.worker(user_id(task["id"]), task["id"])
    assert all(method == "HEAD" for method, _ in source["requests"])
    transfers.recover_interrupted()
    assert client.post(f'/api/remote-downloads/{task["id"]}/resume', headers=headers).json()["status"] == "pending"
    assert client.post(f'/api/remote-downloads/{task["id"]}/cancel', headers=headers).json()["status"] == "cancelled"
    remote.worker(user_id(task["id"]), task["id"])
    with SessionLocal() as db:
        assert db.get(Transfer, task["id"]).reserved_bytes == 0


def test_range_resume_reconciles_acknowledged_bytes(client, signup, add_account, source, fake):
    headers = signup()
    account = add_account(headers)
    task = make_task(client, headers, account)
    uid = user_id(task["id"])
    # Simulate a chunk accepted by Drive before process interruption.
    with SessionLocal() as db:
        transfers.put_chunk(db, uid, task["id"], 0, source["data"][:262144], allow_remote=True)
    transfers.recover_interrupted()
    client.post(f'/api/remote-downloads/{task["id"]}/resume', headers=headers)
    remote.worker(uid, task["id"])
    done = client.get("/api/transfers/" + task["id"], headers=headers).json()
    assert done["status"] == "completed"
    assert source["requests"][-1][1]["Range"] == "bytes=262144-"
    import hashlib
    result = fake.items[account.id][done["result_file_id"]]["data"]
    assert hashlib.sha256(result).digest() == hashlib.sha256(source["data"]).digest()


def test_refused_range_never_appends_wrong_bytes(client, signup, add_account, source):
    headers = signup()
    account = add_account(headers)
    task = make_task(client, headers, account)
    uid = user_id(task["id"])
    with SessionLocal() as db:
        transfers.put_chunk(db, uid, task["id"], 0, source["data"][:262144], allow_remote=True)
        db.get(Transfer, task["id"]).status = "pending"
        db.commit()
    source["range"] = False
    remote.worker(uid, task["id"])
    done = client.get("/api/transfers/" + task["id"], headers=headers).json()
    assert done["status"] == "failed"
    assert done["bytes_transferred"] == 262144


@pytest.mark.parametrize("headers", [{"Content-Type": "text/html"}, {"Content-Length": ""}, {"Content-Length": "0"}, {"Content-Encoding": "gzip"}])
def test_unsupported_sources_rejected(monkeypatch, headers):
    @contextmanager
    def response(*a, **k):
        yield Response(b"hello", headers=headers)
    monkeypatch.setattr(remote, "open_source", response)
    with pytest.raises(remote_http.RemoteError):
        remote.metadata("https://source.example/file")


def test_changed_etag_fails_without_appending(client, signup, add_account, source):
    headers = signup()
    account = add_account(headers)
    task = make_task(client, headers, account)
    source["etag"] = '"changed"'
    remote.worker(user_id(task["id"]), task["id"])
    result = client.get("/api/transfers/" + task["id"], headers=headers).json()
    assert result["status"] == "failed"
    assert result["bytes_transferred"] == 0
    assert "changed" in result["error"]


def test_cross_account_create_and_key_collision(client, signup, add_account, source):
    headers = signup()
    account = add_account(headers)
    other = signup("other@example.com")
    foreign = client.post("/api/remote-downloads", headers=other, json={"url": "https://source.example/file.bin", "account_id": account.id, "idempotency_key": "test"})
    assert foreign.status_code == 404
    task = make_task(client, headers, account)
    duplicate = client.post("/api/remote-downloads", headers=headers, json={"url": "https://different.example/file.bin", "account_id": account.id, "idempotency_key": "remote-test"})
    assert duplicate.status_code == 409


def test_pause_after_chunk_then_resume(client, signup, add_account, source, monkeypatch, fake):
    source["data"] = b"x" * (transfers.CHUNK_SIZE + 1000)
    headers = signup()
    account = add_account(headers)
    task = make_task(client, headers, account)
    uid = user_id(task["id"])
    original = transfers.put_chunk
    def pause_after(db, *args, **kwargs):
        result = original(db, *args, **kwargs)
        result.status = "paused"
        db.commit()
        return result
    monkeypatch.setattr(transfers, "put_chunk", pause_after)
    remote.worker(uid, task["id"])
    paused = client.get("/api/transfers/" + task["id"], headers=headers).json()
    assert paused["status"] == "paused"
    assert paused["bytes_transferred"] == transfers.CHUNK_SIZE
    monkeypatch.setattr(transfers, "put_chunk", original)
    assert client.post(f'/api/remote-downloads/{task["id"]}/resume', headers=headers).status_code == 200
    remote.worker(uid, task["id"])
    done = client.get("/api/transfers/" + task["id"], headers=headers).json()
    assert done["status"] == "completed"
    assert fake.items[account.id][done["result_file_id"]]["data"] == source["data"]


def test_failed_provider_retry_and_history_removal(client, signup, add_account, source, fake):
    headers = signup()
    account = add_account(headers)
    task = make_task(client, headers, account)
    uid = user_id(task["id"])
    assert client.delete(f'/api/remote-downloads/{task["id"]}', headers=headers).status_code == 409
    fake.fail_upload_once = True
    remote.worker(uid, task["id"])
    assert client.get("/api/transfers/" + task["id"], headers=headers).json()["status"] == "failed"
    assert client.post(f'/api/remote-downloads/{task["id"]}/retry', headers=headers).status_code == 200
    remote.worker(uid, task["id"])
    done = client.get("/api/transfers/" + task["id"], headers=headers).json()
    assert done["status"] == "completed"
    assert done["checksum_verified"] is True
    integrity = client.get(f'/api/remote-downloads/{task["id"]}/integrity', headers=headers).json()
    import hashlib
    assert integrity["source_sha256"] == hashlib.sha256(source["data"]).hexdigest()
    assert client.delete(f'/api/remote-downloads/{task["id"]}', headers=headers).status_code == 204
    assert done["result_file_id"] in fake.items[account.id]


def test_remote_history_real_pagination_and_user_scope(client, signup, add_account, source):
    headers = signup()
    account = add_account(headers)
    task = make_task(client, headers, account)
    listing = client.get("/api/remote-downloads?limit=1", headers=headers).json()
    assert listing["items"][0]["id"] == task["id"]
    assert listing["next_offset"] is None
    other = signup("other@example.com")
    assert client.get("/api/remote-downloads", headers=other).json()["items"] == []
    assert client.get("/api/remote-downloads?limit=200", headers=headers).status_code == 422


def test_encryption_key_alias_preserves_existing_variable_priority(monkeypatch):
    from app.config import Settings
    from cryptography.fernet import Fernet
    original, alias = Fernet.generate_key().decode(), Fernet.generate_key().decode()
    monkeypatch.setenv("FERNET_KEY", original)
    monkeypatch.setenv("TOKEN_ENCRYPTION_KEY", alias)
    assert Settings(_env_file=None).fernet_key == original
    monkeypatch.delenv("FERNET_KEY")
    assert Settings(_env_file=None).fernet_key == alias
