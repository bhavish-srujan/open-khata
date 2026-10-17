import enum
import uuid
from datetime import datetime
from typing import Optional

from sqlalchemy import DateTime, Integer, String
from sqlalchemy.dialects.postgresql import ENUM, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base
from app.models.base import utc_now_naive


class UserRole(str, enum.Enum):
    OWNER = "OWNER"
    MANAGER = "MANAGER"
    CASHIER = "CASHIER"


# Name must match TypeORM's created enum 'users_role_enum'
users_role_enum = ENUM(
    "OWNER",
    "MANAGER",
    "CASHIER",
    name="users_role_enum",
    create_type=False,
)


class User(Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        primary_key=True,
        default=uuid.uuid4,
    )
    phone_number: Mapped[str] = mapped_column(
        "phone_number",
        String,
        unique=True,
        nullable=False,
    )
    normalized_phone: Mapped[Optional[str]] = mapped_column(
        "normalized_phone",
        String,
        unique=True,
        nullable=True,
    )
    name: Mapped[Optional[str]] = mapped_column("name", String, nullable=True)

    # Mixed naming in TypeORM: quoted camelCase in Postgres
    current_otp: Mapped[Optional[str]] = mapped_column(
        "currentOtp",
        String,
        nullable=True,
    )

    role: Mapped[UserRole] = mapped_column(
        "role",
        users_role_enum,
        default=UserRole.OWNER,
        nullable=False,
    )

    # Naive timestamp (DateTime(timezone=False)) matching TypeORM's naive 'timestamp'
    otp_expiry: Mapped[Optional[datetime]] = mapped_column(
        "otpExpiry",
        DateTime(timezone=False),
        nullable=True,
    )

    otp_attempts: Mapped[int] = mapped_column(
        "otpAttempts",
        Integer,
        default=0,
        nullable=False,
    )

    session_version: Mapped[int] = mapped_column(
        "sessionVersion",
        Integer,
        default=1,
        nullable=False,
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
