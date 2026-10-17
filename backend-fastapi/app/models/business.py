import uuid
from datetime import datetime
from typing import Optional

from sqlalchemy import DateTime, ForeignKey, String
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base
from app.models.base import utc_now_naive


class Business(Base):
    __tablename__ = "businesses"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        default=uuid.uuid4,
    )
    owner_id: Mapped[uuid.UUID] = mapped_column(
        "owner_id",
        UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="NO ACTION"),
        nullable=False,
    )
    name: Mapped[str] = mapped_column("name", String, nullable=False)
    gst_number: Mapped[Optional[str]] = mapped_column(
        "gst_number", String, nullable=True
    )
    type: Mapped[Optional[str]] = mapped_column("type", String, nullable=True)
    address: Mapped[Optional[str]] = mapped_column("address", String, nullable=True)
    category: Mapped[Optional[str]] = mapped_column("category", String, nullable=True)
    bank_account: Mapped[Optional[str]] = mapped_column(
        "bank_account", String, nullable=True
    )
    profile_image_url: Mapped[Optional[str]] = mapped_column(
        "profile_image_url", String, nullable=True
    )

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

    owner = relationship("User", foreign_keys=[owner_id])
