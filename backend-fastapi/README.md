# Open Khata - FastAPI Backend

High-performance, async Python 3.12 + FastAPI backend for Open Khata, engineered side-by-side with the NestJS backend to preserve 100% Android Retrofit and Gson client parity.

---

## Key Architectural & Contract Guarantees

1. **Exact Error Shape Parity**:
   All error responses conform strictly to the NestJS format:
   ```json
   {
     "statusCode": 400,
     "message": "Human readable error string",
     "error": "Bad Request"
   }
   ```
   - Validation failures return `400` (never `422`).
   - Authentication failures return `401` (never `403` or `422`), preserving Android's `shouldSignalSessionExpiry` session handling.

2. **Route Prefix Consistency**:
   Matches the Android client's Retrofit `@GET` / `@POST` paths:
   - Nested API prefix: `/api/v1/ledger`, `/api/v1/parties`, `/api/v1/scanning`, `/api/v1/reminders`
   - Top-level routes: `/health`, `/auth`, `/users`, `/businesses`, `/bills`, `/inventory`, `/reports`, `/uploads`

3. **Database & Schema Invariants**:
   - Built directly on top of the existing Supabase Postgres database.
   - Preserves all 10 TypeORM tables and mixed casing conventions (`businessId` vs `business_id`, quoted `"createdAt"`, `"currentBalance"`, `"currentOtp"`, `"sessionVersion"`).
   - Zero statement caching (`statement_cache_size=0`) to safely cooperate with Supabase pooler / PgBouncer in transaction mode.
   - Money decimals serialized to exact string format (e.g. `"1250.50"`), preventing float precision bugs.
   - `BillResponse` serializes `created_at` in snake_case to match Gson expectations.

4. **Security & Data Isolation**:
   - Complete multi-tenant scoping across all CRUD operations.
   - Client-generated UUID idempotency prevents duplicate entry creation on mobile network retries.
   - Cross-tenant requests to private resources answer `404 Not Found` (never `403`) to avoid resource enumeration oracles.
   - Magic byte content-sniffing for uploads (JPEG, PNG, PDF) with local and S3 object storage adapters.
   - Pessimistic locking (`FOR UPDATE`) for ledger entries with concurrent balance protection.

---

## Module Breakdown

| Module | Route Prefix | Key Responsibilities |
| :--- | :--- | :--- |
| **Health** | `/health` | Uptime check and DB ping |
| **Auth** | `/auth` | Phone OTP request (TextBee), verification, lockout, and JWT issuance |
| **Users** | `/users` | Staff member management and user profile retrieval |
| **Businesses** | `/businesses` | Business CRUD, member roles (`OWNER`, `MANAGER`, `CASHIER`) |
| **Parties** | `/api/v1/parties` | Customer/Supplier CRUD, interest rate rules, and collection frequency |
| **Ledger** | `/api/v1/ledger` | Cashbook feed, balance delta calculation, and audit trail |
| **Bills** | `/bills` | Invoice creation with JSON line items and client idempotency |
| **Inventory** | `/inventory` | Stock tracking, low-stock alerts, and role-based access control |
| **Reports** | `/reports` | Cashflow, Sales summaries, and downloadable PDF statements |
| **Uploads** | `/uploads` | Receipt upload with magic-byte validation and secure streaming |
| **Scanning** | `/api/v1/scanning` | Gemini multimodal OCR with strict ISO date sanitization |
| **Reminders** | `/api/v1/reminders` | Payment reminders with per-party cooldown and automated cron loop |

---

## Running the Application

### 1. Prerequisites
- Python 3.12+
- `uv` package manager (`curl -LsSf https://astral.sh/uv/install.sh` or `winget install astral-sh.uv`)

### 2. Environment Configuration
The application reads from `.env` in the current folder or falls back to `../backend/.env`.

Key environment variables:
```env
PORT=3000
NODE_ENV=development
DATABASE_URL=postgresql://postgres:[password]@db.[project].supabase.co:5432/postgres
JWT_SECRET=your_jwt_secret_here

# SMS (OTP Only)
TEXTBEE_API_KEY=
TEXTBEE_DEVICE_ID=

# S3 / Object Storage (Optional, falls back to local disk in dev)
S3_BUCKET=
S3_ACCESS_KEY_ID=
S3_SECRET_ACCESS_KEY=

# AI Scanning (Optional, falls back to mock in dev)
GEMINI_API_KEY=

# Automated Reminders
REMINDERS_ENABLED=false
```

### 3. Running Locally
```bash
uv run uvicorn app.main:app --host 0.0.0.0 --port 3000 --reload
```

### 4. Running the Test Suite
The full integration and unit test suite runs completely in-memory with `aiosqlite`:
```bash
uv run pytest -v
```
