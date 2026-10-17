import json
import pytest
from fastapi import APIRouter, HTTPException
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel, Field

from app.main import app

error_test_router = APIRouter(prefix="/test-errors")


class DummyPayload(BaseModel):
    name: str = Field(min_length=3)
    amount: float = Field(gt=0)


@error_test_router.post("/validation")
async def dummy_validation_route(payload: DummyPayload):
    return {"success": True}


@error_test_router.get("/unauthorized")
async def dummy_unauthorized_route():
    raise HTTPException(status_code=401, detail="Invalid token")


@error_test_router.get("/server-error")
async def dummy_server_error_route():
    raise RuntimeError("Unexpected failure in service")


# Mount test routes
app.include_router(error_test_router)


@pytest.mark.asyncio
async def test_validation_error_shape():
    """FastAPI validation error MUST return 400 (not 422) with string message for Android Gson."""
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        # Invalid body (name too short, amount <= 0)
        res = await client.post(
            "/test-errors/validation",
            json={"name": "a", "amount": -10},
        )
        assert res.status_code == 400
        data = res.json()
        assert data["statusCode"] == 400
        assert data["error"] == "Bad Request"
        # message MUST be a single string for Android ApiErrorBody(val message: String?)
        assert isinstance(data["message"], str)
        assert "name" in data["message"]
        assert "amount" in data["message"]


@pytest.mark.asyncio
async def test_401_error_shape():
    """Auth failures must stay 401 and conform to NestJS shape."""
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        res = await client.get("/test-errors/unauthorized")
        assert res.status_code == 401
        data = res.json()
        assert data["statusCode"] == 401
        assert data["message"] == "Invalid token"
        assert data["error"] == "Unauthorized"


@pytest.mark.asyncio
async def test_404_error_shape():
    """Non-existent routes must return 404 in NestJS shape."""
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        res = await client.get("/non-existent-route-404")
        assert res.status_code == 404
        data = res.json()
        assert data["statusCode"] == 404
        assert data["error"] == "Not Found"
        assert isinstance(data["message"], str)


@pytest.mark.asyncio
async def test_500_error_shape():
    """Unhandled exceptions must return 500 in NestJS shape."""
    transport = ASGITransport(app=app, raise_app_exceptions=False)
    async with AsyncClient(
        transport=transport, base_url="http://test"
    ) as client:
        res = await client.get("/test-errors/server-error")
        assert res.status_code == 500
        data = res.json()
        assert data["statusCode"] == 500
        assert data["message"] == "Internal server error"
        assert data["error"] == "Internal Server Error"
