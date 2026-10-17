from datetime import datetime
from decimal import Decimal
from typing import Any, Optional

from pydantic import Field, field_validator

from app.schemas.base import CamelModel

MAX_MONEY = Decimal("999999999999.99")


def _check_max_decimals(v: Optional[Decimal], max_places: int, name: str) -> Optional[Decimal]:
    if v is None:
        return v
    exp = v.as_tuple().exponent
    if isinstance(exp, int) and exp < 0 and -exp > max_places:
        raise ValueError(f"{name} must have at most {max_places} decimal places")
    return v


class CreateItemDto(CamelModel):
    name: str = Field(..., max_length=120)
    sku: Optional[str] = Field(None, max_length=60)
    selling_price: Optional[Decimal] = Field(Decimal("0.00"), ge=0, le=MAX_MONEY)
    purchase_price: Optional[Decimal] = Field(Decimal("0.00"), ge=0, le=MAX_MONEY)
    current_stock: Optional[Decimal] = Field(Decimal("0.00"), ge=0, le=MAX_MONEY)
    low_stock_threshold: Optional[Decimal] = Field(Decimal("5.00"), ge=0, le=MAX_MONEY)

    @field_validator("selling_price", "purchase_price", "current_stock", "low_stock_threshold", mode="before")
    @classmethod
    def check_number_types(cls, v: Any, info) -> Any:
        if isinstance(v, str):
            raise ValueError(f"{info.field_name} must be a number")
        return v

    @field_validator("name")
    @classmethod
    def validate_name(cls, v: str) -> str:
        if not v or not v.strip():
            raise ValueError("name must not be blank")
        return v

    @field_validator("selling_price")
    @classmethod
    def validate_selling_price(cls, v: Optional[Decimal]) -> Optional[Decimal]:
        return _check_max_decimals(v, 2, "sellingPrice")

    @field_validator("purchase_price")
    @classmethod
    def validate_purchase_price(cls, v: Optional[Decimal]) -> Optional[Decimal]:
        return _check_max_decimals(v, 2, "purchasePrice")

    @field_validator("current_stock")
    @classmethod
    def validate_current_stock(cls, v: Optional[Decimal]) -> Optional[Decimal]:
        return _check_max_decimals(v, 2, "currentStock")

    @field_validator("low_stock_threshold")
    @classmethod
    def validate_low_stock_threshold(cls, v: Optional[Decimal]) -> Optional[Decimal]:
        return _check_max_decimals(v, 2, "lowStockThreshold")


class UpdateItemDto(CamelModel):
    name: Optional[str] = Field(None, max_length=120)
    sku: Optional[str] = Field(None, max_length=60)
    selling_price: Optional[Decimal] = Field(None, ge=0, le=MAX_MONEY)
    purchase_price: Optional[Decimal] = Field(None, ge=0, le=MAX_MONEY)
    current_stock: Optional[Decimal] = Field(None, ge=0, le=MAX_MONEY)
    low_stock_threshold: Optional[Decimal] = Field(None, ge=0, le=MAX_MONEY)

    @field_validator("selling_price", "purchase_price", "current_stock", "low_stock_threshold", mode="before")
    @classmethod
    def check_number_types(cls, v: Any, info) -> Any:
        if isinstance(v, str):
            raise ValueError(f"{info.field_name} must be a number")
        return v

    @field_validator("name")
    @classmethod
    def validate_name(cls, v: Optional[str]) -> Optional[str]:
        if v is not None and (not v or not v.strip()):
            raise ValueError("name must not be blank")
        return v

    @field_validator("selling_price")
    @classmethod
    def validate_selling_price(cls, v: Optional[Decimal]) -> Optional[Decimal]:
        return _check_max_decimals(v, 2, "sellingPrice")

    @field_validator("purchase_price")
    @classmethod
    def validate_purchase_price(cls, v: Optional[Decimal]) -> Optional[Decimal]:
        return _check_max_decimals(v, 2, "purchasePrice")

    @field_validator("current_stock")
    @classmethod
    def validate_current_stock(cls, v: Optional[Decimal]) -> Optional[Decimal]:
        return _check_max_decimals(v, 2, "currentStock")

    @field_validator("low_stock_threshold")
    @classmethod
    def validate_low_stock_threshold(cls, v: Optional[Decimal]) -> Optional[Decimal]:
        return _check_max_decimals(v, 2, "lowStockThreshold")


class ItemResponse(CamelModel):
    id: str
    name: str
    sku: Optional[str] = None
    selling_price: Decimal
    purchase_price: Decimal
    current_stock: Decimal
    low_stock_threshold: Decimal
    business_id: str
    created_at: datetime
    updated_at: datetime
