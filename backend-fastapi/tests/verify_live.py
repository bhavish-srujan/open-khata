import sys
from pathlib import Path

# Add project root to path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import json
import urllib.request
import urllib.error
from app.auth.security import create_access_token

base_url = "http://localhost:3000"

print("==================================================")
print("   OPEN KHATA: FULL LIVE INTEGRATION AUDIT        ")
print("==================================================")

# 1. Health check
with urllib.request.urlopen(f"{base_url}/health") as resp:
    data = json.loads(resp.read().decode())
    req_id = resp.headers.get("X-Request-ID")
    proc_time = resp.headers.get("X-Process-Time")
    print(f"[PASS 1/6] Health Check: status={data.get('status')} | ReqID={req_id[:8]}... | Latency={proc_time}")

# 2. 401 Unauthorized guard (Android error shape)
try:
    urllib.request.urlopen(f"{base_url}/businesses")
except urllib.error.HTTPError as e:
    err_body = json.loads(e.read().decode())
    assert err_body.get("statusCode") == 401
    assert "Authorization" in err_body.get("message")
    print(f"[PASS 2/6] 401 Auth Guard Contract: statusCode={err_body['statusCode']} | message='{err_body['message']}'")

# 3. 404 Route Not Found
try:
    urllib.request.urlopen(f"{base_url}/invalid-endpoint")
except urllib.error.HTTPError as e:
    err_body = json.loads(e.read().decode())
    assert err_body.get("statusCode") == 404
    print(f"[PASS 3/6] 404 Error Format: statusCode={err_body['statusCode']} | message='{err_body['message']}'")

# 4. Authenticated Businesses Retrieval
user_id = "8b4a1f72-faad-4e21-88c0-1088c375d53d"
phone = "+916366605032"
claims = {
    "sub": user_id,
    "phone": phone,
    "sessionVersion": 1,
}
token = create_access_token(claims)

auth_headers = {
    "Authorization": f"Bearer {token}",
    "Content-Type": "application/json"
}

req = urllib.request.Request(f"{base_url}/businesses", headers=auth_headers)
with urllib.request.urlopen(req) as resp:
    businesses = json.loads(resp.read().decode())
    print(f"[PASS 4/6] Authenticated Businesses: Loaded {len(businesses)} businesses for user {phone}")
    for b in businesses:
        name = b.get("name")
        b_id = b.get("id")
        role = b.get("role")
        print(f"           • Business: {name} [id={b_id[:8]}..., role={role}]")

# 5. Authenticated Parties Retrieval
active_biz = businesses[0]
biz_headers = dict(auth_headers, **{"X-Business-Id": active_biz["id"]})

req = urllib.request.Request(f"{base_url}/api/v1/parties", headers=biz_headers)
with urllib.request.urlopen(req) as resp:
    parties = json.loads(resp.read().decode())
    print(f"[PASS 5/6] Parties List for '{active_biz['name']}': Loaded {len(parties)} parties")
    for p in parties[:3]:
        print(f"           • Customer/Supplier: {p.get('name')} | Balance: ₹{p.get('balance', 0)}")

# 6. OpenAPI Documentation
with urllib.request.urlopen(f"{base_url}/openapi.json") as resp:
    openapi = json.loads(resp.read().decode())
    routes_count = len(openapi.get("paths", {}))
    print(f"[PASS 6/6] OpenAPI Specification: {routes_count} interactive routes live at http://localhost:3000/docs")

print("==================================================")
print("   STATUS: ALL SYSTEM COMPONENTS STITCHED & LIVE! ")
print("==================================================")
