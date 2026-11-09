import concurrent.futures
from urllib.parse import parse_qs, urlsplit

from sqlalchemy import select

from app import oauth
from app.db import SessionLocal
from app.models import Account, Transfer
from app.security import decrypt


def test_authentication_and_logout(client, signup):
    assert client.get("/api/dashboard").status_code == 401
    assert client.post("/api/auth/register", json={"email": "x@example.com", "password": "short"}).status_code == 422
    headers = signup()
    assert client.get("/api/auth/me", headers=headers).json()["email"] == "user@example.com"
    assert client.post("/api/auth/login", json={"email": "user@example.com", "password": "wrong"}).status_code == 401
    assert client.post("/api/auth/login", json={"email": "user@example.com", "password": "a-strong-password"}).status_code == 200
    assert client.post("/api/auth/logout", headers=headers).status_code == 204
    assert client.get("/api/auth/me", headers=headers).status_code == 401


def test_oauth_pkce_state_one_time_and_encrypted(client, signup, monkeypatch):
    headers = signup()
    result = client.post("/api/oauth/start", headers=headers).json()
    query = parse_qs(urlsplit(result["authorization_url"]).query)
    assert query["code_challenge_method"] == ["S256"]
    assert "https://www.googleapis.com/auth/drive" in query["scope"][0]
    monkeypatch.setattr(oauth, "exchange_code", lambda code, verifier: (
        {"access_token": "test-access", "refresh_token": "test-refresh", "expires_in": 3600},
        {"sub": "google-123", "email": "drive@example.com", "email_verified": True}))
    state = query["state"][0]
    result = client.get("/api/oauth/callback", params={"state": state, "code": "test-code"})
    assert result.status_code == 200
    assert "test-access" not in result.text
    assert client.get("/api/oauth/callback", params={"state": state, "code": "test-code"}).status_code == 400
    with SessionLocal() as db:
        account = db.scalar(select(Account))
        assert account.refresh_token_enc != "test-refresh"
        assert decrypt(account.refresh_token_enc) == "test-refresh"
    accounts = client.get("/api/accounts", headers=headers).json()
    assert accounts[0]["email"] == "drive@example.com"
    assert "test-refresh" not in str(accounts)


def test_real_quota_sums_unlimited_and_unavailable(client, signup, add_account, fake):
    headers = signup()
    a = add_account(headers, "one@example.com", limit=1000, usage=400)
    b = add_account(headers, "two@example.com", limit=3000, usage=1000)
    assert client.post("/api/accounts/refresh", headers=headers).status_code == 200
    result = client.get("/api/dashboard", headers=headers).json()
    assert (result["capacity_bytes"], result["used_bytes"], result["remaining_bytes"]) == (4000, 1400, 2600)
    assert result["utilization_percent"] == 35.0
    fake.quota_map[b.id]["limit"] = None
    client.post("/api/accounts/refresh", headers=headers)
    result = client.get("/api/dashboard", headers=headers).json()
    assert result["capacity_bytes"] is None and result["used_bytes"] == 1400
    assert result["accounts"][1]["unlimited"] is True
    fake.fail_quota.add(a.id)
    client.post("/api/accounts/refresh", headers=headers)
    result = client.get("/api/dashboard", headers=headers).json()
    assert result["used_bytes"] is None and result["accounts"][0]["quota_available"] is False


