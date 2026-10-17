import uuid
from datetime import datetime, timezone
from decimal import Decimal
from typing import Optional

from sqlalchemy import DateTime, ForeignKey, Index, Numeric, String
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base
from app.models.base import utc_now_naive


class LedgerEntry(Base):
    __tablename__ = "ledger_entries"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        default=uuid.uuid4,
    )
    receipt_url: Mapped[Optional[str]] = mapped_column(
        "receiptUrl", String, nullable=True
    )
    business_id: Mapped[uuid.UUID] = mapped_column(
        "business_id",
        UUID(as_uuid=True),
        ForeignKey("businesses.id", ondelete="NO ACTION"),
        nullable=False,
    )
    party_id: Mapped[uuid.UUID] = mapped_column(
        "party_id",
        UUID(as_uuid=True),
        ForeignKey("parties.id", ondelete="NO ACTION"),
        nullable=False,
    )
    amount: Mapped[Decimal] = mapped_column(
        "amount", Numeric(14, 2), nullable=False
    )
    type: Mapped[str] = mapped_column("type", String, nullable=False)
    note: Mapped[Optional[str]] = mapped_column(
        "note", String, nullable=True, default=""
    )
    payment_method: Mapped[str] = mapped_column(
        "paymentMethod", String, default="Cash", nullable=False
    )
    source: Mapped[str] = mapped_column(
        "source", String, default="MANUAL", nullable=False
    )
    status: Mapped[str] = mapped_column(
        "status", String, default="CONFIRMED", nullable=False
    )

    # Naive timestamp
    created_at: Mapped[datetime] = mapped_column(
        "createdAt",
        DateTime(timezone=False),
        default=utc_now_naive,
        nullable=False,
    )

    # TIMESTAMPTZ (timezone=True) — distinct from createdAt
    transaction_date: Mapped[datetime] = mapped_column(
        "transaction_date",
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        nullable=False,
    )

    business = relationship("Business", foreign_keys=[business_id])
    party = relationship("Party", foreign_keys=[party_id])

    __table_args__ = (
        Index("IDX_ledger_entries_business", "business_id"),
        Index("IDX_ledger_entries_party", "party_id"),
        Index("IDX_ledger_entries_transaction_date", "transaction_date"),
    )


class BalanceHistory(Base):
    __tablename__ = "balance_history"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        default=uuid.uuid4,
    )
    business_id: Mapped[uuid.UUID] = mapped_column(
        "business_id",
        UUID(as_uuid=True),
        ForeignKey("businesses.id", ondelete="NO ACTION"),
        nullable=False,
    )
    party_id: Mapped[uuid.UUID] = mapped_column(
        "party_id",
        UUID(as_uuid=True),
        ForeignKey("parties.id", ondelete="NO ACTION"),
        nullable=False,
    )
    ledger_entry_id: Mapped[Optional[uuid.UUID]] = mapped_column(
        "ledger_entry_id",
        UUID(as_uuid=True),
        ForeignKey("ledger_entries.id", ondelete="NO ACTION"),
        nullable=True,
    )
    previous_balance: Mapped[Decimal] = mapped_column(
        "previousBalance", Numeric(14, 2), nullable=False
    )
    new_balance: Mapped[Decimal] = mapped_column(
        "newBalance", Numeric(14, 2), nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(
        "createdAt",
        DateTime(timezone=False),
        default=utc_now_naive,
        nullable=False,
    )

    business = relationship("Business", foreign_keys=[business_id])
    party = relationship("Party", foreign_keys=[party_id])
    ledger_entry = relationship("LedgerEntry", foreign_keys=[ledger_entry_id])
