import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.security import decode_access_token, hash_otp
from app.models.user import User, UserRole
from app.services.sms import sms_service


@pytest.mark.asyncio
async def test_request_otp_unconfigured_fails_503(client: AsyncClient, monkeypatch):
    monkeypatch.setattr(sms_service, "is_configured", lambda: False)
    res = await client.post(
        "/auth/request-otp",
        json={"phoneNumber": "+919876543210"},
    )
    assert res.status_code == 503
    data = res.json()
    assert data["statusCode"] == 503
    assert "OTP delivery is not configured" in data["message"]


@pytest.mark.asyncio
async def test_full_otp_and_login_flow(client: AsyncClient, test_db: AsyncSession, monkeypatch):
    monkeypatch.setattr(sms_service, "is_configured", lambda: True)
    sent_messages = []

    async def mock_send(phone, msg):
        sent_messages.append((phone, msg))
        return True

    monkeypatch.setattr(sms_service, "send_otp_sms", mock_send)

    phone = "+919876543210"

    # 1. Request OTP
    res = await client.post("/auth/request-otp", json={"phoneNumber": phone})
    assert res.status_code == 200
    assert res.json()["success"] is True
    assert len(sent_messages) == 1

    # Extract OTP from message
    import re
    match = re.search(r"\b(\d{6})\b", sent_messages[0][1])
    assert match is not None
    otp = match.group(1)

    # 2. Verify invalid OTP
    bad_res = await client.post(
        "/auth/verify-otp",
        json={"phoneNumber": phone, "otp": "000000"},
    )
    assert bad_res.status_code == 401
    assert bad_res.json()["statusCode"] == 401
    assert bad_res.json()["message"] == "Invalid OTP"

    # 3. Verify valid OTP
    good_res = await client.post(
        "/auth/verify-otp",
        json={"phoneNumber": phone, "otp": otp},
    )
    assert good_res.status_code == 200
    login_data = good_res.json()
    assert "access_token" in login_data
    assert login_data["is_new_user"] is True

    token = login_data["access_token"]
    payload = decode_access_token(token)
    assert payload["phone"] == phone
    assert "sub" in payload
    assert "businessId" in payload
    assert payload["role"] == "OWNER"
    assert payload["sessionVersion"] == 2

    # 4. Logout invalidates the token
    logout_res = await client.post(
        "/auth/logout",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert logout_res.status_code == 200
    assert logout_res.json()["success"] is True

    # 5. Using the old token after logout must fail with 401 session expired
    expired_res = await client.post(
        "/auth/logout",
        headers={"Authorization": f"Bearer {token}"},
    )
    assert expired_res.status_code == 401
    assert expired_res.json()["statusCode"] == 401
    assert "Session expired" in expired_res.json()["message"]


@pytest.mark.asyncio
async def test_otp_lockout_after_max_attempts(client: AsyncClient, test_db: AsyncSession, monkeypatch):
    monkeypatch.setattr(sms_service, "is_configured", lambda: True)
    monkeypatch.setattr(sms_service, "send_otp_sms", lambda p, m: True)

    phone = "+919999988888"
    await client.post("/auth/request-otp", json={"phoneNumber": phone})

    # Fail 4 times
    for _ in range(4):
        res = await client.post("/auth/verify-otp", json={"phoneNumber": phone, "otp": "000000"})
        assert res.status_code == 401
        assert res.json()["message"] == "Invalid OTP"

    # 5th failure burns code
    res5 = await client.post("/auth/verify-otp", json={"phoneNumber": phone, "otp": "000000"})
    assert res5.status_code == 401
    assert "Too many incorrect attempts" in res5.json()["message"]
