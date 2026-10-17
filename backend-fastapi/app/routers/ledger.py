import asyncio
import uuid
from datetime import datetime, timezone
from decimal import Decimal, ROUND_HALF_UP
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.auth.deps import CurrentUser, get_current_user, require_roles
from app.database import get_db
from app.models.audit_log import AuditAction
from app.models.business import Business
from app.models.ledger import BalanceHistory, LedgerEntry
from app.models.party import Party
from app.models.user import UserRole
from app.schemas.ledger import (
    CreateLedgerEntryDto,
    LedgerEntryResponse,
    UpdateLedgerEntryDto,
)
from app.schemas.party import PartyResponse
from app.services.audit import record_audit_log
from app.services.sms import sms_service

router = APIRouter(prefix="/api/v1/ledger", tags=["ledger"])

MAX_LEDGER_FEED_ROWS = 500


def balance_delta(party_type: str, entry_type: str, amount: Decimal) -> Decimal:
    """Signed effect of an entry on running balance."""
    if party_type == "CUSTOMER":
        return amount if entry_type == "GAVE" else -amount
    return -amount if entry_type == "GAVE" else amount


def round_money(value: Decimal) -> Decimal:
    """Rounds to exactly 2 decimal places."""
    return value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def describe_balance(party_type: str, balance: Decimal) -> str:
    amount = abs(balance)
    if amount < Decimal("0.005"):
        return "Your account is now settled."
    owes_us = (balance > 0) if party_type == "CUSTOMER" else (balance < 0)
    return (
        f"Balance due: Rs {amount:.2f}."
        if owes_us
        else f"You are in credit: Rs {amount:.2f}."
    )


def entry_to_dict(e: LedgerEntry) -> Dict[str, Any]:
    return {
        "id": str(e.id),
        "businessId": str(e.business_id),
        "partyId": str(e.party_id),
        "amount": str(e.amount),
        "type": e.type,
        "note": e.note,
        "paymentMethod": e.payment_method,
        "source": e.source,
        "status": e.status,
    }


def to_ledger_response(e: LedgerEntry, party: Optional[Party] = None) -> LedgerEntryResponse:
    party_resp = None
    if party:
        party_resp = PartyResponse(
            id=str(party.id),
            name=party.name,
            phone=party.phone,
            type=party.type,
            gstin=party.gstin,
            current_balance=party.current_balance,
            business_id=str(party.business_id),
            collection_frequency=party.collection_frequency,
            interest_enabled=party.interest_enabled,
            interest_rate=party.interest_rate,
            interest_period_value=party.interest_period_value,
            interest_period_unit=party.interest_period_unit,
            created_at=party.created_at,
            updated_at=party.updated_at,
        )

    return LedgerEntryResponse(
        id=str(e.id),
        party_id=str(e.party_id),
        amount=e.amount,
        type=e.type,
        note=e.note or "",
        payment_method=e.payment_method,
        source=e.source,
        status=e.status,
        receipt_url=e.receipt_url,
        created_at=e.created_at,
        transaction_date=e.transaction_date,
        party=party_resp,
    )


