from datetime import datetime
from decimal import Decimal
from typing import Literal, Optional

from pydantic import Field, field_validator

from app.schemas.base import CamelModel


class CreatePartyDto(CamelModel):
    id: Optional[str] = None
    name: str = Field(..., max_length=120)
    phone: Optional[str] = None
    type: Literal["CUSTOMER", "SUPPLIER"]
    gstin: Optional[str] = Field(None, max_length=15)

    # Address / backward-compat fields sent by Android client
    business_id: Optional[str] = None
    flat_building: Optional[str] = None
    area_locality: Optional[str] = None
    pincode: Optional[str] = None
    city: Optional[str] = None
    state: Optional[str] = None
    is_shipping_same: Optional[bool] = None

    collection_frequency: Optional[Literal["DAILY", "WEEKLY", "MONTHLY"]] = None

    interest_enabled: Optional[bool] = None
    interest_rate: Optional[Decimal] = Field(None, ge=0, le=1000)
    interest_period_value: Optional[int] = Field(None, ge=1)
    interest_period_unit: Optional[Literal["DAYS", "MONTHS"]] = None

    @field_validator("name")
    @classmethod
    def validate_name_not_blank(cls, v: str) -> str:
        if not v or not v.strip():
            raise ValueError("name must not be blank")
        return v

    @field_validator("phone")
    @classmethod
    def validate_phone(cls, v: Optional[str]) -> Optional[str]:
        if v is None or v == "":
            return v
        import re

        if not re.match(r"^[+0-9][0-9\s\-()]{5,19}$", v):
            raise ValueError("phone must be a valid phone number")
        return v


class UpdatePartyInterestDto(CamelModel):
    interest_enabled: bool
    interest_rate: Optional[Decimal] = Field(None, ge=0, le=1000)
    interest_period_value: Optional[int] = Field(None, ge=1)
    interest_period_unit: Optional[Literal["DAYS", "MONTHS"]] = None


class UpdateCollectionFrequencyDto(CamelModel):
    collection_frequency: Literal["DAILY", "WEEKLY", "MONTHLY"]


class PartyResponse(CamelModel):
    id: str
    name: str
    phone: str
    type: str
    gstin: Optional[str] = None
    current_balance: Decimal
    business_id: str
    collection_frequency: Optional[str] = "DAILY"
    interest_enabled: Optional[bool] = False
    interest_rate: Optional[Decimal] = Decimal("0.000")
    interest_period_value: Optional[int] = 0
    interest_period_unit: Optional[str] = "DAYS"
    created_at: datetime
    updated_at: Optional[datetime] = None
