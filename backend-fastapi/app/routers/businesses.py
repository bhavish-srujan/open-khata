import uuid
from typing import List

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.auth.deps import CurrentUser, get_current_user
from app.database import get_db
from app.models.bill import Bill
from app.models.business import Business
from app.models.business_member import BusinessMember
from app.models.inventory import Item
from app.models.ledger import LedgerEntry
from app.models.party import Party
from app.models.receipt import Receipt
from app.models.user import UserRole
from app.schemas.business import (
    BusinessResponse,
    CreateBusinessDto,
    UpdateBusinessDto,
)

router = APIRouter(prefix="/businesses", tags=["businesses"])


def to_business_response(b: Business, role: str) -> BusinessResponse:
    return BusinessResponse(
        id=str(b.id),
        owner_id=str(b.owner_id),
        name=b.name,
        type=b.type,
        gst_number=b.gst_number,
        address=b.address,
        category=b.category,
        bank_account=b.bank_account,
        profile_image_url=b.profile_image_url,
        created_at=b.created_at,
        updated_at=b.updated_at,
        role=role,
    )


@router.post("", response_model=BusinessResponse)
async def create_business(
    dto: CreateBusinessDto,
    current_user: CurrentUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    user_uuid = uuid.UUID(current_user.id)
    business = Business(
        owner_id=user_uuid,
        name=dto.name,
        type=dto.type,
        gst_number=dto.gst_number,
        address=dto.address,
        category=dto.category,
        bank_account=dto.bank_account,
        profile_image_url=dto.profile_image_url,
    )
    db.add(business)
    await db.flush()

    member = BusinessMember(
        business_id=business.id,
        user_id=user_uuid,
        role=UserRole.OWNER,
    )
    db.add(member)
    await db.commit()
    await db.refresh(business)

    return to_business_response(business, UserRole.OWNER)


@router.get("", response_model=List[BusinessResponse])
async def find_all_businesses(
    current_user: CurrentUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    user_uuid = uuid.UUID(current_user.id)
    res = await db.execute(
        select(BusinessMember)
        .options(selectinload(BusinessMember.business))
        .where(BusinessMember.user_id == user_uuid)
        .order_by(BusinessMember.created_at.asc())
    )
    memberships = res.scalars().all()

    return [
        to_business_response(m.business, m.role)
        for m in memberships
        if m.business is not None
    ]


@router.get("/{id}", response_model=BusinessResponse)
async def find_one_business(
    id: str,
    current_user: CurrentUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    try:
        b_uuid = uuid.UUID(id)
        user_uuid = uuid.UUID(current_user.id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Business not found",
        )

    res = await db.execute(
        select(BusinessMember)
        .options(selectinload(BusinessMember.business))
        .where(
            BusinessMember.business_id == b_uuid,
            BusinessMember.user_id == user_uuid,
        )
    )
    membership = res.scalar_one_or_none()

    if not membership or not membership.business:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Business not found",
        )

    return to_business_response(membership.business, membership.role)


@router.put("/{id}", response_model=BusinessResponse)
async def update_business(
    id: str,
    dto: UpdateBusinessDto,
    current_user: CurrentUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    try:
        b_uuid = uuid.UUID(id)
        user_uuid = uuid.UUID(current_user.id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Business not found",
        )

    # findOne is owner-only for mutation
    res = await db.execute(
        select(Business).where(
            Business.id == b_uuid,
            Business.owner_id == user_uuid,
        )
    )
    business = res.scalar_one_or_none()
    if not business:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Business not found",
        )

    if dto.name is not None:
        business.name = dto.name
    if dto.type is not None:
        business.type = dto.type
    if dto.gst_number is not None:
        business.gst_number = dto.gst_number
    if dto.address is not None:
        business.address = dto.address
    if dto.category is not None:
        business.category = dto.category
    if dto.bank_account is not None:
        business.bank_account = dto.bank_account
    if dto.profile_image_url is not None:
        business.profile_image_url = dto.profile_image_url

    await db.commit()
    await db.refresh(business)

    return to_business_response(business, UserRole.OWNER)


@router.delete("/{id}")
async def delete_business(
    id: str,
    current_user: CurrentUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    try:
        b_uuid = uuid.UUID(id)
        user_uuid = uuid.UUID(current_user.id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Business not found",
        )

    res = await db.execute(
        select(Business).where(
            Business.id == b_uuid,
            Business.owner_id == user_uuid,
        )
    )
    business = res.scalar_one_or_none()
    if not business:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Business not found",
        )

    # Check referenced records across tables
    parties_count = (
        await db.execute(
            select(func.count(Party.id)).where(Party.business_id == b_uuid)
        )
    ).scalar_one()

    entries_count = (
        await db.execute(
            select(func.count(LedgerEntry.id)).where(
                LedgerEntry.business_id == b_uuid
            )
        )
    ).scalar_one()

    bills_count = (
        await db.execute(
            select(func.count(Bill.id)).where(Bill.business_id == b_uuid)
        )
    ).scalar_one()

    items_count = (
        await db.execute(
            select(func.count(Item.id)).where(Item.business_id == b_uuid)
        )
    ).scalar_one()

    receipts_count = (
        await db.execute(
            select(func.count(Receipt.id)).where(Receipt.business_id == b_uuid)
        )
    ).scalar_one()

    held = (
        parties_count
        + entries_count
        + bills_count
        + items_count
        + receipts_count
    )

    if held > 0:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=(
                f"This business still holds {held} record(s) — {parties_count} parties, "
                f"{entries_count} ledger entries, {bills_count} bills, {items_count} items, "
                f"{receipts_count} receipts. Remove them before deleting the business."
            ),
        )

    # Delete memberships and business
    del_members_res = await db.execute(
        select(BusinessMember).where(BusinessMember.business_id == b_uuid)
    )
    for m in del_members_res.scalars().all():
        await db.delete(m)

    await db.delete(business)
    await db.commit()

    return {}