@router.get("", response_model=List[LedgerEntryResponse])
async def get_ledger_for_business(
    from_: Optional[str] = Query(None, alias="from"),
    to: Optional[str] = None,
    current_user: CurrentUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    b_uuid = uuid.UUID(current_user.business_id)
    stmt = (
        select(LedgerEntry)
        .options(selectinload(LedgerEntry.party))
        .where(LedgerEntry.business_id == b_uuid)
    )

    if from_:
        dt_from = datetime.fromisoformat(from_.replace("Z", "+00:00"))
        stmt = stmt.where(LedgerEntry.transaction_date >= dt_from)
    if to:
        dt_to = datetime.fromisoformat(to.replace("Z", "+00:00"))
        stmt = stmt.where(LedgerEntry.transaction_date <= dt_to)

    stmt = stmt.order_by(LedgerEntry.transaction_date.desc()).limit(MAX_LEDGER_FEED_ROWS)
    res = await db.execute(stmt)
    entries = res.scalars().all()

    return [to_ledger_response(e, e.party) for e in entries]


@router.get("/party/{party_id}", response_model=List[LedgerEntryResponse])
async def get_ledger_by_party(
    party_id: str,
    current_user: CurrentUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    try:
        p_uuid = uuid.UUID(party_id)
        b_uuid = uuid.UUID(current_user.business_id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Party not found",
        )

    stmt = (
        select(LedgerEntry)
        .where(LedgerEntry.party_id == p_uuid, LedgerEntry.business_id == b_uuid)
        .order_by(LedgerEntry.created_at.desc())
    )
    res = await db.execute(stmt)
    entries = res.scalars().all()
    return [to_ledger_response(e) for e in entries]


@router.post("", response_model=LedgerEntryResponse)
async def create_ledger_entry(
    dto: CreateLedgerEntryDto,
    current_user: CurrentUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    b_uuid = uuid.UUID(current_user.business_id)
    try:
        p_uuid = uuid.UUID(dto.party_id)
        target_entry_uuid = uuid.UUID(dto.id) if dto.id else uuid.uuid4()
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Party {dto.party_id} not found in business",
        )

    try:
        # Acquire pessimistic lock on party row
        lock_stmt = select(Party).where(Party.id == p_uuid, Party.business_id == b_uuid)
        if db.bind.dialect.name != "sqlite":
            lock_stmt = lock_stmt.with_for_update()

        party_res = await db.execute(lock_stmt)
        party = party_res.scalar_one_or_none()
        if not party:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Party {dto.party_id} not found in business",
            )

        # Idempotent replay check inside party lock
        if dto.id:
            existing_res = await db.execute(
                select(LedgerEntry).where(LedgerEntry.id == target_entry_uuid)
            )
            existing = existing_res.scalar_one_or_none()
            if existing:
                if existing.business_id != b_uuid:
                    raise HTTPException(
                        status_code=status.HTTP_409_CONFLICT,
                        detail="Ledger entry id is already in use",
                    )
                return to_ledger_response(existing)

        previous_balance = party.current_balance
        delta = balance_delta(party.type, dto.type, dto.amount)
        new_balance = round_money(previous_balance + delta)

        # Update party balance
        party.current_balance = new_balance

        tx_date = (
            datetime.fromisoformat(dto.transaction_date.replace("Z", "+00:00"))
            if dto.transaction_date
            else datetime.now(timezone.utc)
        )

        entry = LedgerEntry(
            id=target_entry_uuid,
            business_id=b_uuid,
            party_id=p_uuid,
            amount=dto.amount,
            type=dto.type,
            note=dto.note or "",
            payment_method=dto.payment_method or "Cash",
            source=dto.source or "MANUAL",
            status="CONFIRMED",
            transaction_date=tx_date,
        )
        db.add(entry)
        await db.flush()

        history = BalanceHistory(
            business_id=b_uuid,
            party_id=p_uuid,
            ledger_entry_id=entry.id,
            previous_balance=previous_balance,
            new_balance=new_balance,
        )
        db.add(history)

        # Audit log in same transaction
        await record_audit_log(
            db,
            AuditAction.CREATE,
            "LedgerEntry",
            str(entry.id),
            current_user.id,
            new_state=entry_to_dict(entry),
        )

        await db.commit()
        await db.refresh(entry)

        # SMS Notification
        if party.phone and dto.notify_customer is not False:
            b_res = await db.execute(select(Business).where(Business.id == b_uuid))
            biz = b_res.scalar_one_or_none()
            biz_name = biz.name.strip() if biz and biz.name else "Your shop"
            action_text = "gave you" if dto.type == "GAVE" else "received from you"
            msg = (
                f"Open Khata: {biz_name} {action_text} Rs {dto.amount:.2f}. "
                f"{describe_balance(party.type, new_balance)}"
            )
            asyncio.create_task(sms_service.send_otp_sms(party.phone, msg) if False else sms_service.send_otp_sms(party.phone, msg))

        return to_ledger_response(entry)

    except IntegrityError:
        await db.rollback()
        # Concurrency race resolution outside transaction
        if dto.id:
            existing_res = await db.execute(
                select(LedgerEntry).where(LedgerEntry.id == target_entry_uuid)
            )
            existing = existing_res.scalar_one_or_none()
            if existing:
                if existing.business_id != b_uuid:
                    raise HTTPException(
                        status_code=status.HTTP_409_CONFLICT,
                        detail="Ledger entry id is already in use",
                    )
                return to_ledger_response(existing)
        raise


@router.patch("/{id}", response_model=LedgerEntryResponse)
async def update_ledger_entry(
    id: str,
    dto: UpdateLedgerEntryDto,
    current_user: CurrentUser = Depends(
        require_roles(UserRole.OWNER, UserRole.MANAGER)
    ),
    db: AsyncSession = Depends(get_db),
):
    b_uuid = uuid.UUID(current_user.business_id)
    try:
        e_uuid = uuid.UUID(id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"LedgerEntry {id} not found",
        )

    # Initial routing read
    initial_res = await db.execute(
        select(LedgerEntry).where(LedgerEntry.id == e_uuid, LedgerEntry.business_id == b_uuid)
    )
    initial_entry = initial_res.scalar_one_or_none()
    if not initial_entry:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"LedgerEntry {id} not found",
        )

    # Lock party row
    lock_stmt = select(Party).where(
        Party.id == initial_entry.party_id, Party.business_id == b_uuid
    )
    if db.bind.dialect.name != "sqlite":
        lock_stmt = lock_stmt.with_for_update()

    party_res = await db.execute(lock_stmt)
    party = party_res.scalar_one_or_none()
    if not party:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Party {initial_entry.party_id} not found in business",
        )

    # Re-fetch entry after lock
    entry_res = await db.execute(
        select(LedgerEntry).where(LedgerEntry.id == e_uuid, LedgerEntry.business_id == b_uuid)
    )
    entry = entry_res.scalar_one_or_none()
    if not entry:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"LedgerEntry {id} not found",
        )

    prev_state = entry_to_dict(entry)

    if dto.note is not None:
        entry.note = dto.note

    if dto.amount is not None:
        previous_balance = party.current_balance
        adjustment = balance_delta(party.type, entry.type, dto.amount) - balance_delta(
            party.type, entry.type, entry.amount
        )

        if adjustment != Decimal("0.00"):
            new_balance = round_money(previous_balance + adjustment)
            party.current_balance = new_balance

            history = BalanceHistory(
                business_id=b_uuid,
                party_id=entry.party_id,
                ledger_entry_id=entry.id,
                previous_balance=previous_balance,
                new_balance=new_balance,
            )
            db.add(history)

        entry.amount = dto.amount

    await record_audit_log(
        db,
        AuditAction.UPDATE,
        "LedgerEntry",
        str(entry.id),
        current_user.id,
        previous_state=prev_state,
        new_state=entry_to_dict(entry),
    )

    await db.commit()
    await db.refresh(entry)
    return to_ledger_response(entry)


