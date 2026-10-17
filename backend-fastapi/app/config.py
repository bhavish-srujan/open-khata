from pathlib import Path
from typing import List, Optional
from urllib.parse import parse_qs, urlencode, urlparse, urlunparse
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    PORT: int = 3000
    NODE_ENV: str = "development"
    DATABASE_URL: str = "postgresql://postgres:password@localhost:5432/khatabook"
    DATABASE_SSL_CA: Optional[str] = None

    JWT_SECRET: str = "your_jwt_secret_here"

    # SMS gateway
    TEXTBEE_API_KEY: Optional[str] = None
    TEXTBEE_DEVICE_ID: Optional[str] = None
    TRANSACTIONAL_SMS_PROVIDER: Optional[str] = None
    SMS_GATEWAY_TIMEOUT_MS: Optional[int] = 5000

    # S3 Object Storage
    S3_BUCKET: Optional[str] = None
    S3_ACCESS_KEY_ID: Optional[str] = None
    S3_SECRET_ACCESS_KEY: Optional[str] = None
    S3_ENDPOINT: Optional[str] = None
    S3_REGION: str = "auto"
    S3_FORCE_PATH_STYLE: bool = False
    S3_SERVER_SIDE_ENCRYPTION: Optional[str] = None
    UPLOADS_DIR: str = "uploads"

    # CORS
    CORS_ORIGINS: str = ""

    # AI scanning
    GEMINI_API_KEY: Optional[str] = None
    AI_TIMEOUT_MS: int = 45000
    GEMINI_MODEL: str = "gemini-flash-lite-latest"

    # Reminders
    REMINDERS_ENABLED: bool = False
    REMINDER_MIN_BALANCE: float = 1.0

    # Self-ping for keeping free-tier hosts warm
    SELF_PING_URL: Optional[str] = None

    # Logging
    LOG_LEVEL: str = "INFO"
    LOG_FORMAT: str = "text"

    model_config = SettingsConfigDict(
        env_file=("../backend/.env", ".env"),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    @property
    def cors_origins_list(self) -> List[str]:
        if not self.CORS_ORIGINS:
            return []
        return [origin.strip() for origin in self.CORS_ORIGINS.split(",") if origin.strip()]

    @property
    def async_database_url(self) -> str:
        """
        Converts standard postgresql:// or postgres:// URL into postgresql+asyncpg://
        and strips parameters that asyncpg handles via connect_args (like sslmode).
        """
        url = self.DATABASE_URL.strip()
        parsed = urlparse(url)

        scheme = parsed.scheme
        if scheme in ("postgres", "postgresql"):
            scheme = "postgresql+asyncpg"

        query_params = parse_qs(parsed.query)
        # asyncpg does not support 'sslmode' in query params; ssl is passed via connect_args
        query_params.pop("sslmode", None)
        new_query = urlencode(query_params, doseq=True)

        return urlunparse((
            scheme,
            parsed.netloc,
            parsed.path,
            parsed.params,
            new_query,
            parsed.fragment,
        ))


settings = Settings()
