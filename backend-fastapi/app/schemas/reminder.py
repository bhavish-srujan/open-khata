import uuid
from pydantic import Field, field_validator

from app.schemas.base import CamelModel


class SendReminderDto(CamelModel):
    party_id: str

    @field_validator("party_id")
    @classmethod
    def validate_party_uuid(cls, v: str) -> str:
        try:
            uuid.UUID(str(v))
        except Exception:
            raise ValueError("partyId must be a UUID")
        return v
