from datetime import datetime
from typing import Optional

from pydantic import Field

from app.schemas.base import CamelModel


class CreateBusinessDto(CamelModel):
    name: str = Field(..., max_length=120)
    type: Optional[str] = Field(None, max_length=60)
    gst_number: Optional[str] = Field(None, max_length=20)
    address: Optional[str] = None
    category: Optional[str] = None
    bank_account: Optional[str] = None
    profile_image_url: Optional[str] = None


class UpdateBusinessDto(CamelModel):
    name: Optional[str] = Field(None, max_length=120)
    type: Optional[str] = Field(None, max_length=60)
    gst_number: Optional[str] = Field(None, max_length=20)
    address: Optional[str] = None
    category: Optional[str] = None
    bank_account: Optional[str] = None
    profile_image_url: Optional[str] = None


class BusinessResponse(CamelModel):
    id: str
    owner_id: str
    name: str
    type: Optional[str] = None
    gst_number: Optional[str] = None
    address: Optional[str] = None
    category: Optional[str] = None
    bank_account: Optional[str] = None
    profile_image_url: Optional[str] = None
    created_at: datetime
    updated_at: datetime
    role: Optional[str] = None
