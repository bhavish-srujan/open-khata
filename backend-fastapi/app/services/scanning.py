import base64
import json
import logging
import re
from datetime import datetime
from typing import Any, Dict, Optional

import httpx

from app.config import settings

logger = logging.getLogger("open_khata.scanning")

SUPPORTED_MIME_TYPES = {
    "image/png",
    "image/jpeg",
    "image/webp",
    "image/heic",
    "image/heif",
}


def is_real_iso_date(value: Any) -> bool:
    """True only for a real calendar date in YYYY-MM-DD form."""
    if not isinstance(value, str) or not re.match(r"^\d{4}-\d{2}-\d{2}$", value):
        return False
    try:
        parsed = datetime.strptime(value, "%Y-%m-%d")
        return parsed.strftime("%Y-%m-%d") == value
    except Exception:
        return False


def sanitize_extraction(raw: Any) -> Any:
    """
    Strips dates the model invented or mangled before they leave this service.
    Amounts and names are left exactly as extracted.
    """
    if not raw or not isinstance(raw, dict):
        return raw

    result = dict(raw)
    if not is_real_iso_date(result.get("date")):
        result["date"] = ""

    records = result.get("records")
    if isinstance(records, list):
        clean_records = []
        for record in records:
            if not isinstance(record, dict):
                clean_records.append(record)
                continue
            rec_copy = dict(record)
            if "transactionDate" in rec_copy and not is_real_iso_date(rec_copy["transactionDate"]):
                del rec_copy["transactionDate"]
            clean_records.append(rec_copy)
        result["records"] = clean_records

    return result


async def extract_ledger_data(image_bytes: bytes, mime_type: str) -> Dict[str, Any]:
    """
    Calls Gemini API to extract handwritten ledger accounting data or returns
    mock data if GEMINI_API_KEY is not configured.
    """
    if not image_bytes:
        raise ValueError("Image is required")

    normalized_mime = (mime_type or "").lower().strip()
    if normalized_mime not in SUPPORTED_MIME_TYPES:
        raise ValueError(
            f'Unsupported image type "{mime_type}". Use JPEG, PNG, WEBP or HEIC.'
        )

    if not settings.GEMINI_API_KEY:
        logger.warning("GEMINI_API_KEY not configured. AI scanning returning mock data.")
        return {
            "date": "",
            "totalCollected": 700.0,
            "records": [
                {"customerName": "Rahul Traders", "amount": 500.0},
                {"customerName": "Pndi", "amount": 200.0},
            ],
        }

    prompt = """
    You are an expert at extracting accounting data from handwritten ledger (khata) images.
    The user is uploading a "Daily Collection Sheet" where multiple customers pay money on a single day.
    Extract the total collected amount and the list of individual records.
    For each record, extract the customer's name (or code) and the amount they paid/received.
    Return the data strictly as a JSON object with this format:
    {
      "date": "YYYY-MM-DD",
      "totalCollected": 5000.0,
      "records": [
        { "customerName": "Name of customer", "amount": 500.0, "transactionDate": "YYYY-MM-DD" }
      ]
    }
    "date" is the single date written on the sheet that applies to every row.
    Leave "date" as an empty string if no date is visible anywhere. Do NOT
    guess a date and do NOT substitute today's date — the app supplies its
    own default, and an invented date is worse than none because the user
    cannot tell it apart from one you actually read.
    Only include "transactionDate" on a record when THAT row carries its own
    date different from the sheet date; otherwise omit the field entirely.
    Never output a date that is not a real calendar date.
    Do not include any markdown formatting or text outside the JSON object.
    """

    model = settings.GEMINI_MODEL or "gemini-flash-lite-latest"
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={settings.GEMINI_API_KEY}"

    payload = {
        "contents": [
            {
                "role": "user",
                "parts": [
                    {"text": prompt},
                    {
                        "inlineData": {
                            "mimeType": normalized_mime,
                            "data": base64.b64encode(image_bytes).decode("utf-8"),
                        }
                    },
                ],
            }
        ],
        "generationConfig": {
            "responseMimeType": "application/json",
            "temperature": 0.1,
        },
    }

    timeout_seconds = float(settings.AI_TIMEOUT_MS) / 1000.0 if settings.AI_TIMEOUT_MS else 45.0

    async with httpx.AsyncClient(timeout=timeout_seconds) as client:
        res = await client.post(url, json=payload)
        if res.status_code != 200:
            logger.error("Gemini API error: %d %s", res.status_code, res.text)
            raise RuntimeError(f"Gemini API returned status {res.status_code}")

        data = res.json()
        candidates = data.get("candidates", [])
        if not candidates:
            raise RuntimeError("No candidate response from Gemini")

        text_content = (
            candidates[0].get("content", {}).get("parts", [{}])[0].get("text", "")
        )
        if not text_content:
            raise RuntimeError("Empty response text from Gemini")

        parsed_json = json.loads(text_content)
        return sanitize_extraction(parsed_json)
