import enum
import uuid
from datetime import datetime
from typing import Any, Optional

from sqlalchemy import DateTime, String
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base
from app.models.base import utc_now_naive


class AuditAction(str, enum.Enum):
    CREATE = "CREATE"
    UPDATE = "UPDATE"
    DELETE = "DELETE"


class AuditLog(Base):
    __tablename__ = "audit_logs"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        default=uuid.uuid4,
    )
    action: Mapped[str] = mapped_column("action", String(50), nullable=False)
    entity_name: Mapped[str] = mapped_column("entityName", String, nullable=False)
    entity_id: Mapped[str] = mapped_column("entityId", String, nullable=False)
    performed_by: Mapped[str] = mapped_column("performed_by", String, nullable=False)
    previous_state: Mapped[Optional[Any]] = mapped_column(
        "previousState", JSONB, nullable=True
    )
    new_state: Mapped[Optional[Any]] = mapped_column("newState", JSONB, nullable=True)

    created_at: Mapped[datetime] = mapped_column(
        "createdAt",
        DateTime(timezone=False),
        default=utc_now_naive,
        nullable=False,
    )
