import ssl
import pytest

from app.database import is_localhost, build_database_ssl
from app.config import settings


def test_is_localhost():
    assert is_localhost("postgresql://postgres:password@localhost:5432/db") is True
    assert is_localhost("postgresql://postgres:password@127.0.0.1:5432/db") is True
    assert is_localhost("postgresql://postgres:password@db:5432/db") is True
    assert is_localhost("postgresql://postgres:password@aws.supabase.co:5432/db") is False


def test_build_database_ssl_localhost():
    # Localhost should not use SSL
    ctx = build_database_ssl("postgresql://postgres:password@localhost:5432/db")
    assert ctx is None


def test_build_database_ssl_remote_with_bundled_ca():
    # Remote DB resolves bundled CA
    ctx = build_database_ssl("postgresql://postgres:password@db.supabase.co:5432/postgres")
    assert isinstance(ctx, ssl.SSLContext)
    assert ctx.check_hostname is True
    assert ctx.verify_mode == ssl.CERT_REQUIRED


def test_build_database_ssl_remote_without_ca_fails(monkeypatch):
    monkeypatch.setattr(settings, "DATABASE_SSL_CA", None)
    # Patch resolve_ca_pem to return None
    import app.database
    monkeypatch.setattr(app.database, "resolve_ca_pem", lambda: None)

    with pytest.raises(ValueError, match="A remote DATABASE_URL requires a CA certificate"):
        build_database_ssl("postgresql://postgres:password@remote.host:5432/db")
