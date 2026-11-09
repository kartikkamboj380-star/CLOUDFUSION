import base64
import hashlib
import secrets
from datetime import timedelta
from urllib.parse import urlencode

import httpx
from fastapi import HTTPException
from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from .config import get_settings
from .db import utcnow
from .models import Account, OAuthState, User
from .security import decrypt, encrypt, token_hash

SCOPES = ["openid", "https://www.googleapis.com/auth/userinfo.email", "https://www.googleapis.com/auth/drive"]


def start_oauth(db: Session, user: User) -> str:
    settings = get_settings()
    if not settings.google_client_id or not settings.google_client_secret:
        raise HTTPException(503, "Google OAuth is not configured on the server")
    db.execute(delete(OAuthState).where(OAuthState.expires_at <= utcnow()))
    state = secrets.token_urlsafe(32)
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    db.add(OAuthState(state_hash=token_hash(state), user_id=user.id,
                      expires_at=utcnow() + timedelta(minutes=10), verifier_enc=encrypt(verifier)))
    db.commit()
    params = {"client_id": settings.google_client_id, "redirect_uri": settings.google_redirect_uri,
              "response_type": "code", "scope": " ".join(SCOPES), "state": state,
              "access_type": "offline", "prompt": "consent select_account",
              "code_challenge": challenge, "code_challenge_method": "S256"}
    return "https://accounts.google.com/o/oauth2/v2/auth?" + urlencode(params)


def exchange_code(code: str, verifier: str) -> tuple[dict, dict]:
    settings = get_settings()
    try:
        response = httpx.post("https://oauth2.googleapis.com/token", data={
            "code": code, "client_id": settings.google_client_id, "client_secret": settings.google_client_secret,
            "redirect_uri": settings.google_redirect_uri, "grant_type": "authorization_code", "code_verifier": verifier,
        }, timeout=30)
        if response.status_code != 200:
            raise HTTPException(400, "Google did not accept the authorization; try connecting again")
        tokens = response.json()
        if not isinstance(tokens, dict) or not tokens.get("access_token"):
            raise HTTPException(502, "Google returned an invalid authorization response")
        scopes = tokens.get("scope")
        if scopes and SCOPES[-1] not in scopes.split():
            raise HTTPException(403, "Full Google Drive access was not granted")
        profile_response = httpx.get("https://openidconnect.googleapis.com/v1/userinfo",
                                     headers={"Authorization": "Bearer " + tokens["access_token"]}, timeout=30)
        if profile_response.status_code != 200:
            raise HTTPException(502, "Could not verify the Google account identity")
        profile = profile_response.json()
        if not isinstance(profile, dict) or not profile.get("sub") or not profile.get("email") or profile.get("email_verified") is not True:
            raise HTTPException(400, "Google did not provide a verified account email")
        return tokens, profile
    except httpx.HTTPError as exc:
        raise HTTPException(502, "Unable to reach Google authorization services") from exc
    except (ValueError, AttributeError, TypeError) as exc:
        raise HTTPException(502, "Google returned an invalid authorization response") from exc


def finish_oauth(db: Session, state: str, code: str) -> Account:
    # Atomic deletion makes a state single-use even with concurrent callbacks.
    record = db.execute(delete(OAuthState).where(OAuthState.state_hash == token_hash(state),
                                                OAuthState.expires_at > utcnow())
                        .returning(OAuthState.user_id, OAuthState.verifier_enc)).first()
    db.commit()
    if not record:
        raise HTTPException(400, "Authorization request expired or was already used; reconnect")
    user_id, verifier = record.user_id, decrypt(record.verifier_enc)
    tokens, profile = exchange_code(code, verifier)
    account = db.scalar(select(Account).where(Account.user_id == user_id, Account.google_sub == profile["sub"]))
    refresh = tokens.get("refresh_token")
    if not account and not refresh:
        raise HTTPException(400, "Google did not issue an offline refresh token; revoke the app in Google and reconnect")
    if not account:
        account = Account(user_id=user_id, google_sub=profile["sub"], email=profile["email"],
                          refresh_token_enc=encrypt(refresh))
        db.add(account)
    elif refresh:
        account.refresh_token_enc = encrypt(refresh)
    account.email = profile["email"]
    account.access_token_enc = encrypt(tokens["access_token"])
    try:
        expires_in = int(tokens.get("expires_in", 3600))
        if not 0 < expires_in <= 86400:
            raise ValueError("Invalid expiry")
    except (ValueError, TypeError, OverflowError) as exc:
        raise HTTPException(502, "Google returned an invalid authorization response") from exc
    account.access_expires_at = utcnow() + timedelta(seconds=expires_in)
    account.reauth_required = False
    db.commit()
    db.refresh(account)
    return account
