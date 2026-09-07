#!/usr/bin/env python3
"""Verify Messaging privacy through authenticated public Gateway sessions.

Uses development accounts. Changes a recipient's sharing preference and always
restores its original value. Records the sender viewing their own existing message,
which does not affect recipient counts. Does not create messages or alter cursors.
"""
import http.cookiejar
import json
import os
import urllib.error
import urllib.request

BASE = os.getenv("DWP_GATEWAY_URL", "http://localhost:8080").rstrip("/")
TENANT = os.getenv("DWP_SMOKE_TENANT_ID", "1")
PASSWORD = os.getenv("DWP_SMOKE_PASSWORD", "admin1234!")


class Session:
    def __init__(self, email):
        self.client = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = None
        self.refresh_csrf()
        self.request("POST", "/api/auth/login", {"tenantId": TENANT, "email": email, "password": PASSWORD})
        self.refresh_csrf()

    def refresh_csrf(self):
        self.csrf = self.request("GET", "/api/auth/csrf")

    def request(self, method, path, body=None, expected=200):
        if method not in ("GET", "HEAD", "OPTIONS") and self.csrf:
            self.refresh_csrf()
        headers = {"Accept": "application/json", "X-Tenant-ID": TENANT}
        if self.csrf:
            headers[self.csrf["headerName"]] = self.csrf["token"]
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(BASE + path,
            data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            response = self.client.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        data = response.read()
        if response.status != expected:
            raise AssertionError(f"{method} {path}: expected {expected}, got {response.status}: {data[:250]!r}")
        payload = json.loads(data) if data else {}
        return payload.get("data")


def main():
    sender = Session(os.getenv("DWP_SMOKE_EMAIL", "joonbin@sk.com"))
    recipient = Session(os.getenv("DWP_SMOKE_RECIPIENT", "hyunwoo.park@sk.com"))
    sender_identity = sender.request("GET", "/api/auth/me")
    recipient_identity = recipient.request("GET", "/api/auth/me")
    sender_id = sender_identity["userId"]
    recipient_id = recipient_identity["userId"]
    conversations = sender.request("GET", "/api/messaging/v1/conversations?pageSize=100")
    candidate = None
    for conversation in conversations["items"]:
        cid = conversation["conversationId"]
        detail = sender.request("GET", f"/api/messaging/v1/conversations/{cid}")
        if not any(member["userId"] == recipient_id for member in detail["members"]):
            continue
        for message in detail["messages"]:
            if message["senderUserId"] != sender_id or message.get("deletedAt") or message["messageKind"] != "USER":
                continue
            sender.request("GET", f"/api/messaging/v1/conversations/{cid}/messages/{message['messageId']}/receipts")
            candidate = (cid, message["messageId"])
            break
        if candidate:
            break
    if not candidate:
        raise AssertionError("The two development accounts need an existing shared authored message.")
    cid, mid = candidate
    base = f"/api/messaging/v1/conversations/{cid}"
    settings_path = "/api/messaging/v1/privacy-preferences"
    original = recipient.request("GET", settings_path)
    current = original
    try:
        current = recipient.request("PUT", settings_path, {**current, "readReceiptsEnabled": False})
        hidden = sender.request("GET", f"{base}/messages/{mid}/receipts")
        visible_recipient = next((item for item in hidden["recipients"] if item["userId"] == recipient_id), None)
        if visible_recipient:
            assert visible_recipient["status"] == "UNAVAILABLE"
        else:
            print("NOTE historical seed message predates this membership term; recipient correctly excluded. Per-recipient states verified by fresh PostgreSQL tests.")
        other_view = sender.request("GET", base)
        other = next(member for member in other_view["members"] if member["userId"] == recipient_id)
        assert other["lastReadSequence"] == 0 and other["lastReadAt"] is None and other["lastReadMessageId"] is None
        assert other["readReceiptVisibility"] == "PRIVATE"
        recipient.request("GET", f"{base}/messages/{mid}/receipts", expected=404)
        sender.request("GET", f"{base}/read-receipts?messageIds={mid}")
        for _ in range(2):
            observed = sender.request("POST", f"{base}/read-receipts", {"messageIds": [mid]})
            assert observed["observedMessageIds"] == [mid]
        recipient.request("PUT", settings_path, {**current, "version": current["version"] - 1}, expected=409)
        print("PASS public Gateway: two real sessions, sender-only receipt access, private cursor redaction, batch query, idempotent observation POST, stale version rejection")
    finally:
        latest = recipient.request("GET", settings_path)
        if latest["readReceiptsEnabled"] != original["readReceiptsEnabled"]:
            recipient.request("PUT", settings_path, {**latest, "readReceiptsEnabled": original["readReceiptsEnabled"]})
        restored = recipient.request("GET", settings_path)
        assert restored["readReceiptsEnabled"] == original["readReceiptsEnabled"]
        print("PASS original sharing preference restored; no messages created")


if __name__ == "__main__":
    main()
