import asyncio
import re
import uuid
from decimal import Decimal
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.deps import CurrentUser, get_current_user, require_roles
from app.database import get_db
from app.models.bill import Bill
from app.models.business import Business
from app.models.ledger import BalanceHistory, LedgerEntry
from app.models.party import Party
from app.models.user import UserRole
from app.schemas.party import (
    CreatePartyDto,
    PartyResponse,
    UpdateCollectionFrequencyDto,
    UpdatePartyInterestDto,
)
from app.services.sms import sms_service

router = APIRouter(prefix="/api/v1/parties", tags=["parties"])


def normalize_party_name(raw: str) -> str:
    """Canonical uppercase format matching Android client's PartyNames.normalize."""
    return re.sub(r"\s+", " ", (raw or "").strip()).upper()


def to_party_response(p: Party) -> PartyResponse:
    return PartyResponse(
        id=str(p.id),
        name=p.name,
        phone=p.phone,
        type=p.type,
        gstin=p.gstin,
        current_balance=p.current_balance,
        business_id=str(p.business_id),
        collection_frequency=p.collection_frequency,
        interest_enabled=p.interest_enabled,
        interest_rate=p.interest_rate,
        interest_period_value=p.interest_period_value,
        interest_period_unit=p.interest_period_unit,
        created_at=p.created_at,
        updated_at=p.updated_at,
    )


async def find_existing_for_replay(
    db: AsyncSession, party_id: uuid.UUID, business_id: uuid.UUID
) -> Optional[Party]:
    res = await db.execute(select(Party).where(Party.id == party_id))
    existing = res.scalar_one_or_none()
    if not existing:
        return None
    if existing.business_id != business_id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Party id is already in use",
        )
    return existing