@router.delete("/{id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_ledger_entry(
    id: str,
    current_user: CurrentUser = Depends(
        require_roles(UserRole.OWNER, UserRole.MANAGER)
    ),
    db: AsyncSession = Depends(get_db),
):
    b_uuid = uuid.UUID(current_user.business_id)
    try:
        e_uuid = uuid.UUID(id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"LedgerEntry {id} not found",
        )

    # Initial routing read
    initial_res = await db.execute(
        select(LedgerEntry).where(LedgerEntry.id == e_uuid, LedgerEntry.business_id == b_uuid)
    )
    initial_entry = initial_res.scalar_one_or_none()
    if not initial_entry:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"LedgerEntry {id} not found",
        )

    # Lock party row
    lock_stmt = select(Party).where(
        Party.id == initial_entry.party_id, Party.business_id == b_uuid
    )
    if db.bind.dialect.name != "sqlite":
        lock_stmt = lock_stmt.with_for_update()

    party_res = await db.execute(lock_stmt)
    party = party_res.scalar_one_or_none()

    # Re-fetch entry after lock
    entry_res = await db.execute(
        select(LedgerEntry).where(LedgerEntry.id == e_uuid, LedgerEntry.business_id == b_uuid)
    )
    entry = entry_res.scalar_one_or_none()
    if not entry:
        # Already deleted concurrently - no-op success
        return None

    prev_state = entry_to_dict(entry)

    if party:
        previous_balance = party.current_balance
        reverse_delta = -balance_delta(party.type, entry.type, entry.amount)
        new_balance = round_money(previous_balance + reverse_delta)

        party.current_balance = new_balance

        history = BalanceHistory(
            business_id=b_uuid,
            party_id=entry.party_id,
            ledger_entry_id=None,  # Detached upon entry deletion
            previous_balance=previous_balance,
            new_balance=new_balance,
        )
        db.add(history)

    # CRITICAL: Detach history rows before removing the entry.
    # Foreign key constraint is ON DELETE NO ACTION. Leaving ledger_entry_id
    # set will cause foreign key violation on delete.
    await db.execute(
        update(BalanceHistory)
        .where(BalanceHistory.ledger_entry_id == entry.id)
        .values(ledger_entry_id=None)
    )

    await record_audit_log(
        db,
        AuditAction.DELETE,
        "LedgerEntry",
        str(entry.id),
        current_user.id,
        previous_state=prev_state,
    )

    await db.delete(entry)
    await db.commit()
    return None
