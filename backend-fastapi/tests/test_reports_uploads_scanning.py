import io
import uuid
from datetime import datetime, timezone
from decimal import Decimal

import pytest
from httpx import AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.bill import Bill
from app.models.ledger import LedgerEntry
from app.models.party import Party
from app.services.scanning import is_real_iso_date, sanitize_extraction
from tests.test_users_and_businesses import create_test_user_and_token


# PNG header magic bytes
VALID_PNG_BYTES = b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15c4\x00\x00\x00\nIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01\r\n-\xb4\x00\x00\x00\x00IEND\xaeB`\x82"
# JPEG header magic bytes
VALID_JPEG_BYTES = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x01\x00`\x00`\x00\x00\xff\xdb\x00C\x00\xff\xd9"


@pytest.mark.asyncio
async def test_reports_endpoints(client: AsyncClient, test_db: AsyncSession):
    user, business, token = await create_test_user_and_token(test_db, phone="+919876543230", role="OWNER")
    headers = {"Authorization": f"Bearer {token}"}

    # 1. Create a party
    party = Party(
        id=uuid.uuid4(),
        business_id=business.id,
        name="Vikram Traders",
        phone="+919876543231",
        type="CUSTOMER",
        current_balance=Decimal("0.00"),
    )
    test_db.add(party)
    await test_db.flush()

    # 2. Add ledger entries (GOT 500, GAVE 200)
    now = datetime.now(timezone.utc).replace(tzinfo=None)
    e1 = LedgerEntry(
        id=uuid.uuid4(),
        business_id=business.id,
        party_id=party.id,
        amount=Decimal("500.00"),
        type="GOT",
        payment_method="Cash",
        transaction_date=now,
    )
    e2 = LedgerEntry(
        id=uuid.uuid4(),
        business_id=business.id,
        party_id=party.id,
        amount=Decimal("200.00"),
        type="GAVE",
        payment_method="Cash",
        transaction_date=now,
    )
    test_db.add_all([e1, e2])

    # 3. Add bills (GST 1000, Non-GST 400)
    b1 = Bill(
        id=uuid.uuid4(),
        business_id=business.id,
        party_id=party.id,
        total_amount=Decimal("1000.00"),
        is_gst=True,
        created_at=now,
    )
    b2 = Bill(
        id=uuid.uuid4(),
        business_id=business.id,
        party_id=party.id,
        total_amount=Decimal("400.00"),
        is_gst=False,
        created_at=now,
    )
    test_db.add_all([b1, b2])
    await test_db.commit()

    # Cashflow Report
    cf_res = await client.get("/reports/cashflow", headers=headers)
    assert cf_res.status_code == 200
    cf_data = cf_res.json()
    assert cf_data["totalIn"] == 500.0
    assert cf_data["totalOut"] == 200.0
    assert cf_data["net"] == 300.0

    # Sales Report
    sales_res = await client.get("/reports/sales", headers=headers)
    assert sales_res.status_code == 200
    sales_data = sales_res.json()
    assert sales_data["totalSales"] == 1400.0
    assert sales_data["gstSales"] == 1000.0
    assert sales_data["nonGstSales"] == 400.0

    # PDF Statement
    pdf_res = await client.get(f"/reports/statement/pdf?partyId={party.id}", headers=headers)
    assert pdf_res.status_code == 200
    assert pdf_res.headers["content-type"] == "application/pdf"
    assert "attachment" in pdf_res.headers["content-disposition"]
    # PDF starts with %PDF magic bytes
    assert pdf_res.content.startswith(b"%PDF")


@pytest.mark.asyncio
async def test_uploads_lifecycle_and_security(client: AsyncClient, test_db: AsyncSession):
    # Setup business A
    user_a, business_a, token_a = await create_test_user_and_token(test_db, phone="+919876543240")
    headers_a = {"Authorization": f"Bearer {token_a}"}

    # Setup business B
    user_b, business_b, token_b = await create_test_user_and_token(test_db, phone="+919876543241")
    headers_b = {"Authorization": f"Bearer {token_b}"}

    # 1. Rejects missing file
    empty_upload = await client.post("/uploads/receipt", headers=headers_a)
    assert empty_upload.status_code == 400

    # 2. Rejects file with fraudulent/non-image content
    fake_png = ("receipt.png", b"<!DOCTYPE html><html><body>Dangerous</body></html>", "image/png")
    fake_res = await client.post(
        "/uploads/receipt",
        headers=headers_a,
        files={"file": fake_png},
    )
    assert fake_res.status_code == 400
    assert "not a JPEG, PNG or PDF" in fake_res.json()["message"]

    # 3. Accepts valid PNG upload
    valid_file = ("receipt.png", VALID_PNG_BYTES, "image/png")
    upload_res = await client.post(
        "/uploads/receipt",
        headers=headers_a,
        files={"file": valid_file},
    )
    assert upload_res.status_code == 200
    receipt = upload_res.json()
    assert receipt["mimeType"] == "image/png"
    receipt_id = receipt["id"]

    # 4. Fetch receipt by owner
    get_res = await client.get(f"/uploads/receipts/{receipt_id}", headers=headers_a)
    if get_res.status_code == 302:
        assert "location" in get_res.headers
        assert get_res.headers["cache-control"] == "private, no-store"
    else:
        assert get_res.status_code == 200
        assert get_res.content == VALID_PNG_BYTES
        assert get_res.headers["content-type"] == "image/png"
        assert get_res.headers["x-content-type-options"] == "nosniff"

    # 5. Cross-tenant retrieval returns 404 (never 403, preventing existence oracle)
    cross_res = await client.get(f"/uploads/receipts/{receipt_id}", headers=headers_b)
    assert cross_res.status_code == 404


def test_scanning_date_sanitization():
    # Strict ISO real date check
    assert is_real_iso_date("2026-10-12") is True
    assert is_real_iso_date("2026-02-28") is True
    assert is_real_iso_date("2026-02-31") is False  # Non-existent date
    assert is_real_iso_date("not-a-date") is False
    assert is_real_iso_date("") is False
    assert is_real_iso_date(None) is False

    # Sanitizer drops invalid dates
    raw = {
        "date": "2026-99-99",
        "totalCollected": 500.0,
        "records": [
            {"customerName": "Alice", "amount": 200.0, "transactionDate": "2026-02-31"},
            {"customerName": "Bob", "amount": 300.0, "transactionDate": "2026-10-12"},
        ],
    }
    clean = sanitize_extraction(raw)
    assert clean["date"] == ""
    assert "transactionDate" not in clean["records"][0]
    assert clean["records"][1]["transactionDate"] == "2026-10-12"


@pytest.mark.asyncio
async def test_scanning_api_extract(client: AsyncClient, test_db: AsyncSession, monkeypatch):
    from app.config import settings

    # Ensure clean test of mock extraction path without requiring live AI quota
    monkeypatch.setattr(settings, "GEMINI_API_KEY", None)

    user, business, token = await create_test_user_and_token(test_db, phone="+919876543250")
    headers = {"Authorization": f"Bearer {token}"}

    # 1. Missing file returns 400
    res_empty = await client.post("/api/v1/scanning/extract", headers=headers)
    assert res_empty.status_code == 400

    # 2. Unsupported file type returns 400
    bad_file = ("doc.txt", b"plain text content", "text/plain")
    res_bad = await client.post(
        "/api/v1/scanning/extract",
        headers=headers,
        files={"image": bad_file},
    )
    assert res_bad.status_code == 400

    # 3. Valid image returns mock extraction data when GEMINI_API_KEY is unset
    image_file = ("sheet.jpg", VALID_JPEG_BYTES, "image/jpeg")
    res_ok = await client.post(
        "/api/v1/scanning/extract",
        headers=headers,
        files={"image": image_file},
    )
    assert res_ok.status_code == 200
    data = res_ok.json()
    assert "totalCollected" in data
    assert "records" in data
    assert len(data["records"]) == 2
