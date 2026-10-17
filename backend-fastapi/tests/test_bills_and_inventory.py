import uuid
from decimal import Decimal
import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from tests.test_users_and_businesses import create_test_user_and_token


@pytest.mark.asyncio
async def test_bills_full_lifecycle(client: AsyncClient, test_db: AsyncSession):
    # Setup business A
    user_a, business_a, token_a = await create_test_user_and_token(test_db, phone="+919876543210")
    headers_a = {"Authorization": f"Bearer {token_a}"}

    # Setup business B (different tenant)
    user_b, business_b, token_b = await create_test_user_and_token(test_db, phone="+919876543211")
    headers_b = {"Authorization": f"Bearer {token_b}"}

    # Create party in business A
    party_a_id = str(uuid.uuid4())
    res_p = await client.post(
        "/api/v1/parties",
        headers=headers_a,
        json={
            "id": party_a_id,
            "name": "Customer A",
            "phone": "+919876543210",
            "type": "CUSTOMER",
        },
    )
    assert res_p.status_code == 200

    # 1. Valid bill creation
    client_bill_id = str(uuid.uuid4())
    bill_payload = {
        "id": client_bill_id,
        "partyId": party_a_id,
        "totalAmount": 1250.50,
        "items": [
            {"description": "Rice 5kg", "quantity": 2, "price": 500.0, "tax": 5.0},
            {"description": "Oil 1L", "quantity": 1, "price": 250.50},
        ],
        "isGst": True,
    }
    res = await client.post("/bills", headers=headers_a, json=bill_payload)
    assert res.status_code == 201
    bill = res.json()
    assert bill["id"] == client_bill_id
    assert bill["partyId"] == party_a_id
    assert bill["totalAmount"] == "1250.50"
    assert bill["isGst"] is True
    assert len(bill["items"]) == 2
    # Critical contract check: Retrofit Gson expects created_at in snake_case
    assert "created_at" in bill
    assert "createdAt" not in bill

    # 2. Idempotent replay with same id returns existing row
    replay_res = await client.post(
        "/bills",
        headers=headers_a,
        json={
            "id": client_bill_id,
            "partyId": party_a_id,
            "totalAmount": 9999.00,
            "items": [{"description": "Different", "quantity": 1, "price": 9999.00}],
        },
    )
    assert replay_res.status_code == 201
    assert replay_res.json()["totalAmount"] == "1250.50"

    # 3. Cross-tenant replay returns 409 conflict
    cross_res = await client.post(
        "/bills",
        headers=headers_b,
        json={
            "id": client_bill_id,
            "partyId": party_a_id,
            "totalAmount": 100.00,
            "items": [{"description": "Item", "quantity": 1, "price": 100.0}],
        },
    )
    assert cross_res.status_code == 409
    assert cross_res.json()["statusCode"] == 409
    assert "already in use" in cross_res.json()["message"]

    # 4. Cross-tenant party rejection returns 404
    foreign_bill_res = await client.post(
        "/bills",
        headers=headers_b,
        json={
            "partyId": party_a_id,  # Party belongs to A, not B
            "totalAmount": 200.00,
            "items": [{"description": "Item", "quantity": 1, "price": 200.0}],
        },
    )
    assert foreign_bill_res.status_code == 404
    assert foreign_bill_res.json()["statusCode"] == 404

    # 5. Validation failures return 400 with Nest error shape
    bad_res_zero = await client.post(
        "/bills",
        headers=headers_a,
        json={
            "partyId": party_a_id,
            "totalAmount": 0,
            "items": [{"description": "Item", "quantity": 1, "price": 0}],
        },
    )
    assert bad_res_zero.status_code == 400
    assert bad_res_zero.json()["statusCode"] == 400
    assert isinstance(bad_res_zero.json()["message"], str)

    bad_res_forbidden = await client.post(
        "/bills",
        headers=headers_a,
        json={
            "partyId": party_a_id,
            "totalAmount": 100.00,
            "items": [{"description": "Item", "quantity": 1, "price": 100.0, "isAdmin": True}],
        },
    )
    assert bad_res_forbidden.status_code == 400

    # 6. List bills
    list_res = await client.get("/bills", headers=headers_a)
    assert list_res.status_code == 200
    bills = list_res.json()
    assert len(bills) == 1
    assert bills[0]["id"] == client_bill_id

    # 7. Get bill by id
    get_res = await client.get(f"/bills/{client_bill_id}", headers=headers_a)
    assert get_res.status_code == 200
    assert get_res.json()["id"] == client_bill_id

    # Non-existent bill returns 404
    get_404 = await client.get(f"/bills/{uuid.uuid4()}", headers=headers_a)
    assert get_404.status_code == 404


