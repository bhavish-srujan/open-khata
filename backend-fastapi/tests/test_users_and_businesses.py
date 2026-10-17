import uuid
from typing import Optional
import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.security import create_access_token
from app.models.business import Business
from app.models.business_member import BusinessMember
from app.models.user import User, UserRole


async def create_test_user_and_token(
    db: AsyncSession,
    phone: str = "+919876543210",
    name: str = "Test Owner",
    role: str = "OWNER",
    business_id: Optional[uuid.UUID] = None,
):
    user = User(
        id=uuid.uuid4(),
        phone_number=phone,
        normalized_phone=phone.replace("+91", ""),
        name=name,
        role=UserRole(role) if role in UserRole.__members__ else UserRole.STAFF,
        session_version=1,
    )
    db.add(user)
    await db.flush()

    if business_id is None:
        business = Business(
            id=uuid.uuid4(),
            owner_id=user.id,
            name="Test Business",
            type="Retail",
        )
        db.add(business)
        await db.flush()
        b_id = business.id
    else:
        b_id = uuid.UUID(str(business_id))
        business = await db.get(Business, b_id)

    member = BusinessMember(
        id=uuid.uuid4(),
        business_id=b_id,
        user_id=user.id,
        role=role,
    )
    db.add(member)
    await db.commit()

    token = create_access_token({
        "sub": str(user.id),
        "phone": user.phone_number,
        "businessId": str(business.id),
        "role": role,
        "sessionVersion": user.session_version,
    })

    return user, business, token


@pytest.mark.asyncio
async def test_business_crud(client: AsyncClient, test_db: AsyncSession):
    user, business, token = await create_test_user_and_token(test_db)
    headers = {"Authorization": f"Bearer {token}"}

    # 1. Create additional business
    res = await client.post(
        "/businesses",
        headers=headers,
        json={"name": "Second Shop", "type": "Kirana"},
    )
    assert res.status_code == 200
    created = res.json()
    assert created["name"] == "Second Shop"
    assert created["type"] == "Kirana"
    assert created["role"] == "OWNER"

    # 2. List businesses
    list_res = await client.get("/businesses", headers=headers)
    assert list_res.status_code == 200
    businesses = list_res.json()
    assert len(businesses) == 2

    # 3. Get single business
    get_res = await client.get(f"/businesses/{created['id']}", headers=headers)
    assert get_res.status_code == 200
    assert get_res.json()["name"] == "Second Shop"

    # 4. Update business
    put_res = await client.put(
        f"/businesses/{created['id']}",
        headers=headers,
        json={"name": "Updated Shop Name"},
    )
    assert put_res.status_code == 200
    assert put_res.json()["name"] == "Updated Shop Name"

    # 5. Delete empty business
    del_res = await client.delete(f"/businesses/{created['id']}", headers=headers)
    assert del_res.status_code == 200


@pytest.mark.asyncio
async def test_staff_management(client: AsyncClient, test_db: AsyncSession):
    user, business, token = await create_test_user_and_token(test_db)
    headers = {"Authorization": f"Bearer {token}"}

    # 1. Add staff
    add_res = await client.post(
        "/users/staff",
        headers=headers,
        json={
            "phoneNumber": "+919111122222",
            "name": "Cashier Bob",
            "role": "CASHIER",
        },
    )
    assert add_res.status_code == 200
    staff_data = add_res.json()
    assert staff_data["name"] == "Cashier Bob"
    assert staff_data["role"] == "CASHIER"
    membership_id = staff_data["membershipId"]

    # 2. Get staff
    staff_list_res = await client.get("/users/staff", headers=headers)
    assert staff_list_res.status_code == 200
    staff_members = staff_list_res.json()
    assert any(s["name"] == "Cashier Bob" for s in staff_members)

    # 3. Cannot add owner as staff
    owner_attempt = await client.post(
        "/users/staff",
        headers=headers,
        json={
            "phoneNumber": "+919333344444",
            "name": "Co-owner",
            "role": "OWNER",
        },
    )
    assert owner_attempt.status_code == 400

    # 4. Remove staff
    del_res = await client.delete(f"/users/staff/{membership_id}", headers=headers)
    assert del_res.status_code == 204


@pytest.mark.asyncio
async def test_update_me(client: AsyncClient, test_db: AsyncSession):
    user, business, token = await create_test_user_and_token(test_db)
    headers = {"Authorization": f"Bearer {token}"}

    res = await client.put(
        "/users/me",
        headers=headers,
        json={"name": "New Owner Name"},
    )
    assert res.status_code == 200
    assert res.json()["name"] == "New Owner Name"
