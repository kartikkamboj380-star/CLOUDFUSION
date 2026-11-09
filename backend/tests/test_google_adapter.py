from datetime import timedelta

import httpx
import pytest
from fastapi import HTTPException

from app import drive, oauth
from app.db import SessionLocal, utcnow
from app.models import Account
from app.security import decrypt


def test_refresh_token_invalid_grant_marks_account_for_reauthorization(client, signup, add_account, monkeypatch):
    headers = signup(); account = add_account(headers)
    monkeypatch.setattr(drive.httpx, "post", lambda *args, **kwargs: httpx.Response(400, json={"error": "invalid_grant"}))
    with SessionLocal() as db:
        a = db.get(Account, account.id)
        a.access_expires_at = utcnow() - timedelta(seconds=1); db.commit()
        with pytest.raises(drive.DriveFailure) as exc:
            drive.GoogleDrive(db, a).access_token()
        assert exc.value.status == 424
        assert db.get(Account, account.id).reauth_required is True


def test_refresh_token_success_stores_only_ciphertext(client, signup, add_account, monkeypatch):
    account = add_account(signup())
    monkeypatch.setattr(drive.httpx, "post", lambda *args, **kwargs: httpx.Response(200, json={"access_token": "fresh-access", "expires_in": 3600}))
    with SessionLocal() as db:
        a = db.get(Account, account.id)
        a.access_expires_at = utcnow() - timedelta(seconds=1); db.commit()
        assert drive.GoogleDrive(db, a).access_token() == "fresh-access"
        assert a.access_token_enc != "fresh-access" and decrypt(a.access_token_enc) == "fresh-access"


def test_google_upload_responses_validate_offset_and_session_host():
    partial = httpx.Response(308, headers={"Range": "bytes=0-262143"})
    assert drive.GoogleDrive._upload_result(partial, 300000) == {"offset": 262144, "done": False}
    assert drive.GoogleDrive._upload_result(httpx.Response(308), 10)["offset"] == 0
    with pytest.raises(drive.DriveFailure):
        drive.GoogleDrive._upload_result(httpx.Response(308, headers={"Range": "garbage"}), 10)
    with pytest.raises(drive.DriveFailure):
        drive.GoogleDrive._upload_result(httpx.Response(308, headers={"Range": "bytes=0-100"}), 10)
    assert drive.valid_session_url("https://www.googleapis.com/upload/drive/v3/files?upload_id=test")
    for bad in ("http://www.googleapis.com/", "https://googleapis.com.evil.example/", "https://googleapis.com@evil.example/", "https://googleapis.com:123/"):
        with pytest.raises(drive.DriveFailure):
            drive.valid_session_url(bad)


def test_google_quota_error_and_invalid_id_are_sanitized():
    response = httpx.Response(403, json={"error": {"errors": [{"reason": "storageQuotaExceeded"}]}})
    assert drive._error(response).status == 409
    with pytest.raises(drive.DriveFailure):
        drive.valid_id("../../private")


def test_oauth_rejects_unverified_google_email_and_missing_scope(monkeypatch):
    monkeypatch.setattr(oauth.httpx, "post", lambda *args, **kwargs: httpx.Response(200, json={"access_token": "secret", "scope": "openid email"}))
    with pytest.raises(HTTPException) as exc:
        oauth.exchange_code("code", "verifier")
    assert exc.value.status_code == 403
    monkeypatch.setattr(oauth.httpx, "post", lambda *args, **kwargs: httpx.Response(200, json={"access_token": "secret", "scope": " ".join(oauth.SCOPES)}))
    monkeypatch.setattr(oauth.httpx, "get", lambda *args, **kwargs: httpx.Response(200, json={"sub": "abc", "email": "x@example.com", "email_verified": False}))
    with pytest.raises(HTTPException) as exc:
        oauth.exchange_code("code", "verifier")
    assert exc.value.status_code == 400


def test_malformed_google_responses_are_sanitized(client, signup, add_account, monkeypatch):
    account = add_account(signup())
    with SessionLocal() as db:
        a = db.get(Account, account.id)
        google = drive.GoogleDrive(db, a)
        monkeypatch.setattr(google, "request", lambda *args, **kwargs: httpx.Response(200, json={"storageQuota": {"usage": "not-a-number"}}))
        with pytest.raises(drive.DriveFailure) as exc:
            google.quota()
        assert exc.value.status == 502 and "not-a-number" not in exc.value.message
    monkeypatch.setattr(oauth.httpx, "post", lambda *args, **kwargs: httpx.Response(200, content=b"not json"))
    with pytest.raises(HTTPException) as exc:
        oauth.exchange_code("code", "verifier")
    assert exc.value.status_code == 502


@pytest.mark.parametrize("reason,status,phrase", [("invalid_client", 503, "Web OAuth"), ("unauthorized_client", 503, "Android client"), ("invalid_grant", 400, "callback")])
def test_web_oauth_client_errors_are_actionable_and_sanitized(monkeypatch, reason, status, phrase):
    monkeypatch.setattr(oauth.httpx, "post", lambda *a, **k: httpx.Response(400, json={"error": reason, "error_description": "secret-should-not-leak"}))
    with pytest.raises(HTTPException) as exc:
        oauth.exchange_code("code", "verifier")
    assert exc.value.status_code == status
    assert phrase in exc.value.detail
    assert "secret-should-not-leak" not in exc.value.detail


def test_drive_api_disabled_error_is_actionable():
    error = drive._error(httpx.Response(403, json={"error": {"errors": [{"reason": "accessNotConfigured"}]}}))
    assert error.status == 403
    assert "enable" in error.message


def test_callback_access_denied_is_not_reported_as_success(client):
    result = client.get("/api/oauth/callback?state=some-state&error=access_denied")
    assert result.status_code == 403
    assert "test-user" in result.json()["detail"]
