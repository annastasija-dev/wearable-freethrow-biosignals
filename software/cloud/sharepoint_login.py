#!/usr/bin/env python3
"""One-time Microsoft login for SharePoint / OneDrive upload."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from app.sharepoint_sync import acquire_token_interactive

if __name__ == "__main__":
    print("Sign in with VGTU account (anastasija.grubinskiene@vilniustech.lt)")
    print("After login, sessions will upload to OneDrive automatically.")
    print()
    result = acquire_token_interactive()
    print()
    print("OK - token saved.")
    print("Account:", result.get("id_token_claims", {}).get("preferred_username", "OK"))
