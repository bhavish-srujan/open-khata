import time
import uuid
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from typing import Optional

from fastapi import APIRouter, Depends, Query, Response
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.auth.deps import CurrentUser, get_current_user, require_roles
from app.database import get_db
from app.models.bill import Bill
from app.models.business import Business
from app.models.ledger import LedgerEntry
from app.models.party import Party
from app.services.pdf import generate_business_statement_pdf

router = APIRouter(prefix="/reports", tags=["reports"])


def _parse_dates(
    start_str: Optional[str], end_str: Optional[str]
) -> tuple[datetime, datetime]:
    now = datetime.now(timezone.utc)
    if end_str:
        try:
            end_dt = datetime.fromisoformat(end_str.replace("Z", "+00:00"))
        except Exception:
            end_dt = now
    else:
        end_dt = now

    if start_str:
        try:
            start_dt = datetime.fromisoformat(start_str.replace("Z", "+00:00"))
        except Exception:
            start_dt = end_dt - timedelta(days=30)
    else:
        start_dt = end_dt - timedelta(days=30)

    # Convert to naive UTC to match database timestamps
    if start_dt.tzinfo is not None:
        start_dt = start_dt.astimezone(timezone.utc).replace(tzinfo=None)
    if end_dt.tzinfo is not None:
        end_dt = end_dt.astimezone(timezone.utc).replace(tzinfo=None)

    return start_dt, end_dt


@router.get(
    "/cashflow",
    dependencies=[Depends(require_roles("OWNER", "MANAGER"))],
)
async def get_cashflow(
    start_date: Optional[str] = Query(None, alias="startDate"),
    end_date: Optional[str] = Query(None, alias="endDate"),
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
):
    business_uuid = uuid.UUID(current_user.business_id)
    start_dt, end_dt = _parse_dates(start_date, end_date)

    # Reporting windows are business dates (transaction_date), falling back to created_at
    stmt = select(LedgerEntry).where(
        LedgerEntry.business_id == business_uuid,
        LedgerEntry.transaction_date >= start_dt,
        LedgerEntry.transaction_date <= end_dt,
    )
    res = await db.execute(stmt)
    entries = res.scalars().all()

    total_in = Decimal("0.00")
    total_out = Decimal("0.00")

    for entry in entries:
        if entry.type == "GOT":
            total_in += entry.amount
        elif entry.type == "GAVE":
            total_out += entry.amount

    net = total_in - total_out
    return {
        "totalIn": float(total_in),
        "totalOut": float(total_out),
        "net": float(net),
    }


@router.get(
    "/sales",
    dependencies=[Depends(require_roles("OWNER", "MANAGER"))],
)
async def get_sales(
    start_date: Optional[str] = Query(None, alias="startDate"),
    end_date: Optional[str] = Query(None, alias="endDate"),
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
):
    business_uuid = uuid.UUID(current_user.business_id)
    start_dt, end_dt = _parse_dates(start_date, end_date)

    stmt = select(Bill).where(
        Bill.business_id == business_uuid,
        Bill.created_at >= start_dt,
        Bill.created_at <= end_dt,
    )
    res = await db.execute(stmt)
    bills = res.scalars().all()

    total_sales = Decimal("0.00")
    gst_sales = Decimal("0.00")
    non_gst_sales = Decimal("0.00")

    for bill in bills:
        total_sales += bill.total_amount
        if bill.is_gst:
            gst_sales += bill.total_amount
        else:
            non_gst_sales += bill.total_amount

    return {
        "totalSales": float(total_sales),
        "gstSales": float(gst_sales),
        "nonGstSales": float(non_gst_sales),
    }


@router.get(
    "/statement/pdf",
    dependencies=[Depends(require_roles("OWNER", "MANAGER"))],
)
async def download_statement(
    party_id: Optional[str] = Query(None, alias="partyId"),
    start_date: Optional[str] = Query(None, alias="startDate"),
    end_date: Optional[str] = Query(None, alias="endDate"),
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
):
    business_uuid = uuid.UUID(current_user.business_id)
    start_dt, end_dt = _parse_dates(start_date, end_date)

    stmt = (
        select(LedgerEntry)
        .options(selectinload(LedgerEntry.party))
        .where(
            LedgerEntry.business_id == business_uuid,
            LedgerEntry.transaction_date >= start_dt,
            LedgerEntry.transaction_date <= end_dt,
        )
        .order_by(LedgerEntry.transaction_date.asc())
    )

    if party_id:
        try:
            p_uuid = uuid.UUID(party_id)
            stmt = stmt.where(LedgerEntry.party_id == p_uuid)
        except ValueError:
            pass

    res = await db.execute(stmt)
    entries = list(res.scalars().all())

    party: Optional[Party] = None
    if entries and party_id:
        party = entries[0].party
    elif party_id:
        try:
            party = await db.get(Party, uuid.UUID(party_id))
        except ValueError:
            party = None

    biz = await db.get(Business, business_uuid)
    business_name = (biz.name.strip() if biz and biz.name else "Open Khata Business")

    pdf_bytes = generate_business_statement_pdf(
        business_name=business_name,
        party=party,
        entries=entries,
        start_date=start_dt,
        end_date=end_dt,
    )

    filename = f"statement-{int(time.time() * 1000)}.pdf"
    return Response(
        content=pdf_bytes,
        media_type="application/pdf",
        headers={
            "Content-Type": "application/pdf",
            "Content-Disposition": f'attachment; filename="{filename}"',
            "Content-Length": str(len(pdf_bytes)),
        },
    )
