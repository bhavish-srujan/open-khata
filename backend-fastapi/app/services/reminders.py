import logging
import math
import time
import uuid
from decimal import Decimal
from typing import Any, Dict

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.models.party import Party
from app.services.sms import sms_service

logger = logging.getLogger("open_khata.reminders")

# 5-minute cooldown between manual reminders for the same party
REMINDER_COOLDOWN_MS = 5 * 60 * 1000

# In-memory tracking of recent manual reminder attempts: f"{business_id}:{party_id}" -> timestamp_ms
_last_manual_send_at: Dict[str, float] = {}


class RemindersService:
    async def send_reminder_for_party(
        self,
        db: AsyncSession,
        party_id: str,
        business_id: str,
    ) -> Dict[str, Any]:
        party_uuid = uuid.UUID(party_id)
        business_uuid = uuid.UUID(business_id)

        res = await db.execute(
            select(Party).where(Party.id == party_uuid, Party.business_id == business_uuid)
        )
        party = res.scalar_one_or_none()
        if not party:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Party {party_id} not found in business {business_id}",
            )

        if not party.phone or not party.phone.strip():
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="This party has no phone number on file",
            )

        cooldown_key = f"{business_id}:{party_id}"
        now_ms = time.time() * 1000.0
        last_sent = _last_manual_send_at.get(cooldown_key)

        if last_sent is not None and (now_ms - last_sent) < REMINDER_COOLDOWN_MS:
            wait_seconds = math.ceil((REMINDER_COOLDOWN_MS - (now_ms - last_sent)) / 1000.0)
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail=f"A reminder was already sent to this party recently. Please wait {wait_seconds}s before retrying.",
            )

        # Reserve cooldown before network call
        _last_manual_send_at[cooldown_key] = now_ms

        try:
            return await self.send_reminder(
                phone_number=party.phone,
                amount=float(party.current_balance),
                party_name=party.name,
            )
        except Exception:
            _last_manual_send_at.pop(cooldown_key, None)
            raise

    async def send_reminder(
        self,
        phone_number: str,
        amount: float,
        party_name: str,
    ) -> Dict[str, Any]:
        if not phone_number:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Phone number is required",
            )

        message = (
            f"Dear {party_name}, this is a gentle reminder that an amount of "
            f"Rs. {amount} is pending. Please clear it at the earliest."
        )

        if not sms_service.is_transactional_configured():
            logger.warning(
                "Reminder to %s not sent: no transactional SMS provider is configured.",
                phone_number,
            )
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Reminders are unavailable: no SMS provider is configured.",
            )

        sent = await sms_service.send_transactional_sms(phone_number, message)
        if not sent:
            logger.error("Failed to send reminder to %s", phone_number)
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Failed to send Reminder",
            )

        return {"success": True, "message": "Reminder sent successfully"}


reminders_service = RemindersService()


async def run_daily_reminders(db: AsyncSession):
    """
    Executes automated daily reminder check for CUSTOMER parties with balance >= min_balance.
    """
    if not settings.REMINDERS_ENABLED:
        logger.info("Automated reminders are disabled. Set REMINDERS_ENABLED=true to turn them on.")
        return

    min_balance = Decimal(str(settings.REMINDER_MIN_BALANCE if settings.REMINDER_MIN_BALANCE > 0 else 1.0))
    stmt = select(Party).where(
        Party.type == "CUSTOMER",
        Party.current_balance >= min_balance,
    )
    res = await db.execute(stmt)
    parties = res.scalars().all()

    for party in parties:
        if party.phone:
            try:
                await reminders_service.send_reminder(
                    party.phone,
                    float(party.current_balance),
                    party.name,
                )
            except Exception as e:
                logger.error("Failed to send automated reminder to %s: %s", party.name, e)
