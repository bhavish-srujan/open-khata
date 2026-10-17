import uuid
from typing import Optional

from fastapi import (
    APIRouter,
    Depends,
    File,
    HTTPException,
    Response,
    UploadFile,
    status,
)
from fastapi.responses import RedirectResponse
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.deps import CurrentUser, get_current_user
from app.database import get_db
from app.models.receipt import Receipt
from app.services.storage import detect_mime, extension_for, get_storage_service

router = APIRouter(prefix="/uploads", tags=["uploads"])

MAX_UPLOAD_BYTES = 5 * 1024 * 1024
MAX_RECEIPTS_PER_BUSINESS = 2000


@router.post("/receipt")
async def upload_receipt(
    file: UploadFile = File(None),
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
):
    if not file:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="No file uploaded",
        )

    content = await file.read()
    if not content:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="No file uploaded",
        )

    if len(content) > MAX_UPLOAD_BYTES:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="File size exceeds the 5MB limit",
        )

    business_uuid = uuid.UUID(current_user.business_id)

    # Check tenancy receipt cap
    count_res = await db.execute(
        select(func.count()).select_from(Receipt).where(Receipt.business_id == business_uuid)
    )
    held = count_res.scalar() or 0
    if held >= MAX_RECEIPTS_PER_BUSINESS:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=f"Receipt limit reached ({MAX_RECEIPTS_PER_BUSINESS}). Delete older receipts before uploading more.",
        )

    # Detect real MIME type from magic bytes
    detected = detect_mime(content)
    if not detected:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="File contents are not a JPEG, PNG or PDF, whatever the file name says.",
        )

    # Storage key: receipts/{businessId}/{uuid}.ext
    ext = extension_for(detected)
    storage_key = f"receipts/{current_user.business_id}/{uuid.uuid4()}{ext}"

    storage = get_storage_service()
    stored = await storage.put(storage_key, content, detected)

    receipt = Receipt(
        id=uuid.uuid4(),
        business_id=business_uuid,
        uploaded_by=current_user.id,
        filename=storage_key,
        mime_type=detected,
        size_bytes=stored.size,
    )
    db.add(receipt)
    await db.commit()
    await db.refresh(receipt)

    return {
        "message": "File uploaded successfully",
        "id": str(receipt.id),
        "url": f"/uploads/receipts/{receipt.id}",
        "mimeType": receipt.mime_type,
        "sizeBytes": receipt.size_bytes,
    }


@router.get("/receipts/{id}")
async def get_receipt(
    id: str,
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
):
    try:
        receipt_uuid = uuid.UUID(id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Receipt not found",
        )

    business_uuid = uuid.UUID(current_user.business_id)
    res = await db.execute(
        select(Receipt).where(
            Receipt.id == receipt_uuid,
            Receipt.business_id == business_uuid,
        )
    )
    receipt = res.scalar_one_or_none()
    if not receipt:
        # Never 403: avoids cross-tenant receipt existence oracle
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Receipt not found",
        )

    storage = get_storage_service()
    if not (await storage.exists(receipt.filename)):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Receipt file is no longer available",
        )

    signed_url = await storage.get_signed_url(receipt.filename)
    if signed_url:
        return RedirectResponse(
            url=signed_url,
            status_code=status.HTTP_302_FOUND,
            headers={"Cache-Control": "private, no-store"},
        )

    try:
        body = await storage.get(receipt.filename)
    except Exception:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Receipt file is no longer available",
        )

    return Response(
        content=body,
        media_type=receipt.mime_type,
        headers={
            "Content-Type": receipt.mime_type,
            "Content-Disposition": f'attachment; filename="receipt-{receipt.id}"',
            "Cache-Control": "private, no-store",
            "X-Content-Type-Options": "nosniff",
            "Content-Length": str(len(body)),
        },
    )
