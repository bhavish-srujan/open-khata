import uuid
import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from tests.test_users_and_businesses import create_test_user_and_token


@pytest.mark.asyncio
async def test_party_crud_and_idempotency(client: AsyncClient, test_db: AsyncSession):
    user, business, token = await create_test_user_and_token(test_db)
    headers = {"Authorization": f"Bearer {token}"}

    client_party_id = str(uuid.uuid4())

    # 1. Create party
    res = await client.post(
        "/api/v1/parties",
        headers=headers,
        json={
            "id": client_party_id,
            "name": "  john doe  ",
            "phone": "+919876543210",
            "type": "CUSTOMER",
            "collectionFrequency": "WEEKLY",
        },
    )
    assert res.status_code == 200
    party = res.json()
    assert party["id"] == client_party_id
    assert party["name"] == "JOHN DOE"  # Canonical uppercase normalized
    assert party["phone"] == "+919876543210"
    assert party["collectionFrequency"] == "WEEKLY"
    assert party["currentBalance"] == "0.00"

    # 2. Replay with same id returns existing party without duplicate insertion
    replay_res = await client.post(
        "/api/v1/parties",
        headers=headers,
        json={
            "id": client_party_id,
            "name": "Different Name",
            "phone": "+919876543210",
            "type": "CUSTOMER",
        },
    )
    assert replay_res.status_code == 200
    assert replay_res.json()["name"] == "JOHN DOE"

    # 3. List parties
    list_res = await client.get("/api/v1/parties", headers=headers)
    assert list_res.status_code == 200
    parties = list_res.json()
    assert len(parties) == 1

    # 4. Filter parties by type
    cust_res = await client.get("/api/v1/parties?type=CUSTOMER", headers=headers)
    assert cust_res.status_code == 200
    assert len(cust_res.json()) == 1

    supp_res = await client.get("/api/v1/parties?type=SUPPLIER", headers=headers)
    assert supp_res.status_code == 200
    assert len(supp_res.json()) == 0

    # 5. Update interest
    interest_res = await client.patch(
        f"/api/v1/parties/{client_party_id}/interest",
        headers=headers,
        json={
            "interestEnabled": True,
            "interestRate": 5.5,
            "interestPeriodValue": 30,
            "interestPeriodUnit": "DAYS",
        },
    )
    assert interest_res.status_code == 200
    assert interest_res.json()["interestEnabled"] is True
    assert interest_res.json()["interestRate"] == "5.500"

    # 6. Update collection frequency
    freq_res = await client.patch(
        f"/api/v1/parties/{client_party_id}/collection-frequency",
        headers=headers,
        json={"collectionFrequency": "MONTHLY"},
    )
    assert freq_res.status_code == 200
    assert freq_res.json()["collectionFrequency"] == "MONTHLY"

    # 7. Delete party
    del_res = await client.delete(f"/api/v1/parties/{client_party_id}", headers=headers)
    assert del_res.status_code == 204

    # 8. Get party after delete returns 404
    get_res = await client.get(f"/api/v1/parties/{client_party_id}", headers=headers)
    assert get_res.status_code == 404
