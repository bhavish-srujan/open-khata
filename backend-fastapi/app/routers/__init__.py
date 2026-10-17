from app.routers.auth import router as auth_router
from app.routers.bills import router as bills_router
from app.routers.businesses import router as businesses_router
from app.routers.health import router as health_router
from app.routers.inventory import router as inventory_router
from app.routers.ledger import router as ledger_router
from app.routers.parties import router as parties_router
from app.routers.reminders import router as reminders_router
from app.routers.reports import router as reports_router
from app.routers.scanning import router as scanning_router
from app.routers.uploads import router as uploads_router
from app.routers.users import router as users_router

__all__ = [
    "health_router",
    "auth_router",
    "users_router",
    "businesses_router",
    "parties_router",
    "ledger_router",
    "bills_router",
    "inventory_router",
    "reports_router",
    "uploads_router",
    "scanning_router",
    "reminders_router",
]
