import os
import ssl
from pathlib import Path
from typing import AsyncGenerator, Optional
from urllib.parse import urlparse

from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.orm import DeclarativeBase

from app.config import settings


def is_localhost(database_url: str) -> bool:
    try:
        parsed = urlparse(database_url)
        hostname = parsed.hostname
        return hostname in ("localhost", "127.0.0.1", "::1", "db")
    except Exception:
        return False


def resolve_ca_pem() -> Optional[str]:
    configured = settings.DATABASE_SSL_CA
    if configured:
        configured = configured.strip()
        if "BEGIN CERTIFICATE" in configured:
            return configured
        ca_path = Path(configured)
        if ca_path.exists():
            return ca_path.read_text(encoding="utf-8")
        raise ValueError(
            "DATABASE_SSL_CA is set but is neither a PEM certificate nor a readable file path"
        )

    # Search candidates
    app_root = Path(__file__).resolve().parent.parent
    candidates = [
        Path.cwd() / "certs" / "supabase-root-2021.crt",
        app_root / "certs" / "supabase-root-2021.crt",
        app_root.parent / "backend" / "certs" / "supabase-root-2021.crt",
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate.read_text(encoding="utf-8")

    return None


def build_database_ssl(database_url: str) -> Optional[ssl.SSLContext]:
    if is_localhost(database_url):
        return None

    ca_pem = resolve_ca_pem()
    if not ca_pem:
        raise ValueError(
            "A remote DATABASE_URL requires a CA certificate. Provide DATABASE_SSL_CA "
            "or restore certs/supabase-root-2021.crt."
        )

    ssl_ctx = ssl.create_default_context(cadata=ca_pem)
    ssl_ctx.check_hostname = True
    ssl_ctx.verify_mode = ssl.CERT_REQUIRED
    return ssl_ctx


class Base(DeclarativeBase):
    pass


def get_engine():
    connect_args: dict = {
        # Critical for Supabase transaction pooler (pgbouncer): disables statement caching
        "statement_cache_size": 0,
    }

    ssl_ctx = build_database_ssl(settings.DATABASE_URL)
    if ssl_ctx is not None:
        connect_args["ssl"] = ssl_ctx

    return create_async_engine(
        settings.async_database_url,
        connect_args=connect_args,
        pool_pre_ping=True,
        echo=(settings.NODE_ENV == "development"),
    )


engine = get_engine()
AsyncSessionLocal = async_sessionmaker(
    bind=engine,
    class_=AsyncSession,
    expire_on_commit=False,
    autocommit=False,
    autoflush=False,
)


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    async with AsyncSessionLocal() as session:
        yield session