def test_file_sync_sort_search_pagination_mutations_and_idor(client, signup, add_account, fake):
    headers = signup()
    other = signup("other@example.com")
    a = add_account(headers, "a@example.com")
    b = add_account(headers, "b@example.com")
    fake.add_file(a, "id_a", "same.txt", b"123456", modified="2025-01-01T00:00:00Z")
    fake.add_file(a, "id_b", "photo.png", b"12", mime="image/png")
    fake.add_file(a, "id_c", "100%_literal.txt", b"123")
    fake.add_file(b, "id_d", "same.txt", b"1")
    sync = client.post("/api/files/sync", headers=headers)
    assert sync.status_code == 200 and sum(x["count"] for x in sync.json()["accounts"]) == 4
    page = client.get("/api/files?sort=size&direction=asc&limit=2", headers=headers).json()
    assert [x["size"] for x in page["items"]] == [1, 2]
    page2 = client.get(f"/api/files?sort=size&direction=asc&limit=2&offset={page['next_offset']}", headers=headers).json()
    assert [x["size"] for x in page2["items"]] == [3, 6]
    assert len(client.get("/api/files?search=same.txt", headers=headers).json()["items"]) == 2
    assert len(client.get("/api/files", params={"search": "%_"}, headers=headers).json()["items"]) == 1
    assert len(client.get("/api/files?type=images", headers=headers).json()["items"]) == 1
    assert client.get(f"/api/files?account_id={a.id}", headers=other).status_code == 404
    assert client.patch(f"/api/files/{a.id}/id_a", json={"name": "stolen"}, headers=other).status_code == 404
    assert client.patch(f"/api/files/{a.id}/id_a", json={"name": "renamed"}, headers=headers).json()["name"] == "renamed"
    assert client.post(f"/api/files/{a.id}/id_a/move", json={"parent_id": "folder1"}, headers=headers).status_code == 200
    folder = client.post("/api/files/folders", json={"account_id": a.id, "name": "Projects"}, headers=headers)
    assert folder.status_code == 201
    assert client.delete(f"/api/files/{a.id}/id_a", headers=headers).status_code == 204
    assert not client.get("/api/files?search=renamed", headers=headers).json()["items"]


def test_failed_index_sync_retains_existing_rows(client, signup, add_account, fake):
    headers = signup(); a = add_account(headers)
    for idx in range(3):
        fake.add_file(a, f"f{idx}", f"file {idx}")
    client.post("/api/files/sync", headers=headers)
    fake.fail_page.add(a.id)
    result = client.post("/api/files/sync", headers=headers).json()
    assert result["accounts"][0]["error"]
    assert len(client.get("/api/files", headers=headers).json()["items"]) == 3


def test_public_sharing_requires_explicit_confirmation(client, signup, add_account, fake):
    headers = signup(); a = add_account(headers)
    fake.add_file(a, "file1", "private.txt")
    path = f"/api/files/{a.id}/file1"
    assert client.post(path + "/public", json={}, headers=headers).status_code == 422
    assert client.get(path + "/permissions", headers=headers).json()["permissions"] == []
    assert client.post(path + "/public", json={"confirm_public": True}, headers=headers).json()["permissions"][0]["type"] == "anyone"
    assert client.delete(path + "/public", headers=headers).json()["permissions"] == []


def test_allocation_reservations_idempotency_and_progress(client, signup, add_account, fake):
    headers = signup(); a = add_account(headers, "busy@example.com", limit=2000000, usage=1200000)
    b = add_account(headers, "free@example.com", limit=2000000, usage=100000)
    req = {"name": "big.bin", "size": 262145, "mime_type": "application/octet-stream", "idempotency_key": "upload-1"}
    response = client.post("/api/uploads", json=req, headers=headers)
    assert response.status_code == 201, response.text
    transfer = response.json()
    assert transfer["destination_account_id"] == b.id
    assert client.post("/api/uploads", json=req, headers=headers).json()["id"] == transfer["id"]
    assert client.post("/api/uploads", json={**req, "size": 22}, headers=headers).status_code == 409
    accounts = client.get("/api/accounts", headers=headers).json()
    assert next(x for x in accounts if x["id"] == b.id)["reserved_bytes"] == req["size"]
    path = f"/api/uploads/{transfer['id']}/chunk"
    assert client.put(path, content=b"bad", headers={**headers, "Upload-Offset": "0"}).status_code == 400
    fake.lost_upload_response_once = True
    first = client.put(path, content=b"a" * 262144, headers={**headers, "Upload-Offset": "0"})
    assert first.status_code == 200 and first.json()["bytes_transferred"] == 262144
    mismatch = client.put(path, content=b"a" * 262144, headers={**headers, "Upload-Offset": "0"})
    assert mismatch.status_code in (400, 409)
    done = client.put(path, content=b"b", headers={**headers, "Upload-Offset": "262144"})
    assert done.status_code == 200 and done.json()["status"] == "completed"
    assert done.json()["result_file_id"]
    assert next(x for x in client.get("/api/accounts", headers=headers).json() if x["id"] == b.id)["reserved_bytes"] == 0
    assert client.post("/api/uploads", json={**req, "name": "too-big", "size": 3_000_000, "idempotency_key": "upload-2"}, headers=headers).status_code == 409
    assert client.post("/api/uploads", json={**req, "size": 0, "idempotency_key": "zero"}, headers=headers).status_code == 422


