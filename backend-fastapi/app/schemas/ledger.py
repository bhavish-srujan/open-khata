from datetime import datetime
from decimal import Decimal
from typing import Literal, Optional

from pydantic import Field, field_validator

from app.schemas.base import CamelModel
from app.schemas.party import PartyResponse

MAX_AMOUNT = Decimal("999999999999.99")


class CreateLedgerEntryDto(CamelModel):
    id: Optional[str] = None
    party_id: str
    amount: Decimal = Field(..., gt=0, le=MAX_AMOUNT)
    type: Literal["GAVE", "GOT"]
    note: Optional[str] = Field("", max_length=500)
    payment_method: Optional[str] = Field("Cash", max_length=50)
    source: Optional[Literal["MANUAL", "OCR"]] = "MANUAL"
    notify_customer: Optional[bool] = None
    transaction_date: Optional[str] = None

    @field_validator("transaction_date")
    @classmethod
    def validate_transaction_date(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        # Strict ISO8601 parsing
        try:
            datetime.fromisoformat(v.replace("Z", "+00:00"))
        except Exception:
            raise ValueError("transactionDate must be a valid ISO 8601 date string")
        return v


class UpdateLedgerEntryDto(CamelModel):
    amount: Optional[Decimal] = Field(None, gt=0, le=MAX_AMOUNT)
    note: Optional[str] = Field(None, max_length=500)


class LedgerEntryResponse(CamelModel):
    id: str
    party_id: str
    amount: Decimal
    type: str
    note: Optional[str] = ""
    payment_method: str
    source: str
    status: str = "CONFIRMED"
    receipt_url: Optional[str] = None
    created_at: datetime
    transaction_date: Optional[datetime] = None
    party: Optional[PartyResponse] = None
