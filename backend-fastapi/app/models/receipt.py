import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Index, Integer, String
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base
from app.models.base import utc_now_naive


class Receipt(Base):
    __tablename__ = "receipts"

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
    uploaded_by: Mapped[str] = mapped_column("uploaded_by", String, nullable=False)
    filename: Mapped[str] = mapped_column("filename", String, nullable=False)
    mime_type: Mapped[str] = mapped_column("mime_type", String, nullable=False)
    size_bytes: Mapped[int] = mapped_column("size_bytes", Integer, nullable=False)

    created_at: Mapped[datetime] = mapped_column(
        "created_at",
        DateTime(timezone=False),
        default=utc_now_naive,
        nullable=False,
    )

    business = relationship("Business", foreign_keys=[business_id])

    __table_args__ = (Index("IDX_receipts_business", "business_id"),)
