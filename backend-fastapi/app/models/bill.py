import uuid
from datetime import datetime
from decimal import Decimal
from typing import Any, Optional

from sqlalchemy import Boolean, DateTime, ForeignKey, Index, Numeric, String
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base
from app.models.base import utc_now_naive


class Bill(Base):
    __tablename__ = "bills"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        default=uuid.uuid4,
    )

    # Note mixed naming in bills: camelCase businessId and partyId
    business_id: Mapped[uuid.UUID] = mapped_column(
        "businessId",
        UUID(as_uuid=True),
        ForeignKey("businesses.id", ondelete="NO ACTION"),
        nullable=False,
    )
    party_id: Mapped[uuid.UUID] = mapped_column(
        "partyId",
        UUID(as_uuid=True),
        ForeignKey("parties.id", ondelete="NO ACTION"),
        nullable=False,
    )
    total_amount: Mapped[Decimal] = mapped_column(
        "totalAmount", Numeric(10, 2), nullable=False
    )
    items: Mapped[Optional[Any]] = mapped_column("items", JSONB, nullable=True)
    ledger_entry_id: Mapped[Optional[uuid.UUID]] = mapped_column(
        "ledgerEntryId",
        UUID(as_uuid=True),
        ForeignKey("ledger_entries.id", ondelete="NO ACTION"),
        nullable=True,
    )
    is_gst: Mapped[bool] = mapped_column("isGst", Boolean, default=False, nullable=False)
    receipt_url: Mapped[Optional[str]] = mapped_column(
        "receiptUrl", String, nullable=True
    )

    # created_at and updated_at are snake_case in bills table!
    created_at: Mapped[datetime] = mapped_column(
        "created_at",
        DateTime(timezone=False),
        default=utc_now_naive,
        nullable=False,
    )
    updated_at: Mapped[datetime] = mapped_column(
        "updated_at",
        DateTime(timezone=False),
        default=utc_now_naive,
        onupdate=utc_now_naive,
        nullable=False,
    )

    business = relationship("Business", foreign_keys=[business_id])
    party = relationship("Party", foreign_keys=[party_id])
    ledger_entry = relationship("LedgerEntry", foreign_keys=[ledger_entry_id])

    __table_args__ = (
        Index("IDX_bills_business", "businessId"),
        Index("IDX_bills_party", "partyId"),
    )
