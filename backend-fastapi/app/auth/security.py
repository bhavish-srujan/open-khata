import hashlib
import hmac
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, Optional

import jwt

from app.config import settings

JWT_ALGORITHM = "HS256"
JWT_EXPIRATION_DAYS = 7


def create_access_token(claims: Dict[str, Any]) -> str:
    payload = claims.copy()
    expire = datetime.now(timezone.utc) + timedelta(days=JWT_EXPIRATION_DAYS)
    payload["exp"] = expire
    return jwt.encode(payload, settings.JWT_SECRET, algorithm=JWT_ALGORITHM)


def decode_access_token(token: str) -> Dict[str, Any]:
    return jwt.decode(token, settings.JWT_SECRET, algorithms=[JWT_ALGORITHM])


def hash_otp(otp: str) -> str:
    """One-way SHA-256 hash of an OTP."""
    return hashlib.sha256(otp.encode("utf-8")).hexdigest()


def timing_safe_equals(a: Optional[str], b: Optional[str]) -> bool:
    """Constant-time string comparison to prevent timing attacks."""
    if a is None or b is None:
        return False
    return hmac.compare_digest(a, b)


def normalize_phone_for_storage(value: str) -> str:
    """
    Normalises phone number for consistent DB storage and lookup.
    Handles Indian phone formats (10 digits, leading 0, 91, 091).
    """
    digits = "".join(ch for ch in (value or "") if ch.isdigit())
    if not digits:
        return ""
    if len(digits) == 10:
        return digits
    if len(digits) == 11 and digits.startswith("0"):
        return digits[1:]
    if len(digits) == 12 and digits.startswith("91"):
        return digits[2:]
    if len(digits) == 13 and digits.startswith("091"):
        return digits[3:]
    return digits
