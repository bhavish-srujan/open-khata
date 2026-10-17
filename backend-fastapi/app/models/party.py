import uuid
from datetime import datetime
from decimal import Decimal
from typing import Optional

from sqlalchemy import Boolean, DateTime, ForeignKey, Index, Integer, Numeric, String
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base
from app.models.base import utc_now_naive


class Party(Base):
    __tablename__ = "parties"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        default=uuid.uuid4,
    )
    name: Mapped[str] = mapped_column("name", String, nullable=False)
    phone: Mapped[str] = mapped_column("phone", String, nullable=False)
    type: Mapped[str] = mapped_column("type", String, default="CUSTOMER", nullable=False)
    gstin: Mapped[Optional[str]] = mapped_column("gstin", String, nullable=True)

    # Mixed naming in TypeORM: quoted camelCase in Postgres
    current_balance: Mapped[Decimal] = mapped_column(
        "currentBalance",
        Numeric(14, 2),
        default=Decimal("0.00"),
        nullable=False,
    )

    business_id: Mapped[uuid.UUID] = mapped_column(
        "business_id",
        UUID(as_uuid=True),
        ForeignKey("businesses.id", ondelete="NO ACTION"),
        nullable=False,
    )

    address: Mapped[Optional[str]] = mapped_column("address", String, nullable=True)

    collection_frequency: Mapped[str] = mapped_column(
        "collection_frequency",
        String(10),
        default="DAILY",
        nullable=False,
    )

    interest_enabled: Mapped[bool] = mapped_column(
        "interest_enabled",
        Boolean,
        default=False,
        nullable=False,
    )
    interest_rate: Mapped[Decimal] = mapped_column(
        "interest_rate",
        Numeric(8, 3),
        default=Decimal("0.000"),
        nullable=False,
    )
    interest_period_value: Mapped[int] = mapped_column(
        "interest_period_value",
        Integer,
        default=0,
        nullable=False,
    )
    interest_period_unit: Mapped[str] = mapped_column(
        "interest_period_unit",
        String(10),
        default="DAYS",
        nullable=False,
    )

    created_at: Mapped[datetime] = mapped_column(
        "createdAt",
        DateTime(timezone=False),
        default=utc_now_naive,
        nullable=False,
    )
    updated_at: Mapped[datetime] = mapped_column(
        "updatedAt",
        DateTime(timezone=False),
        default=utc_now_naive,
        onupdate=utc_now_naive,
        nullable=False,
    )

    business = relationship("Business", foreign_keys=[business_id])

    __table_args__ = (Index("IDX_parties_business", "business_id"),)
