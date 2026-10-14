#!/usr/bin/env python3
"""Export stable service contracts and compose the browser-facing Gateway contract."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import sys
from collections import deque
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable


ROOT = Path(__file__).resolve().parents[1]
CONTRACT_ROOT = ROOT / "contracts" / "openapi"
GATEWAY_OWNED_SNAPSHOT = CONTRACT_ROOT / "gateway-owned.json"
AGENT_PUBLIC_SNAPSHOT = CONTRACT_ROOT / "agent-public.json"
PRODUCT_AUTHORIZATION_REGISTRY = (
    ROOT / "contracts" / "product-authorization" / "product-surfaces-v1.bundle-v40.json"
)
PRODUCT_AUTHORIZATION_VERSION = 40
HRM_EMPLOYEE_CHANGE_AUTHORIZATION_REGISTRY = (
    ROOT / "contracts" / "product-authorization" / "product-surfaces-v1.bundle-v38.json"
)
HRM_EMPLOYEE_CHANGE_AUTHORIZATION_VERSION = 38
COMPENSATION_PLANNING_CONTROLLER_FLAG = (
    "DWP_HRIS_PERFORMANCE_COMPENSATION_PLANNING_ENABLED"
)
HRIS_DESIGN_TIME_OVERLAY = CONTRACT_ROOT / "hris-wave1-design-time.json"
HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}
SYS_REFERENCE_CONFIGURATION_OPERATION_CONTRACTS = {
    "route.hcm.configuration.settings-reference.page": (
        "get",
        "/v1/hris/workbench/settings-reference",
        "hrisReferenceConfigurationWorkbench",
        "200",
    ),
    "route.hcm.configuration.configurations.data": (
        "get",
        "/v1/admin/hris/configurations",
        "hrisReferenceConfigurations",
        "200",
    ),
    "route.hcm.configuration.configuration-version.data": (
        "get",
        "/v1/admin/hris/configurations/{configId}/versions/{version}",
        "hrisReferenceConfigurationVersion",
        "200",
    ),
    "route.hcm.configuration.configuration-version-create.action": (
        "post",
        "/v1/admin/hris/configurations/{configId}/versions",
        "hrisReferenceConfigurationCreateVersion",
        "202",
    ),
    "route.hcm.configuration.configuration-version-validate.action": (
        "post",
        "/v1/admin/hris/configurations/{configId}/versions/{version}/validate",
        "hrisReferenceConfigurationValidateVersion",
        "202",
    ),
    "route.hcm.configuration.configuration-version-simulate.action": (
        "post",
        "/v1/admin/hris/configurations/{configId}/versions/{version}/simulate",
        "hrisReferenceConfigurationSimulateVersion",
        "202",
    ),
    "route.hcm.configuration.configuration-version-submit.action": (
        "post",
        "/v1/admin/hris/configurations/{configId}/versions/{version}/submit",
        "hrisReferenceConfigurationSubmitVersion",
        "202",
    ),
    "route.hcm.configuration.configuration-version-approve.action": (
        "post",
        "/v1/admin/hris/configurations/{configId}/versions/{version}/approve",
        "hrisReferenceConfigurationApproveVersion",
        "202",
    ),
    "route.hcm.configuration.configuration-version-schedule.action": (
        "post",
        "/v1/admin/hris/configurations/{configId}/versions/{version}/schedule",
        "hrisReferenceConfigurationScheduleVersion",
        "202",
    ),
    "route.hcm.configuration.configuration-version-publish.action": (
        "post",
        "/v1/admin/hris/configurations/{configId}/versions/{version}/publish",
        "hrisReferenceConfigurationPublishVersion",
        "202",
    ),
    "route.hcm.configuration.configuration-version-rollback.action": (
        "post",
        "/v1/admin/hris/configurations/{configId}/versions/{version}/rollback",
        "hrisReferenceConfigurationRollbackVersion",
        "202",
    ),
    "route.hcm.configuration.configuration-version-retire.action": (
        "post",
        "/v1/admin/hris/configurations/{configId}/versions/{version}/retire",
        "hrisReferenceConfigurationRetireVersion",
        "202",
    ),
}
SYS_REFERENCE_CONFIGURATION_ROUTE_KEYS = frozenset(
    SYS_REFERENCE_CONFIGURATION_OPERATION_CONTRACTS
)
HRM_EMPLOYEE_CHANGE_OPERATION_CONTRACTS = {
    "route.hcm.operations.employee-change-requests.page": (
        "get",
        "/v1/hris/workbench/operations/employee/change/requests",
        "employeeChangeRequestWorkbench",
        "200",
    ),
    "route.hcm.operations.employee-change-request-create.action": (
        "post",
        "/v1/hris/employee-change-requests",
        "createEmployeeChangeRequest",
        "202",
    ),
    "route.hcm.operations.employee-change-request-submit.action": (
        "post",
        "/v1/hris/employee-change-requests/{requestPublicId}/submit",
        "submitEmployeeChangeRequest",
        "202",
    ),
    "route.hcm.operations.employee-change-request-apply.action": (
        "post",
        "/v1/hris/employee-change-requests/{requestPublicId}/apply",
        "applyEmployeeChangeRequest",
        "202",
    ),
}
HRM_EMPLOYEE_CHANGE_ROUTE_KEYS = frozenset(
    HRM_EMPLOYEE_CHANGE_OPERATION_CONTRACTS
)
COMPENSATION_PLANNING_OPERATION_CONTRACTS = {
    "route.hcm.operations.compensation-planning.page": (
        "get", "/v1/hris/performance/compensation-planning/cycles",
        "modern.compplan.cycles.query", None,
        "ModernCompplanCyclesQueryResponseV5",
    ),
    "route.hcm.operations.compensation-cycle-detail.data": (
        "get", "/v1/hris/performance/compensation-planning/cycles/{cycleId}",
        "modern.compplan.cycle.query", None,
        "ModernCompplanCycleQueryResponseV5",
    ),
    "route.hcm.operations.compensation-proposals.data": (
        "get",
        "/v1/hris/performance/compensation-planning/cycles/{cycleId}/proposals",
        "modern.compplan.proposals.query", None,
        "ModernCompplanProposalsQueryResponseV4",
    ),
    "route.hcm.operations.compensation-proposal-detail.data": (
        "get", "/v1/hris/performance/compensation-planning/proposals/{proposalId}",
        "modern.compplan.proposal.query", None,
        "ModernCompplanProposalQueryResponseV4",
    ),
    "route.hcm.operations.compensation-cycle-create.action": (
        "post", "/v1/hris/performance/compensation-planning/cycles",
        "modern.compplan.cycle.create", "ModernCompplanCycleCreateRequestV2",
        "PerformanceCommandReceiptV1",
    ),
    "route.hcm.operations.compensation-cycle-revise.action": (
        "post",
        "/v1/hris/performance/compensation-planning/cycles/{cycleId}/revisions",
        "modern.compplan.cycle.revise", "ModernCompplanCycleReviseRequestV4",
        "ModernCompplanRichCommandResultV3",
    ),
    "route.hcm.operations.compensation-budget-allocate.action": (
        "post",
        "/v1/hris/performance/compensation-planning/cycles/{cycleId}/budget-allocations",
        "modern.compplan.budget.allocate", "ModernCompplanBudgetAllocateRequestV2",
        "PerformanceCommandReceiptV1",
    ),
    "route.hcm.operations.compensation-proposal-upsert.action": (
        "post",
        "/v1/hris/performance/compensation-planning/cycles/{cycleId}/proposals/{proposalId}",
        "modern.compplan.proposal.upsert", "ModernCompplanProposalUpsertRequestV4",
        "ModernCompplanRichCommandResultV3",
    ),
    "route.hcm.operations.compensation-proposal-submit.action": (
        "post",
        "/v1/hris/performance/compensation-planning/cycles/{cycleId}/proposals/{proposalId}/submit",
        "modern.compplan.proposal.submit", "ModernCompplanProposalSubmitRequestV1",
        "PerformanceCommandReceiptV1",
    ),
    "route.hcm.operations.compensation-plan-approve.action": (
        "post", "/v1/hris/performance/compensation-planning/cycles/{cycleId}/approve",
        "modern.compplan.plan.approve", "ModernCompplanPlanApproveRequestV2",
        "PerformanceCommandReceiptV1",
    ),
    "route.hcm.operations.compensation-snapshot-publish.action": (
        "post",
        "/v1/hris/performance/compensation-planning/cycles/{cycleId}/approved-snapshots",
        "modern.compplan.snapshot.publish", "ModernCompplanSnapshotPublishRequestV2",
        "ModernCompplanSnapshotPublishResponseV2",
    ),
    "route.hcm.operations.compensation-cycle-cancel.action": (
        "post", "/v1/hris/performance/compensation-planning/cycles/{cycleId}/cancel",
        "modern.compplan.cycle.cancel", None, "ModernCompplanRichCommandResultV3",
    ),
}
COMPENSATION_PLANNING_ROUTE_KEYS = frozenset(
    COMPENSATION_PLANNING_OPERATION_CONTRACTS
)
HRM_EMPLOYEE_CHANGE_INTERNAL_OPERATION = (
    "post",
    "/internal/hris-people/v1/employee-change-requests/{requestPublicId}/approval-decisions",
    "recordEmployeeChangeDecision",
    "200",
)
HRM_REQUEST_PUBLIC_ID_PARAMETER = {
    "in": "path",
    "name": "requestPublicId",
    "required": True,
    "schema": {"format": "uuid", "type": "string"},
}
HRM_COMMAND_ERROR_STATUSES = frozenset({
    "400", "401", "403", "404", "409", "422", "423", "429", "500",
})
HRM_WORKBENCH_ERROR_STATUSES = frozenset({
    "400", "401", "403", "404", "409", "429", "503",
})
HRM_RESPONSE_HEADER_CONTRACTS = {
    "ETag": {
        "description": "Entity version for conditional follow-up requests.",
        "schema": {"type": "string", "minLength": 1},
    },
    "X-Correlation-ID": {
        "description": "End-to-end request correlation identifier.",
        "schema": {"type": "string", "format": "uuid"},
    },
    "X-Authorization-Decision-Revision": {
        "description": "Authorization decision revision used for this response.",
        "schema": {"type": "string", "minLength": 1, "maxLength": 128},
    },
    "Cache-Control": {
        "description": "Authorization-aware response cache policy.",
        "schema": {"type": "string", "minLength": 1},
    },
}
HRM_RESPONSE_HEADERS = frozenset(HRM_RESPONSE_HEADER_CONTRACTS)
HRM_CANONICAL_SCHEMA_SHA256 = {
    "HrisUuid": "bcba6987baaf3ab7dad13356df3cb4125229d151ded2d51c97be1668fb562414",
    "HrisDate": "0bd47e8b24e7520f30c77a23997f34e33542c25d6a2b3fd90ac7e525581fe981",
    "HrisDigest": "0c1c444817fdd79b088e2b418e6a85461413177f651a625f5f6f168dfaeb2f64",
    "EmployeeChangeRequestCreateV1": "217e9b8bd5e5e3185af98e49ab3d0e9fdf35cb4a9fe60ba4839df48497736c18",
    "EmptyCommandV1": "88dc81f2f7e45ebb6c3a27c4f0347be758459e23b246a0645530421ff82ff4ba",
    "HrisProblemDetailsV1": "e373ef4b10749b1d690607c9f833f08ccb561adf59e9d2751b4d5ef42afc4292",
    "Iaq009EmployeeChangeRequestFilterV1": "799a91a191851d077f13189be3c9433f7af8394f97b818115e40f37c47f6e2cb",
    "Iaq009EmployeeChangeRequestQueryV1": "72c216f6e1e61b4e2f4ae26fe5c1ce62116bbd888dc018b56378f1f61de2d38f",
    "Iaq009EmployeeChangeRequestProjectionV1": "33d49ed51cae1c901fa6083c834bd5bced99b14a31b9e2c0451a3a03f6e83190",
    "Iaq009ProblemDetailsV1": "a9739f600f711b7f022ba55b58b65e71a2697b62af0864a5aefbdd68b67952af",
    "IaqStatusFilterV1": "babfa049273fcd993f995e6a3fd77488ff2389625e0d77b555176046bf188953",
    "IaqAggregateFilterV1": "82a7c188f32b6b894c456a750bee3bfd2c0c309487a12f8f2bc36a60615e00dd",
    "IaqEffectiveRangeFilterV1": "da2cbb42a4a5fb19e519f8cf3417c44778c56f1b2709f236204c8b3cc402a01b",
    "IaqStatusValueV1": "5b3560221544b336691b8c661c658eb1ec5303612d295626d30f2d1aaeab3005",
    "IaqAggregateValueV1": "7c537eadcf924e1dac1ee12e66466ae4e8d3768fa07a6fb212391a7967e28319",
    "IaqEffectiveRangeValueV1": "74f21cf2c2130710c1642db2d64239608fcbea7612453aae99ed5c8e4156cb82",
    "CommandReceiptV1": "b464534080642275344bdfdf7d11c6b1dbf864d5c4307b425ad6b805857b58a2",
    "ApprovalDecisionCallbackV1": "1f9d93ba4c5263b19ab0209dec0b301447c149eb85120cdcbf51866cfdc07ec1",
}
# Springdoc cannot infer the closed-wire constraints enforced by the canonical
# CommandReceiptV1 deserializer.  Admit only this exact lossy runtime projection;
# the reviewed schema hash above remains the published contract.
HRM_RUNTIME_SCHEMA_PROJECTION_SHA256 = {
    ("people", "CommandReceiptV1"):
        "1710749e86f42104b360e82893d184bc43b27da3b3976a678e154139a816233e",
}
HRM_WORKLOAD_PROOF_V2 = {
    "audience": "dwp-people-server",
    "bindingId": "SVC-PEP-HRM-001",
    "callerWorkloadId": "dwp-approval-server",
    "method": "POST",
    "pathTemplate": HRM_EMPLOYEE_CHANGE_INTERNAL_OPERATION[1],
    "purpose": "HRIS_APPROVAL_DECISION_CALLBACK",
}
PATH_PARAMETER_PATTERN = re.compile(r"\{[^{}]+}")
TELEMETRY_PUBLIC_PATH = "/api/platform/v1/observability/product-surface-events"
TELEMETRY_TRUSTED_HEADERS = {"X-DWP-Tenant-ID", "X-DWP-Rollout-Cohort"}
PUBLIC_COMMAND_PROOF_HEADERS = {
    "x-dwp-step-up-challenge",
    "x-dwp-expected-decision-revision",
    "x-dwp-expected-object-version",
}
WAVE2_TRUSTED_PURPOSE_OPERATIONS = frozenset({
    ("/api/people/v1/hris/workbench/self-service/employment-documents", "get"),
    ("/api/people/v1/hris/workbench/operations/employment/assignments", "get"),
    ("/api/people/v1/hris/assignment-events", "post"),
    ("/api/people/v1/hris/assignment-events/{eventPublicId}", "get"),
    ("/api/people/v1/hris/assignment-events/{eventPublicId}/validate", "post"),
    ("/api/people/v1/hris/assignment-events/{eventPublicId}/submit", "post"),
    ("/api/people/v1/hris/assignment-events/{eventPublicId}/publish", "post"),
    ("/api/people/v1/hris/command-receipts/{receiptId}", "get"),
    ("/api/time/v1/hris/workbench/time-interpretation", "get"),
    ("/api/time/v1/interpretation-runs", "post"),
    ("/api/time/v1/interpretation-runs/{runId}/trace", "get"),
    ("/api/payroll/v1/workbench/settings-payroll", "get"),
    ("/api/payroll/v1/elements/versions", "post"),
    ("/api/payroll/v1/formulas/versions", "post"),
    ("/api/payroll/v1/formulas/{formulaId}/validations", "post"),
    ("/api/payroll/v1/formulas/{formulaId}/simulations", "post"),
    ("/api/payroll/v1/formulas/{formulaId}/publish", "post"),
})
PLATFORM_DEVICE_SECURITY_SCHEMES = {
    "DeviceTenant": {
        "name": "X-Tenant-ID",
        "description": (
            "Positive tenant identifier for the actorless DEVICE identity plane. "
            "The Gateway verifies and rewrites this value before forwarding it."
        ),
    },
    "DeviceCredential": {
        "name": "X-Device-Credential",
        "description": (
            "Opaque tenant-bound device credential for the actorless DEVICE identity plane. "
            "The Gateway verifies its shape and forwards it only to exact device routes."
        ),
    },
}
PLATFORM_DEVICE_OPERATIONS = frozenset({
    ("post", "/v1/device/workplace/devices:register"),
    ("post", "/v1/device/workplace/devices/{deviceId}/heartbeat"),
    ("get", "/v1/device/workplace/devices/{deviceId}/projection"),
    ("post", "/v1/device/workplace/devices/{deviceId}/access-pass:pair"),
    ("get", "/v1/workplace/kiosk/session"),
    ("get", "/v1/workplace/kiosk/visits/{visitId}"),
    ("post", "/v1/workplace/kiosk/visits/{visitId}:arrive"),
    ("post", "/v1/workplace/kiosk/visits/{visitId}:checkout"),
    ("post", "/v1/workplace/kiosk/devices/{deviceId}:heartbeat"),
    ("post", "/v1/workplace/kiosk/devices/{deviceId}:help"),
})
SCOPE_SELECTION_PARAMETER = {
    "name": "contextScopeKey",
    "in": "query",
    "required": False,
    "description": (
        "Opaque management scope returned by the product authority contract. Send exactly "
        "one value when more than one scope is available; omit it only when authority has "
        "one unambiguous scope. Blank, duplicate, malformed, oversized, revoked, or stale "
        "values fail closed. The Gateway consumes this parameter and forwards only its "
        "server-verified X-DWP-Context-Scope-Key evidence."
    ),
    "schema": {
        "type": "string",
        "minLength": 3,
        "maxLength": 200,
        "pattern": "^[A-Za-z0-9][A-Za-z0-9._:-]{2,199}$",
    },
}
EXPECTED_DECISION_REVISION_PARAMETER = {
    "name": "X-DWP-Expected-Decision-Revision",
    "in": "header",
    "required": False,
    "description": (
        "Required and fail-closed for product-authorization rollout states 110/111; "
        "optional for backward-compatible baseline/shadow states 000/100."
    ),
    "schema": {
        "type": "string",
        "minLength": 1,
        "maxLength": 200,
    },
    "x-dwp-conditional-required": {
        "enforcement": "FAIL_CLOSED",
        "rolloutStates": ["110", "111"],
    },
}
RESPONSE_DECISION_REVISION_HEADER = {
    "description": (
        "Current server-owned product authority decision revision. Reuse this value as "
        "X-DWP-Expected-Decision-Revision for a subsequent governed mutation."
    ),
    "schema": {"type": "string", "minLength": 1, "maxLength": 200},
    "x-dwp-conditional-present": {
        "rolloutStates": ["100", "110", "111"],
        "authority": "GATEWAY",
    },
}
HOME_RUNTIME_PATHS = frozenset({
    "/api/platform/v2/home",
    "/api/platform/v2/home/shadow-receipts",
    "/api/platform/v2/home/widget-actions:execute",
})
HOME_RUNTIME_MUTATION_PATHS = frozenset({
    "/api/platform/v2/home/shadow-receipts",
    "/api/platform/v2/home/widget-actions:execute",
})

APPROVAL_SIGNATURE_FEATURE_OPERATIONS = frozenset({
    ("get", "/v1/admin/signatures/diagnostics"),
    ("get", "/v1/admin/signatures/providers/{providerId}/diagnostics"),
    ("get", "/v1/admin/signatures/diagnostic-history"),
    ("post", "/v1/admin/signatures/probes"),
    ("post", "/v1/admin/signatures/kms/probes"),
    ("post", "/v1/admin/signatures/worm-inspections"),
    ("get", "/v1/admin/signatures/policy"),
    ("get", "/v1/admin/signatures/policies/{policyId}/history"),
    ("post", "/v1/admin/signatures/policies"),
    ("put", "/v1/admin/signatures/policies/{policyId}/draft"),
    ("post", "/v1/admin/signatures/policies/{policyId}/publish"),
    ("get", "/v1/requests/{requestId}/external-signature-context"),
    ("post", "/v1/requests/{requestId}/external-signature-requests"),
    ("get", "/v1/external-signature-requests/{signatureRequestId}"),
    ("post", "/v1/external-signature-requests/{signatureRequestId}/handovers"),
    ("post", "/v1/external-signature-requests/{signatureRequestId}/refresh"),
    ("post", "/v1/external-signature-requests/{signatureRequestId}/cancel"),
    ("get", "/v1/external-signature-requests/{signatureRequestId}/audit"),
    ("get", "/v1/external-signature-requests/{signatureRequestId}/artifacts/{artifactId}"),
    ("get", "/v1/requests/{requestId}/signature-context"),
    ("post", "/v1/requests/{requestId}/signature-requests"),
    ("get", "/v1/signature-requests/{signatureRequestId}"),
    ("post", "/v1/signature-requests/{signatureRequestId}/consents"),
    ("post", "/v1/signature-requests/{signatureRequestId}/sign"),
    ("post", "/v1/signature-requests/{signatureRequestId}/cancel"),
    ("get", "/v1/signature-requests/{signatureRequestId}/audit"),
    ("get", "/v1/signature-command-receipts/{idempotencyKey}"),
})
APPROVAL_SIGNATURE_LEGACY_OPERATION = ("get", "/v1/admin/signatures")


@dataclass(frozen=True)
class ServiceContract:
    name: str
    port: int
    public_path: Callable[[str], str | None]
    default_live: bool = True


def auth_path(path: str) -> str | None:
    if path.startswith("/scim/v2/") or path == "/scim/v2":
        return path
    if path.startswith("/auth/") or path == "/auth":
        return f"/api{path}"
    return None


def prefixed(prefix: str) -> Callable[[str], str | None]:
    def transform(path: str) -> str | None:
        if path.startswith("/internal/") or not (path.startswith("/v1/") or path == "/v1"):
            return None
        return f"{prefix}{path}"

    return transform


PROVIDER_WIDGET_REGISTRY_PREFIXES = (
    "/v1/admin/widget-definitions",
    "/v1/admin/widget-definition-versions",
    "/v1/admin/widget-runtime-controls",
    "/v1/admin/widget-registry",
)
PLATFORM_V2_PUBLIC_PATHS = frozenset({
    "/v2/home",
    "/v2/home/shadow-receipts",
    "/v2/home/widget-actions:execute",
})


def platform_path(path: str) -> str | None:
    if path.startswith("/internal/"):
        return None
    if path in PLATFORM_V2_PUBLIC_PATHS:
        return f"/api/platform{path}"
    if not (path.startswith("/v1/") or path == "/v1"):
        return None
    if any(path == prefix or path.startswith(f"{prefix}/")
           for prefix in PROVIDER_WIDGET_REGISTRY_PREFIXES):
        return f"/api/provider{path}"
    return f"/api/platform{path}"


SERVICES = (
    ServiceContract("auth", 8001, auth_path),
    ServiceContract("platform", 8002, platform_path),
    ServiceContract("people", 8003, prefixed("/api/people")),
    ServiceContract("provider", 8004, prefixed("/api/provider")),
    ServiceContract("approval", 8005, prefixed("/api/approvals")),
    ServiceContract("space", 8006, prefixed("/api/spaces")),
    ServiceContract("messaging", 8007, prefixed("/api/messaging")),
    ServiceContract("notification", 8008, prefixed("/api/notifications")),
    ServiceContract("meeting", 8009, prefixed("/api/meetings")),
    # These Wave 1 owners remain default-off until their trusted authority adapters are
    # provisioned. Their reviewed design-time contracts are still part of the public API
    # inventory, while an explicit --service selects their live springdoc endpoint.
    ServiceContract("time", 8011, prefixed("/api/time"), default_live=False),
    ServiceContract("payroll", 8012, prefixed("/api/payroll"), default_live=False),
)
AGENT_PUBLIC_SERVICE = ServiceContract("agent", 8010, prefixed("/api/agent"))
GATEWAY_SERVICES = (*SERVICES, AGENT_PUBLIC_SERVICE)


def validate_unique_operation_ids(document: dict[str, Any], contract_name: str) -> None:
    operation_ids: dict[str, list[str]] = {}
    for path, path_item in document.get("paths", {}).items():
        for method, operation in path_item.items():
            if method not in HTTP_METHODS or not isinstance(operation, dict):
                continue
            operation_id = operation.get("operationId")
            if not isinstance(operation_id, str) or not operation_id.strip():
                raise RuntimeError(
                    f"{contract_name} operation has no stable operationId: "
                    f"{method.upper()} {path}"
                )
            operation_ids.setdefault(operation_id, []).append(
                f"{method.upper()} {path}"
            )
    duplicates = {
        operation_id: locations
        for operation_id, locations in operation_ids.items()
        if len(locations) > 1
    }
    if duplicates:
        raise RuntimeError(
            f"{contract_name} publishes duplicate operationIds: {duplicates}"
        )


def fetch_contract(service: ServiceContract) -> dict[str, Any]:
    url = os.environ.get(
        f"DWP_OPENAPI_{service.name.upper().replace('-', '_')}_URL",
        f"http://127.0.0.1:{service.port}/v3/api-docs",
    )
    try:
        with urllib.request.urlopen(url, timeout=15) as response:
            document = json.load(response)
    except (OSError, urllib.error.URLError, json.JSONDecodeError) as error:
        raise RuntimeError(f"Unable to read {service.name} OpenAPI document at {url}: {error}") from error
    if not str(document.get("openapi", "")).startswith("3."):
        raise RuntimeError(f"{service.name} did not publish an OpenAPI 3 document")
    if not document.get("paths"):
        raise RuntimeError(f"{service.name} published an empty OpenAPI path registry")
    if service.name == "people" and not any(
        path.startswith("/v1/hris/performance/compensation-planning")
        for path in document["paths"]
    ):
        raise RuntimeError(
            "People OpenAPI was not exported with "
            f"{COMPENSATION_PLANNING_CONTROLLER_FLAG}=true"
        )
    document.pop("servers", None)
    document = apply_design_time_overlay(service, document)
    validate_unique_operation_ids(document, service.name)
    if service.name == "approval":
        validate_approval_signature_operations(document)
    return document


def load_snapshot(service: ServiceContract) -> dict[str, Any]:
    target = CONTRACT_ROOT / f"{service.name}.json"
    try:
        document = json.loads(target.read_text(encoding="utf-8"))
    except FileNotFoundError:
        document = {
            "openapi": "3.1.0",
            "info": {
                "title": f"DWP {service.name.title()} Service API",
                "version": "1.0.0",
            },
            "paths": {},
        }
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(
            f"Unable to read approved {service.name} OpenAPI snapshot at {target}: {error}"
        ) from error
    document = apply_design_time_overlay(service, document, approved_snapshot=True)
    if not str(document.get("openapi", "")).startswith("3.") or not document.get("paths"):
        raise RuntimeError(f"{service.name} approved OpenAPI snapshot is invalid")
    validate_unique_operation_ids(document, service.name)
    if service.name == "approval":
        validate_approval_signature_operations(document)
    return document


def load_design_time_overlays() -> dict[str, dict[str, Any]]:
    try:
        envelope = json.loads(HRIS_DESIGN_TIME_OVERLAY.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(
            f"Unable to read HRIS design-time OpenAPI overlay at "
            f"{HRIS_DESIGN_TIME_OVERLAY}: {error}"
        ) from error
    if (
        envelope.get("version") != 1
        or set(envelope) != {"version", "services"}
        or not isinstance(envelope.get("services"), dict)
    ):
        raise RuntimeError("HRIS design-time OpenAPI overlay envelope is invalid")
    return envelope["services"]


def parameter_identity(parameter: dict[str, Any]) -> tuple[str, str]:
    return str(parameter.get("in", "")).lower(), str(parameter.get("name", "")).lower()


def merge_reviewed_parameters(
        existing: dict[str, Any], reviewed: dict[str, Any], *,
        conditional_required: bool = False) -> None:
    """Add reviewed parameters and retain their fail-closed constraints.

    Springdoc remains authoritative for additional schema detail, but it may not silently drop
    a reviewed requirement or constraint. Missing reviewed schema keys are restored and a
    conflicting live value fails the export instead of weakening or misrepresenting the contract.
    """
    reviewed_parameters = reviewed.get("parameters", [])
    if not isinstance(reviewed_parameters, list):
        raise RuntimeError("HRIS design-time operation parameters must be an array")
    if not reviewed_parameters:
        return

    parameters = existing.setdefault("parameters", [])
    if not isinstance(parameters, list):
        raise RuntimeError("HRIS live operation parameters must be an array")
    by_identity = {
        parameter_identity(parameter): parameter
        for parameter in parameters
        if isinstance(parameter, dict) and all(parameter_identity(parameter))
    }
    for parameter in reviewed_parameters:
        if not isinstance(parameter, dict):
            raise RuntimeError("HRIS design-time operation parameter must be an object")
        identity = parameter_identity(parameter)
        if not all(identity):
            raise RuntimeError("HRIS design-time operation parameter identity is invalid")
        existing_parameter = by_identity.get(identity)
        if existing_parameter is None:
            parameters.append(copy.deepcopy(parameter))
            by_identity[identity] = parameters[-1]
            continue

        if "required" in parameter:
            reviewed_required = parameter["required"]
            live_required = existing_parameter.get("required")
            if live_required is None:
                existing_parameter["required"] = reviewed_required
            elif live_required != reviewed_required and not conditional_required:
                raise RuntimeError(
                    f"HRIS reviewed parameter required constraint collision: {identity}"
                )

        reviewed_schema = parameter.get("schema")
        if reviewed_schema is None:
            continue
        if not isinstance(reviewed_schema, dict):
            raise RuntimeError("HRIS reviewed parameter schema must be an object")
        live_schema = existing_parameter.get("schema")
        if live_schema is None:
            existing_parameter["schema"] = copy.deepcopy(reviewed_schema)
            continue
        if not isinstance(live_schema, dict):
            raise RuntimeError("HRIS live parameter schema must be an object")
        for key, value in reviewed_schema.items():
            if key not in live_schema:
                live_schema[key] = copy.deepcopy(value)
            elif live_schema[key] != value:
                raise RuntimeError(
                    f"HRIS reviewed parameter schema collision: {identity} {key}"
                )


def make_variant_parameters_conditional(
        existing: dict[str, Any], reviewed: dict[str, Any]) -> None:
    """Keep query-variant requirements out of the shared OpenAPI operation.

    A PATCH overlay represents one query-dispatched controller variant. Parameters required
    only after that variant is selected remain recorded in ``x-dwp-controller-variants``;
    marking them required on the shared operation would reject valid calls to the base handler.
    """
    required_parameters = reviewed.get("x-dwp-required-parameters", [])
    if not isinstance(required_parameters, list) or not all(
            isinstance(value, str) and value for value in required_parameters):
        raise RuntimeError("HRIS design-time required parameters must be a string array")
    conditional_names = {value.split("=", 1)[0] for value in required_parameters}
    for parameter in existing.get("parameters", []):
        if (
                isinstance(parameter, dict)
                and parameter.get("in") == "query"
                and parameter.get("name") in conditional_names
        ):
            parameter["required"] = False


def merge_reviewed_components(
        merged: dict[str, Any],
        reviewed: dict[str, Any],
        service_name: str,
        *,
        approved_snapshot: bool = False) -> None:
    """Merge focused springdoc schemas, replacing only stale approved captures."""
    if not isinstance(reviewed, dict) or set(reviewed) - {"schemas"}:
        raise RuntimeError(
            f"HRIS design-time components are invalid for {service_name}"
        )
    schemas = reviewed.get("schemas", {})
    if not isinstance(schemas, dict):
        raise RuntimeError(
            f"HRIS design-time component schemas are invalid for {service_name}"
        )
    target = merged.setdefault("components", {}).setdefault("schemas", {})
    if not isinstance(target, dict):
        raise RuntimeError(f"HRIS live component schemas are invalid for {service_name}")
    for name, schema in schemas.items():
        if not isinstance(name, str) or not name or not isinstance(schema, dict):
            raise RuntimeError(
                f"HRIS design-time component schema is invalid for {service_name}: {name}"
            )
        existing = target.get(name)
        exact_runtime_projection = (
            existing is not None
            and HRM_RUNTIME_SCHEMA_PROJECTION_SHA256.get((service_name, name))
            == canonical_contract_hash(existing)
            and HRM_CANONICAL_SCHEMA_SHA256.get(name)
            == canonical_contract_hash(schema)
        )
        if (
            existing is not None
            and existing != schema
            and not approved_snapshot
            and not exact_runtime_projection
        ):
            raise RuntimeError(
                f"HRIS reviewed component collision for {service_name}: {name}"
            )
        target[name] = copy.deepcopy(schema)


def apply_design_time_overlay(
        service: ServiceContract,
        document: dict[str, Any],
        *,
        approved_snapshot: bool = False) -> dict[str, Any]:
    """Merge reviewed HRIS operations into a runtime/approved service contract.

    ADD operations become the canonical representation when an approved snapshot has not yet
    observed the controller. ``x-dwp-runtime-default`` distinguishes current always-on controllers
    from reviewed default-off owners. If springdoc does expose the controller, its method-derived
    operationId is checked before the reviewed stable operationId is applied. PATCH operations
    describe query-dispatched controller variants that OpenAPI cannot represent as a second
    operation for the same method and path.
    """
    overlay = load_design_time_overlays().get(service.name)
    if overlay is None:
        return document
    if (
        set(overlay) not in ({"paths"}, {"components", "paths"})
        or not isinstance(overlay["paths"], dict)
    ):
        raise RuntimeError(f"HRIS design-time overlay is invalid for {service.name}")

    merged = copy.deepcopy(document)
    if "components" in overlay:
        merge_reviewed_components(
            merged,
            overlay["components"],
            service.name,
            approved_snapshot=approved_snapshot,
        )
    paths = merged.setdefault("paths", {})
    for path, path_item in overlay["paths"].items():
        if not isinstance(path, str) or not path.startswith("/") or not isinstance(path_item, dict):
            raise RuntimeError(f"HRIS design-time path is invalid for {service.name}: {path}")
        target_path_item = paths.setdefault(path, {})
        for method, reviewed in path_item.items():
            if method not in HTTP_METHODS or not isinstance(reviewed, dict):
                raise RuntimeError(
                    f"HRIS design-time operation is invalid for {service.name}: {method} {path}"
                )
            mode = reviewed.get("x-dwp-overlay-mode")
            controller_method = reviewed.get("x-dwp-controller-method")
            stable_operation_id = reviewed.get("operationId")
            if (
                mode not in {"ADD", "PATCH"}
                or not isinstance(controller_method, str)
                or not controller_method
                or not isinstance(stable_operation_id, str)
                or not stable_operation_id
            ):
                raise RuntimeError(
                    f"HRIS design-time operation metadata is invalid for "
                    f"{service.name}: {method.upper()} {path}"
                )
            if mode == "ADD" and reviewed.get("x-dwp-runtime-default") not in {"ON", "OFF"}:
                raise RuntimeError(
                    f"HRIS design-time runtime default is invalid for "
                    f"{service.name}: {method.upper()} {path}"
                )

            existing = target_path_item.get(method)
            if mode == "PATCH":
                if not isinstance(existing, dict):
                    raise RuntimeError(
                        f"HRIS PATCH overlay has no base operation for "
                        f"{service.name}: {method.upper()} {path}"
                    )
                variants = existing.setdefault("x-dwp-controller-variants", [])
                variant = {
                    "controllerMethod": controller_method,
                    "operationId": stable_operation_id,
                    "requiredParameters": reviewed.get("x-dwp-required-parameters", []),
                }
                variants = [
                    item for item in variants
                    if item.get("controllerMethod") != controller_method
                ]
                variants.append(variant)
                existing["x-dwp-controller-variants"] = sorted(
                    variants, key=lambda item: item["controllerMethod"]
                )
                merge_reviewed_parameters(
                    existing, reviewed, conditional_required=True
                )
                make_variant_parameters_conditional(existing, reviewed)
                continue

            canonical = copy.deepcopy(reviewed)
            canonical.pop("x-dwp-overlay-mode", None)
            if existing is not None:
                if not isinstance(existing, dict):
                    raise RuntimeError(
                        f"HRIS live operation is invalid for "
                        f"{service.name}: {method.upper()} {path}"
                    )
                actual_operation_id = existing.get("operationId")
                if actual_operation_id not in {controller_method, stable_operation_id}:
                    raise RuntimeError(
                        f"HRIS live controller method drift for {service.name}: "
                        f"{method.upper()} {path}; expected {controller_method}, "
                        f"found {actual_operation_id}"
                    )
                captured_slice = canonical.get("x-dwp-reviewed-springdoc-capture") is True
                reviewed_design_time_revision = (
                    approved_snapshot
                    and canonical.get("x-dwp-reviewed-design-time-contract")
                    == "HRM-010"
                )
                if not (
                    approved_snapshot and captured_slice
                ) and not reviewed_design_time_revision:
                    # Preserve springdoc's exact schemas while canonicalizing the public
                    # identifier. A marked reviewed slice only replaces a stale approved
                    # snapshot; a live service remains authoritative.
                    live = copy.deepcopy(existing)
                    live["operationId"] = stable_operation_id
                    live["x-dwp-controller-method"] = controller_method
                    merge_reviewed_parameters(live, canonical)
                    for key, value in canonical.items():
                        if key.startswith("x-dwp-"):
                            live[key] = value
                    canonical = live
            target_path_item[method] = canonical
    return merged


def validate_approval_signature_operations(
        document: dict[str, Any], *, gateway: bool = False) -> None:
    prefix = "/api/approvals" if gateway else ""
    expected = {
        (method, f"{prefix}{path}")
        for method, path in APPROVAL_SIGNATURE_FEATURE_OPERATIONS
    }
    expected.add((APPROVAL_SIGNATURE_LEGACY_OPERATION[0],
                  f"{prefix}{APPROVAL_SIGNATURE_LEGACY_OPERATION[1]}"))
    actual = {
        (method, path)
        for path, path_item in document.get("paths", {}).items()
        if "signature" in path
        and (not gateway or path.startswith("/api/approvals/"))
        for method in path_item
        if method in HTTP_METHODS
    }
    if actual != expected:
        missing = sorted(expected - actual)
        unexpected = sorted(actual - expected)
        raise RuntimeError(
            "Approval signature OpenAPI operations are not exact: "
            f"missing={missing}, unexpected={unexpected}"
        )


def load_gateway_owned_snapshot() -> dict[str, Any]:
    try:
        document = json.loads(GATEWAY_OWNED_SNAPSHOT.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(
            f"Unable to read Gateway-owned OpenAPI snapshot at {GATEWAY_OWNED_SNAPSHOT}: {error}"
        ) from error
    if not str(document.get("openapi", "")).startswith("3.") or not document.get("paths"):
        raise RuntimeError("Gateway-owned OpenAPI snapshot is invalid")
    validate_unique_operation_ids(document, "gateway-owned")
    for path in document["paths"]:
        if not path.startswith("/api/") or path.startswith("/api/internal/"):
            raise RuntimeError(f"Gateway-owned contract contains a non-public path: {path}")
    return document


def load_agent_public_snapshot() -> dict[str, Any]:
    try:
        document = json.loads(AGENT_PUBLIC_SNAPSHOT.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(
            f"Unable to read Agent public OpenAPI snapshot at "
            f"{AGENT_PUBLIC_SNAPSHOT}: {error}"
        ) from error
    expected = {
        "/v1/admin/ai-control": {"get"},
        "/v1/admin/ai-control/bootstrap": {"post"},
        "/v1/admin/ai-control/policy": {"put"},
        "/v1/admin/ai-control/emergency": {"post"},
    }
    actual = document.get("paths", {})
    if (
        not str(document.get("openapi", "")).startswith("3.")
        or not actual
        or any(actual.get(path) is None
               or {method for method in actual[path] if method in HTTP_METHODS} != methods
               for path, methods in expected.items())
        or any(path.startswith("/internal/") for path in actual)
    ):
        raise RuntimeError("Agent public approved OpenAPI snapshot is invalid")
    validate_unique_operation_ids(document, "agent")
    return document


def component_names(service: str, document: dict[str, Any]) -> dict[tuple[str, str], str]:
    return {
        (category, name): f"{service}_{name}"
        for category, values in document.get("components", {}).items()
        if isinstance(values, dict)
        for name in values
    }


def rewrite_contract_value(value: Any, names: dict[tuple[str, str], str]) -> Any:
    if isinstance(value, list):
        return [rewrite_contract_value(item, names) for item in value]
    if isinstance(value, str) and value.startswith("#/components/"):
        parts = value.split("/")
        replacement = names.get((parts[2], parts[3])) if len(parts) == 4 else None
        return f"#/components/{parts[2]}/{replacement}" if replacement else value
    if not isinstance(value, dict):
        return value

    rewritten: dict[str, Any] = {}
    for key, child in value.items():
        if key == "security" and isinstance(child, list):
            rewritten[key] = [
                {
                    names.get(("securitySchemes", scheme), scheme): scopes
                    for scheme, scopes in requirement.items()
                }
                for requirement in child
            ]
        else:
            rewritten[key] = rewrite_contract_value(child, names)
    return rewritten


def gateway_component_value(
        service: str,
        category: str,
        name: str,
        value: Any,
        names: dict[tuple[str, str], str]) -> Any:
    rewritten = rewrite_contract_value(copy.deepcopy(value), names)
    public_device_scheme = PLATFORM_DEVICE_SECURITY_SCHEMES.get(name)
    if (
        service == "platform"
        and category == "securitySchemes"
        and public_device_scheme is not None
    ):
        if not isinstance(rewritten, dict) or rewritten.get("type") != "apiKey":
            raise RuntimeError(f"Platform DEVICE security scheme is invalid: {name}")
        rewritten["in"] = "header"
        rewritten.update(public_device_scheme)
    return rewritten


def validate_platform_device_identity(
        document: dict[str, Any], *, gateway: bool = False) -> None:
    prefix = "/api/platform" if gateway else ""
    scheme_prefix = "platform_" if gateway else ""
    expected = {(method, prefix + path) for method, path in PLATFORM_DEVICE_OPERATIONS}
    actual = {
        (method, path)
        for path, path_item in document.get("paths", {}).items()
        for method, operation in path_item.items()
        if method in HTTP_METHODS
        and isinstance(operation, dict)
        and operation.get("x-dwp-identity-plane") == "DEVICE"
    }
    if actual != expected:
        raise RuntimeError(
            "Platform DEVICE OpenAPI operations are not exact: "
            f"missing={sorted(expected - actual)}, unexpected={sorted(actual - expected)}"
        )
    security_schemes = document.get("components", {}).get("securitySchemes", {})
    for name, public in PLATFORM_DEVICE_SECURITY_SCHEMES.items():
        scheme = security_schemes.get(scheme_prefix + name)
        expected_header = public["name"] if gateway else {
            "DeviceTenant": "X-DWP-Tenant-ID",
            "DeviceCredential": "X-DWP-Device-Credential",
        }[name]
        if (
            not isinstance(scheme, dict)
            or scheme.get("type") != "apiKey"
            or scheme.get("in") != "header"
            or scheme.get("name") != expected_header
        ):
            raise RuntimeError(
                f"Platform DEVICE OpenAPI security scheme is invalid: {scheme_prefix}{name}"
            )
    expected_security = [{
        scheme_prefix + "DeviceTenant": [],
        scheme_prefix + "DeviceCredential": [],
    }]
    for method, path in expected:
        operation = document["paths"][path][method]
        if operation.get("security") != expected_security:
            raise RuntimeError(
                f"Platform DEVICE OpenAPI security is invalid: {method.upper()} {path}"
            )
        parameters = operation.get("parameters", [])
        if any(
            isinstance(parameter, dict)
            and parameter.get("name") in {
                "contextScopeKey", "X-DWP-Expected-Decision-Revision"
            }
            for parameter in parameters
        ):
            raise RuntimeError(
                f"Platform DEVICE operation was mixed with human PRODUCT governance: "
                f"{method.upper()} {path}"
            )


def gateway_contract(
        documents: dict[str, dict[str, Any]],
        gateway_owned: dict[str, Any] | None = None) -> dict[str, Any]:
    gateway: dict[str, Any] = {
        "openapi": "3.1.0",
        "info": {
            "title": "DWP Gateway Public API",
            "version": "1.0.0",
            "description": "Browser-facing, same-origin API contract. Internal service paths are excluded.",
        },
        "servers": [{"url": "/", "description": "Same-origin enterprise Gateway"}],
        "paths": {},
        "components": {},
        "tags": [],
    }
    if gateway_owned is not None:
        names = component_names("gateway", gateway_owned)
        for path, path_item in gateway_owned.get("paths", {}).items():
            gateway["paths"][path] = rewrite_contract_value(copy.deepcopy(path_item), names)
        for category, values in gateway_owned.get("components", {}).items():
            if not isinstance(values, dict):
                continue
            target = gateway["components"].setdefault(category, {})
            for name, value in values.items():
                target[names[(category, name)]] = rewrite_contract_value(
                    copy.deepcopy(value), names
                )
        gateway["tags"].append({
            "name": "gateway",
            "description": "Gateway-owned public contracts",
        })
    for service in GATEWAY_SERVICES:
        document = documents[service.name]
        names = component_names(service.name, document)
        for path, path_item in document.get("paths", {}).items():
            public_path = service.public_path(path)
            if public_path is None:
                continue
            rewritten = rewrite_contract_value(copy.deepcopy(path_item), names)
            for method, operation in rewritten.items():
                if method in HTTP_METHODS and isinstance(operation, dict):
                    parameters = operation.get("parameters")
                    if isinstance(parameters, list):
                        operation["parameters"] = [
                            parameter
                            for parameter in parameters
                            if not (
                                isinstance(parameter, dict)
                                and str(parameter.get("in", "")).lower() == "header"
                                and (
                                    (
                                        str(parameter.get("name", "")).lower()
                                        .startswith("x-dwp-")
                                        and str(parameter.get("name", "")).lower()
                                        not in PUBLIC_COMMAND_PROOF_HEADERS
                                    )
                                    or (
                                        (public_path, method)
                                        in WAVE2_TRUSTED_PURPOSE_OPERATIONS
                                        and str(parameter.get("name", "")).lower()
                                        == "x-purpose-code"
                                    )
                                )
                            )
                        ]
                    operation["tags"] = [service.name]
                    if operation.get("operationId"):
                        operation["operationId"] = f"{service.name}_{operation['operationId']}"
                    if public_path == TELEMETRY_PUBLIC_PATH:
                        operation["parameters"] = [
                            parameter
                            for parameter in operation.get("parameters", [])
                            if not (
                                isinstance(parameter, dict)
                                and parameter.get("in") == "header"
                                and parameter.get("name") in TELEMETRY_TRUSTED_HEADERS
                            )
                        ]
                        if not operation["parameters"]:
                            operation.pop("parameters", None)
            if public_path in gateway["paths"]:
                raise RuntimeError(f"Gateway contract path collision: {public_path}")
            gateway["paths"][public_path] = rewritten

        for category, values in document.get("components", {}).items():
            if not isinstance(values, dict):
                continue
            target = gateway["components"].setdefault(category, {})
            for name, value in values.items():
                target[names[(category, name)]] = gateway_component_value(
                    service.name, category, name, value, names
                )
        gateway["tags"].append({"name": service.name, "description": f"{service.name} service"})

    if not gateway["paths"]:
        raise RuntimeError("Gateway public contract contains no routes")
    add_product_governance_contract(gateway)
    return prune_unreachable_components(gateway)


def load_authorization_registry(
    registry_path: Path, registry_version: int
) -> dict[str, Any]:
    try:
        registry = json.loads(registry_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(
            f"Unable to read product authorization registry at "
            f"{registry_path}: {error}"
        ) from error
    if (
        registry.get("bundleKey") != "product-surfaces"
        or registry.get("version") != registry_version
        or registry.get("bundleStatus") != "DRAFT"
        or not isinstance(registry.get("routes"), list)
    ):
        raise RuntimeError(
            f"Product authorization v{registry_version} registry is invalid"
        )
    return registry


def load_product_authorization_registry() -> dict[str, Any]:
    return load_authorization_registry(
        PRODUCT_AUTHORIZATION_REGISTRY, PRODUCT_AUTHORIZATION_VERSION
    )


def load_hrm_employee_change_authorization_registry() -> dict[str, Any]:
    return load_authorization_registry(
        HRM_EMPLOYEE_CHANGE_AUTHORIZATION_REGISTRY,
        HRM_EMPLOYEE_CHANGE_AUTHORIZATION_VERSION,
    )


def product_governed_operations() -> dict[tuple[str, str], bool]:
    registry = load_product_authorization_registry()
    operations: dict[tuple[str, str], bool] = {}
    for route in registry["routes"]:
        subject = route.get("subject", {})
        if route.get("lifecycleState") != "ACTIVE" or subject.get("type") != "PRODUCT":
            continue
        state_changing = (
            route.get("routeKind") == "ACTION"
            and route.get("sideEffectFree") is not True
        )
        bindings = route.get("gatewayApiBindings")
        if not isinstance(bindings, list) or not bindings:
            raise RuntimeError(
                f"Active PRODUCT route has no Gateway binding: {route.get('routeContractKey')}"
            )
        for binding in bindings:
            method = str(binding.get("method", "")).lower()
            path = binding.get("path")
            if (
                method not in HTTP_METHODS
                or not isinstance(path, str)
                or not path.startswith("/api/")
            ):
                raise RuntimeError(
                    f"Active PRODUCT route has an invalid Gateway binding: "
                    f"{route.get('routeContractKey')}"
                )
            key = (path, method)
            operations[key] = operations.get(key, False) or state_changing
    if not operations:
        raise RuntimeError(
            f"Product authorization v{PRODUCT_AUTHORIZATION_VERSION} registry has no public operations"
        )
    return operations


def governed_operation(
        document: dict[str, Any], path: str, method: str
) -> tuple[str, dict[str, Any]] | None:
    exact = document.get("paths", {}).get(path, {}).get(method)
    if isinstance(exact, dict):
        return path, exact
    expected_shape = PATH_PARAMETER_PATTERN.sub("{}", path)
    matches = [
        (candidate_path, path_item[method])
        for candidate_path, path_item in document.get("paths", {}).items()
        if PATH_PARAMETER_PATTERN.sub("{}", candidate_path) == expected_shape
        and isinstance(path_item.get(method), dict)
    ]
    if len(matches) > 1:
        raise RuntimeError(
            f"Gateway product binding is structurally ambiguous: "
            f"{method.upper()} {path}"
        )
    return matches[0] if matches else None


def add_product_governance_contract(document: dict[str, Any]) -> None:
    injected = 0
    revision_injected = 0
    for (path, method), state_changing in sorted(product_governed_operations().items()):
        resolved = governed_operation(document, path, method)
        if resolved is None:
            # The DRAFT registry also contains fail-closed bindings for service
            # routes that are not yet exported as public OpenAPI operations.
            continue
        actual_path, operation = resolved
        parameters = operation.setdefault("parameters", [])
        if not isinstance(parameters, list):
            raise RuntimeError(
                f"Gateway operation parameters are invalid: "
                f"{method.upper()} {actual_path}"
            )
        collisions = [
            parameter
            for parameter in parameters
            if isinstance(parameter, dict)
            and parameter.get("in") == "query"
            and parameter.get("name") == SCOPE_SELECTION_PARAMETER["name"]
        ]
        if collisions:
            raise RuntimeError(
                f"Gateway scope selection parameter collision: "
                f"{method.upper()} {actual_path}"
            )
        parameters.append(copy.deepcopy(SCOPE_SELECTION_PARAMETER))
        injected += 1
        if state_changing:
            revision_collisions = [
                (index, parameter)
                for index, parameter in enumerate(parameters)
                if isinstance(parameter, dict)
                and str(parameter.get("in", "")).lower() == "header"
                and str(parameter.get("name", "")).lower()
                == EXPECTED_DECISION_REVISION_PARAMETER["name"].lower()
            ]
            if len(revision_collisions) > 1:
                raise RuntimeError(
                    f"Gateway expected-decision revision parameter collision: "
                    f"{method.upper()} {actual_path}"
                )
            command_proof_required = (
                operation.get("x-dwp-command-proof-required") is True
            )
            if command_proof_required:
                if not revision_collisions:
                    raise RuntimeError(
                        f"Gateway required command proof is missing a decision revision: "
                        f"{method.upper()} {actual_path}"
                    )
                canonical_revision = copy.deepcopy(revision_collisions[0][1])
                if (
                    canonical_revision.get("required") is not True
                    or canonical_revision.get("schema")
                    != {"type": "string", "minLength": 1, "maxLength": 200}
                ):
                    raise RuntimeError(
                        f"Gateway required command proof decision revision is invalid: "
                        f"{method.upper()} {actual_path}"
                    )
            else:
                canonical_revision = copy.deepcopy(EXPECTED_DECISION_REVISION_PARAMETER)
            if actual_path in HOME_RUNTIME_MUTATION_PATHS and not command_proof_required:
                canonical_revision["description"] = (
                    "Required and fail-closed for the Home Runtime authority states "
                    "100/110/111; state 000 never enters the Home Runtime owner contract."
                )
                canonical_revision["x-dwp-conditional-required"]["rolloutStates"] = [
                    "100", "110", "111"
                ]
            if revision_collisions:
                parameters[revision_collisions[0][0]] = canonical_revision
            else:
                parameters.append(canonical_revision)
            revision_injected += 1
        if actual_path in HOME_RUNTIME_PATHS:
            for status, response in operation.get("responses", {}).items():
                if status not in {"200", "202", "204", "304"} \
                        or not isinstance(response, dict):
                    continue
                headers = response.setdefault("headers", {})
                if "X-DWP-Decision-Revision" in headers:
                    raise RuntimeError(
                        f"Gateway decision revision response header collision: "
                        f"{method.upper()} {actual_path} {status}"
                    )
                headers["X-DWP-Decision-Revision"] = copy.deepcopy(
                    RESPONSE_DECISION_REVISION_HEADER
                )
    if injected == 0:
        raise RuntimeError("Gateway public contract has no exported governed PRODUCT operation")
    if revision_injected == 0:
        raise RuntimeError(
            "Gateway public contract has no exported state-changing PRODUCT operation"
        )


def validate_sys_reference_configuration_contract(
        platform_document: dict[str, Any],
        gateway_document: dict[str, Any]) -> None:
    """Pin the SYS-007 source, owner, and browser contracts to one exact registry."""
    registry = load_product_authorization_registry()
    routes = {
        route.get("routeContractKey"): route
        for route in registry["routes"]
        if route.get("routeContractKey") in SYS_REFERENCE_CONFIGURATION_ROUTE_KEYS
    }
    if set(routes) != SYS_REFERENCE_CONFIGURATION_ROUTE_KEYS:
        missing = sorted(SYS_REFERENCE_CONFIGURATION_ROUTE_KEYS - set(routes))
        unexpected = sorted(set(routes) - SYS_REFERENCE_CONFIGURATION_ROUTE_KEYS)
        raise RuntimeError(
            "SYS reference-configuration authorization routes are not exact: "
            f"missing={missing}, unexpected={unexpected}"
        )

    expected_operations: list[
        tuple[str, str, str, str, str, dict[str, Any]]
    ] = []
    for route_key, route in sorted(routes.items()):
        if (
            route.get("lifecycleState") != "ACTIVE"
            or route.get("subject", {}).get("type") != "PRODUCT"
        ):
            raise RuntimeError(
                f"SYS reference-configuration route is not active PRODUCT: {route_key}"
            )
        gateway_bindings = route.get("gatewayApiBindings")
        owner_bindings = route.get("servicePepBindings")
        if (
            not isinstance(gateway_bindings, list)
            or len(gateway_bindings) != 1
            or not isinstance(owner_bindings, list)
            or len(owner_bindings) != 1
        ):
            raise RuntimeError(
                f"SYS reference-configuration route must have one browser and owner "
                f"binding: {route_key}"
            )
        gateway_binding = gateway_bindings[0]
        owner_binding = owner_bindings[0]
        expected_method, expected_owner_path, operation_id, response_status = (
            SYS_REFERENCE_CONFIGURATION_OPERATION_CONTRACTS[route_key]
        )
        method = str(gateway_binding.get("method", "")).lower()
        browser_path = gateway_binding.get("path")
        owner_path = owner_binding.get("path")
        if (
            method not in HTTP_METHODS
            or method != expected_method
            or str(owner_binding.get("method", "")).lower() != method
            or gateway_binding.get("bindingKey") != owner_binding.get("bindingKey")
            or owner_binding.get("serviceKey") != "platform"
            or not isinstance(browser_path, str)
            or not isinstance(owner_path, str)
            or owner_path != expected_owner_path
            or platform_path(owner_path) != browser_path
        ):
            raise RuntimeError(
                f"SYS reference-configuration browser/owner binding drift: {route_key}"
            )
        expected_operations.append((
            method,
            owner_path,
            browser_path,
            operation_id,
            response_status,
            route,
        ))

    platform_overlay = load_design_time_overlays().get("platform", {}).get("paths", {})
    source_operations = {
        (method, path)
        for path, path_item in platform_overlay.items()
        if (
            path == "/v1/hris/workbench/settings-reference"
            or path.startswith("/v1/admin/hris/configurations")
        )
        for method in path_item
        if method in HTTP_METHODS
    }
    expected_source_operations = {
        (method, owner_path)
        for method, owner_path, _, _, _, _ in expected_operations
    }
    if source_operations != expected_source_operations:
        raise RuntimeError(
            "SYS reference-configuration design-time operations are not exact: "
            f"missing={sorted(expected_source_operations - source_operations)}, "
            f"unexpected={sorted(source_operations - expected_source_operations)}"
        )

    def parameters_by_identity(
            operation: dict[str, Any]) -> dict[tuple[str, str], dict[str, Any]]:
        return {
            (
                str(parameter.get("in", "")).lower(),
                str(parameter.get("name", "")).lower(),
            ): parameter
            for parameter in operation.get("parameters", [])
            if isinstance(parameter, dict)
        }

    for (
        method,
        owner_path,
        browser_path,
        operation_id,
        response_status,
        route,
    ) in expected_operations:
        source_operation = platform_overlay[owner_path].get(method)
        owner_operation = platform_document.get("paths", {}).get(
            owner_path, {}
        ).get(method)
        browser_operation = gateway_document.get("paths", {}).get(
            browser_path, {}
        ).get(method)
        if not all(
            isinstance(operation, dict)
            for operation in (source_operation, owner_operation, browser_operation)
        ):
            raise RuntimeError(
                "SYS reference-configuration operation is missing from source, owner, "
                f"or Gateway: {method.upper()} {owner_path}"
            )
        assert isinstance(source_operation, dict)
        assert isinstance(owner_operation, dict)
        assert isinstance(browser_operation, dict)
        if (
            source_operation.get("x-dwp-overlay-mode") != "ADD"
            or source_operation.get("x-dwp-runtime-default") != "OFF"
            or owner_operation.get("x-dwp-runtime-default") != "OFF"
            or browser_operation.get("x-dwp-runtime-default") != "OFF"
        ):
            raise RuntimeError(
                "SYS reference-configuration operation must remain default-off: "
                f"{method.upper()} {owner_path}"
            )

        operation_contracts = (
            ("source", source_operation, operation_id),
            ("owner", owner_operation, operation_id),
            ("Gateway", browser_operation, f"platform_{operation_id}"),
        )
        for contract_name, operation, expected_operation_id in operation_contracts:
            if operation.get("operationId") != expected_operation_id:
                raise RuntimeError(
                    "SYS reference-configuration operationId drift: "
                    f"{contract_name} {method.upper()} {owner_path}"
                )
            if set(operation.get("responses", {})) != {response_status}:
                raise RuntimeError(
                    "SYS reference-configuration response drift: "
                    f"{contract_name} {method.upper()} {owner_path}"
                )
            # An explicit empty security requirement makes an operation anonymous in
            # OpenAPI. Session-authenticated PRODUCT operations may inherit a scheme or
            # declare a non-empty one, but may never opt out of authentication.
            if operation.get("security", object()) == []:
                raise RuntimeError(
                    "SYS reference-configuration anonymous security override: "
                    f"{contract_name} {method.upper()} {owner_path}"
                )

        browser_parameters = parameters_by_identity(browser_operation)
        scope_parameter = browser_parameters.get(("query", "contextscopekey"))
        if scope_parameter != SCOPE_SELECTION_PARAMETER:
            raise RuntimeError(
                "SYS reference-configuration Gateway operation is not governed: "
                f"{method.upper()} {browser_path}"
            )
        if method == "post":
            owner_parameters = parameters_by_identity(owner_operation)
            source_parameters = parameters_by_identity(source_operation)
            idempotency_identity = ("header", "idempotency-key")
            if (
                idempotency_identity not in source_parameters
                or idempotency_identity not in owner_parameters
                or idempotency_identity not in browser_parameters
                or any(
                    parameters[idempotency_identity].get("required") is not True
                    for parameters in (
                        source_parameters,
                        owner_parameters,
                        browser_parameters,
                    )
                )
                or source_operation.get("requestBody", {}).get("required") is not True
                or owner_operation.get("requestBody", {}).get("required") is not True
                or browser_operation.get("requestBody", {}).get("required") is not True
            ):
                raise RuntimeError(
                    "SYS reference-configuration mutation is not idempotent: "
                    f"{method.upper()} {owner_path}"
                )
            step_up_bindings = route.get("stepUpCommandBindings", [])
            if not isinstance(step_up_bindings, list):
                raise RuntimeError(
                    f"SYS reference-configuration step-up binding is invalid: "
                    f"{route['routeContractKey']}"
                )
            if step_up_bindings:
                proof_headers = {
                    ("header", "x-dwp-step-up-challenge"),
                    ("header", "x-dwp-expected-decision-revision"),
                    ("header", "x-dwp-expected-object-version"),
                }
                if (
                    len(step_up_bindings) != 1
                    or owner_operation.get("x-dwp-command-proof-required") is not True
                    or browser_operation.get("x-dwp-command-proof-required") is not True
                    or not proof_headers <= set(owner_parameters)
                    or not proof_headers <= set(browser_parameters)
                    or any(
                        owner_parameters[identity].get("required") is not True
                        or browser_parameters[identity].get("required") is not True
                        for identity in proof_headers
                    )
                ):
                    raise RuntimeError(
                        "SYS reference-configuration step-up proof drift: "
                        f"{route['routeContractKey']}"
                    )
            else:
                revision = browser_parameters.get(
                    ("header", "x-dwp-expected-decision-revision")
                )
                if revision != EXPECTED_DECISION_REVISION_PARAMETER:
                    raise RuntimeError(
                        "SYS reference-configuration decision revision drift: "
                        f"{route['routeContractKey']}"
                    )


def canonical_contract_hash(value: Any) -> str:
    payload = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def normalize_gateway_people_schema(value: Any) -> Any:
    if isinstance(value, list):
        return [normalize_gateway_people_schema(item) for item in value]
    if isinstance(value, str) and value.startswith("#/components/schemas/people_"):
        return value.replace("#/components/schemas/people_", "#/components/schemas/", 1)
    if isinstance(value, dict):
        return {
            key: normalize_gateway_people_schema(child)
            for key, child in value.items()
        }
    return value


def validate_compensation_planning_contract(
    people_document: dict[str, Any], gateway_document: dict[str, Any]
) -> None:
    """Require the complete v39 route and closed payload graph for compensation."""
    registry = load_product_authorization_registry()
    routes = {
        route.get("routeContractKey"): route
        for route in registry["routes"]
        if route.get("routeContractKey") in COMPENSATION_PLANNING_ROUTE_KEYS
    }
    if set(routes) != COMPENSATION_PLANNING_ROUTE_KEYS:
        raise RuntimeError(
            "Compensation-planning v39 authorization routes are not exact"
        )

    owner_operations: set[tuple[str, str]] = set()
    browser_operations: set[tuple[str, str]] = set()
    for route_key, route in routes.items():
        owner_bindings = route.get("servicePepBindings")
        browser_bindings = route.get("gatewayApiBindings")
        if (
            route.get("lifecycleState") != "ACTIVE"
            or route.get("subject", {}).get("type") != "PRODUCT"
            or not isinstance(owner_bindings, list)
            or len(owner_bindings) != 1
            or not isinstance(browser_bindings, list)
            or len(browser_bindings) != 1
        ):
            raise RuntimeError(
                f"Compensation-planning v39 route binding is invalid: {route_key}"
            )
        owner = owner_bindings[0]
        browser = browser_bindings[0]
        method = str(owner.get("method", "")).lower()
        owner_path = owner.get("path")
        browser_path = browser.get("path")
        expected_method, expected_owner_path, _, _, _ = (
            COMPENSATION_PLANNING_OPERATION_CONTRACTS[route_key]
        )
        if (
            method not in HTTP_METHODS
            or method != expected_method
            or owner_path != expected_owner_path
            or str(browser.get("method", "")).lower() != method
            or owner.get("serviceKey") != "people"
            or owner.get("bindingKey") != browser.get("bindingKey")
            or not isinstance(owner_path, str)
            or prefixed("/api/people")(owner_path) != browser_path
        ):
            raise RuntimeError(
                f"Compensation-planning v39 owner/browser binding drift: {route_key}"
            )
        owner_operations.add((method, owner_path))
        browser_operations.add((method, browser_path))

    actual_owner = {
        (method, path)
        for path, path_item in people_document.get("paths", {}).items()
        if path.startswith("/v1/hris/performance/compensation-planning")
        for method in path_item
        if method in HTTP_METHODS
    }
    actual_browser = {
        (method, path)
        for path, path_item in gateway_document.get("paths", {}).items()
        if path.startswith("/api/people/v1/hris/performance/compensation-planning")
        for method in path_item
        if method in HTTP_METHODS
    }
    if actual_owner != owner_operations or actual_browser != browser_operations:
        raise RuntimeError(
            "Compensation-planning OpenAPI operations are not exact: "
            f"ownerMissing={sorted(owner_operations - actual_owner)}, "
            f"ownerUnexpected={sorted(actual_owner - owner_operations)}, "
            f"browserMissing={sorted(browser_operations - actual_browser)}, "
            f"browserUnexpected={sorted(actual_browser - browser_operations)}"
        )

    def schema_ref(
        schema: Any, expected_name: str, prefix: str, label: str
    ) -> None:
        expected = f"#/components/schemas/{prefix}{expected_name}"
        if not isinstance(schema, dict) or schema.get("$ref") != expected:
            raise RuntimeError(
                f"Compensation-planning payload schema drift: {label} "
                f"expected={expected}, actual={schema}"
            )

    def operation_payload(
        document: dict[str, Any],
        method: str,
        path: str,
        operation_id: str,
        request_schema: str | None,
        response_schema: str,
        prefix: str,
        label: str,
    ) -> None:
        operation = document.get("paths", {}).get(path, {}).get(method)
        expected_operation_id = f"{prefix}{operation_id}"
        if (
            not isinstance(operation, dict)
            or operation.get("operationId") != expected_operation_id
        ):
            raise RuntimeError(
                "Compensation-planning operationId drift: "
                f"{label} {method.upper()} {path}"
            )
        if any(
            isinstance(parameter, dict) and parameter.get("name") == "query"
            for parameter in operation.get("parameters", [])
        ):
            raise RuntimeError(
                "Compensation-planning capture-only query parameter leaked: "
                f"{label} {method.upper()} {path}"
            )
        request_body = operation.get("requestBody")
        if request_schema is None:
            if request_body is not None:
                raise RuntimeError(
                    "Compensation-planning bodyless operation published a request body: "
                    f"{label} {method.upper()} {path}"
                )
        else:
            if not isinstance(request_body, dict) or request_body.get("required") is not True:
                raise RuntimeError(
                    "Compensation-planning command request body is not required: "
                    f"{label} {method.upper()} {path}"
                )
            schema_ref(
                request_body.get("content", {}).get("application/json", {}).get("schema"),
                request_schema,
                prefix,
                f"{label} {method.upper()} {path} request",
            )
        response_wrapper = (
            operation.get("responses", {}).get("200", {})
            .get("content", {}).get("application/json", {}).get("schema")
        )
        if not isinstance(response_wrapper, dict):
            raise RuntimeError(
                "Compensation-planning JSON response is missing: "
                f"{label} {method.upper()} {path}"
            )
        wrapper_ref = response_wrapper.get("$ref")
        component_prefix = "#/components/schemas/"
        if not isinstance(wrapper_ref, str) or not wrapper_ref.startswith(component_prefix):
            raise RuntimeError(
                "Compensation-planning response wrapper is not a component: "
                f"{label} {method.upper()} {path}"
            )
        wrapper_name = wrapper_ref.removeprefix(component_prefix)
        schemas = document.get("components", {}).get("schemas", {})
        wrapper = schemas.get(wrapper_name)
        if not isinstance(wrapper, dict):
            raise RuntimeError(
                f"Compensation-planning response wrapper is unresolved: {wrapper_name}"
            )
        schema_ref(
            wrapper.get("properties", {}).get("data"),
            response_schema,
            prefix,
            f"{label} {method.upper()} {path} response",
        )

    for route_key, contract in COMPENSATION_PLANNING_OPERATION_CONTRACTS.items():
        method, owner_path, operation_id, request_schema, response_schema = contract
        operation_payload(
            people_document, method, owner_path, operation_id,
            request_schema, response_schema, "", "People owner",
        )
        operation_payload(
            gateway_document, method, prefixed("/api/people")(owner_path),
            operation_id, request_schema, response_schema, "people_", "Gateway",
        )

    exact_schema_shapes = {
        "ModernCompplanHrmSourceSnapshotRefV1": (
            {"snapshotPublicId", "ownerRevision", "projectionSchemaId", "asOf",
             "sha256", "purposeCode"},
            {"snapshotPublicId", "ownerRevision", "projectionSchemaId", "asOf",
             "sha256", "purposeCode"}, set()),
        "ModernCompplanCycleCreateRequestV2": (
            {"cycleCode", "guidelines", "population", "effectiveFrom", "effectiveTo",
             "populationSourceRef"},
            {"cycleCode", "guidelines", "population", "effectiveFrom", "effectiveTo",
             "populationSourceRef"}, {"effectiveTo"}),
        "ModernCompplanCycleReviseRequestV4": (
            {"guidelines", "population", "populationSourceRef", "effectiveFrom",
             "effectiveTo"},
            {"guidelines", "population", "populationSourceRef", "effectiveFrom",
             "effectiveTo"}, {"effectiveTo"}),
        "ModernCompplanBudgetAllocateRequestV2": (
            {"organizationPublicId", "amount", "currency", "ledgerEntryType",
             "reversesEntryPublicId"},
            {"organizationPublicId", "amount", "currency", "ledgerEntryType"},
            {"reversesEntryPublicId"}),
        "ModernCompplanProposalUpsertRequestV4": (
            {"content"}, {"content"}, set()),
        "ModernCompplanProposalSubmitRequestV1": (
            {"submissionDigest", "effectiveDate"}, {"submissionDigest"},
            {"effectiveDate"}),
        "ModernCompplanPlanApproveRequestV2": (
            {"decision", "reasonCode"}, {"decision", "reasonCode"}, set()),
        "ModernCompplanSnapshotPublishRequestV2": (
            {"approvalReceiptId"}, {"approvalReceiptId"}, set()),
        "PerformanceCommandReceiptV1": (
            {"receiptId", "status", "aggregateId", "aggregateVersion",
             "correlationId", "occurredAt", "payloadDigest"},
            {"receiptId", "status", "correlationId", "occurredAt", "payloadDigest"},
            set()),
        "ModernCompplanBudgetEntryProjectionV1": (
            {"entryId", "entrySequence", "organizationPublicId", "currency", "amount",
             "entryType", "sourceEntryPublicId", "createdAt"},
            {"entryId", "entrySequence", "organizationPublicId", "currency", "amount",
             "entryType", "sourceEntryPublicId", "createdAt"},
            {"sourceEntryPublicId"}),
        "ModernCompplanBudgetAggregateV1": (
            {"organizationPublicId", "currency", "allocatedAmount", "committedAmount",
             "remainingAmount"},
            {"organizationPublicId", "currency", "allocatedAmount", "committedAmount",
             "remainingAmount"}, set()),
        "ModernCompplanProposalProjectionV4": (
            {"proposalId", "aggregateVersion", "cycleId", "workerId", "assignmentId",
             "componentCode", "currency", "effectiveFrom", "effectiveTo",
             "currentRevision", "status", "contentDigest", "amountMasked", "amount",
             "content"},
            {"proposalId", "aggregateVersion", "cycleId", "workerId", "assignmentId",
             "componentCode", "currency", "effectiveFrom", "effectiveTo",
             "currentRevision", "status", "contentDigest", "amountMasked"},
            {"effectiveTo"}),
        "ModernCompplanCycleProjectionV5": (
            {"cycleId", "aggregateVersion", "cycleCode", "effectiveFrom", "effectiveTo",
             "status", "guidelines", "population", "budgetMasked", "budgetEntries",
             "budgetAggregates"},
            {"cycleId", "aggregateVersion", "cycleCode", "effectiveFrom", "effectiveTo",
             "status", "budgetMasked"}, {"effectiveTo"}),
        "ModernCompplanCyclesQueryResponseV5": (
            {"items", "nextCursor", "hasMore", "asOf", "scopeRevision",
             "fieldPolicyRevision"},
            {"items", "nextCursor", "hasMore", "asOf", "scopeRevision",
             "fieldPolicyRevision"}, {"nextCursor"}),
        "ModernCompplanCycleQueryResponseV5": (
            {"item", "referenceEffectiveAt", "freshnessWatermark",
             "fieldPolicyRevision"},
            {"item", "referenceEffectiveAt", "freshnessWatermark",
             "fieldPolicyRevision"}, set()),
        "ModernCompplanProposalsQueryResponseV4": (
            {"items", "nextCursor", "hasMore", "referenceEffectiveAt",
             "freshnessWatermark", "fieldPolicyRevision"},
            {"items", "nextCursor", "hasMore", "referenceEffectiveAt",
             "freshnessWatermark", "fieldPolicyRevision"}, {"nextCursor"}),
        "ModernCompplanProposalQueryResponseV4": (
            {"item", "referenceEffectiveAt", "freshnessWatermark",
             "fieldPolicyRevision"},
            {"item", "referenceEffectiveAt", "freshnessWatermark",
             "fieldPolicyRevision"}, set()),
        "ModernCompplanTypedChildResultV3": (
            {"entityType", "publicId", "aggregateVersion", "ordinal", "resultDigest"},
            {"entityType", "publicId", "aggregateVersion", "ordinal", "resultDigest"},
            set()),
        "ModernCompplanRichCommandResultV3": (
            {"commandReceiptId", "status", "aggregatePublicId", "aggregateVersion",
             "correlationId", "completedAt", "resultDigest", "childResults"},
            {"commandReceiptId", "status", "aggregatePublicId", "aggregateVersion",
             "correlationId", "completedAt", "resultDigest", "childResults"}, set()),
        "ModernCompplanSnapshotPublishResponseV2": (
            {"approvalReceiptId", "cycleId", "snapshotId", "snapshotRevision",
             "sourceVersion", "aggregateVersion", "lineCount", "payloadDigest",
             "correlationId", "publishedAt"},
            {"approvalReceiptId", "cycleId", "snapshotId", "snapshotRevision",
             "sourceVersion", "aggregateVersion", "lineCount", "payloadDigest",
             "correlationId", "publishedAt"}, set()),
        "CompensationGuidelineV1": (
            {"componentCode", "currency", "minimumAmount", "maximumAmount",
             "exceptionPolicyCode", "roundingPolicyVersionPublicId"},
            {"componentCode", "currency", "minimumAmount", "maximumAmount",
             "exceptionPolicyCode", "roundingPolicyVersionPublicId"}, set()),
        "CompensationGuidelinesContentV1": (
            {"components", "budgetMode", "effectiveFrom", "effectiveTo"},
            {"components", "budgetMode", "effectiveFrom", "effectiveTo"},
            {"effectiveTo"}),
        "CompensationPopulationLineV1": (
            {"workerPublicId", "assignmentPublicId", "organizationPublicId",
             "gradePublicId", "jobProfilePublicId", "assignmentOwnerRevision",
             "eligibleComponentCodes"},
            {"workerPublicId", "assignmentPublicId", "organizationPublicId",
             "gradePublicId", "jobProfilePublicId", "assignmentOwnerRevision",
             "eligibleComponentCodes"}, {"gradePublicId", "jobProfilePublicId"}),
        "CompensationPopulationContentV1": (
            {"asOf", "hrmOwnerRevision", "lines"},
            {"asOf", "hrmOwnerRevision", "lines"}, set()),
        "CompensationProposalContentV1": (
            {"cyclePublicId", "workerPublicId", "assignmentPublicId", "componentCode",
             "currency", "amount", "effectiveFrom", "effectiveTo",
             "exceptionReasonCode", "supersedesProposalPublicId"},
            {"cyclePublicId", "workerPublicId", "assignmentPublicId", "componentCode",
             "currency", "amount", "effectiveFrom", "effectiveTo",
             "exceptionReasonCode", "supersedesProposalPublicId"},
            {"effectiveTo", "exceptionReasonCode", "supersedesProposalPublicId"}),
    }
    payload_edges = {
        ("ModernCompplanCycleCreateRequestV2", ("properties", "guidelines")):
            "CompensationGuidelinesContentV1",
        ("ModernCompplanCycleCreateRequestV2", ("properties", "population")):
            "CompensationPopulationContentV1",
        ("ModernCompplanCycleCreateRequestV2", ("properties", "populationSourceRef")):
            "ModernCompplanHrmSourceSnapshotRefV1",
        ("ModernCompplanCycleReviseRequestV4", ("properties", "guidelines")):
            "CompensationGuidelinesContentV1",
        ("ModernCompplanCycleReviseRequestV4", ("properties", "population")):
            "CompensationPopulationContentV1",
        ("ModernCompplanCycleReviseRequestV4", ("properties", "populationSourceRef")):
            "ModernCompplanHrmSourceSnapshotRefV1",
        ("ModernCompplanProposalUpsertRequestV4", ("properties", "content")):
            "CompensationProposalContentV1",
        ("ModernCompplanProposalProjectionV4", ("properties", "content")):
            "CompensationProposalContentV1",
        ("ModernCompplanCycleProjectionV5", ("properties", "guidelines")):
            "CompensationGuidelinesContentV1",
        ("ModernCompplanCycleProjectionV5", ("properties", "population")):
            "CompensationPopulationContentV1",
        ("ModernCompplanCycleProjectionV5", ("properties", "budgetEntries", "items")):
            "ModernCompplanBudgetEntryProjectionV1",
        ("ModernCompplanCycleProjectionV5", ("properties", "budgetAggregates", "items")):
            "ModernCompplanBudgetAggregateV1",
        ("ModernCompplanCyclesQueryResponseV5", ("properties", "items", "items")):
            "ModernCompplanCycleProjectionV5",
        ("ModernCompplanCycleQueryResponseV5", ("properties", "item")):
            "ModernCompplanCycleProjectionV5",
        ("ModernCompplanProposalsQueryResponseV4", ("properties", "items", "items")):
            "ModernCompplanProposalProjectionV4",
        ("ModernCompplanProposalQueryResponseV4", ("properties", "item")):
            "ModernCompplanProposalProjectionV4",
        ("ModernCompplanRichCommandResultV3", ("properties", "childResults", "items")):
            "ModernCompplanTypedChildResultV3",
        ("CompensationGuidelinesContentV1", ("properties", "components", "items")):
            "CompensationGuidelineV1",
        ("CompensationPopulationContentV1", ("properties", "lines", "items")):
            "CompensationPopulationLineV1",
    }
    for label, document, prefix in (
        ("People owner", people_document, ""),
        ("Gateway", gateway_document, "people_"),
    ):
        schemas = document.get("components", {}).get("schemas", {})
        for name, (expected_properties, expected_required, expected_nullable) in (
                exact_schema_shapes.items()):
            published_name = f"{prefix}{name}"
            schema = schemas.get(published_name)
            if not isinstance(schema, dict) or schema.get("additionalProperties") is not False:
                raise RuntimeError(
                    "Compensation-planning closed schema drift: "
                    f"{label} {published_name}"
                )
            properties = schema.get("properties", {})
            if not isinstance(properties, dict) or set(properties) != expected_properties:
                raise RuntimeError(
                    "Compensation-planning property set drift: "
                    f"{label} {published_name}"
                )
            if (name == "ModernCompplanCycleProjectionV5"
                    and "budgetAggregates" in schema.get("required", [])):
                raise RuntimeError(
                    "Compensation-planning rolling compatibility drift: "
                    f"{label} budgetAggregates must remain optional during staged rollout"
                )
            if set(schema.get("required", [])) != expected_required:
                raise RuntimeError(
                    "Compensation-planning required field drift: "
                    f"{label} {published_name}"
                )
            actual_nullable = {
                property_name
                for property_name, property_schema in properties.items()
                if isinstance(property_schema, dict)
                and (
                    (isinstance(property_schema.get("type"), list)
                     and "null" in property_schema["type"])
                    or any(
                        isinstance(option, dict) and option.get("type") == "null"
                        for keyword in ("oneOf", "anyOf")
                        for option in property_schema.get(keyword, [])
                    )
                )
            }
            if actual_nullable != expected_nullable:
                raise RuntimeError(
                    "Compensation-planning nullable field drift: "
                    f"{label} {published_name}"
                )
        for (source_name, path), target_name in payload_edges.items():
            value: Any = schemas[f"{prefix}{source_name}"]
            for segment in path:
                value = value.get(segment) if isinstance(value, dict) else None
            schema_ref(
                value, target_name, prefix,
                f"{label} {source_name}.{'.'.join(path)}",
            )
        cycle_schema = schemas[f"{prefix}ModernCompplanCycleProjectionV5"]
        if "budgetAggregates" in cycle_schema.get("required", []):
            raise RuntimeError(
                "Compensation-planning rolling compatibility drift: "
                f"{label} budgetAggregates must remain optional during staged rollout"
            )
        for path in document.get("paths", {}):
            if "approved-compensation-plan-snapshots" in path:
                raise RuntimeError(
                    "Compensation-planning workload-only refetch leaked to public OpenAPI: "
                    f"{label} {path}"
                )


def validate_hrm_employee_change_contract(
    people_document: dict[str, Any], gateway_document: dict[str, Any]
) -> None:
    """Pin HRM-010 source, owner, browser, and workload-only callback contracts."""
    registry = load_hrm_employee_change_authorization_registry()
    routes = {
        route.get("routeContractKey"): route
        for route in registry["routes"]
        if isinstance(route.get("routeContractKey"), str)
        and route["routeContractKey"].startswith(
            "route.hcm.operations.employee-change-"
        )
    }
    if set(routes) != HRM_EMPLOYEE_CHANGE_ROUTE_KEYS:
        missing = sorted(HRM_EMPLOYEE_CHANGE_ROUTE_KEYS - set(routes))
        unexpected = sorted(set(routes) - HRM_EMPLOYEE_CHANGE_ROUTE_KEYS)
        raise RuntimeError(
            "HRM employee-change authorization routes are not exact: "
            f"missing={missing}, unexpected={unexpected}"
        )

    expected_public: list[
        tuple[str, str, str, str, str, dict[str, Any]]
    ] = []
    for route_key, route in sorted(routes.items()):
        if (
            route.get("lifecycleState") != "ACTIVE"
            or route.get("subject", {}).get("type") != "PRODUCT"
        ):
            raise RuntimeError(
                f"HRM employee-change route is not active PRODUCT: {route_key}"
            )
        gateway_bindings = route.get("gatewayApiBindings")
        owner_bindings = route.get("servicePepBindings")
        if (
            not isinstance(gateway_bindings, list)
            or len(gateway_bindings) != 1
            or not isinstance(owner_bindings, list)
            or len(owner_bindings) != 1
        ):
            raise RuntimeError(
                "HRM employee-change route must have one browser and owner binding: "
                f"{route_key}"
            )
        gateway_binding = gateway_bindings[0]
        owner_binding = owner_bindings[0]
        expected_method, expected_owner_path, operation_id, response_status = (
            HRM_EMPLOYEE_CHANGE_OPERATION_CONTRACTS[route_key]
        )
        method = str(gateway_binding.get("method", "")).lower()
        browser_path = gateway_binding.get("path")
        owner_path = owner_binding.get("path")
        if (
            method != expected_method
            or str(owner_binding.get("method", "")).lower() != method
            or gateway_binding.get("bindingKey") != owner_binding.get("bindingKey")
            or owner_binding.get("serviceKey") != "people"
            or owner_path != expected_owner_path
            or not isinstance(browser_path, str)
            or prefixed("/api/people")(owner_path) != browser_path
        ):
            raise RuntimeError(
                f"HRM employee-change browser/owner binding drift: {route_key}"
            )
        expected_public.append((
            method,
            owner_path,
            browser_path,
            operation_id,
            response_status,
            route,
        ))

    people_source = load_design_time_overlays().get("people", {})
    people_overlay = people_source.get("paths", {})
    source_schemas = people_source.get("components", {}).get("schemas", {})
    owner_schemas = people_document.get("components", {}).get("schemas", {})
    gateway_schemas = gateway_document.get("components", {}).get("schemas", {})
    gateway_public_schema_names = (
        set(HRM_CANONICAL_SCHEMA_SHA256) - {"ApprovalDecisionCallbackV1"}
    )
    for schema_name, expected_hash in HRM_CANONICAL_SCHEMA_SHA256.items():
        for contract_name, schemas, published_name, normalize in (
            ("design-time", source_schemas, schema_name, False),
            ("People owner", owner_schemas, schema_name, False),
            (
                "Gateway",
                gateway_schemas,
                f"people_{schema_name}",
                True,
            ),
        ):
            if contract_name == "Gateway" and schema_name not in gateway_public_schema_names:
                continue
            schema = schemas.get(published_name) if isinstance(schemas, dict) else None
            if not isinstance(schema, dict):
                raise RuntimeError(
                    "HRM employee-change canonical schema is missing: "
                    f"{contract_name} {published_name}"
                )
            canonical_schema = (
                normalize_gateway_people_schema(schema) if normalize else schema
            )
            if canonical_contract_hash(canonical_schema) != expected_hash:
                raise RuntimeError(
                    "HRM employee-change canonical schema drift: "
                    f"{contract_name} {published_name}"
                )
    internal_method, internal_path, internal_operation_id, internal_status = (
        HRM_EMPLOYEE_CHANGE_INTERNAL_OPERATION
    )
    expected_source_operations = {
        (method, owner_path)
        for method, owner_path, _, _, _, _ in expected_public
    } | {(internal_method, internal_path)}

    def is_employee_change_path(path: str) -> bool:
        return (
            path == "/v1/hris/workbench/operations/employee/change/requests"
            or "employee-change-requests" in path
        )

    source_operations = {
        (method, path)
        for path, path_item in people_overlay.items()
        if is_employee_change_path(path)
        for method in path_item
        if method in HTTP_METHODS
    }
    owner_operations = {
        (method, path)
        for path, path_item in people_document.get("paths", {}).items()
        if is_employee_change_path(path)
        for method in path_item
        if method in HTTP_METHODS
    }
    expected_gateway_operations = {
        (method, browser_path)
        for method, _, browser_path, _, _, _ in expected_public
    }
    gateway_operations = {
        (method, path)
        for path, path_item in gateway_document.get("paths", {}).items()
        if (
            path == "/api/people/v1/hris/workbench/operations/employee/change/requests"
            or "employee-change-requests" in path
        )
        for method in path_item
        if method in HTTP_METHODS
    }
    for contract_name, actual, expected in (
        ("design-time", source_operations, expected_source_operations),
        ("People owner", owner_operations, expected_source_operations),
        ("Gateway", gateway_operations, expected_gateway_operations),
    ):
        if actual != expected:
            raise RuntimeError(
                f"HRM employee-change {contract_name} operations are not exact: "
                f"missing={sorted(expected - actual)}, "
                f"unexpected={sorted(actual - expected)}"
            )

    if any(path.startswith("/api/hris/v1") for _, path in gateway_operations):
        raise RuntimeError("HRM employee-change /api/hris/v1 alias is forbidden")

    def parameters_by_identity(
        operation: dict[str, Any],
    ) -> dict[tuple[str, str], dict[str, Any]]:
        return {
            (
                str(parameter.get("in", "")).lower(),
                str(parameter.get("name", "")).lower(),
            ): parameter
            for parameter in operation.get("parameters", [])
            if isinstance(parameter, dict)
        }

    def expected_pep_binding(
        route_key: str, route: dict[str, Any]
    ) -> dict[str, Any]:
        subject = route["subject"]
        owner_binding = route["servicePepBindings"][0]
        return {
            "authentication": "HUMAN_SESSION",
            "bindingKey": owner_binding["bindingKey"],
            "ownerServiceKey": owner_binding["serviceKey"],
            "productKey": subject["productKey"],
            "registryVersion": HRM_EMPLOYEE_CHANGE_AUTHORIZATION_VERSION,
            "routeContractKey": route_key,
            "subjectType": subject["type"],
            "surfaceKey": subject["surfaceKey"],
        }

    def validate_auth_semantics(
        contract_name: str,
        operation: dict[str, Any],
        expected_pep: dict[str, Any] | None = None,
    ) -> None:
        security = operation.get("security")
        if security is not None and (
            not isinstance(security, list)
            or not security
            or any(not isinstance(requirement, dict) or not requirement
                   for requirement in security)
        ):
            raise RuntimeError(
                "HRM employee-change empty/anonymous security semantics: "
                f"{contract_name}"
            )
        if expected_pep is not None and (
            operation.get("x-dwp-product-authorization-v38") != expected_pep
        ):
            raise RuntimeError(
                "HRM employee-change v38 PEP authorization linkage drift: "
                f"{contract_name}"
            )

    def validate_response(
        contract_name: str,
        response: Any,
        schema_name: str,
        *,
        gateway: bool,
        problem: bool = False,
    ) -> None:
        if not isinstance(response, dict):
            raise RuntimeError(
                f"HRM employee-change response is invalid: {contract_name}"
            )
        media_type = "application/problem+json" if problem else "application/json"
        prefix = "people_" if gateway else ""
        expected_content = {
            media_type: {
                "schema": {
                    "$ref": f"#/components/schemas/{prefix}{schema_name}"
                }
            }
        }
        if (
            response.get("content") != expected_content
            or response.get("headers") != HRM_RESPONSE_HEADER_CONTRACTS
        ):
            raise RuntimeError(
                "HRM employee-change response schema/header drift: "
                f"{contract_name}"
            )

    for (
        method,
        owner_path,
        browser_path,
        operation_id,
        response_status,
        route,
    ) in expected_public:
        source_operation = people_overlay.get(owner_path, {}).get(method)
        owner_operation = people_document.get("paths", {}).get(
            owner_path, {}
        ).get(method)
        browser_operation = gateway_document.get("paths", {}).get(
            browser_path, {}
        ).get(method)
        if not all(
            isinstance(operation, dict)
            for operation in (source_operation, owner_operation, browser_operation)
        ):
            raise RuntimeError(
                "HRM employee-change operation is missing from source, owner, or Gateway: "
                f"{method.upper()} {owner_path}"
            )
        assert isinstance(source_operation, dict)
        assert isinstance(owner_operation, dict)
        assert isinstance(browser_operation, dict)
        if (
            source_operation.get("x-dwp-overlay-mode") != "ADD"
            or source_operation.get("x-dwp-runtime-default") != "OFF"
            or source_operation.get("x-dwp-reviewed-design-time-contract") != "HRM-010"
            or owner_operation.get("x-dwp-runtime-default") != "OFF"
            or owner_operation.get("x-dwp-reviewed-design-time-contract") != "HRM-010"
            or browser_operation.get("x-dwp-runtime-default") != "OFF"
            or browser_operation.get("x-dwp-reviewed-design-time-contract") != "HRM-010"
        ):
            raise RuntimeError(
                "HRM employee-change operation must remain default-off: "
                f"{method.upper()} {owner_path}"
            )
        expected_pep = expected_pep_binding(route["routeContractKey"], route)
        expected_statuses = (
            {response_status} | HRM_WORKBENCH_ERROR_STATUSES
            if method == "get"
            else {response_status} | HRM_COMMAND_ERROR_STATUSES
        )
        for contract_name, operation, expected_operation_id, is_gateway in (
            ("source", source_operation, operation_id, False),
            ("owner", owner_operation, operation_id, False),
            ("Gateway", browser_operation, f"people_{operation_id}", True),
        ):
            if operation.get("operationId") != expected_operation_id:
                raise RuntimeError(
                    "HRM employee-change operationId drift: "
                    f"{contract_name} {method.upper()} {owner_path}"
                )
            responses = operation.get("responses", {})
            if set(responses) != expected_statuses:
                raise RuntimeError(
                    "HRM employee-change response drift: "
                    f"{contract_name} {method.upper()} {owner_path}"
                )
            validate_auth_semantics(
                f"{contract_name} {method.upper()} {owner_path}",
                operation,
                expected_pep,
            )
            validate_response(
                f"{contract_name} {method.upper()} {owner_path} {response_status}",
                responses[response_status],
                (
                    "Iaq009EmployeeChangeRequestProjectionV1"
                    if method == "get"
                    else "CommandReceiptV1"
                ),
                gateway=is_gateway,
            )
            for error_status in expected_statuses - {response_status}:
                validate_response(
                    f"{contract_name} {method.upper()} {owner_path} {error_status}",
                    responses[error_status],
                    (
                        "Iaq009ProblemDetailsV1"
                        if method == "get"
                        else "HrisProblemDetailsV1"
                    ),
                    gateway=is_gateway,
                    problem=True,
                )

        browser_parameters = parameters_by_identity(browser_operation)
        if browser_parameters.get(("query", "contextscopekey")) != SCOPE_SELECTION_PARAMETER:
            raise RuntimeError(
                "HRM employee-change Gateway operation is not governed: "
                f"{method.upper()} {browser_path}"
            )
        if "{requestPublicId}" in owner_path and any(
            parameters.get(("path", "requestpublicid"))
            != HRM_REQUEST_PUBLIC_ID_PARAMETER
            for parameters in (
                parameters_by_identity(source_operation),
                parameters_by_identity(owner_operation),
                browser_parameters,
            )
        ):
            raise RuntimeError(
                "HRM employee-change requestPublicId path parameter drift: "
                f"{method.upper()} {owner_path}"
            )
        if method == "get":
            query_schema = source_schemas["Iaq009EmployeeChangeRequestQueryV1"]
            query_properties = query_schema["properties"]
            required_queries = set(query_schema["required"])
            expected_query_names = set(query_properties)
            for contract_name, operation, is_gateway in (
                ("source", source_operation, False),
                ("owner", owner_operation, False),
                ("Gateway", browser_operation, True),
            ):
                query_parameters = [
                    parameter
                    for parameter in operation.get("parameters", [])
                    if isinstance(parameter, dict)
                    and str(parameter.get("in", "")).lower() == "query"
                ]
                query_parameters_by_name = {
                    parameter.get("name"): parameter
                    for parameter in query_parameters
                }
                expected_names = expected_query_names | (
                    {"contextScopeKey"} if is_gateway else set()
                )
                if (
                    len(query_parameters) != len(expected_names)
                    or set(query_parameters_by_name) != expected_names
                ):
                    raise RuntimeError(
                        "HRM employee-change IAQ-009 query parameter drift: "
                        f"{contract_name}"
                    )
                for query_name, schema in query_properties.items():
                    expected_schema = copy.deepcopy(schema)
                    expected_parameter = {
                        "in": "query",
                        "name": query_name,
                        "required": query_name in required_queries,
                        "schema": expected_schema,
                    }
                    if query_name == "filter":
                        expected_schema = {
                            "$ref": "#/components/schemas/"
                            + ("people_" if is_gateway else "")
                            + "Iaq009EmployeeChangeRequestFilterV1"
                        }
                        expected_parameter["schema"] = expected_schema
                        expected_parameter["style"] = "deepObject"
                        expected_parameter["explode"] = True
                    if query_parameters_by_name[query_name] != expected_parameter:
                        raise RuntimeError(
                            "HRM employee-change IAQ-009 query schema drift: "
                            f"{contract_name} {query_name}"
                        )
                if is_gateway and (
                    query_parameters_by_name.get("contextScopeKey")
                    != SCOPE_SELECTION_PARAMETER
                ):
                    raise RuntimeError(
                        "HRM employee-change Gateway operation is not governed: "
                        f"{method.upper()} {browser_path}"
                    )
                expected_query_ref = {
                    "$ref": "#/components/schemas/"
                    + ("people_" if is_gateway else "")
                    + "Iaq009EmployeeChangeRequestQueryV1"
                }
                if operation.get("x-dwp-query-schema") != expected_query_ref:
                    raise RuntimeError(
                        "HRM employee-change IAQ-009 named query drift: "
                        f"{contract_name}"
                    )
        if method != "post":
            continue

        source_parameters = parameters_by_identity(source_operation)
        owner_parameters = parameters_by_identity(owner_operation)
        idempotency = ("header", "idempotency-key")
        if_match = ("header", "if-match")
        correlation = ("header", "x-correlation-id")
        purpose = ("header", "x-purpose-code")
        expected_idempotency_schema = {
            "type": "string", "minLength": 16, "maxLength": 128,
        }
        expected_if_match_schema = {
            "type": "string", "pattern": '^(W/)?"[A-Za-z0-9._:-]+"$',
        }
        if (
            any(
                parameters.get(idempotency, {}).get("required") is not True
                or parameters.get(idempotency, {}).get("schema")
                != expected_idempotency_schema
                or parameters.get(if_match, {}).get("required") is not True
                or parameters.get(if_match, {}).get("schema")
                != expected_if_match_schema
                or parameters.get(correlation, {}).get("required") is not True
                or parameters.get(correlation, {}).get("schema")
                != {"type": "string", "format": "uuid"}
                for parameters in (
                    source_parameters,
                    owner_parameters,
                    browser_parameters,
                )
            )
            or any(
                parameters.get(purpose) != {
                    "in": "header",
                    "name": "X-Purpose-Code",
                    "required": False,
                    "schema": {
                        "maxLength": 64,
                        "minLength": 3,
                        "type": "string",
                    },
                }
                for parameters in (
                    source_parameters,
                    owner_parameters,
                    browser_parameters,
                )
            )
            or any(
                operation.get("requestBody", {}).get("required") is not True
                for operation in (
                    source_operation,
                    owner_operation,
                    browser_operation,
                )
            )
        ):
            raise RuntimeError(
                "HRM employee-change mutation is not idempotent: "
                f"{method.upper()} {owner_path}"
            )

        request_schema = (
            "EmployeeChangeRequestCreateV1"
            if owner_path == "/v1/hris/employee-change-requests"
            else "EmptyCommandV1"
        )
        for contract_name, operation, is_gateway in (
            ("source", source_operation, False),
            ("owner", owner_operation, False),
            ("Gateway", browser_operation, True),
        ):
            expected_request_body = {
                "content": {
                    "application/json": {
                        "schema": {
                            "$ref": "#/components/schemas/"
                            + ("people_" if is_gateway else "")
                            + request_schema
                        }
                    }
                },
                "required": True,
            }
            if operation.get("requestBody") != expected_request_body:
                raise RuntimeError(
                    "HRM employee-change request schema drift: "
                    f"{contract_name} {method.upper()} {owner_path}"
                )

        step_up_bindings = route.get("stepUpCommandBindings", [])
        if not isinstance(step_up_bindings, list):
            raise RuntimeError(
                f"HRM employee-change step-up binding is invalid: "
                f"{route['routeContractKey']}"
            )
        proof_parameters = {
            ("header", "x-dwp-step-up-challenge"): {
                "in": "header",
                "name": "X-DWP-Step-Up-Challenge",
                "required": True,
                "schema": {"minLength": 1, "type": "string"},
            },
            ("header", "x-dwp-expected-decision-revision"): {
                "in": "header",
                "name": "X-DWP-Expected-Decision-Revision",
                "required": True,
                "schema": {
                    "maxLength": 200,
                    "minLength": 1,
                    "type": "string",
                },
            },
            ("header", "x-dwp-expected-object-version"): {
                "in": "header",
                "name": "X-DWP-Expected-Object-Version",
                "required": True,
                "schema": {
                    "format": "int64",
                    "minimum": 0,
                    "type": "integer",
                },
            },
        }
        proof_headers = set(proof_parameters)
        if step_up_bindings:
            if (
                len(step_up_bindings) != 1
                or source_operation.get("x-dwp-command-proof-required") is not True
                or owner_operation.get("x-dwp-command-proof-required") is not True
                or browser_operation.get("x-dwp-command-proof-required") is not True
                or not proof_headers <= set(source_parameters)
                or not proof_headers <= set(owner_parameters)
                or not proof_headers <= set(browser_parameters)
                or any(
                    parameters.get(identity) != expected
                    for identity, expected in proof_parameters.items()
                    for parameters in (
                        source_parameters,
                        owner_parameters,
                        browser_parameters,
                    )
                )
            ):
                raise RuntimeError(
                    "HRM employee-change step-up proof drift: "
                    f"{route['routeContractKey']}"
                )
        else:
            unexpected_proof = proof_headers - {
                ("header", "x-dwp-expected-decision-revision")
            }
            if (
                source_operation.get("x-dwp-command-proof-required") is True
                or owner_operation.get("x-dwp-command-proof-required") is True
                or browser_operation.get("x-dwp-command-proof-required") is True
                or unexpected_proof & set(source_parameters)
                or unexpected_proof & set(owner_parameters)
                or unexpected_proof & set(browser_parameters)
                or browser_parameters.get(
                    ("header", "x-dwp-expected-decision-revision")
                ) != EXPECTED_DECISION_REVISION_PARAMETER
            ):
                raise RuntimeError(
                    "HRM employee-change non-step-up proof drift: "
                    f"{route['routeContractKey']}"
                )

    internal_source = people_overlay.get(internal_path, {}).get(internal_method)
    internal_owner = people_document.get("paths", {}).get(
        internal_path, {}
    ).get(internal_method)
    if not isinstance(internal_source, dict) or not isinstance(internal_owner, dict):
        raise RuntimeError(
            "HRM employee-change internal callback is missing from source or owner"
        )
    required_callback_parameters = {
        ("path", "requestpublicid"): HRM_REQUEST_PUBLIC_ID_PARAMETER,
        ("header", "x-dwp-service-identity"): {
            "in": "header",
            "name": "X-DWP-Service-Identity",
            "required": True,
            "schema": {"enum": ["dwp-approval-server"], "type": "string"},
        },
        ("header", "x-dwp-workload-proof"): {
            "in": "header",
            "name": "X-DWP-Workload-Proof",
            "required": True,
            "schema": {"minLength": 1, "type": "string"},
        },
    }
    for contract_name, operation, is_gateway in (
        ("source", internal_source, False),
        ("owner", internal_owner, False),
    ):
        parameters = parameters_by_identity(operation)
        if (
            operation.get("operationId") != internal_operation_id
            or set(operation.get("responses", {})) != {internal_status, "403", "409"}
            or operation.get("x-dwp-runtime-default") != "OFF"
            or operation.get("x-dwp-workload-proof-required") is not True
            or operation.get("x-dwp-workload-proof-v2") != HRM_WORKLOAD_PROOF_V2
            or operation.get("requestBody", {}).get("required") is not True
            or any(
                parameters.get(identity) != expected
                for identity, expected in required_callback_parameters.items()
            )
        ):
            raise RuntimeError(
                f"HRM employee-change internal callback security drift: {contract_name}"
            )
        validate_auth_semantics(f"{contract_name} internal callback", operation)
        expected_callback_body = {
            "content": {
                "application/json": {
                    "schema": {
                        "$ref": "#/components/schemas/ApprovalDecisionCallbackV1"
                    }
                }
            },
            "required": True,
        }
        if operation.get("requestBody") != expected_callback_body:
            raise RuntimeError(
                f"HRM employee-change callback request schema drift: {contract_name}"
            )
        responses = operation["responses"]
        validate_response(
            f"{contract_name} internal callback {internal_status}",
            responses[internal_status],
            "CommandReceiptV1",
            gateway=is_gateway,
        )
        for error_status in ("403", "409"):
            validate_response(
                f"{contract_name} internal callback {error_status}",
                responses[error_status],
                "HrisProblemDetailsV1",
                gateway=is_gateway,
                problem=True,
            )
    if any(
        internal_path in path or "approval-decisions" in path
        for path in gateway_document.get("paths", {})
    ):
        raise RuntimeError(
            "HRM employee-change internal callback was exposed through Gateway"
        )


def prune_unreachable_components(document: dict[str, Any]) -> dict[str, Any]:
    """Keep only components reachable from public operations and their transitive refs."""
    components = document.get("components", {})
    pending: deque[tuple[str, str]] = deque()
    reachable: set[tuple[str, str]] = set()

    def discover(value: Any) -> None:
        if isinstance(value, list):
            for item in value:
                discover(item)
            return
        if not isinstance(value, dict):
            if isinstance(value, str) and value.startswith("#/components/"):
                parts = value.split("/")
                if len(parts) == 4:
                    pending.append((parts[2], parts[3]))
            return
        for key, child in value.items():
            if key == "security" and isinstance(child, list):
                for requirement in child:
                    if isinstance(requirement, dict):
                        for scheme in requirement:
                            pending.append(("securitySchemes", scheme))
            discover(child)

    discover(document.get("paths", {}))
    discover(document.get("security", []))
    while pending:
        category, name = pending.popleft()
        key = (category, name)
        if key in reachable:
            continue
        value = components.get(category, {}).get(name)
        if value is None:
            raise RuntimeError(
                f"Gateway public contract references a missing component: {category}/{name}"
            )
        reachable.add(key)
        discover(value)

    document["components"] = {
        category: {
            name: value
            for name, value in values.items()
            if (category, name) in reachable
        }
        for category, values in components.items()
        if isinstance(values, dict)
        and any(candidate_category == category for candidate_category, _ in reachable)
    }
    return document


def rendered(document: dict[str, Any]) -> str:
    return json.dumps(document, ensure_ascii=True, indent=2, sort_keys=True) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="replace checked-in contract snapshots")
    mode.add_argument("--check", action="store_true", help="fail when runtime contracts drift")
    parser.add_argument(
        "--service",
        action="append",
        choices=[service.name for service in SERVICES],
        help=(
            "fetch only this service from runtime and compose the Gateway contract with "
            "approved snapshots for the others; repeat to select multiple services"
        ),
    )
    parser.add_argument(
        "--approved-snapshots",
        action="store_true",
        help="compose without live service fetches, using every approved snapshot",
    )
    args = parser.parse_args()

    selected = (
        set()
        if args.approved_snapshots
        else set(args.service or (
            service.name for service in SERVICES if service.default_live
        ))
    )
    documents = {
        service.name: fetch_contract(service) if service.name in selected else load_snapshot(service)
        for service in SERVICES
    }
    documents[AGENT_PUBLIC_SERVICE.name] = load_agent_public_snapshot()
    gateway_owned = load_gateway_owned_snapshot()
    outputs = {
        f"{service.name}.json": documents[service.name] for service in SERVICES
    }
    outputs["gateway-owned.json"] = gateway_owned
    outputs["gateway-public.json"] = gateway_contract(documents, gateway_owned)
    validate_unique_operation_ids(outputs["gateway-public.json"], "gateway-public")
    validate_sys_reference_configuration_contract(
        outputs["platform.json"], outputs["gateway-public.json"]
    )
    validate_hrm_employee_change_contract(
        outputs["people.json"], outputs["gateway-public.json"]
    )
    validate_compensation_planning_contract(
        outputs["people.json"], outputs["gateway-public.json"]
    )
    validate_platform_device_identity(outputs["platform.json"])
    validate_platform_device_identity(outputs["gateway-public.json"], gateway=True)
    validate_approval_signature_operations(outputs["gateway-public.json"], gateway=True)
    CONTRACT_ROOT.mkdir(parents=True, exist_ok=True)

    changed: list[str] = []
    for filename, document in outputs.items():
        target = CONTRACT_ROOT / filename
        content = rendered(document)
        if args.write:
            target.write_text(content, encoding="utf-8")
        elif not target.exists() or target.read_text(encoding="utf-8") != content:
            changed.append(filename)

    if changed:
        print("OpenAPI contract drift detected: " + ", ".join(changed), file=sys.stderr)
        print("Review the API change and run scripts/export-openapi-contracts.py --write.", file=sys.stderr)
        return 1
    action = "wrote" if args.write else "verified"
    print(
        f"OpenAPI contracts {action}: {len(documents)} services, "
        f"{len(outputs['gateway-public.json']['paths'])} public Gateway paths."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
