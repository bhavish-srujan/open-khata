from fastapi import APIRouter, Depends, File, HTTPException, Request, UploadFile, status

from app.auth.deps import CurrentUser, get_current_user
from app.limiter import limiter
from app.services.scanning import extract_ledger_data

router = APIRouter(prefix="/api/v1/scanning", tags=["scanning"])

MAX_IMAGE_BYTES = 10 * 1024 * 1024


@router.post("/extract")
@limiter.limit("10/minute")
async def extract_ledger(
    request: Request,
    image: UploadFile = File(None),
    current_user: CurrentUser = Depends(get_current_user),
):
    if not image:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="No image file provided or image exceeds the 10MB limit",
        )

    content = await image.read()
    if not content or len(content) > MAX_IMAGE_BYTES:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="No image file provided or image exceeds the 10MB limit",
        )

    try:
        return await extract_ledger_data(content, image.content_type or "image/jpeg")
    except ValueError as e:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=str(e),
        )
    except Exception as e:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to extract ledger data: {str(e)}",
        )
