"""Check both the MCP process and its database connection without logging secrets.

The target is a fixed loopback literal (host and port), never a dynamic URL:
no redirects are followed and no name resolution is involved, so the request
cannot be redirected to internal services or cloud metadata endpoints.
"""

import http.client
import os

connection = http.client.HTTPConnection("127.0.0.1", 8000, timeout=4)
try:
    connection.request("GET", "/healthz", headers={
        "Authorization": f"Bearer {os.environ['MYSQL_MCP_TOKEN']}"
    })
    response = connection.getresponse()
    if response.status != 200:
        raise SystemExit(1)
finally:
    connection.close()
