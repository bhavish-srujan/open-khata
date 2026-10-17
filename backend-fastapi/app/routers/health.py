from datetime import datetime, timezone
from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter(tags=["health"])


class HealthResponse(BaseModel):
    status: str
    timestamp: str


@router.get("/health", response_model=HealthResponse)
async def get_health():
    """
    Parity with NestJS HealthController:
    returns { status: 'ok', timestamp: new Date().toISOString() }
    """
    now_iso = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
    return HealthResponse(status="ok", timestamp=now_iso)
