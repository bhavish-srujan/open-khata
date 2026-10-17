from fastapi import APIRouter, Depends, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.deps import CurrentUser, get_current_user, require_roles
from app.database import get_db
from app.limiter import limiter
from app.schemas.reminder import SendReminderDto
from app.services.reminders import reminders_service

router = APIRouter(prefix="/api/v1/reminders", tags=["reminders"])


@router.post(
    "/send",
    status_code=status.HTTP_200_OK,
    dependencies=[Depends(require_roles("OWNER", "MANAGER"))],
)
@limiter.limit("5/minute")
async def send_reminder(
    request: Request,
    dto: SendReminderDto,
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
):
    return await reminders_service.send_reminder_for_party(
        db=db,
        party_id=dto.party_id,
        business_id=current_user.business_id,
    )
