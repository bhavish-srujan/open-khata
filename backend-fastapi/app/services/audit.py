import uuid
from typing import Any, Optional

from sqlalchemy.ext.asyncio import AsyncSession

from app.models.audit_log import AuditAction, AuditLog


async def record_audit_log(
    db: AsyncSession,
    action: AuditAction,
    entity_name: str,
    entity_id: str,
    performed_by: str,
    previous_state: Optional[Any] = None,
    new_state: Optional[Any] = None,
) -> AuditLog:
    """
    Records an AuditLog row inside the caller's active database transaction.
    Guarantees audit rows commit in the exact same transaction as the mutated entity.
    """
    log_entry = AuditLog(
        action=action.value if hasattr(action, "value") else str(action),
        entity_name=entity_name,
        entity_id=str(entity_id),
        performed_by=performed_by or "SYSTEM",
        previous_state=previous_state,
        new_state=new_state,
    )
    db.add(log_entry)
    return log_entry
