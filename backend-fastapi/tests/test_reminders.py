import uuid
from decimal import Decimal
from unittest.mock import AsyncMock

import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.party import Party
from app.services.reminders import run_daily_reminders
from app.services.sms import sms_service
from tests.test_users_and_businesses import create_test_user_and_token


@pytest.mark.asyncio
async def test_reminders_security_and_cooldown(
    client: AsyncClient, test_db: AsyncSession, monkeypatch
):
    # Setup business A
    user_owner, business_a, token_owner = await create_test_user_and_token(
        test_db, phone="+919876543260", role="OWNER"
    )
    headers_owner = {"Authorization": f"Bearer {token_owner}"}

    # Setup cashier in business A
    user_cashier, _, token_cashier = await create_test_user_and_token(
        test_db,
        phone="+919876543261",
        business_id=business_a.id,
        role="CASHIER",
    )
    headers_cashier = {"Authorization": f"Bearer {token_cashier}"}

    # Setup business B (different tenant)
    user_b, business_b, token_b = await create_test_user_and_token(
        test_db, phone="+919876543262", role="OWNER"
    )
    headers_b = {"Authorization": f"Bearer {token_b}"}

    # Create party in business A with phone and balance
    party_a = Party(
        id=uuid.uuid4(),
        business_id=business_a.id,
        name="Sunil Kirana",
        phone="+919876543263",
        type="CUSTOMER",
        current_balance=Decimal("1500.00"),
    )
    # Party in business A without phone
    party_no_phone = Party(
        id=uuid.uuid4(),
        business_id=business_a.id,
        name="No Phone Customer",
        phone="",
        type="CUSTOMER",
        current_balance=Decimal("500.00"),
    )
    test_db.add_all([party_a, party_no_phone])
    await test_db.commit()

    # 1. CASHIER cannot send reminder (403)
    cashier_res = await client.post(
        "/api/v1/reminders/send",
        headers=headers_cashier,
        json={"partyId": str(party_a.id)},
    )
    assert cashier_res.status_code == 403

    # 2. Cross-tenant party rejection returns 404
    cross_res = await client.post(
        "/api/v1/reminders/send",
        headers=headers_b,
        json={"partyId": str(party_a.id)},
    )
    assert cross_res.status_code == 404

    # 3. Party without phone returns 400
    no_phone_res = await client.post(
        "/api/v1/reminders/send",
        headers=headers_owner,
        json={"partyId": str(party_no_phone.id)},
    )
    assert no_phone_res.status_code == 400
    assert "no phone number on file" in no_phone_res.json()["message"]

    # 4. When transactional provider is NOT configured, returns 503
    monkeypatch.setattr(sms_service, "is_transactional_configured", lambda: False)
    unavail_res = await client.post(
        "/api/v1/reminders/send",
        headers=headers_owner,
        json={"partyId": str(party_a.id)},
    )
    assert unavail_res.status_code == 503
    assert "no SMS provider is configured" in unavail_res.json()["message"]

    # 5. When transactional provider IS configured:
    monkeypatch.setattr(sms_service, "is_transactional_configured", lambda: True)
    monkeypatch.setattr(
        sms_service, "send_transactional_sms", AsyncMock(return_value=True)
    )

    success_res = await client.post(
        "/api/v1/reminders/send",
        headers=headers_owner,
        json={"partyId": str(party_a.id)},
    )
    assert success_res.status_code == 200
    assert success_res.json()["success"] is True

    # 6. Immediate retry triggers 429 per-party cooldown
    retry_res = await client.post(
        "/api/v1/reminders/send",
        headers=headers_owner,
        json={"partyId": str(party_a.id)},
    )
    assert retry_res.status_code == 429
    assert "already sent to this party recently" in retry_res.json()["message"]


@pytest.mark.asyncio
async def test_automated_daily_reminders(test_db: AsyncSession, monkeypatch):
    from app.config import settings

    monkeypatch.setattr(settings, "REMINDERS_ENABLED", True)
    monkeypatch.setattr(settings, "REMINDER_MIN_BALANCE", 100.0)

    # Mock send_transactional_sms
    mock_send = AsyncMock(return_value=True)
    monkeypatch.setattr(sms_service, "is_transactional_configured", lambda: True)
    monkeypatch.setattr(sms_service, "send_transactional_sms", mock_send)

    biz_id = uuid.uuid4()

    # Customer who owes >= 100 (qualifies)
    p1 = Party(
        id=uuid.uuid4(),
        business_id=biz_id,
        name="Customer Debtor",
        phone="+919876543270",
        type="CUSTOMER",
        current_balance=Decimal("250.00"),
    )
    # Customer who owes < 100 (below threshold)
    p2 = Party(
        id=uuid.uuid4(),
        business_id=biz_id,
        name="Small Balance",
        phone="+919876543271",
        type="CUSTOMER",
        current_balance=Decimal("50.00"),
    )
    # Supplier with positive balance (we owe them! Must NOT be reminded)
    p3 = Party(
        id=uuid.uuid4(),
        business_id=biz_id,
        name="Supplier Creditor",
        phone="+919876543272",
        type="SUPPLIER",
        current_balance=Decimal("500.00"),
    )
    test_db.add_all([p1, p2, p3])
    await test_db.commit()

    await run_daily_reminders(test_db)

    # Verify only Customer Debtor was messaged
    assert mock_send.call_count == 1
    call_args = mock_send.call_args[0]
    assert call_args[0] == "+919876543270"
    assert "Rs. 250" in call_args[1]
