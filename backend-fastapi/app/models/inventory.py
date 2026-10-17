import uuid
from datetime import datetime
from decimal import Decimal
from typing import Optional

from sqlalchemy import DateTime, ForeignKey, Index, Numeric, String
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base
from app.models.base import utc_now_naive


class Item(Base):
    __tablename__ = "items"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        default=uuid.uuid4,
    )
    name: Mapped[str] = mapped_column("name", String, nullable=False)
    sku: Mapped[Optional[str]] = mapped_column("sku", String, nullable=True)

    selling_price: Mapped[Decimal] = mapped_column(
        "sellingPrice", Numeric(14, 2), default=Decimal("0.00"), nullable=False
    )
    purchase_price: Mapped[Decimal] = mapped_column(
        "purchasePrice", Numeric(14, 2), default=Decimal("0.00"), nullable=False
    )
    current_stock: Mapped[Decimal] = mapped_column(
        "currentStock", Numeric(14, 2), default=Decimal("0.00"), nullable=False
    )
    low_stock_threshold: Mapped[Decimal] = mapped_column(
        "lowStockThreshold", Numeric(14, 2), default=Decimal("5.00"), nullable=False
    )

    business_id: Mapped[uuid.UUID] = mapped_column(
        "business_id",
        UUID(as_uuid=True),
        ForeignKey("businesses.id", ondelete="NO ACTION"),
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

    __table_args__ = (Index("IDX_items_business", "business_id"),)
