import uuid
from decimal import Decimal
from typing import List

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.deps import CurrentUser, get_current_user, require_roles
from app.database import get_db
from app.models.audit_log import AuditAction
from app.models.inventory import Item
from app.schemas.inventory import CreateItemDto, ItemResponse, UpdateItemDto
from app.services.audit import record_audit_log

router = APIRouter(prefix="/inventory", tags=["inventory"])


def to_item_response(it: Item) -> ItemResponse:
    return ItemResponse(
        id=str(it.id),
        name=it.name,
        sku=it.sku,
        selling_price=it.selling_price,
        purchase_price=it.purchase_price,
        current_stock=it.current_stock,
        low_stock_threshold=it.low_stock_threshold,
        business_id=str(it.business_id),
        created_at=it.created_at,
        updated_at=it.updated_at,
    )


@router.get(
    "",
    response_model=List[ItemResponse],
    dependencies=[Depends(require_roles("OWNER", "MANAGER", "CASHIER"))],
)
async def list_items(
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
):
    business_uuid = uuid.UUID(current_user.business_id)
    res = await db.execute(
        select(Item).where(Item.business_id == business_uuid).order_by(Item.created_at.desc())
    )
    items = res.scalars().all()
    return [to_item_response(it) for it in items]


@router.get(
    "/low-stock",
    response_model=List[ItemResponse],
    dependencies=[Depends(require_roles("OWNER", "MANAGER"))],
)
async def get_low_stock_items(
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
):
    business_uuid = uuid.UUID(current_user.business_id)
    res = await db.execute(
        select(Item).where(
            Item.business_id == business_uuid,
            Item.current_stock <= Item.low_stock_threshold,
        ).order_by(Item.created_at.desc())
    )
    items = res.scalars().all()
    return [to_item_response(it) for it in items]


@router.get(
    "/{id}",
    response_model=ItemResponse,
    dependencies=[Depends(require_roles("OWNER", "MANAGER", "CASHIER"))],
)
async def get_item(
    id: str,
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
):
    try:
        item_uuid = uuid.UUID(id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Item not found",
        )

    business_uuid = uuid.UUID(current_user.business_id)
    res = await db.execute(
        select(Item).where(Item.id == item_uuid, Item.business_id == business_uuid)
    )
    item = res.scalar_one_or_none()
    if not item:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Item not found",
        )
    return to_item_response(item)


@router.post(
    "",
    response_model=ItemResponse,
    status_code=status.HTTP_201_CREATED,
    dependencies=[Depends(require_roles("OWNER", "MANAGER"))],
)
async def create_item(
    dto: CreateItemDto,
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
):
    business_uuid = uuid.UUID(current_user.business_id)

    item = Item(
        id=uuid.uuid4(),
        name=dto.name,
        sku=dto.sku,
        selling_price=dto.selling_price if dto.selling_price is not None else Decimal("0.00"),
        purchase_price=dto.purchase_price if dto.purchase_price is not None else Decimal("0.00"),
        current_stock=dto.current_stock if dto.current_stock is not None else Decimal("0.00"),
        low_stock_threshold=dto.low_stock_threshold if dto.low_stock_threshold is not None else Decimal("5.00"),
        business_id=business_uuid,
    )
    db.add(item)

    await record_audit_log(
        db=db,
        action=AuditAction.CREATE,
        entity_name="Item",
        entity_id=str(item.id),
        performed_by=current_user.id,
        new_state={"name": item.name, "currentStock": str(item.current_stock)},
    )

    await db.commit()
    await db.refresh(item)
    return to_item_response(item)


@router.put(
    "/{id}",
    response_model=ItemResponse,
    dependencies=[Depends(require_roles("OWNER", "MANAGER"))],
)
async def update_item(
    id: str,
    dto: UpdateItemDto,
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
):
    try:
        item_uuid = uuid.UUID(id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Item not found",
        )

    business_uuid = uuid.UUID(current_user.business_id)
    res = await db.execute(
        select(Item).where(Item.id == item_uuid, Item.business_id == business_uuid)
    )
    item = res.scalar_one_or_none()
    if not item:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Item not found",
        )

    prev_state = {"name": item.name, "currentStock": str(item.current_stock)}

    # Apply only editable fields (never businessId or identity)
    if dto.name is not None:
        item.name = dto.name
    if dto.sku is not None:
        item.sku = dto.sku
    if dto.selling_price is not None:
        item.selling_price = dto.selling_price
    if dto.purchase_price is not None:
        item.purchase_price = dto.purchase_price
    if dto.current_stock is not None:
        item.current_stock = dto.current_stock
    if dto.low_stock_threshold is not None:
        item.low_stock_threshold = dto.low_stock_threshold

    await record_audit_log(
        db=db,
        action=AuditAction.UPDATE,
        entity_name="Item",
        entity_id=str(item.id),
        performed_by=current_user.id,
        previous_state=prev_state,
        new_state={"name": item.name, "currentStock": str(item.current_stock)},
    )

    await db.commit()
    await db.refresh(item)
    return to_item_response(item)


@router.delete(
    "/{id}",
    dependencies=[Depends(require_roles("OWNER"))],
)
async def delete_item(
    id: str,
    db: AsyncSession = Depends(get_db),
    current_user: CurrentUser = Depends(get_current_user),
):
    try:
        item_uuid = uuid.UUID(id)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Item not found",
        )

    business_uuid = uuid.UUID(current_user.business_id)
    res = await db.execute(
        select(Item).where(Item.id == item_uuid, Item.business_id == business_uuid)
    )
    item = res.scalar_one_or_none()
    if not item:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Item not found",
        )

    await record_audit_log(
        db=db,
        action=AuditAction.DELETE,
        entity_name="Item",
        entity_id=str(item.id),
        performed_by=current_user.id,
        previous_state={"name": item.name},
    )

    await db.delete(item)
    await db.commit()
    return {"message": "Item deleted successfully"}
