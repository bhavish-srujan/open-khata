import time
import uuid
from typing import Callable

from fastapi import Request, Response
from starlette.middleware.base import BaseHTTPMiddleware

from app.logger import get_logger, request_id_ctx_var

logger = get_logger("open_khata.http")


class RequestLoggingMiddleware(BaseHTTPMiddleware):
    """
    HTTP middleware that tracks request execution time, assigns or propagates
    X-Request-ID correlation tokens, and logs structured access summaries.
    """

    async def dispatch(self, request: Request, call_next: Callable) -> Response:
        # 1. Resolve or generate correlation request ID
        req_id = request.headers.get("X-Request-ID") or str(uuid.uuid4())
        token = request_id_ctx_var.set(req_id)

        # 2. Extract request metadata
        client_host = request.client.host if request.client else "unknown"
        method = request.method
        path = request.url.path
        if request.url.query:
            path = f"{path}?{request.url.query}"

        start_time = time.perf_counter()
        logger.info("--> %s %s [client=%s]", method, path, client_host)

        try:
            response = await call_next(request)
            duration_ms = (time.perf_counter() - start_time) * 1000

            # Attach tracking headers
            response.headers["X-Request-ID"] = req_id
            response.headers["X-Process-Time"] = f"{duration_ms:.2f}ms"

            status_code = response.status_code
            if status_code >= 500:
                logger.error("<-- %d %s %s in %.2fms", status_code, method, path, duration_ms)
            elif status_code >= 400:
                logger.warning("<-- %d %s %s in %.2fms", status_code, method, path, duration_ms)
            else:
                logger.info("<-- %d %s %s in %.2fms", status_code, method, path, duration_ms)

            return response
        except Exception as exc:
            duration_ms = (time.perf_counter() - start_time) * 1000
            logger.error("<!- 500 %s %s FAILED in %.2fms: %s", method, path, duration_ms, exc)
            raise
        finally:
            request_id_ctx_var.reset(token)
