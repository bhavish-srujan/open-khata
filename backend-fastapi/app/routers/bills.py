import uuid
from typing import List, Optional

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.deps import CurrentUser, get_current_user
from app.database import get_db
from app.models.audit_log import AuditAction
from app.models.bill import Bill
from app.models.party import Party
from app.schemas.bill import BillResponse, CreateBillDto
from app.services.audit import record_audit_log

router = APIRouter(prefix="/bills", tags=["bills"])


def to_bill_response(b: Bill) -> BillResponse:
    return BillResponse(
        id=str(b.id),
        party_id=str(b.party_id),
        business_id=str(b.business_id) if b.business_id else None,
        total_amount=b.total_amount,
        items=b.items,
        ledger_entry_id=str(b.ledger_entry_id) if b.ledger_entry_id else None,
        is_gst=b.is_gst,
        receipt_url=b.receipt_url,
        created_at=b.created_at,
        updated_at=b.updated_at,
    )


async def find_existing_for_replay(
    db: AsyncSession, bill_id: uuid.UUID, business_id: uuid.UUID
) -> Optional[Bill]:
    res = await db.execute(select(Bill).where(Bill.id == bill_id))
    existing = res.scalar_one_or_none()
    if not existing:
        return None
    if existing.business_id != business_id:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Bill id is already in use",
        )
    return existing


@router.post("", response_model=BillResponse, status_code=status.HTTP_201_CREATED)
async def create_bill(
    dto: CreateBillDto,
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
):
    business_uuid = uuid.UUID(current_user.business_id)
    bill_uuid = uuid.UUID(dto.id) if dto.id else None

    # Idempotent replay pre-check
    if bill_uuid:
        existing = await find_existing_for_replay(db, bill_uuid, business_uuid)
        if existing:
            return to_bill_response(existing)

    # partyId arrives from client, verify it belongs to caller's business
    party_uuid = uuid.UUID(dto.party_id)
    party_res = await db.execute(
        select(Party).where(Party.id == party_uuid, Party.business_id == business_uuid)
    )
    party = party_res.scalar_one_or_none()
    if not party:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Party {dto.party_id} not found in business",
        )

    # Convert line items to JSON-compatible structures
    items_payload = [
        {
            "description": item.description,
            "quantity": float(item.quantity),
            "price": float(item.price),
            **({"tax": float(item.tax)} if item.tax is not None else {}),
        }
        for item in dto.items
    ]

    bill = Bill(
        id=bill_uuid or uuid.uuid4(),
        business_id=business_uuid,
        party_id=party_uuid,
        total_amount=dto.total_amount,
        items=items_payload,
        is_gst=dto.is_gst or False,
    )
    db.add(bill)

    try:
        await record_audit_log(
            db=db,
            action=AuditAction.CREATE,
            entity_name="Bill",
            entity_id=str(bill.id),
            performed_by=current_user.id,
            new_state={"totalAmount": str(dto.total_amount), "partyId": str(party_uuid)},
        )
        await db.commit()
        await db.refresh(bill)
    except IntegrityError:
        await db.rollback()
        if bill_uuid:
            existing = await find_existing_for_replay(db, bill_uuid, business_uuid)
            if existing:
                return to_bill_response(existing)
        raise

    return to_bill_response(bill)


@router.get("", response_model=List[BillResponse])
async def list_bills(
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
):
    business_uuid = uuid.UUID(current_user.business_id)
    res = await db.execute(
        select(Bill).where(Bill.business_id == business_uuid).order_by(Bill.created_at.desc())
    )
    bills = res.scalars().all()
    return [to_bill_response(b) for b in bills]


@router.get("/{id}", response_model=BillResponse)
async def get_bill(
    id: str,
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
):
    try:
        bill_uuid = uuid.UUID(id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Bill not found",
        )

    business_uuid = uuid.UUID(current_user.business_id)
    res = await db.execute(
        select(Bill).where(Bill.id == bill_uuid, Bill.business_id == business_uuid)
    )
    bill = res.scalar_one_or_none()
    if not bill:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Bill not found",
        )
    return to_bill_response(bill)
