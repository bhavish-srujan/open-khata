import uuid
from datetime import datetime
from decimal import Decimal
from typing import Any, List, Optional

from pydantic import Field, field_validator

from app.schemas.base import CamelModel

MAX_BILL_TOTAL = Decimal("99999999.99")


def _check_max_decimals(v: Decimal, max_places: int, name: str) -> Decimal:
    exp = v.as_tuple().exponent
    if isinstance(exp, int) and exp < 0 and -exp > max_places:
        raise ValueError(f"{name} must have at most {max_places} decimal places")
    return v


class BillItemDto(CamelModel):
    description: str = Field(..., max_length=200)
    quantity: Decimal = Field(..., gt=0, le=Decimal("1000000"))
    price: Decimal = Field(..., ge=0, le=MAX_BILL_TOTAL)
    tax: Optional[Decimal] = Field(None, ge=0, le=100)

    @field_validator("quantity", "price", "tax", mode="before")
    @classmethod
    def check_number_types(cls, v: Any, info) -> Any:
        if isinstance(v, str):
            raise ValueError(f"{info.field_name} must be a number")
        return v

    @field_validator("description")
    @classmethod
    def validate_description(cls, v: str) -> str:
        if not v or not v.strip():
            raise ValueError("description must not be blank")
        return v

    @field_validator("quantity")
    @classmethod
    def validate_quantity_decimals(cls, v: Decimal) -> Decimal:
        return _check_max_decimals(v, 3, "quantity")

    @field_validator("price")
    @classmethod
    def validate_price_decimals(cls, v: Decimal) -> Decimal:
        return _check_max_decimals(v, 2, "price")

    @field_validator("tax")
    @classmethod
    def validate_tax_decimals(cls, v: Optional[Decimal]) -> Optional[Decimal]:
        if v is not None:
            return _check_max_decimals(v, 2, "tax")
        return v


class CreateBillDto(CamelModel):
    id: Optional[str] = None
    party_id: str
    total_amount: Decimal = Field(..., gt=0, le=MAX_BILL_TOTAL)
    items: List[BillItemDto] = Field(..., min_length=1, max_length=200)
    is_gst: Optional[bool] = False

    @field_validator("total_amount", mode="before")
    @classmethod
    def check_total_amount_type(cls, v: Any) -> Any:
        if isinstance(v, str):
            raise ValueError("totalAmount must be a number")
        return v

    @field_validator("id")
    @classmethod
    def validate_id_uuid(cls, v: Optional[str]) -> Optional[str]:
        if v is not None:
            try:
                uuid.UUID(str(v))
            except Exception:
                raise ValueError("id must be a UUID")
        return v

    @field_validator("party_id")
    @classmethod
    def validate_party_uuid(cls, v: str) -> str:
        try:
            uuid.UUID(str(v))
        except Exception:
            raise ValueError("partyId must be a UUID")
        return v

    @field_validator("total_amount")
    @classmethod
    def validate_total_amount_decimals(cls, v: Decimal) -> Decimal:
        return _check_max_decimals(v, 2, "totalAmount")


class BillResponse(CamelModel):
    id: str
    party_id: str
    business_id: Optional[str] = None
    total_amount: Decimal
    items: Optional[Any] = None
    ledger_entry_id: Optional[str] = None
    is_gst: bool = False
    receipt_url: Optional[str] = None
    # Note: Retrofit BillResponse expects created_at (snake_case)
    created_at: datetime = Field(..., alias="created_at", serialization_alias="created_at")
    updated_at: Optional[datetime] = Field(None, alias="updated_at", serialization_alias="updated_at")
