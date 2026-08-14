#!/usr/bin/env python3

from __future__ import annotations

import http.cookiejar
import json
import os
import urllib.error
import urllib.request


BASE_URL = os.getenv("DWP_GATEWAY_URL", "http://localhost:8080").rstrip("/")
TENANT_ID = os.getenv("DWP_SMOKE_TENANT_ID", "1")
EMAIL = os.getenv("DWP_SMOKE_EMAIL", "joonbin@sk.com")
PASSWORD = os.getenv("DWP_SMOKE_PASSWORD", "admin1234!")

cookies = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))


def request(
    method: str,
    path: str,
    *,
    body: object | None = None,
    headers: dict[str, str] | None = None,
    expected: int = 200,
) -> tuple[object | None, urllib.response.addinfourl]:
    encoded = None if body is None else json.dumps(body).encode("utf-8")
    request_headers = {"Accept": "application/json", "X-Tenant-ID": TENANT_ID}
    if encoded is not None:
        request_headers["Content-Type"] = "application/json"
    request_headers.update(headers or {})
    message = urllib.request.Request(
        BASE_URL + path,
        data=encoded,
        headers=request_headers,
        method=method,
    )
    try:
        response = client.open(message, timeout=10)
        status = response.status
        payload_bytes = response.read()
    except urllib.error.HTTPError as error:
        response = error
        status = error.code
        payload_bytes = error.read()
    if status != expected:
        details = payload_bytes.decode("utf-8", errors="replace")[:500]
        raise AssertionError(
            f"{method} {path}: expected {expected}, received {status}; response={details}"
        )
    payload = json.loads(payload_bytes) if payload_bytes else None
    return payload, response


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


csrf_payload, csrf_response = request("GET", "/api/auth/csrf")
csrf = csrf_payload["data"]
csrf_header = csrf["headerName"]
csrf_token = csrf["token"]
require(any(cookie.name == "XSRF-TOKEN" for cookie in cookies), "CSRF cookie was not issued")

_, login_response = request(
    "POST",
    "/api/auth/login",
    body={"tenantId": TENANT_ID, "email": EMAIL, "password": PASSWORD},
    headers={csrf_header: csrf_token},
)
require(any(cookie.name == "DWP_SESSION" for cookie in cookies), "Session cookie was not issued")

# Spring Security rotates the CSRF token when authentication changes. Browser
# clients perform the same refresh before their first authenticated mutation.
csrf_payload, _ = request("GET", "/api/auth/csrf")
csrf = csrf_payload["data"]
csrf_header = csrf["headerName"]
csrf_token = csrf["token"]

me_payload, me_response = request("GET", "/api/auth/me")
identity = me_payload["data"]
require(identity["email"].lower() == EMAIL.lower(), "Authenticated email does not match")
require(str(identity["tenantId"]) == TENANT_ID, "Authenticated tenant does not match")
require("TENANT_ADMIN" in identity["roles"], "Smoke persona is missing TENANT_ADMIN")

security_headers = {name.lower(): value for name, value in me_response.headers.items()}
for required_header in (
    "content-security-policy",
    "permissions-policy",
    "referrer-policy",
    "x-content-type-options",
    "x-frame-options",
):
    require(required_header in security_headers, f"Gateway response is missing {required_header}")

# Authenticated auth reads can rotate Spring Security's CSRF repository.
csrf_payload, _ = request("GET", "/api/auth/csrf")
csrf = csrf_payload["data"]
csrf_header = csrf["headerName"]
csrf_token = csrf["token"]
request(
    "POST",
    "/api/platform/v1/observability/web-vitals",
    body={
        "name": "LCP",
        "value": 1250.0,
        "delta": 25.0,
        "id": "ci-runtime-smoke",
        "rating": "good",
        "navigationType": "navigate",
        "routeGroup": "runtime.smoke",
    },
    headers={csrf_header: csrf_token},
    expected=202,
)

request(
    "GET",
    "/api/auth/me",
    headers={"X-Tenant-ID": "2"},
    expected=403,
)

# The rejected auth request clears the CSRF cookie; refresh it before logout.
csrf_payload, _ = request("GET", "/api/auth/csrf")
csrf = csrf_payload["data"]
csrf_header = csrf["headerName"]
csrf_token = csrf["token"]
request("POST", "/api/auth/logout", headers={csrf_header: csrf_token})
request("GET", "/api/auth/me", expected=401)

print("PASS runtime smoke: Gateway CSRF, login, tenant isolation, telemetry, headers, and logout")