@pytest.mark.asyncio
async def test_inventory_crud_and_rbac(client: AsyncClient, test_db: AsyncSession):
    # Create owner and business
    user_owner, business, token_owner = await create_test_user_and_token(
        test_db, phone="+919876543220", role="OWNER"
    )
    headers_owner = {"Authorization": f"Bearer {token_owner}"}

    # Create cashier in same business
    user_cashier, _, token_cashier = await create_test_user_and_token(
        test_db,
        phone="+919876543221",
        business_id=str(business.id),
        role="CASHIER",
    )
    headers_cashier = {"Authorization": f"Bearer {token_cashier}"}

    # 1. CASHIER cannot create item (403)
    cashier_create_res = await client.post(
        "/inventory",
        headers=headers_cashier,
        json={"name": "Atta 10kg", "sellingPrice": 350.0},
    )
    assert cashier_create_res.status_code == 403

    # 2. OWNER can create item (201)
    create_res = await client.post(
        "/inventory",
        headers=headers_owner,
        json={
            "name": "Basmati Rice 5kg",
            "sku": "RICE-BAS-5",
            "sellingPrice": 450.00,
            "purchasePrice": 380.00,
            "currentStock": 3.00,
            "lowStockThreshold": 5.00,
        },
    )
    assert create_res.status_code == 201
    item = create_res.json()
    assert item["name"] == "Basmati Rice 5kg"
    assert item["sku"] == "RICE-BAS-5"
    assert item["sellingPrice"] == "450.00"
    assert item["purchasePrice"] == "380.00"
    assert item["currentStock"] == "3.00"
    assert item["lowStockThreshold"] == "5.00"
    item_id = item["id"]

    # 3. Create normal stocked item
    create_res_2 = await client.post(
        "/inventory",
        headers=headers_owner,
        json={
            "name": "Sunflower Oil 1L",
            "sellingPrice": 140.00,
            "purchasePrice": 120.00,
            "currentStock": 20.00,
            "lowStockThreshold": 5.00,
        },
    )
    assert create_res_2.status_code == 201

    # 4. List items accessible to CASHIER and OWNER
    list_res = await client.get("/inventory", headers=headers_cashier)
    assert list_res.status_code == 200
    assert len(list_res.json()) == 2

    # 5. Low-stock endpoint returns only items with stock <= threshold
    low_res = await client.get("/inventory/low-stock", headers=headers_owner)
    assert low_res.status_code == 200
    low_items = low_res.json()
    assert len(low_items) == 1
    assert low_items[0]["name"] == "Basmati Rice 5kg"

    # 6. Update item
    update_res = await client.put(
        f"/inventory/{item_id}",
        headers=headers_owner,
        json={"currentStock": 10.00, "sellingPrice": 460.00},
    )
    assert update_res.status_code == 200
    updated = update_res.json()
    assert updated["currentStock"] == "10.00"
    assert updated["sellingPrice"] == "460.00"
    assert updated["name"] == "Basmati Rice 5kg"  # Preserved

    # Low-stock endpoint now returns 0 items
    low_res_after = await client.get("/inventory/low-stock", headers=headers_owner)
    assert low_res_after.status_code == 200
    assert len(low_res_after.json()) == 0

    # 7. CASHIER cannot delete item (403)
    del_cashier = await client.delete(f"/inventory/{item_id}", headers=headers_cashier)
    assert del_cashier.status_code == 403

    # 8. OWNER can delete item (200)
    del_owner = await client.delete(f"/inventory/{item_id}", headers=headers_owner)
    assert del_owner.status_code == 200

    # Fetching deleted item returns 404
    get_del = await client.get(f"/inventory/{item_id}", headers=headers_owner)
    assert get_del.status_code == 404
