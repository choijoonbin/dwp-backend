#!/usr/bin/env python3
"""Write bounded, secret-free structured results for authenticated smoke tools."""

from __future__ import annotations

import atexit
import json
import os
import tempfile
from pathlib import Path
from typing import Any


class SmokeResult:
    def __init__(self, tool: str) -> None:
        self.tool = tool
        self.output = (
            Path(os.environ["DWP_SMOKE_RESULT_PATH"])
            if os.getenv("DWP_SMOKE_RESULT_PATH")
            else None
        )
        self.correlation_id = os.environ.get("DWP_SMOKE_CORRELATION_ID", "")
        self.target = os.environ.get("DWP_GATEWAY_URL", "")
        self.tenant_id = os.environ.get("DWP_SMOKE_TENANT_ID", "")
        self.expected_roles = sorted(
            role
            for role in os.environ.get("DWP_SMOKE_EXPECTED_ROLES", "").split(",")
            if role
        )
        self.observations: list[dict[str, Any]] = []
        self.assertions: set[str] = set()
        self.expected_roles_verified = False
        self.completed = False
        atexit.register(self._write_uncaught)

    def observe(
        self,
        method: str,
        path: str,
        expected_status: int,
        actual_status: int,
        response_correlation_id: str | None = None,
        error_class: str | None = None,
    ) -> None:
        observation: dict[str, Any] = {
            "method": method,
            "path": path,
            "expectedStatus": expected_status,
            "actualStatus": actual_status,
        }
        if response_correlation_id:
            observation["responseCorrelationId"] = response_correlation_id[:128]
        if error_class:
            observation["errorClass"] = error_class
        self.observations.append(observation)

    def mark_expected_roles_verified(self) -> None:
        self.expected_roles_verified = True

    def mark_assertion(self, assertion_id: str) -> None:
        if not assertion_id or len(assertion_id) > 128:
            raise ValueError("smoke assertion id must contain 1..128 characters")
        self.assertions.add(assertion_id)

    def finish(self, status: str, failure_code: str | None = None) -> None:
        if self.completed:
            return
        self.completed = True
        document: dict[str, Any] = {
            "schemaVersion": 1,
            "tool": self.tool,
            "status": status,
            "correlationId": self.correlation_id,
            "target": self.target,
            "tenantId": self.tenant_id,
            "expectedRoles": self.expected_roles,
            "expectedRolesVerified": self.expected_roles_verified,
            "assertions": sorted(self.assertions),
            "observations": self.observations,
        }
        if failure_code:
            document["failureCode"] = failure_code
        self._write(document)

    def _write_uncaught(self) -> None:
        if not self.completed:
            self.finish("FAIL", "UNCAUGHT_OR_ASSERTION")

    def _write(self, document: dict[str, Any]) -> None:
        if self.output is None:
            return
        self.output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            dir=self.output.parent,
            prefix=".smoke-tool-",
            delete=False,
        ) as handle:
            json.dump(document, handle, indent=2)
            handle.write("\n")
            temporary = Path(handle.name)
        os.chmod(temporary, 0o600)
        os.replace(temporary, self.output)
