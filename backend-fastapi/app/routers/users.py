import uuid
from typing import List

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.auth.deps import CurrentUser, get_current_user, require_roles
from app.auth.security import normalize_phone_for_storage
from app.database import get_db
from app.models.business_member import BusinessMember
from app.models.user import User, UserRole
from app.schemas.user import (
    AddStaffDto,
    StaffMemberResponse,
    UpdateMeDto,
    UserResponse,
)

router = APIRouter(prefix="/users", tags=["users"])


@router.get("/staff", response_model=List[StaffMemberResponse])
async def get_staff(
    current_user: CurrentUser = Depends(
        require_roles(UserRole.OWNER, UserRole.MANAGER)
    ),
    db: AsyncSession = Depends(get_db),
):
    business_uuid = uuid.UUID(current_user.business_id)
    res = await db.execute(
        select(BusinessMember)
        .options(selectinload(BusinessMember.user))
        .where(BusinessMember.business_id == business_uuid)
        .order_by(BusinessMember.created_at.asc())
    )
    members = res.scalars().all()

    return [
        StaffMemberResponse(
            membership_id=str(m.id),
            user_id=str(m.user_id),
            name=m.user.name if m.user else None,
            phone_number=m.user.phone_number if m.user else "",
            role=m.role,
            added_at=m.created_at,
        )
        for m in members
    ]


@router.post("/staff", response_model=StaffMemberResponse)
async def add_staff(
    dto: AddStaffDto,
    current_user: CurrentUser = Depends(
        require_roles(UserRole.OWNER, UserRole.MANAGER)
    ),
    db: AsyncSession = Depends(get_db),
):
    if dto.role == UserRole.OWNER:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot add another owner",
        )

    normalized = normalize_phone_for_storage(dto.phone_number)
    if not normalized:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="A valid phone number is required",
        )

    business_uuid = uuid.UUID(current_user.business_id)

    # Re-use existing user account if present, or create
    res = await db.execute(select(User).where(User.normalized_phone == normalized))
    user = res.scalar_one_or_none()

    if not user:
        user = User(
            phone_number=dto.phone_number,
            normalized_phone=normalized,
            name=dto.name,
            role=dto.role,
        )
        db.add(user)
        await db.flush()

    # Check existing membership in this business
    existing_res = await db.execute(
        select(BusinessMember).where(
            BusinessMember.business_id == business_uuid,
            BusinessMember.user_id == user.id,
        )
    )
    existing = existing_res.scalar_one_or_none()
    if existing:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="This person is already a member of this business",
        )

    member = BusinessMember(
        business_id=business_uuid,
        user_id=user.id,
        role=dto.role,
    )
    db.add(member)
    await db.commit()

    return StaffMemberResponse(
        membership_id=str(member.id),
        user_id=str(user.id),
        name=user.name or dto.name,
        phone_number=user.phone_number,
        role=member.role,
        added_at=member.created_at,
    )


@router.delete("/staff/{membership_id}", status_code=status.HTTP_204_NO_CONTENT)
async def remove_staff(
    membership_id: str,
    current_user: CurrentUser = Depends(
        require_roles(UserRole.OWNER, UserRole.MANAGER)
    ),
    db: AsyncSession = Depends(get_db),
):
    try:
        m_uuid = uuid.UUID(membership_id)
        b_uuid = uuid.UUID(current_user.business_id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Staff member not found in this business",
        )

    res = await db.execute(
        select(BusinessMember).where(
            BusinessMember.id == m_uuid,
            BusinessMember.business_id == b_uuid,
        )
    )
    member = res.scalar_one_or_none()

    if not member:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Staff member not found in this business",
        )

    if member.role == UserRole.OWNER:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Cannot remove an owner",
        )

    if str(member.user_id) == current_user.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You cannot remove yourself",
        )

    await db.delete(member)
    await db.commit()
    return None


@router.put("/me", response_model=UserResponse)
async def update_me(
    dto: UpdateMeDto,
    current_user: CurrentUser = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    user = current_user.user
    if dto.name is not None:
        user.name = dto.name
    await db.commit()
    await db.refresh(user)

    return UserResponse(
        id=str(user.id),
        phone_number=user.phone_number,
        name=user.name,
        role=user.role,
        created_at=user.created_at,
        updated_at=user.updated_at,
    )
