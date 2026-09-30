"""Check both the MCP process and its database connection without logging secrets."""

import os
import urllib.request


request = urllib.request.Request("http://127.0.0.1:8000/healthz", headers={
    "Authorization": f"Bearer {os.environ['MYSQL_MCP_TOKEN']}"
})
with urllib.request.urlopen(request, timeout=4) as response:
    if response.status != 200:
        raise SystemExit(1)