def test_concurrent_allocation_cannot_overreserve(client, signup, add_account):
    headers = signup(); add_account(headers, limit=1000, usage=0)
    def create(i):
        return client.post("/api/uploads", json={"name": f"f{i}", "size": 700, "idempotency_key": f"key{i}"}, headers=headers).status_code
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(create, range(2)))
    assert sorted(results) == [201, 409]


def test_cancel_upload_access_control_and_disconnect(client, signup, add_account):
    headers = signup(); other = signup("other@example.com"); a = add_account(headers)
    t = client.post("/api/uploads", json={"name": "file", "size": 7, "account_id": a.id, "idempotency_key": "abc"}, headers=headers).json()
    assert client.get(f"/api/transfers/{t['id']}", headers=other).status_code == 404
    assert client.post(f"/api/transfers/{t['id']}/cancel", headers=other).status_code == 404
    assert client.delete(f"/api/accounts/{a.id}", headers=headers).status_code == 409
    assert client.post(f"/api/transfers/{t['id']}/cancel", headers=headers).json()["status"] == "cancelled"
    assert client.delete(f"/api/accounts/{a.id}", headers=headers).status_code == 204


def test_backend_copy_retry_and_checksum(client, signup, add_account, fake):
    headers = signup(); source = add_account(headers, "source@example.com"); target = add_account(headers, "target@example.com")
    fake.add_file(source, "source_id", "report.txt", b"copy this file")
    fake.fail_upload_once = True
    body = {"source_account_id": source.id, "source_file_id": "source_id", "destination_account_id": target.id, "idempotency_key": "copy-1"}
    response = client.post("/api/transfers/copy", json=body, headers=headers)
    assert response.status_code == 201, response.text
    tid = response.json()["id"]
    failed = client.get(f"/api/transfers/{tid}", headers=headers).json()
    assert failed["status"] == "failed"
    assert client.post(f"/api/transfers/{tid}/retry", headers=headers).status_code == 200
    completed = client.get(f"/api/transfers/{tid}", headers=headers).json()
    assert completed["status"] == "completed" and completed["checksum_verified"] is True
    assert fake.items[target.id][completed["result_file_id"]]["data"] == b"copy this file"
    assert client.post("/api/transfers/copy", json=body, headers=headers).json()["id"] == tid
    assert client.post(f"/api/transfers/{tid}/retry", headers=headers).status_code == 409


def test_download_stream_and_history(client, signup, add_account, fake):
    headers = signup(); account = add_account(headers)
    fake.add_file(account, "file1", "hello.txt", b"the actual file")
    response = client.get(f"/api/files/{account.id}/file1/download", headers=headers)
    assert response.status_code == 200 and response.content == b"the actual file"
    tid = response.headers["X-Transfer-Id"]
    transfer = client.get(f"/api/transfers/{tid}", headers=headers).json()
    assert transfer["status"] == "completed" and transfer["bytes_transferred"] == len(response.content)


def test_expired_google_credentials_and_remote_error_are_safe(client, signup, add_account, fake):
    headers = signup(); account = add_account(headers)
    fake.fail_quota.add(account.id)
    result = client.post("/api/accounts/refresh", headers=headers)
    assert result.json()[0]["quota_available"] is False
    assert "secret-refresh" not in result.text and "secret-access" not in result.text
    invalid = client.post("/api/files/folders", json={"account_id": account.id, "name": "x", "parent_id": "../../bad"}, headers=headers)
    assert invalid.status_code == 400
