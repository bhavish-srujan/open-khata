import json
import logging
import sys
import time
from contextvars import ContextVar
from typing import Any, Dict, Optional

# Context variable tracking current request ID across async coroutines
request_id_ctx_var: ContextVar[str] = ContextVar("request_id", default="")


class ColorCodes:
    RESET = "\033[0m"
    BOLD = "\033[1m"
    DIM = "\033[2m"
    CYAN = "\033[36m"
    GREEN = "\033[32m"
    YELLOW = "\033[33m"
    RED = "\033[31m"
    BOLD_RED = "\033[1;31m"
    PURPLE = "\033[35m"


LEVEL_COLORS = {
    logging.DEBUG: ColorCodes.CYAN,
    logging.INFO: ColorCodes.GREEN,
    logging.WARNING: ColorCodes.YELLOW,
    logging.ERROR: ColorCodes.RED,
    logging.CRITICAL: ColorCodes.BOLD_RED,
}


class ConsoleTextFormatter(logging.Formatter):
    """
    Colorized and structured console formatter with request correlation ID support.
    """

    def format(self, record: logging.LogRecord) -> str:
        req_id = request_id_ctx_var.get()
        color = LEVEL_COLORS.get(record.levelno, ColorCodes.RESET)
        reset = ColorCodes.RESET
        dim = ColorCodes.DIM

        # Format timestamp
        timestamp = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(record.created))

        # Format request ID tag
        req_tag = f"{ColorCodes.PURPLE}[{req_id[:8]}]{reset} " if req_id else ""

        # Format level name with color
        level_str = f"{color}{record.levelname:<7}{reset}"

        # Logger name
        logger_name = f"{dim}{record.name}{reset}"

        # Message
        message = record.getMessage()

        formatted = f"{dim}{timestamp}{reset} | {level_str} | {req_tag}{logger_name} - {message}"

        if record.exc_info:
            formatted += f"\n{self.formatException(record.exc_info)}"

        return formatted


class JsonLogFormatter(logging.Formatter):
    """
    Structured JSON log formatter for production environments (e.g., Datadog, CloudWatch, GCP).
    """

    def format(self, record: logging.LogRecord) -> str:
        log_obj: Dict[str, Any] = {
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(record.created)),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }

        req_id = request_id_ctx_var.get()
        if req_id:
            log_obj["request_id"] = req_id

        if record.exc_info:
            log_obj["exception"] = self.formatException(record.exc_info)

        return json.dumps(log_obj)


class PlainFileFormatter(logging.Formatter):
    """
    Clean, uncolored structured formatter for file logging.
    """

    def format(self, record: logging.LogRecord) -> str:
        req_id = request_id_ctx_var.get()
        timestamp = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(record.created))
        req_tag = f"[{req_id[:8]}] " if req_id else ""
        formatted = f"{timestamp} | {record.levelname:<7} | {req_tag}{record.name} - {record.getMessage()}"
        if record.exc_info:
            formatted += f"\n{self.formatException(record.exc_info)}"
        return formatted


def setup_logging(log_level: str = "INFO", log_format: str = "text") -> None:
    """
    Configures application-wide logging handlers and formats for root and sub-loggers.
    """
    numeric_level = getattr(logging, log_level.upper(), logging.INFO)

    # Choose formatter
    formatter: logging.Formatter
    if log_format.lower() == "json":
        formatter = JsonLogFormatter()
    else:
        formatter = ConsoleTextFormatter()

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(formatter)
    handler.setLevel(numeric_level)

    # File handler to write directly to server.log
    file_handler = logging.FileHandler("server.log", encoding="utf-8")
    file_handler.setFormatter(PlainFileFormatter())
    file_handler.setLevel(numeric_level)

    # Configure root logger with the proper handlers
    root_logger = logging.getLogger()
    root_logger.setLevel(numeric_level)
    root_logger.handlers.clear()
    # console_handler writes to stdout with optional colors
    root_logger.addHandler(console_handler)
    # debug_file_handler writes plain text OTP/dev logs
    root_logger.addHandler(debug_file_handler)
    # server_file_handler writes all logs to server.log using PlainFileFormatter
    root_logger.addHandler(server_file_handler)

    # Silence overly verbose external libraries
    logging.getLogger("uvicorn.access").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.WARNING)


def get_logger(name: str) -> logging.Logger:
    """
    Returns a configured logger instance with the given module name.
    """
    return logging.getLogger(name)
