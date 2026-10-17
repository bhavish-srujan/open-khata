import logging
from typing import Optional

import httpx

from app.config import settings

logger = logging.getLogger("open_khata.sms")


class SmsService:
    def is_configured(self) -> bool:
        """True when TextBee is configured. OTP-path only."""
        return bool(
            settings.TEXTBEE_API_KEY
            and settings.TEXTBEE_API_KEY.strip()
            and settings.TEXTBEE_DEVICE_ID
            and settings.TEXTBEE_DEVICE_ID.strip()
        )

    def is_transactional_configured(self) -> bool:
        """
        True when a dedicated transactional SMS provider is configured.
        Deliberately independent of TextBee: TextBee is reserved for OTP only.
        """
        return bool(
            settings.TRANSACTIONAL_SMS_PROVIDER
            and settings.TRANSACTIONAL_SMS_PROVIDER.strip()
        )

    async def send_transactional_sms(self, phone_number: str, message: str) -> bool:
        """
        Customer-facing SMS (ledger entries, reminders).
        Returns False without sending when no transactional provider is configured.
        """
        if not self.is_transactional_configured():
            logger.info(
                "Transactional SMS not configured; skipping message to %s. "
                "TextBee is reserved for OTP and is intentionally not used here.",
                phone_number,
            )
            return False
        # Hook for real transactional SMS provider
        return False

    def normalize_for_gateway(self, value: str) -> str:
        """
        Best-effort E.164 for Indian numbers:
        10 digits, 0-prefixed, 91-prefixed, 091, or +91.
        """
        raw = (value or "").strip()
        if not raw:
            return raw

        digits = "".join(ch for ch in raw if ch.isdigit())
        if not digits:
            return raw

        if len(digits) == 10:
            return f"+91{digits}"
        if len(digits) == 11 and digits.startswith("0"):
            return f"+91{digits[1:]}"
        if len(digits) == 12 and digits.startswith("91"):
            return f"+{digits}"
        if len(digits) == 13 and digits.startswith("091"):
            return f"+{digits[1:]}"

        return raw if raw.startswith("+") else f"+{digits}"

    async def send_otp_sms(self, phone_number: str, message: str) -> bool:
        """OTP delivery via TextBee. Authentication messages only."""
        if not self.is_configured():
            logger.warning(
                "TextBee API not configured. Mocking SMS to %s: %s",
                phone_number,
                message,
            )
            return True

        api_key = settings.TEXTBEE_API_KEY.strip()
        device_id = settings.TEXTBEE_DEVICE_ID.strip()
        target_url = f"https://api.textbee.dev/api/v1/gateway/devices/{device_id}/send-sms"

        payload = {
            "receivers": [self.normalize_for_gateway(phone_number)],
            "smsBody": message,
        }

        timeout = (
            settings.SMS_GATEWAY_TIMEOUT_MS / 1000.0
            if settings.SMS_GATEWAY_TIMEOUT_MS
            else 5.0
        )

        try:
            logger.debug("Sending SMS to %s via TextBee", phone_number)
            async with httpx.AsyncClient(timeout=timeout) as client:
                response = await client.post(
                    target_url,
                    json=payload,
                    headers={
                        "Content-Type": "application/json",
                        "x-api-key": api_key,
                    },
                )
                logger.info(
                    "Successfully sent SMS to %s. TextBee status: %d",
                    phone_number,
                    response.status_code,
                )
                return response.is_success
        except Exception as e:
            logger.error("Failed to send SMS via TextBee to %s: %s", phone_number, e)
            return False


sms_service = SmsService()
