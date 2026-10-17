import uuid
from decimal import Decimal
import pytest
from httpx import AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.audit_log import AuditAction, AuditLog
from app.models.ledger import BalanceHistory, LedgerEntry
from tests.test_users_and_businesses import create_test_user_and_token


@pytest.mark.asyncio
async def test_ledger_complete_lifecycle_and_invariants(
    client: AsyncClient, test_db: AsyncSession
):
    user, business, token = await create_test_user_and_token(test_db)
    headers = {"Authorization": f"Bearer {token}"}

    # 1. Create a customer party
    party_res = await client.post(
        "/api/v1/parties",
        headers=headers,
        json={"name": "Alice Customer", "phone": "+919876543210", "type": "CUSTOMER"},
    )
    assert party_res.status_code == 200
    party_id = party_res.json()["id"]

    entry_id = str(uuid.uuid4())

    # 2. Create GAVE entry of 500.00
    gave_res = await client.post(
        "/api/v1/ledger",
        headers=headers,
        json={
            "id": entry_id,
            "partyId": party_id,
            "amount": 500.00,
            "type": "GAVE",
            "note": "Goods on credit",
        },
    )
    assert gave_res.status_code == 200
    entry_data = gave_res.json()
    assert entry_data["id"] == entry_id
    assert entry_data["amount"] == "500.00"

    # Check party balance moved to 500.00
    p_check = await client.get(f"/api/v1/parties/{party_id}", headers=headers)
    assert p_check.json()["currentBalance"] == "500.00"

    # 3. IDEMPOTENT REPLAY: Replaying the same entry id returns the entry and leaves balance alone
    replay_res = await client.post(
        "/api/v1/ledger",
        headers=headers,
        json={
            "id": entry_id,
            "partyId": party_id,
            "amount": 500.00,
            "type": "GAVE",
        },
    )
    assert replay_res.status_code == 200
    p_check2 = await client.get(f"/api/v1/parties/{party_id}", headers=headers)
    assert p_check2.json()["currentBalance"] == "500.00"  # Did NOT become 1000.00!

    # 4. Check BalanceHistory created
    bh_res = await test_db.execute(
        select(BalanceHistory).where(BalanceHistory.ledger_entry_id == uuid.UUID(entry_id))
    )
    history_rows = bh_res.scalars().all()
    assert len(history_rows) == 1
    assert history_rows[0].previous_balance == Decimal("0.00")
    assert history_rows[0].new_balance == Decimal("500.00")

    # 5. Check AuditLog created for entry
    audit_res = await test_db.execute(
        select(AuditLog).where(AuditLog.entity_id == entry_id)
    )
    audit_rows = audit_res.scalars().all()
    assert len(audit_rows) == 1
    assert audit_rows[0].action == AuditAction.CREATE.value
    assert audit_rows[0].performed_by == str(user.id)

    # 6. Update entry amount from 500 to 700
    patch_res = await client.patch(
        f"/api/v1/ledger/{entry_id}",
        headers=headers,
        json={"amount": 700.00, "note": "Updated note"},
    )
    assert patch_res.status_code == 200
    assert patch_res.json()["amount"] == "700.00"

    p_check3 = await client.get(f"/api/v1/parties/{party_id}", headers=headers)
    assert p_check3.json()["currentBalance"] == "700.00"

    # 7. Create GOT entry of 200.00
    got_res = await client.post(
        "/api/v1/ledger",
        headers=headers,
        json={
            "partyId": party_id,
            "amount": 200.00,
            "type": "GOT",
            "note": "Partial cash repayment",
        },
    )
    assert got_res.status_code == 200
    p_check4 = await client.get(f"/api/v1/parties/{party_id}", headers=headers)
    assert p_check4.json()["currentBalance"] == "500.00"

    # 8. Feed query by party and business
    party_feed = await client.get(f"/api/v1/ledger/party/{party_id}", headers=headers)
    assert party_feed.status_code == 200
    assert len(party_feed.json()) == 2

    biz_feed = await client.get("/api/v1/ledger", headers=headers)
    assert biz_feed.status_code == 200
    assert len(biz_feed.json()) == 2

    # 9. Delete entry reverses the delta and detaches history rows
    del_res = await client.delete(f"/api/v1/ledger/{entry_id}", headers=headers)
    assert del_res.status_code == 204

    # Balance 500 - 700 = -200 (in credit)
    p_check5 = await client.get(f"/api/v1/parties/{party_id}", headers=headers)
    assert p_check5.json()["currentBalance"] == "-200.00"

    # Verify history rows for entry_id are detached (ledger_entry_id is NULL)
    detached_res = await test_db.execute(
        select(BalanceHistory).where(BalanceHistory.party_id == uuid.UUID(party_id))
    )
    all_histories = detached_res.scalars().all()
    # All rows that were linked to entry_id must now have ledger_entry_id None
    assert all(h.ledger_entry_id != uuid.UUID(entry_id) for h in all_histories)
