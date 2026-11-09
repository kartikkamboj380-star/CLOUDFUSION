from functools import lru_cache

from cryptography.fernet import Fernet
from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str = "sqlite:///./cloudfusion.db"
    fernet_key: str
    google_client_id: str = ""
    google_client_secret: str = ""
    google_redirect_uri: str = "http://localhost:8000/api/oauth/callback"
    cors_origins: str = ""
    session_days: int = 30
    max_chunk_bytes: int = 8 * 1024 * 1024

    @field_validator("fernet_key")
    @classmethod
    def valid_key(cls, value: str) -> str:
        try:
            Fernet(value.encode())
        except Exception as exc:
            raise ValueError("FERNET_KEY must be a generated Fernet key") from exc
        return value


@lru_cache
def get_settings() -> Settings:
    return Settings()
