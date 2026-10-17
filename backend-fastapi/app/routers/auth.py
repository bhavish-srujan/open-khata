import logging
import random
import uuid
from datetime import datetime, timedelta, timezone
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.deps import CurrentUser, get_current_user
from app.auth.security import (
    create_access_token,
    hash_otp,
    normalize_phone_for_storage,
    timing_safe_equals,
)
from app.config import settings
from app.database import get_db
from app.limiter import limiter
from app.models.business import Business
from app.models.business_member import BusinessMember
from app.models.user import User, UserRole
from app.schemas.auth import (
    LoginResponse,
    LogoutResponse,
    RequestOtpDto,
    RequestOtpResponse,
    VerifyOtpDto,
)
from app.services.sms import sms_service

router = APIRouter(prefix="/auth", tags=["auth"])
logger = logging.getLogger(__name__)

OTP_TTL_MINUTES = 10
MAX_OTP_ATTEMPTS = 5


async def find_user_by_phone(db: AsyncSession, phone_number: str) -> Optional[User]:
    if not phone_number:
        return None
    normalized = normalize_phone_for_storage(phone_number)
    if not normalized:
        return None

    # 1. Look up by normalized_phone
    res = await db.execute(select(User).where(User.normalized_phone == normalized))
    by_norm = res.scalar_one_or_none()
    if by_norm:
        return by_norm

    # 2. Look up by raw phone_number
    res = await db.execute(select(User).where(User.phone_number == phone_number))
    by_raw = res.scalar_one_or_none()
    if by_raw:
        if by_raw.normalized_phone != normalized:
            by_raw.normalized_phone = normalized
            await db.commit()
        return by_raw

    # 3. Fallback: match digits in stored phone_number
    res = await db.execute(
        select(User).where(
            func.regexp_replace(User.phone_number, r"\D", "", "g") == normalized
        )
    )
    by_digits = res.scalar_one_or_none()
    if by_digits:
        if by_digits.normalized_phone != normalized:
            by_digits.normalized_phone = normalized
            await db.commit()
        return by_digits

    return None


@router.post("/request-otp", response_model=RequestOtpResponse)
async def request_otp(
    dto: RequestOtpDto,
    db: AsyncSession = Depends(get_db),
):
    """
    Deliberately unthrottled: no per-IP limit and no resend cooldown.
    Matches NestJS AuthService.requestOtp.
    """
    phone_number = dto.phone_number
    if not phone_number:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Phone number is required",
        )

    if not sms_service.is_configured() and settings.NODE_ENV == "production":
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="OTP delivery is not configured. Please try again later.",
        )

    user = await find_user_by_phone(db, phone_number)
    if not user:
        normalized = normalize_phone_for_storage(phone_number)
        user = User(
            phone_number=phone_number,
            normalized_phone=normalized,
        )
        db.add(user)
        await db.flush()

    # Generate cryptographically strong random 6-digit OTP
    otp_code = str(random.SystemRandom().randint(100000, 999999))
    now = datetime.now(timezone.utc).replace(tzinfo=None)
    expiry = now + timedelta(minutes=OTP_TTL_MINUTES)

    # Prominently display OTP for development – use logger (cross‑platform safe)
    logger.info(
        ">>> [DEV] GENERATED OTP FOR %s: %s <<<",
        phone_number,
        otp_code,
    )

    user.current_otp = hash_otp(otp_code)
    user.otp_expiry = expiry
    user.otp_attempts = 0
    await db.commit()

    sent = await sms_service.send_otp_sms(
        phone_number,
        f"Your Open Khata login code is {otp_code}. It is valid for 10 minutes.",
    )

    if not sent and settings.NODE_ENV == "production":
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Failed to send OTP. Please try again later.",
        )

    dev_message = f"OTP Sent successfully (DEV OTP: {otp_code})" if settings.NODE_ENV != "production" else "OTP Sent successfully"
    return RequestOtpResponse(
        success=True,
        message=dev_message,
        otp=otp_code,
    )


@router.post("/verify-otp", response_model=LoginResponse)
@limiter.limit("10/minute")
async def verify_otp(
    request: Request,
    dto: VerifyOtpDto,
    db: AsyncSession = Depends(get_db),
):
    phone_number = dto.phone_number
    otp = dto.otp

    if not phone_number or not otp:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Phone number and OTP are required",
        )

    user = await find_user_by_phone(db, phone_number)
    if not user or not user.current_otp:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="No OTP requested for this number",
        )

    now = datetime.now(timezone.utc).replace(tzinfo=None)
    # Expiry checked before hash comparison
    if user.otp_expiry and now > user.otp_expiry:
        user.current_otp = None
        user.otp_expiry = None
        user.otp_attempts = 0
        await db.commit()
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="OTP has expired",
        )

    if not timing_safe_equals(user.current_otp, hash_otp(otp)):
        user.otp_attempts = (user.otp_attempts or 0) + 1
        if user.otp_attempts >= MAX_OTP_ATTEMPTS:
            user.current_otp = None
            user.otp_expiry = None
            user.otp_attempts = 0
            await db.commit()
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Too many incorrect attempts. Please request a new code.",
            )

        await db.commit()
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid OTP",
        )

    # OTP matched: reset OTP state & bump sessionVersion
    user.current_otp = None
    user.otp_expiry = None
    user.otp_attempts = 0
    user.session_version = (user.session_version or 1) + 1
    user.normalized_phone = normalize_phone_for_storage(user.phone_number)
    await db.commit()

    is_new_user = not bool(user.name)

    # Resolve business membership, ordered by createdAt ASC
    members_res = await db.execute(
        select(BusinessMember)
        .where(BusinessMember.user_id == user.id)
        .order_by(BusinessMember.created_at.asc())
    )
    memberships = members_res.scalars().all()

    # Prefer owned membership
    owned = next((m for m in memberships if m.role == UserRole.OWNER), None)
    active_m = owned or (memberships[0] if memberships else None)

    business = None
    role = UserRole.OWNER

    if active_m:
        b_res = await db.execute(
            select(Business).where(Business.id == active_m.business_id)
        )
        business = b_res.scalar_one_or_none()
        role = active_m.role

    if not business:
        business = Business(
            owner_id=user.id,
            name="My Khata Business",
            type="General Store",
        )
        db.add(business)
        await db.flush()

        member = BusinessMember(
            business_id=business.id,
            user_id=user.id,
            role=UserRole.OWNER,
        )
        db.add(member)
        await db.commit()
        role = UserRole.OWNER

    claims = {
        "sub": str(user.id),
        "phone": user.phone_number,
        "businessId": str(business.id),
        "role": role,
        "sessionVersion": user.session_version,
    }

    token = create_access_token(claims)
    return LoginResponse(access_token=token, is_new_user=is_new_user)


@router.post("/logout", response_model=LogoutResponse)
async def logout(
    current_user: CurrentUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    user = current_user.user
    user.session_version = (user.session_version or 1) + 1
    await db.commit()
    return LogoutResponse(success=True)
