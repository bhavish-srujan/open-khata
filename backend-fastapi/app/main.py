import asyncio
import logging
from contextlib import asynccontextmanager
from typing import AsyncGenerator

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from slowapi.errors import RateLimitExceeded
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.config import settings
from app.database import engine
from app.exceptions import (
    http_exception_handler,
    unhandled_exception_handler,
    validation_exception_handler,
)
from app.limiter import limiter
from app.routers import (
    auth_router,
    bills_router,
    businesses_router,
    health_router,
    inventory_router,
    ledger_router,
    parties_router,
    reminders_router,
    reports_router,
    scanning_router,
    uploads_router,
    users_router,
)

from app.logger import get_logger, setup_logging
from app.middleware import RequestLoggingMiddleware

setup_logging(settings.LOG_LEVEL, settings.LOG_FORMAT)
logger = get_logger("open_khata")


async def self_ping_worker(url: str):
    """Periodically ping self_ping_url every 14 minutes to prevent free-tier instances from idling."""
    interval_seconds = 14 * 60
    async with httpx.AsyncClient(timeout=10.0) as client:
        while True:
            try:
                await asyncio.sleep(interval_seconds)
                res = await client.get(url)
                logger.info("Self-ping successful: %d", res.status_code)
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error("Self-ping failed: %s", e)


async def reminders_worker():
    """Background worker for automated collection reminders if enabled."""
    while True:
        try:
            from app.database import AsyncSessionLocal
            from app.services.reminders import run_daily_reminders

            async with AsyncSessionLocal() as session:
                await run_daily_reminders(session)
            # Sleep 24 hours between runs
            await asyncio.sleep(24 * 3600)
        except asyncio.CancelledError:
            break
        except Exception as e:
            logger.error("Error in reminders worker: %s", e)
            await asyncio.sleep(3600)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    ping_task = None
    reminders_task = None

    if settings.SELF_PING_URL:
        logger.info("Starting self-ping worker for %s", settings.SELF_PING_URL)
        ping_task = asyncio.create_task(self_ping_worker(settings.SELF_PING_URL))

    if settings.REMINDERS_ENABLED:
        logger.info("Starting automated reminders worker")
        reminders_task = asyncio.create_task(reminders_worker())

    yield

    if ping_task:
        ping_task.cancel()
        try:
            await ping_task
        except asyncio.CancelledError:
            pass

    if reminders_task:
        reminders_task.cancel()
        try:
            await reminders_task
        except asyncio.CancelledError:
            pass

    # Clean up DB engine connections
    await engine.dispose()


async def rate_limit_exceeded_handler(
    request: Request, exc: RateLimitExceeded
) -> JSONResponse:
    """Emits the NestJS Throttler error shape on rate limit exceeded."""
    return JSONResponse(
        status_code=429,
        content={
            "statusCode": 429,
            "message": "ThrottlerException: Too Many Requests",
            "error": "Too Many Requests",
        },
    )


def create_app() -> FastAPI:
    app = FastAPI(
        title="Open Khata API",
        version="1.0.0",
        lifespan=lifespan,
    )

    # Attach limiter to application state
    app.state.limiter = limiter

    # Request tracking and access logging middleware
    app.add_middleware(RequestLoggingMiddleware)

    # CORS configuration
    allowed_origins = settings.cors_origins_list
    if allowed_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=allowed_origins,
            allow_credentials=True,
            allow_methods=["*"],
            allow_headers=["*"],
        )

    # Exception Handlers to preserve the Android & NestJS contract
    app.add_exception_handler(RequestValidationError, validation_exception_handler)
    app.add_exception_handler(RateLimitExceeded, rate_limit_exceeded_handler)
    app.add_exception_handler(StarletteHTTPException, http_exception_handler)
    app.add_exception_handler(Exception, unhandled_exception_handler)

    # Routers (matching exact route paths: /health, /auth, /users, /businesses, /api/v1/parties, /api/v1/ledger)
    app.include_router(health_router)
    app.include_router(auth_router)
    app.include_router(users_router)
    app.include_router(businesses_router)
    app.include_router(parties_router)
    app.include_router(ledger_router)
    app.include_router(bills_router)
    app.include_router(inventory_router)
    app.include_router(reports_router)
    app.include_router(uploads_router)
    app.include_router(scanning_router)
    app.include_router(reminders_router)

    return app


app = create_app()

if __name__ == "__main__":
    import uvicorn

    uvicorn.run("app.main:app", host="0.0.0.0", port=settings.PORT, reload=True)
