from datetime import datetime
from typing import Literal, Optional

from pydantic import Field

from app.models.user import UserRole
from app.schemas.base import CamelModel


class AddStaffDto(CamelModel):
    phone_number: str = Field(
        ...,
        pattern=r"^[+0-9][0-9\s\-()]{5,19}$",
    )
    name: str = Field(..., max_length=120)
    role: Literal[UserRole.MANAGER, UserRole.CASHIER]


class UpdateMeDto(CamelModel):
    name: Optional[str] = None


class StaffMemberResponse(CamelModel):
    membership_id: str
    user_id: str
    name: Optional[str] = None
    phone_number: str
    role: str
    added_at: datetime


class UserResponse(CamelModel):
    id: str
    phone_number: str
    name: Optional[str] = None
    role: str
    created_at: datetime
    updated_at: datetime
