import uuid
from dataclasses import dataclass
from typing import Callable, List, Optional

import jwt
from fastapi import Depends, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.security import decode_access_token
from app.database import get_db
from app.models.business_member import BusinessMember
from app.models.user import User, UserRole


@dataclass
class CurrentUser:
    id: str
    phone: str
    business_id: str
    role: str
    session_version: int
    user: User


def read_single_header(request: Request, name: str) -> Optional[str]:
    raw_list = request.headers.getlist(name)
    if len(raw_list) > 1:
        # A repeated header is rejected rather than guessed at
        return None
    val = request.headers.get(name)
    return val.strip() if val and val.strip() else None


async def get_current_user(
    request: Request,
    db: AsyncSession = Depends(get_db),
) -> CurrentUser:
    auth_header = request.headers.get("Authorization")
    if not auth_header or not auth_header.startswith("Bearer "):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing or invalid Authorization header",
        )

    token = auth_header.split(" ", 1)[1].strip()

    try:
        payload = decode_access_token(token)
    except jwt.PyJWTError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token is invalid or expired",
        )

    user_id_str = payload.get("sub")
    session_version = payload.get("sessionVersion")

    if not user_id_str or session_version is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token is invalid or expired",
        )

    try:
        user_uuid = uuid.UUID(user_id_str)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token is invalid or expired",
        )

    user_result = await db.execute(select(User).where(User.id == user_uuid))
    user = user_result.scalar_one_or_none()

    if not user or user.session_version != session_version:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Session expired. Logged in from another device.",
        )

    # Active business may be switched per request via x-business-id header
    requested_business_id = (
        read_single_header(request, "x-business-id") or payload.get("businessId")
    )

    active_business_id = str(requested_business_id) if requested_business_id else ""
    active_role = payload.get("role", UserRole.OWNER)

    if requested_business_id:
        try:
            business_uuid = uuid.UUID(requested_business_id)
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="You no longer have access to this business.",
            )

        member_result = await db.execute(
            select(BusinessMember).where(
                BusinessMember.business_id == business_uuid,
                BusinessMember.user_id == user.id,
            )
        )
        membership = member_result.scalar_one_or_none()

        if not membership:
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="You no longer have access to this business.",
            )

        active_business_id = str(membership.business_id)
        active_role = membership.role

    return CurrentUser(
        id=str(user.id),
        phone=user.phone_number,
        business_id=active_business_id,
        role=active_role,
        session_version=user.session_version,
        user=user,
    )


def require_roles(*allowed_roles: str) -> Callable:
    async def role_checker(
        current_user: CurrentUser = Depends(get_current_user),
    ) -> CurrentUser:
        if current_user.role not in allowed_roles:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Forbidden resource",
            )
        return current_user

    return role_checker