@router.get("", response_model=List[PartyResponse])
async def get_all_parties(
    type: Optional[str] = Query(None, pattern="^(CUSTOMER|SUPPLIER)$"),
    current_user: CurrentUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    b_uuid = uuid.UUID(current_user.business_id)
    stmt = select(Party).where(Party.business_id == b_uuid)
    if type:
        stmt = stmt.where(Party.type == type)
    stmt = stmt.order_by(Party.created_at.desc())

    res = await db.execute(stmt)
    parties = res.scalars().all()
    return [to_party_response(p) for p in parties]


@router.get("/{id}", response_model=PartyResponse)
async def get_party_by_id(
    id: str,
    current_user: CurrentUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    try:
        p_uuid = uuid.UUID(id)
        b_uuid = uuid.UUID(current_user.business_id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Party {id} not found in business {current_user.business_id}",
        )

    res = await db.execute(
        select(Party).where(Party.id == p_uuid, Party.business_id == b_uuid)
    )
    party = res.scalar_one_or_none()
    if not party:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Party {id} not found in business {current_user.business_id}",
        )

    return to_party_response(party)


@router.post("", response_model=PartyResponse)
async def create_party(
    dto: CreatePartyDto,
    current_user: CurrentUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    b_uuid = uuid.UUID(current_user.business_id)
    target_uuid = uuid.UUID(dto.id) if dto.id else uuid.uuid4()

    # Idempotent check
    if dto.id:
        existing = await find_existing_for_replay(db, target_uuid, b_uuid)
        if existing:
            return to_party_response(existing)

    party = Party(
        id=target_uuid,
        name=normalize_party_name(dto.name),
        phone=(dto.phone or "").strip(),
        type=dto.type,
        collection_frequency=dto.collection_frequency or "DAILY",
        gstin=dto.gstin,
        business_id=b_uuid,
        current_balance=Decimal("0.00"),
        interest_enabled=bool(dto.interest_enabled),
        interest_rate=dto.interest_rate if dto.interest_enabled else Decimal("0.000"),
        interest_period_value=dto.interest_period_value if dto.interest_enabled else 0,
        interest_period_unit=dto.interest_period_unit if dto.interest_enabled else "DAYS",
    )

    db.add(party)
    try:
        await db.commit()
        await db.refresh(party)
    except IntegrityError:
        await db.rollback()
        # Catch potential concurrent create unique violation
        if dto.id:
            existing = await find_existing_for_replay(db, target_uuid, b_uuid)
            if existing:
                return to_party_response(existing)
        raise

    # Transactional welcome SMS if phone is provided
    if party.phone:
        b_res = await db.execute(select(Business).where(Business.id == b_uuid))
        business = b_res.scalar_one_or_none()
        business_name = business.name.strip() if business and business.name else "your shop"

        msg = f"Welcome to {business_name}! Your Open Khata account has been created."
        asyncio.create_task(sms_service.send_otp_sms(party.phone, msg) if False else sms_service.send_otp_sms(party.phone, msg))

    return to_party_response(party)


@router.patch("/{id}/interest", response_model=PartyResponse)
async def update_party_interest(
    id: str,
    dto: UpdatePartyInterestDto,
    current_user: CurrentUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    try:
        p_uuid = uuid.UUID(id)
        b_uuid = uuid.UUID(current_user.business_id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Party {id} not found in business {current_user.business_id}",
        )

    res = await db.execute(
        select(Party).where(Party.id == p_uuid, Party.business_id == b_uuid)
    )
    party = res.scalar_one_or_none()
    if not party:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Party {id} not found in business {current_user.business_id}",
        )

    party.interest_enabled = dto.interest_enabled
    party.interest_rate = dto.interest_rate if dto.interest_enabled else Decimal("0.000")
    party.interest_period_value = dto.interest_period_value if dto.interest_enabled else 0
    party.interest_period_unit = dto.interest_period_unit if dto.interest_enabled else "DAYS"

    await db.commit()
    await db.refresh(party)
    return to_party_response(party)


@router.patch("/{id}/collection-frequency", response_model=PartyResponse)
async def update_collection_frequency(
    id: str,
    dto: UpdateCollectionFrequencyDto,
    current_user: CurrentUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    try:
        p_uuid = uuid.UUID(id)
        b_uuid = uuid.UUID(current_user.business_id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Party {id} not found in business {current_user.business_id}",
        )

    res = await db.execute(
        select(Party).where(Party.id == p_uuid, Party.business_id == b_uuid)
    )
    party = res.scalar_one_or_none()
    if not party:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Party {id} not found in business {current_user.business_id}",
        )

    party.collection_frequency = dto.collection_frequency
    await db.commit()
    await db.refresh(party)
    return to_party_response(party)


@router.delete("/{id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_party(
    id: str,
    current_user: CurrentUser = Depends(
        require_roles(UserRole.OWNER, UserRole.MANAGER)
    ),
    db: AsyncSession = Depends(get_db),
):
    try:
        p_uuid = uuid.UUID(id)
        b_uuid = uuid.UUID(current_user.business_id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Party {id} not found in business {current_user.business_id}",
        )

    # Initial check
    initial_res = await db.execute(
        select(Party).where(Party.id == p_uuid, Party.business_id == b_uuid)
    )
    if not initial_res.scalar_one_or_none():
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Party {id} not found in business {current_user.business_id}",
        )

    # In transaction with pessimistic row lock
    # Note: SQLite in tests doesn't support with_for_update() with postgres dialects,
    # so we conditionally apply with_for_update() based on dialect.
    lock_stmt = select(Party).where(Party.id == p_uuid, Party.business_id == b_uuid)
    if db.bind.dialect.name != "sqlite":
        lock_stmt = lock_stmt.with_for_update()

    party_res = await db.execute(lock_stmt)
    party = party_res.scalar_one_or_none()
    if not party:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Party {id} not found in business {current_user.business_id}",
        )

    # Delete bills for this party
    bills_res = await db.execute(
        select(Bill).where(Bill.party_id == p_uuid, Bill.business_id == b_uuid)
    )
    for bill in bills_res.scalars().all():
        await db.delete(bill)

    # Delete balance_history for this party
    bh_res = await db.execute(
        select(BalanceHistory).where(
            BalanceHistory.party_id == p_uuid, BalanceHistory.business_id == b_uuid
        )
    )
    for bh in bh_res.scalars().all():
        await db.delete(bh)

    # Delete ledger_entries for this party
    le_res = await db.execute(
        select(LedgerEntry).where(
            LedgerEntry.party_id == p_uuid, LedgerEntry.business_id == b_uuid
        )
    )
    for le in le_res.scalars().all():
        await db.delete(le)

    await db.delete(party)
    await db.commit()
    return None
