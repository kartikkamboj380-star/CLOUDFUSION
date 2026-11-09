import base64
import hashlib
import hmac
import secrets
from datetime import timedelta

from cryptography.fernet import Fernet, InvalidToken
from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import delete
from sqlalchemy.orm import Session

from .config import get_settings
from .db import get_db, utcnow
from .models import LoginSession, User

bearer = HTTPBearer(auto_error=False)


def encrypt(value: str) -> str:
    return Fernet(get_settings().fernet_key.encode()).encrypt(value.encode()).decode()


def decrypt(value: str) -> str:
    try:
        return Fernet(get_settings().fernet_key.encode()).decrypt(value.encode()).decode()
    except InvalidToken as exc:
        # Do not disclose ciphertext or credential details.
        raise HTTPException(503, "Stored credentials cannot be decrypted; check server configuration") from exc


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(password.encode(), salt=salt, n=2**14, r=8, p=1, dklen=32)
    return "scrypt$16384$8$1$" + base64.urlsafe_b64encode(salt).decode() + "$" + base64.urlsafe_b64encode(digest).decode()


def verify_password(password: str, encoded: str) -> bool:
    try:
        algorithm, n, r, p, salt, expected = encoded.split("$")
        if algorithm != "scrypt" or (n, r, p) != ("16384", "8", "1"):
            return False
        digest = hashlib.scrypt(password.encode(), salt=base64.urlsafe_b64decode(salt), n=int(n), r=int(r), p=int(p), dklen=32)
        return hmac.compare_digest(digest, base64.urlsafe_b64decode(expected))
    except (ValueError, TypeError):
        return False


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def make_session(db: Session, user: User) -> str:
    db.execute(delete(LoginSession).where(LoginSession.expires_at <= utcnow()))
    token = secrets.token_urlsafe(32)
    db.add(LoginSession(token_hash=token_hash(token), user_id=user.id,
                        expires_at=utcnow() + timedelta(days=get_settings().session_days)))
    db.commit()
    return token


def current_user(credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
                 db: Session = Depends(get_db)) -> User:
    if not credentials or credentials.scheme.lower() != "bearer" or len(credentials.credentials) > 256:
        raise HTTPException(401, "Authentication required", headers={"WWW-Authenticate": "Bearer"})
    session = db.get(LoginSession, token_hash(credentials.credentials))
    if not session or session.expires_at <= utcnow():
        raise HTTPException(401, "Session expired or invalid", headers={"WWW-Authenticate": "Bearer"})
    user = db.get(User, session.user_id)
    if not user:
        raise HTTPException(401, "Session expired or invalid")
    return user
