import logging
from http import HTTPStatus
from typing import Any

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

logger = logging.getLogger("open_khata.errors")


def get_status_phrase(status_code: int) -> str:
    try:
        return HTTPStatus(status_code).phrase
    except ValueError:
        return "Error"


async def http_exception_handler(
    request: Request, exc: StarletteHTTPException
) -> JSONResponse:
    """
    Ensures all HTTP exceptions conform to the NestJS shape:
    {"statusCode": ..., "message": "...", "error": "..."}
    where `message` is guaranteed to be a string.
    """
    phrase = get_status_phrase(exc.status_code)

    if isinstance(exc.detail, str):
        message = exc.detail
    elif isinstance(exc.detail, dict) and "message" in exc.detail:
        message = str(exc.detail["message"])
    else:
        message = str(exc.detail)

    return JSONResponse(
        status_code=exc.status_code,
        content={
            "statusCode": exc.status_code,
            "message": message,
            "error": phrase,
        },
    )


async def validation_exception_handler(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    """
    Translates FastAPI's 422 RequestValidationError into NestJS's 400 Bad Request
    with a single string `message` so Android Gson can parse it into ApiErrorBody.
    """
    messages = []
    for err in exc.errors():
        # Strip internal markers like 'body' from field locations
        loc_parts = [str(l) for l in err.get("loc", []) if l != "body"]
        loc_str = ".".join(loc_parts)
        msg_str = err.get("msg", "")

        if loc_str:
            messages.append(f"{loc_str}: {msg_str}")
        else:
            messages.append(msg_str)

    error_str = "; ".join(messages) if messages else "Validation failed"

    return JSONResponse(
        status_code=400,
        content={
            "statusCode": 400,
            "message": error_str,
            "error": "Bad Request",
        },
    )


async def unhandled_exception_handler(
    request: Request, exc: Exception
) -> JSONResponse:
    """
    Catches unhandled 500 errors and returns the standard NestJS error shape.
    """
    logger.exception("Unhandled server error processing %s: %s", request.url.path, exc)
    return JSONResponse(
        status_code=500,
        content={
            "statusCode": 500,
            "message": "Internal server error",
            "error": "Internal Server Error",
        },
    )
