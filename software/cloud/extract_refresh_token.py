# Extract refresh_token from MSAL cache after sharepoint_login.py
# Usage: python extract_refresh_token.py
from __future__ import annotations

import json
from pathlib import Path

cache_path = Path(__file__).resolve().parent / ".token_cache.json"
if not cache_path.exists():
    raise SystemExit("No .token_cache.json — run sharepoint_login.py first")

data = json.loads(cache_path.read_text(encoding="utf-8"))
# MSAL serializable cache stores RefreshToken section
refresh_section = data.get("RefreshToken") or {}
if not refresh_section:
    raise SystemExit("No RefreshToken in cache")

token = None
for item in refresh_section.values():
    if isinstance(item, dict) and item.get("secret"):
        token = item["secret"]
        break

if not token:
    raise SystemExit("Refresh token secret not found")

print(token)
