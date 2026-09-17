#!/usr/bin/env python3
"""Network destination policy for authenticated smoke commands."""

from __future__ import annotations

import ipaddress
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler


def _is_loopback(hostname: str) -> bool:
    if hostname.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(hostname).is_loopback
    except ValueError:
        return False


def different_positive_tenant_id(tenant_id: str) -> str:
    """Return a deterministic positive tenant ID different from the selected tenant."""
    if not tenant_id.isdecimal() or int(tenant_id) < 1:
        raise RuntimeError("Smoke tenant ID must be a positive integer")
    return "2" if int(tenant_id) == 1 else "1"


def validate_smoke_base_url(raw_url: str) -> str:
    try:
        parsed = urlsplit(raw_url)
        _ = parsed.port
    except ValueError as exc:
        raise RuntimeError("Smoke Gateway URL is malformed") from exc
    if not parsed.hostname:
        raise RuntimeError("Smoke Gateway URL must include a host")
    if parsed.username is not None or parsed.password is not None:
        raise RuntimeError("Smoke Gateway URL must not contain userinfo")
    if parsed.query or parsed.fragment:
        raise RuntimeError("Smoke Gateway URL must not contain a query or fragment")
    if parsed.scheme == "https":
        return raw_url.rstrip("/")
    if parsed.scheme == "http" and _is_loopback(parsed.hostname):
        return raw_url.rstrip("/")
    raise RuntimeError("Authenticated smoke requires HTTPS or a loopback HTTP Gateway URL")


class RejectRedirects(HTTPRedirectHandler):
    """Keep credentials and session cookies on the explicitly validated origin."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001
        return None


def smoke_proxy_handler(base_url: str, allow_remote_https_proxy: bool) -> ProxyHandler:
    parsed = urlsplit(base_url)
    if parsed.hostname and _is_loopback(parsed.hostname):
        return ProxyHandler({})
    return ProxyHandler() if allow_remote_https_proxy else ProxyHandler({})
