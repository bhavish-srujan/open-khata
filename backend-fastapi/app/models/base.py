from datetime import datetime, timezone


def utc_now_naive() -> datetime:
    """Returns naive UTC datetime matching Postgres TIMESTAMP naive columns."""
    return datetime.now(timezone.utc).replace(tzinfo=None)
