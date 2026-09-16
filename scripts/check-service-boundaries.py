#!/usr/bin/env python3

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
POLICY_FILE = ROOT / "docs/architecture/service-interface-contracts.json"

SERVICE_PACKAGES = {
    "dwp-auth-server": "com.dwp.services.auth",
    "dwp-platform-server": "com.dwp.services.platform",
    "dwp-people-server": "com.dwp.services.people",
    "dwp-provider-server": "com.dwp.services.provider",
    "dwp-approval-server": "com.dwp.services.approval",
    "dwp-space-server": "com.dwp.services.space",
    "dwp-messaging-server": "com.dwp.services.messaging",
    "dwp-notification-server": "com.dwp.services.notification",
    "dwp-meeting-server": "com.dwp.services.meeting",
    "dwp-gateway": "com.dwp.gateway",
}

SHARED_MODULES = {
    "dwp-core",
    "dwp-audit",
    "dwp-observability",
    "dwp-platform-contracts",
}

OWNED_DATABASE_PREFIXES = {
    "dwp-auth-server": "AUTH",
    "dwp-platform-server": "PLATFORM",
    "dwp-people-server": "PEOPLE",
    "dwp-provider-server": "PROVIDER",
}

VALID_INTERFACE_TYPES = {
    "gateway-verifier",
    "internal-http",
    "external-connector",
}

IMPORT_RE = re.compile(r"^\s*import\s+([^;]+);", re.MULTILINE)
PROJECT_DEP_RE = re.compile(r"^\s*(?:api|implementation|compileOnly|runtimeOnly)\s+project\(['\"]:([^'\"]+)['\"]\)", re.MULTILINE)
HTTP_CLIENT_IMPORT_RE = re.compile(
    r"import\s+(?:org\.springframework\.(?:web\.client\.RestClient|web\.reactive\.function\.client\.WebClient)|java\.net\.http\.HttpClient);"
)
APP_YML_CROSS_DB_RE = re.compile(
    r"\$\{(?P<db>AUTH|PLATFORM|PEOPLE|PROVIDER)_DB_NAME:"
)
JAVA_NON_CODE_RE = re.compile(
    r'""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*.*?\*/|//[^\r\n]*',
    re.DOTALL,
)
REPOSITORY_TYPE_RE = re.compile(r"\b[A-Z][A-Za-z0-9_$]*Repository\b")
CONTROLLER_TYPE_RE = re.compile(r"\b[A-Z][A-Za-z0-9_$]*Controller\b")
SIGNED_WORKLOAD_FIELDS = {
    "profile", "protocolSource", "signerSource", "method", "path", "header", "issuer", "audience", "ttlSeconds",
}
PURPOSE_TOKEN_HEADERS = {
    "X-DWP-Provisioning-Token", "X-DWP-Identity-Sync-Token", "X-DWP-Approval-Recovery-Token",
    "X-DWP-Approval-Form-User-Token",
    "X-DWP-Approval-Workflow-Runtime-Token",
    "X-DWP-Approval-Policy-Impact-Token",
    "X-DWP-Approval-Information-Replay-Token",
    "X-DWP-Approval-Workflow-Planning-Token",
    "X-DWP-Approval-Signature-Source-Token",
    "X-DWP-Notification-Approval-System-Sla-Token",
    "X-DWP-Approval-System-Sla-Token",
    "X-DWP-Approval-Retention-Execution-Token",
}
# Generic internal clients may use owner-specific purpose credentials that are
# not part of the Approval proof families above. Keep those credentials out of
# the Approval forbidden-set algebra while still requiring an explicit token in
# each registered internal HTTP contract.
INTERNAL_PURPOSE_TOKEN_HEADERS = PURPOSE_TOKEN_HEADERS | {
    "X-DWP-Work-Source-Token",
}
WORKFLOW_RUNTIME_TOKEN_HEADER = "X-DWP-Approval-Workflow-Runtime-Token"
WORKFLOW_RUNTIME_CLIENT = (
    "dwp-approval-server/src/main/java/com/dwp/services/approval/workflowauthority/"
    "WorkflowRuntimeAuthorityClient.java"
)
WORKFLOW_RUNTIME_REQUIRED_MARKERS = {
    "/internal/approval-workflow/runtime-authority", WORKFLOW_RUNTIME_TOKEN_HEADER,
    "X-DWP-Service-Identity", "dwp-approval-server", "WorkflowRuntimeProofIssuer.Exchange",
    "WorkflowRuntimeAttestationVerifier", "WorkflowRuntimeResponseBody",
    "OutboundHttpHeaders.propagateObservability", "HttpClient.Redirect.NEVER",
    "connectTimeout(Duration.ofSeconds(3))", ".timeout(Duration.ofSeconds(5))",
    "pending.get(5,TimeUnit.SECONDS)", "pending.cancel(true)", "body.cancel()",
}
WORKFLOW_RUNTIME_FORBIDDEN_MARKERS = {
    "/api/", "X-DWP-Service-Token", "X-DWP-Product-Surface-Token",
    "X-DWP-Identity-Sync-Token", "X-DWP-Approval-Form-User-Token",
    "X-DWP-Approval-Recovery-Token", "Authorization", "Cookie", "@Retry",
    "X-DWP-Approval-Policy-Impact-Token",
    "X-DWP-Approval-Information-Replay-Token",
    "X-DWP-Approval-Workflow-Planning-Token",
    "X-DWP-Approval-Signature-Source-Token",
    "X-DWP-Notification-Approval-System-Sla-Token",
    "X-DWP-Approval-System-Sla-Token",
}
POLICY_IMPACT_TOKEN_HEADER = "X-DWP-Approval-Policy-Impact-Token"
POLICY_IMPACT_CLIENT = (
    "dwp-approval-server/src/main/java/com/dwp/services/approval/policyimpactsource/"
    "AuthApprovalPolicyImpactAuthorityClient.java"
)
POLICY_IMPACT_REQUIRED_MARKERS = {
    "/internal/auth/v1/approval-policy-impact-authority/evaluate", POLICY_IMPACT_TOKEN_HEADER,
    "X-DWP-Service-Identity", "dwp-approval-server", "PolicyImpactSourceProofIssuer.Exchange",
    "PolicyImpactSourceAttestationVerifier", "PolicyImpactSourceResponseBody",
    "OutboundHttpHeaders.propagateObservability", "HttpClient.Redirect.NEVER",
    "connectTimeout(Duration.ofSeconds(3))", ".timeout(Duration.ofSeconds(5))",
    "pending.get(5, TimeUnit.SECONDS)", "pending.cancel(true)", "body.cancel()",
}
POLICY_IMPACT_FORBIDDEN_MARKERS = PURPOSE_TOKEN_HEADERS - {POLICY_IMPACT_TOKEN_HEADER} | {
    "/api/", "X-DWP-Service-Token", "X-DWP-Product-Surface-Token", "Authorization", "Cookie", "@Retry",
}
INFORMATION_REPLAY_TOKEN_HEADER = "X-DWP-Approval-Information-Replay-Token"
INFORMATION_REPLAY_CLIENT = (
    "dwp-approval-server/src/main/java/com/dwp/services/approval/informationreplay/"
    "InformationReplayAuthorityClient.java"
)
INFORMATION_REPLAY_REQUIRED_MARKERS = {
    "/internal/approval-workflow/information-command-replay", INFORMATION_REPLAY_TOKEN_HEADER,
    "X-DWP-Service-Identity", "dwp-approval-server", "InformationReplayProofIssuer.Exchange",
    "InformationReplayAttestationVerifier", "InformationReplayResponseBody",
    "OutboundHttpHeaders.propagateObservability", "HttpClient.Redirect.NEVER",
    "connectTimeout(Duration.ofSeconds(3))", ".timeout(Duration.ofSeconds(5))",
    "pending.get(5,TimeUnit.SECONDS)", "pending.cancel(true)", "body.cancel()",
}
INFORMATION_REPLAY_FORBIDDEN_MARKERS = PURPOSE_TOKEN_HEADERS - {INFORMATION_REPLAY_TOKEN_HEADER} | {
    "/api/", "X-DWP-Service-Token", "X-DWP-Product-Surface-Token", "Authorization", "Cookie", "@Retry",
}
WORKFLOW_PLANNING_TOKEN_HEADER = "X-DWP-Approval-Workflow-Planning-Token"
WORKFLOW_PLANNING_CLIENT = (
    "dwp-approval-server/src/main/java/com/dwp/services/approval/workflowplanning/"
    "WorkflowPlanningAuthorityClient.java"
)
WORKFLOW_PLANNING_REQUIRED_MARKERS = {
    "/internal/approval-workflow/admin-planning", WORKFLOW_PLANNING_TOKEN_HEADER,
    "X-DWP-Service-Identity", "dwp-approval-server", "WorkflowPlanningProofIssuer.Exchange",
    "WorkflowPlanningAttestationVerifier", "WorkflowPlanningResponseBody",
    "OutboundHttpHeaders.propagateObservability", "HttpClient.Redirect.NEVER",
    "connectTimeout(Duration.ofSeconds(3))", ".timeout(Duration.ofSeconds(5))",
    "pending.get(5,TimeUnit.SECONDS)", "pending.cancel(true)", "body.cancel()",
}
WORKFLOW_PLANNING_FORBIDDEN_MARKERS = PURPOSE_TOKEN_HEADERS - {WORKFLOW_PLANNING_TOKEN_HEADER} | {
    "/api/", "X-DWP-Service-Token", "X-DWP-Product-Surface-Token", "Authorization", "Cookie", "@Retry",
}
SIGNED_WORKLOAD_FORBIDDEN = PURPOSE_TOKEN_HEADERS | {
    "/api/", "X-DWP-Service-Token", "X-DWP-Service-Identity", "X-DWP-Product-Surface-Token",
    "Authorization", "@Retry",
}
OWNER_TOKEN_FIELDS = {
    "profile", "method", "path", "header", "identityHeader", "identity",
    "securitySource", "endpointSource",
}
CURRENT_PROOF_CLIENTS = {
    "approval-signature": {
        "sourceService": "dwp-approval-server", "targetService": "dwp-auth-server",
        "path": "dwp-approval-server/src/main/java/com/dwp/services/approval/signatures/ApprovalSignatureAuthorityClient.java",
        "endpoint": "/internal/auth/v1/approval-signature-authority/evaluate",
        "header": "X-DWP-Approval-Signature-Source-Token",
        "purpose": "DWP_APPROVAL_SIGNATURE_TRANSPORT_V1",
        "protocol": "ApprovalSignatureSourceExchange", "response": "ApprovalSignatureResponseBody",
        "verification": "ApprovalSignatureAuthority", "responseLimit": 32768,
    },
    "notification-approval-sla": {
        "sourceService": "dwp-notification-server", "targetService": "dwp-approval-server",
        "path": "dwp-notification-server/src/main/java/com/dwp/services/notification/integration/ApprovalSlaRecipientAuthorityClient.java",
        "endpoint": "/internal/approval/v1/quorum-sla/recipient-authority/evaluate",
        "header": "X-DWP-Notification-Approval-System-Sla-Token",
        "purpose": "NOTIFICATION_APPROVAL_SYSTEM_SLA_TRANSPORT_V1",
        "protocol": "ApprovalSlaTransportProof", "response": "ApprovalSlaAuthorityResponseBody",
        "verification": "ApprovalSlaRecipientAuthority", "responseLimit": 524288,
    },
    "approval-system-sla": {
        "sourceService": "dwp-approval-server", "targetService": "dwp-auth-server",
        "path": "dwp-approval-server/src/main/java/com/dwp/services/approval/systemslaauthority/AuthApprovalSystemSlaAuthorityClient.java",
        "endpoint": "/internal/auth/v1/approval-system-sla-authority/evaluate",
        "header": "X-DWP-Approval-System-Sla-Token",
        "purpose": "APPROVAL_SYSTEM_SLA_TRANSPORT_V1",
        "protocol": "SystemSlaSourceProtocol", "issuer": "SystemSlaSourceProofIssuer",
        "response": "SystemSlaSourceResponseBody", "verification": "SystemSlaSourceAttestationVerifier",
        "responseLimit": 524288,
    },
}
OWNER_TOKEN_PROFILES = {
    "notification-producer": {
        "targetService": "dwp-notification-server", "retryMode": "outbox-owned",
        "failureMode": "fail-contained",
        "method": "POST", "path": "/internal/v1/intents/direct",
        "header": "X-DWP-Service-Token", "identityHeader": "X-DWP-Source-Service",
        "identity": "dwp-meeting-server",
        "securitySource": "dwp-notification-server/src/main/java/com/dwp/services/notification/security/NotificationSecurityFilter.java",
        "endpointSource": "dwp-notification-server/src/main/java/com/dwp/services/notification/api/NotificationProducerController.java",
    },
    "meeting-followup-authority": {
        "targetService": "dwp-auth-server", "retryMode": "none", "failureMode": "fail-closed",
        "method": "POST", "path": "/internal/auth/v1/meeting-followup-authority/evaluate",
        "header": "X-DWP-Meeting-Followup-Authority-Token",
        "identityHeader": "X-DWP-Service-Identity", "identity": "dwp-meeting-server",
        "securitySource": "dwp-auth-server/src/main/java/com/dwp/services/auth/config/ProductSurfaceInternalSecurityConfig.java",
        "endpointSource": "dwp-auth-server/src/main/java/com/dwp/services/auth/controller/MeetingFollowupAuthorityController.java",
    },
}


def java_without_comments(source: str) -> str:
    """Keep Java literals intact, so URL slashes are not mistaken for comments."""
    return JAVA_NON_CODE_RE.sub(
        lambda match: " " if match.group().startswith(("//", "/*")) else match.group(), source
    )


def executable_literal_matches(pattern: str, source: str) -> list[re.Match[str]]:
    """Match literals only when their enclosing expression starts in executable Java."""
    masked = JAVA_NON_CODE_RE.sub(
        lambda match: re.sub(r"[^\r\n]", " ", match.group()), source
    )
    return [
        match for match in re.finditer(pattern, source)
        if not masked[match.start()].isspace() and masked[match.start()] == source[match.start()]
    ]


def signed_workload_manifest_violations(entry: dict[str, Any]) -> list[str]:
    violations: list[str] = []
    prefix = f"httpClients:{entry.get('id')} signedWorkload"
    workload = entry.get("signedWorkload")
    if not isinstance(workload, dict) or set(workload) != SIGNED_WORKLOAD_FIELDS:
        return [f"{prefix} must define exactly {sorted(SIGNED_WORKLOAD_FIELDS)}"]
    if entry.get("interfaceType") != "internal-http":
        violations.append(f"{prefix} is only valid for internal-http")
    if workload.get("profile") != "dwp1-hmac-sha256":
        violations.append(f"{prefix} requires the dwp1-hmac-sha256 profile")
    if entry.get("retryMode") != "none" or entry.get("failureMode") != "fail-closed":
        violations.append(f"{prefix} requires no retries and fail-closed handling")
    targets = entry.get("targetServices")
    if not isinstance(targets, list) or len(targets) != 1 or entry.get("sourceService") in targets:
        violations.append(f"{prefix} requires one distinct owner service")
    if workload.get("method") != "POST":
        violations.append(f"{prefix} method must be POST")
    path = workload.get("path")
    if not isinstance(path, str) or not re.fullmatch(r"/internal/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+", path):
        violations.append(f"{prefix} path must be an exact canonical /internal/ path")
    header = workload.get("header")
    if not isinstance(header, str) or not re.fullmatch(r"X-DWP-[A-Za-z0-9-]+-Assertion", header):
        violations.append(f"{prefix} requires a dedicated assertion header")
    for key in ("issuer", "audience"):
        value = workload.get(key)
        if not isinstance(value, str) or not re.fullmatch(r"dwp-[a-z0-9-]+", value) or value == "dwp-gateway":
            violations.append(f"{prefix}.{key} requires a dedicated non-Gateway workload identity")
    if workload.get("issuer") == workload.get("audience"):
        violations.append(f"{prefix} issuer and audience must differ")
    ttl = workload.get("ttlSeconds")
    if type(ttl) is not int or not 1 <= ttl <= 30:
        violations.append(f"{prefix} ttlSeconds must be an integer from 1 to 30")
    sources: list[Path] = []
    for key, value in (("client", entry.get("path")), ("protocolSource", workload.get("protocolSource")),
                       ("signerSource", workload.get("signerSource"))):
        relative = validate_relative_path(violations, "httpClients", str(entry.get("id")), value)
        if relative is None:
            continue
        owner_relative = Path(str(entry.get("sourceService"))) / "src/main/java"
        owner_root = ROOT / owner_relative
        source = ROOT / relative
        if (not relative.is_relative_to(owner_relative) or not source.is_file() or source.suffix != ".java"
                or not source.resolve().is_relative_to(owner_root.resolve())):
            violations.append(f"{prefix}.{key} must be a Java file owned by sourceService")
        sources.append(relative)
    if len(set(sources)) != 3:
        violations.append(f"{prefix} client, protocol and signer must be distinct source files")
    if not SIGNED_WORKLOAD_FORBIDDEN <= set(string_entries(entry.get("forbiddenMarkers"))):
        violations.append(f"{prefix} must forbid Gateway/token reuse and automatic retries")
    return violations


def signed_workload_source_violations(entry: dict[str, Any], source: str) -> list[str]:
    """Check declared source wiring, not cryptographic or owner-authorization correctness."""
    violations: list[str] = []
    workload = entry["signedWorkload"]
    prefix = f"{entry['path']} signedWorkload"
    protocol_path = Path(workload["protocolSource"])
    signer_path = Path(workload["signerSource"])
    protocol = java_without_comments((ROOT / protocol_path).read_text(encoding="utf-8"))
    signer = java_without_comments((ROOT / signer_path).read_text(encoding="utf-8"))
    client_code = JAVA_NON_CODE_RE.sub(" ", source)
    signer_code = JAVA_NON_CODE_RE.sub(" ", signer)
    for name, value in {
        "PATH": workload["path"], "ASSERTION_HEADER": workload["header"],
        "ISSUER": workload["issuer"], "AUDIENCE": workload["audience"],
    }.items():
        constants = [match.group(1) for match in executable_literal_matches(
            rf'\bstatic\s+final\s+String\s+{name}\s*=\s*"([^"\\]*)"\s*;', protocol
        )]
        if constants != [value]:
            violations.append(f"{prefix} protocol {name} must equal the declared literal exactly once")
    protocol_name = ".".join(protocol_path.parts[protocol_path.parts.index("java") + 1:]).removesuffix(".java")
    for path, value in ((Path(entry["path"]), source), (protocol_path, protocol), (signer_path, signer)):
        code = JAVA_NON_CODE_RE.sub(" ", value)
        package = ".".join(path.parts[path.parts.index("java") + 1:-1])
        if not re.search(rf"\bpackage\s+{re.escape(package)}\s*;", code) or not re.search(rf"\bclass\s+{re.escape(path.stem)}\b", code):
            violations.append(f"{prefix} source package/class does not match {path}")
    signer_package = ".".join(signer_path.parts[signer_path.parts.index("java") + 1:-1])
    if not re.search(rf"\b(?:package\s+{re.escape(signer_package)}|import\s+{re.escape(signer_package)}\.{re.escape(signer_path.stem)})\s*;", client_code):
        violations.append(f"{prefix} client must resolve the declared signer class")
    for label, code in (("client", client_code), ("signer", signer_code)):
        if not re.search(rf"\bimport\s+static\s+{re.escape(protocol_name)}\.\*\s*;", code):
            violations.append(f"{prefix} {label} must import the declared protocol constants")
    for label, pattern in {
        "canonical endpoint": r"\.resolve\(\s*PATH\s*\)",
        "request endpoint": r"\bHttpRequest\.newBuilder\(\s*endpoint\s*\)",
        "no-redirect policy": r"\.followRedirects\(\s*HttpClient\.Redirect\.NEVER\s*\)",
        "POST body": r"\.POST\(\s*HttpRequest\.BodyPublishers\.ofByteArray\(\s*body\s*\)\s*\)",
        "signed request": r"\.header\(\s*ASSERTION_HEADER\s*,\s*signer\.sign\(\s*request\s*,\s*body\s*\)\s*\)",
        "signer construction": rf"\bnew\s+{re.escape(signer_path.stem)}\s*\(",
        "trace propagation": r"\bOutboundHttpHeaders\.propagateObservability\s*\(",
    }.items():
        if not re.search(pattern, client_code):
            violations.append(f"{prefix} client is missing executable {label} wiring")
    for label, pattern in {
        "request/source/action claims": (
            r'\bnew\s+Claims\(\s*1\s*,\s*keyId\s*,\s*ISSUER\s*,\s*AUDIENCE\s*,\s*"POST"\s*,\s*PATH\s*,'
            r'\s*request\.tenantId\(\)\s*,\s*request\.actorUserId\(\)\s*,\s*request\.source\(\)\.meetingId\(\)\s*,'
            r'\s*request\.source\(\)\.reportId\(\)\s*,\s*request\.source\(\)\.candidateId\(\)\s*,\s*request\.action\(\)\s*,'
            rf'\s*issuedAt\s*,\s*issuedAt\s*\+\s*{workload["ttlSeconds"]}\s*,\s*nonce\.get\(\)\s*,'
            r'\s*HexFormat\.of\(\)\.formatHex\(\s*MessageDigest\.getInstance\(\s*"SHA-256"\s*\)\.digest\(\s*exactBody\s*\)\s*\)\s*\)'
        ),
        "HMAC algorithm": r'\bMac\.getInstance\(\s*"HmacSHA256"\s*\)',
        "dedicated signing key": r'\bmac\.init\(\s*new\s+SecretKeySpec\(\s*secret\s*,\s*"HmacSHA256"\s*\)\s*\)',
        "raw-body digest": r'\bMessageDigest\.getInstance\(\s*"SHA-256"\s*\)\.digest\(\s*exactBody\s*\)',
        "versioned envelope": r'\bString\s+input\s*=\s*"dwp1\."\s*\+\s*BASE64\.encodeToString\(',
    }.items():
        if not executable_literal_matches(pattern, signer):
            violations.append(f"{prefix} signer is missing {label}")
    for label, pattern in {
        "bounded lifetime": rf"\bissuedAt\s*\+\s*{workload['ttlSeconds']}\b",
        "fresh nonce": r"\bnonce\.get\(\)",
        "HMAC execution": r"\bmac\.doFinal\(\s*input\.getBytes\(\s*StandardCharsets\.US_ASCII\s*\)\s*\)",
        "minimum key length": r"\bsecret\.length\s*<\s*32\b",
    }.items():
        if not re.search(pattern, signer_code):
            violations.append(f"{prefix} signer is missing executable {label}")
    for forbidden in SIGNED_WORKLOAD_FORBIDDEN:
        if any(forbidden in value for value in (java_without_comments(source), protocol, signer)):
            violations.append(f"{prefix} source contains forbidden marker {forbidden!r}")
    return violations


def owner_token_forbidden(profile: dict[str, str]) -> set[str]:
    return (PURPOSE_TOKEN_HEADERS | {
        "/api/", "Authorization", "Cookie", "@Retry", "X-DWP-Product-Surface-Token",
        "X-DWP-Service-Token", "X-DWP-Service-Identity", "X-DWP-Source-Service",
        "X-DWP-Meeting-Followup-Authority-Token",
    }) - {profile["header"], profile["identityHeader"]}


def workflow_runtime_manifest_violations(entry: dict[str, Any]) -> list[str]:
    required = set(string_entries(entry.get("requiredMarkers")))
    if WORKFLOW_RUNTIME_TOKEN_HEADER not in required and entry.get("path") != WORKFLOW_RUNTIME_CLIENT:
        return []
    prefix = f"httpClients:{entry.get('id')} workflow runtime proof"
    if (entry.get("interfaceType") != "internal-http" or entry.get("sourceService") != "dwp-approval-server"
            or entry.get("targetServices") != ["dwp-auth-server"] or entry.get("path") != WORKFLOW_RUNTIME_CLIENT
            or entry.get("retryMode") != "none" or entry.get("failureMode") != "fail-closed"
            or "signedWorkload" in entry or "ownerToken" in entry):
        return [f"{prefix} requires the exact Approval-to-Auth client and dedicated proof contract"]
    violations = []
    if not WORKFLOW_RUNTIME_REQUIRED_MARKERS <= required:
        violations.append(f"{prefix} requires bounded transport, trace propagation and signed exchange verification")
    if not WORKFLOW_RUNTIME_FORBIDDEN_MARKERS <= set(string_entries(entry.get("forbiddenMarkers"))):
        violations.append(f"{prefix} must forbid borrowed credentials, browser state and automatic retries")
    return violations


def workflow_runtime_source_violations(entry: dict[str, Any], source: str) -> list[str]:
    if entry.get("path") != WORKFLOW_RUNTIME_CLIENT:
        return []
    source = java_without_comments(source)
    prefix = f"{entry['path']} workflow runtime proof"
    violations = []
    for name, expected in (("ENDPOINT_PATH", "/internal/approval-workflow/runtime-authority"),
                           ("TOKEN_HEADER", WORKFLOW_RUNTIME_TOKEN_HEADER)):
        if not exact_java_string(source, name, expected):
            violations.append(f"{prefix} requires the exact executable {name} literal")
    for label, pattern in {
        "protocol endpoint parity": r"ENDPOINT_PATH\.equals\(PATH\)",
        "protocol token parity": r"TOKEN_HEADER\.equals\(HEADER\)",
        "sealed transport token": r"\.header\(\s*TOKEN_HEADER\s*,\s*exchange\.transportToken\(\)\s*\)",
        "exact private request bytes": r"\.POST\(\s*HttpRequest\.BodyPublishers\.ofByteArray\(exchange\.body\(\)\)\s*\)",
        "bounded signed response": r"verifier\.verify\(response\.body\(\),\s*exchange\)",
        "body size bound": r"exchange\.body\(\)\.length\s*>\s*BODY_MAX",
        "header size bound": r"exchange\.transportToken\(\)\.length\(\)\s*>\s*TRANSPORT_MAX",
    }.items():
        if not re.search(pattern, source):
            violations.append(f"{prefix} is missing executable {label}")
    return violations


def policy_impact_manifest_violations(entry: dict[str, Any]) -> list[str]:
    required = set(string_entries(entry.get("requiredMarkers")))
    if POLICY_IMPACT_TOKEN_HEADER not in required and entry.get("path") != POLICY_IMPACT_CLIENT:
        return []
    prefix = f"httpClients:{entry.get('id')} policy impact proof"
    if (entry.get("interfaceType") != "internal-http" or entry.get("sourceService") != "dwp-approval-server"
            or entry.get("targetServices") != ["dwp-auth-server"] or entry.get("path") != POLICY_IMPACT_CLIENT
            or entry.get("retryMode") != "none" or entry.get("failureMode") != "fail-closed"
            or "signedWorkload" in entry or "ownerToken" in entry):
        return [f"{prefix} requires the exact Approval-to-Auth client and dedicated proof contract"]
    violations = []
    if not POLICY_IMPACT_REQUIRED_MARKERS <= required:
        violations.append(f"{prefix} requires bounded transport, tracing and signed verification")
    if not POLICY_IMPACT_FORBIDDEN_MARKERS <= set(string_entries(entry.get("forbiddenMarkers"))):
        violations.append(f"{prefix} must forbid borrowed credentials, browser state and automatic retries")
    return violations


def policy_impact_source_violations(entry: dict[str, Any], source: str) -> list[str]:
    if entry.get("path") != POLICY_IMPACT_CLIENT:
        return []
    source = java_without_comments(source)
    prefix = f"{entry['path']} policy impact proof"
    violations = []
    for name, expected in (("ENDPOINT_PATH", "/internal/auth/v1/approval-policy-impact-authority/evaluate"),
                           ("TOKEN_HEADER", POLICY_IMPACT_TOKEN_HEADER)):
        if not exact_java_string(source, name, expected):
            violations.append(f"{prefix} requires the exact executable {name} literal")
    for label, pattern in {
        "protocol endpoint parity": r"ENDPOINT_PATH\.equals\(PATH\)",
        "protocol token parity": r"TOKEN_HEADER\.equals\(HEADER\)",
        "sealed transport token": r"\.header\(\s*TOKEN_HEADER\s*,\s*exchange\.transport\(\)\s*\)",
        "exact private request bytes": r"\.POST\(\s*HttpRequest\.BodyPublishers\.ofByteArray\(exchange\.body\(\)\)\s*\)",
        "verified signed response": r"verifier\.verify\(response\.body\(\),\s*exchange\)",
        "body size bound": r"exchange\.body\(\)\.length\s*>\s*BODY_LIMIT",
        "header size bound": r"exchange\.transport\(\)\.length\(\)\s*>\s*TRANSPORT_LIMIT",
    }.items():
        if not re.search(pattern, source):
            violations.append(f"{prefix} is missing executable {label}")
    return violations


def information_replay_manifest_violations(entry: dict[str, Any]) -> list[str]:
    required = set(string_entries(entry.get("requiredMarkers")))
    if INFORMATION_REPLAY_TOKEN_HEADER not in required and entry.get("path") != INFORMATION_REPLAY_CLIENT:
        return []
    prefix = f"httpClients:{entry.get('id')} information replay proof"
    if (entry.get("interfaceType") != "internal-http" or entry.get("sourceService") != "dwp-approval-server"
            or entry.get("targetServices") != ["dwp-auth-server"] or entry.get("path") != INFORMATION_REPLAY_CLIENT
            or entry.get("retryMode") != "none" or entry.get("failureMode") != "fail-closed"
            or "signedWorkload" in entry or "ownerToken" in entry):
        return [f"{prefix} requires the exact Approval-to-Auth client and dedicated proof contract"]
    violations = []
    if not INFORMATION_REPLAY_REQUIRED_MARKERS <= required:
        violations.append(f"{prefix} requires bounded transport, tracing and signed verification")
    if not INFORMATION_REPLAY_FORBIDDEN_MARKERS <= set(string_entries(entry.get("forbiddenMarkers"))):
        violations.append(f"{prefix} must forbid borrowed credentials, browser state and automatic retries")
    return violations


def information_replay_source_violations(entry: dict[str, Any], source: str) -> list[str]:
    if entry.get("path") != INFORMATION_REPLAY_CLIENT:
        return []
    source = java_without_comments(source)
    prefix = f"{entry['path']} information replay proof"
    violations = []
    for name, expected in (("ENDPOINT_PATH", "/internal/approval-workflow/information-command-replay"),
                           ("TOKEN_HEADER", INFORMATION_REPLAY_TOKEN_HEADER)):
        if not exact_java_string(source, name, expected):
            violations.append(f"{prefix} requires the exact executable {name} literal")
    for label, pattern in {
        "protocol endpoint parity": r"ENDPOINT_PATH\.equals\(PATH\)",
        "protocol token parity": r"TOKEN_HEADER\.equals\(HEADER\)",
        "sealed transport token": r"\.header\(\s*TOKEN_HEADER\s*,\s*exchange\.token\(\)\s*\)",
        "exact private request bytes": r"\.POST\(\s*HttpRequest\.BodyPublishers\.ofByteArray\(exchange\.body\(\)\)\s*\)",
        "verified signed response": r"verifier\.verify\(response\.body\(\),\s*exchange\)",
        "body size bound": r"exchange\.body\(\)\.length\s*>\s*BODY_MAX",
        "header size bound": r"exchange\.token\(\)\.length\(\)\s*>\s*TOKEN_MAX",
    }.items():
        if not re.search(pattern, source):
            violations.append(f"{prefix} is missing executable {label}")
    return violations


def workflow_planning_manifest_violations(entry: dict[str, Any]) -> list[str]:
    required = set(string_entries(entry.get("requiredMarkers")))
    if WORKFLOW_PLANNING_TOKEN_HEADER not in required and entry.get("path") != WORKFLOW_PLANNING_CLIENT:
        return []
    prefix = f"httpClients:{entry.get('id')} workflow planning proof"
    if (entry.get("interfaceType") != "internal-http" or entry.get("sourceService") != "dwp-approval-server"
            or entry.get("targetServices") != ["dwp-auth-server"] or entry.get("path") != WORKFLOW_PLANNING_CLIENT
            or entry.get("retryMode") != "none" or entry.get("failureMode") != "fail-closed"
            or "signedWorkload" in entry or "ownerToken" in entry):
        return [f"{prefix} requires the exact Approval-to-Auth planning client and dedicated proof"]
    violations = []
    if not WORKFLOW_PLANNING_REQUIRED_MARKERS <= required:
        violations.append(f"{prefix} requires bounded transport, tracing and signed verification")
    if not WORKFLOW_PLANNING_FORBIDDEN_MARKERS <= set(string_entries(entry.get("forbiddenMarkers"))):
        violations.append(f"{prefix} must forbid borrowed credentials, browser state and automatic retries")
    return violations


def workflow_planning_source_violations(entry: dict[str, Any], source: str) -> list[str]:
    if entry.get("path") != WORKFLOW_PLANNING_CLIENT:
        return []
    source = java_without_comments(source)
    prefix = f"{entry['path']} workflow planning proof"
    violations = []
    for name, expected in (("ENDPOINT_PATH", "/internal/approval-workflow/admin-planning"),
                           ("TOKEN_HEADER", WORKFLOW_PLANNING_TOKEN_HEADER)):
        if not exact_java_string(source, name, expected):
            violations.append(f"{prefix} requires the exact executable {name} literal")
    for label, pattern in {
        "protocol endpoint parity": r"ENDPOINT_PATH\.equals\(PATH\)",
        "protocol token parity": r"TOKEN_HEADER\.equals\(HEADER\)",
        "sealed transport token": r"\.header\(\s*TOKEN_HEADER\s*,\s*exchange\.token\(\)\s*\)",
        "exact private request bytes": r"\.POST\(\s*HttpRequest\.BodyPublishers\.ofByteArray\(exchange\.body\(\)\)\s*\)",
        "verified signed response": r"verifier\.verify\(response\.body\(\),\s*exchange\)",
        "body size bound": r"exchange\.body\(\)\.length\s*>\s*BODY_MAX",
        "header size bound": r"exchange\.token\(\)\.length\(\)\s*>\s*TOKEN_MAX",
    }.items():
        if not re.search(pattern, source):
            violations.append(f"{prefix} is missing executable {label}")
    return violations


def current_proof_required(profile: dict[str, Any]) -> set[str]:
    return {
        profile["endpoint"], profile["header"], "X-DWP-Service-Identity", profile["sourceService"],
        profile.get("issuer", profile["protocol"]) + ".Exchange", profile["response"], "OutboundHttpHeaders.propagateObservability",
        "HttpClient.Redirect.NEVER", "connectTimeout(Duration.ofSeconds(3))", ".timeout(Duration.ofSeconds(5))",
        "pending.get(5,", "pending.cancel(true)", "body.cancel()",
        "SignedJWT.parse(assertion)" if profile["verification"] == "ApprovalSignatureAuthority" else profile["verification"],
    }


def current_proof_forbidden(profile: dict[str, Any]) -> set[str]:
    return PURPOSE_TOKEN_HEADERS - {profile["header"]} | {
        "/api/", "X-DWP-Service-Token", "X-DWP-Product-Surface-Token", "Authorization", "Cookie", "@Retry",
        "X-DWP-User-ID", "X-DWP-Tenant-ID", "X-DWP-Roles", "X-DWP-Resource-Roles", "X-DWP-Identity-Plane",
    }


def current_proof_manifest_violations(entry: dict[str, Any]) -> list[str]:
    violations = []
    required = set(string_entries(entry.get("requiredMarkers")))
    for name, profile in CURRENT_PROOF_CLIENTS.items():
        if entry.get("path") != profile["path"] and profile["header"] not in required:
            continue
        prefix = f"httpClients:{entry.get('id')} {name} proof"
        if (entry.get("interfaceType") != "internal-http" or entry.get("path") != profile["path"]
                or entry.get("sourceService") != profile["sourceService"]
                or entry.get("targetServices") != [profile["targetService"]]
                or entry.get("retryMode") != "none" or entry.get("failureMode") != "fail-closed"
                or "signedWorkload" in entry or "ownerToken" in entry):
            violations.append(f"{prefix} requires the exact client, owner and dedicated proof without exemptions")
        if not current_proof_required(profile) <= required:
            violations.append(f"{prefix} requires bounded transport, tracing and current signed verification")
        if not current_proof_forbidden(profile) <= set(string_entries(entry.get("forbiddenMarkers"))):
            violations.append(f"{prefix} must forbid borrowed credentials, identity headers and automatic retries")
    return violations


def current_proof_source_violations(entry: dict[str, Any], source: str,
                                    supporting_sources: dict[str, str] | None = None) -> list[str]:
    profile = next((p for p in CURRENT_PROOF_CLIENTS.values() if p["path"] == entry.get("path")), None)
    if profile is None:
        return []
    prefix = f"{entry['path']} current proof"
    source = java_without_comments(source)
    violations = []
    for name, value in (("ENDPOINT_PATH", profile["endpoint"]), ("TOKEN_HEADER", profile["header"])):
        if not exact_java_string(source, name, value):
            violations.append(f"{prefix} requires exact executable {name}")
    protocol = re.escape(profile["protocol"])
    patterns = {
        "HTTP import": r"import\s+java\.net\.http\.HttpClient;",
        "protocol endpoint parity": rf"ENDPOINT_PATH\.equals\(\s*{protocol}\.PATH\s*\)",
        "protocol token parity": rf"TOKEN_HEADER\.equals\(\s*{protocol}\.HEADER\s*\)",
        "endpoint path restriction": r"ENDPOINT_PATH\.equals\(\s*endpoint\.getRawPath\(\)\s*\)",
        "no endpoint user info": r"endpoint\.getUserInfo\(\)\s*!=\s*null",
        "no endpoint query": r"endpoint\.getQuery\(\)\s*!=\s*null",
        "no endpoint fragment": r"endpoint\.getFragment\(\)\s*!=\s*null",
        "HTTPS endpoint": r'\(\s*"https"\.equals\(endpoint\.getScheme\(\)\)',
        "HTTP loopback only": (r'\|\|\s*"http"\.equals\(endpoint\.getScheme\(\)\)\s*&&\s*'
                               r'Set\.of\("localhost",\s*"127\.0\.0\.1",\s*"::1",\s*"\[::1\]"\)\.contains\(endpoint\.getHost\(\)\)'),
        "sealed transport token": r"\.header\(\s*TOKEN_HEADER\s*,\s*exchange\.token\(\)\s*\)",
        "service identity": rf'\.header\(\s*"X-DWP-Service-Identity"\s*,\s*"{profile["sourceService"]}"\s*\)',
        "private POST bytes": r"\.POST\(\s*HttpRequest\.BodyPublishers\.ofByteArray\(\s*exchange\.body\(\)\s*\)\s*\)",
        "connect deadline": r"\.connectTimeout\(\s*Duration\.ofSeconds\(3\)\s*\)",
        "request deadline": r"\.timeout\(\s*Duration\.ofSeconds\(5\)\s*\)",
        "total deadline": r"pending\.get\(\s*5\s*,\s*(?:java\.util\.concurrent\.)?TimeUnit\.SECONDS\s*\)",
        "no redirects": r"\.followRedirects\(\s*HttpClient\.Redirect\.NEVER\s*\)",
        "bounded subscriber": rf'new\s+{re.escape(profile["response"])}\(\)',
        "request and response cancellation": r"finally\s*\{\s*pending\.cancel\(true\);\s*body\.cancel\(\);\s*\}",
        "trace-only propagation": r'Set\.of\("traceparent",\s*"tracestate",\s*"x-correlation-id"\)\.contains\(',
    }
    if profile["verification"] == "SystemSlaSourceAttestationVerifier":
        patterns.update({
            "HTTP import": r"import\s+java\.net\.http\.\*;",
            "no endpoint query": r"endpoint\.getRawQuery\(\)\s*!=\s*null",
            "no endpoint fragment": r"endpoint\.getRawFragment\(\)\s*!=\s*null",
            "sealed transport token": r"\.header\(\s*TOKEN_HEADER\s*,\s*exchange\.transport\(\)\s*\)",
            "private verified exchange": r"return\s+verifier\.verify\(response\.body\(\),\s*exchange\)",
            "request bound": r"exchange\.body\(\)\.length\s*>\s*SystemSlaSourceProtocol\.BODY_LIMIT",
            "token bound": r"exchange\.transport\(\)\.length\(\)\s*>\s*2048",
        })
    elif profile["verification"] == "ApprovalSignatureAuthority":
        patterns.update({"signed response": r"jwt\.verify\(new\s+RSASSAVerifier\(rsa\)\)",
                         "closed response echoes": (r'for\s*\(String\s+field\s*:\s*Set\.of\("nonce",\s*"contextKey",'
                                                     r'\s*"contextScopeKey",\s*"resourceSetKey",\s*"decisionRevision",'
                                                     r'\s*"registrySha256",\s*"sourceSha256",\s*"bodySha256"\)\)'),
                         "exact response bindings": r"java\.util\.Objects\.equals\(b\.get\(field\),\s*claims\.getClaim\(field\)\)",
                         "request bound": r"exchange\.body\(\)\.length\s*>\s*65536",
                         "token bound": r"exchange\.token\(\)\.length\(\)\s*>\s*32768"})
    else:
        patterns["private verified plan/nonce/body/expiry"] = (
            r"return\s+verifier\.verify\(response\.body\(\),\s*plan,\s*exchange\.nonce\(\),"
            r"\s*exchange\.bodySha256\(\),\s*exchange\.expiresAt\(\)\)")
    for label, pattern in patterns.items():
        if not executable_literal_matches(pattern, source):
            violations.append(f"{prefix} is missing executable {label}")
    for marker in current_proof_forbidden(profile):
        if marker in source:
            violations.append(f"{prefix} contains forbidden marker {marker!r}")
    if (len(executable_literal_matches(r"http\.sendAsync\(", source)) != 1
            or executable_literal_matches(r"@(?:[\w]+\.)*Retry\b", source)):
        violations.append(f"{prefix} requires one executable HTTP attempt without retry annotations")
    supports = {}
    for kind in ("protocol", "response", "verification") + (("issuer",) if "issuer" in profile else ()):
        name = profile[kind]
        path = Path(entry["path"]).with_name(name + ".java")
        raw = supporting_sources.get(name) if supporting_sources is not None else (ROOT / path).read_text(encoding="utf-8") if (ROOT / path).is_file() else None
        if raw is None:
            violations.append(f"{prefix} is missing exact {kind} source {path}")
        supports[kind] = java_without_comments(raw or "")
    for name, value in (("PATH", profile["endpoint"]), ("HEADER", profile["header"])):
        if not exact_java_string(supports["protocol"], name, value):
            violations.append(f"{prefix} protocol requires exact executable {name}")
    support_patterns = {
        "protocol": {
            "immutable request storage": r"body\s*=\s*body\.clone\(\)",
            "immutable request accessor": r"byte\[\]\s+body\(\)\s*\{\s*return\s+body\.clone\(\);\s*\}",
            "disjoint transport purpose": rf'\.claim\("purpose",\s*"{profile["purpose"]}"\)',
        },
        "response": {"subscriber cancellation": r"subscription\.cancel\(\)"},
        "verification": {"private verified result": r"private\s+Verified\(",
                         "actual signature": r"\.verify\(new\s+RSASSAVerifier\((?:rsa|key)\)\)"},
    }
    if profile["verification"] == "SystemSlaSourceAttestationVerifier":
        support_patterns["protocol"] = {
            "exact byte limit": r"public\s+static\s+final\s+int\s+BODY_LIMIT\s*=\s*524288\s*;",
        }
        for name, value in (("OWNER_PURPOSE", "APPROVAL_SYSTEM_SLA_SOURCE_V1"),
                            ("TRANSPORT_PURPOSE", profile["purpose"]),
                            ("ATTESTATION_PURPOSE", "APPROVAL_SYSTEM_SLA_ATTESTATION_V1"),
                            ("OWNER_ISSUER", "dwp-approval-system-sla-owner"),
                            ("OWNER_AUDIENCE", "dwp-auth-system-sla-owner"),
                            ("TRANSPORT_ISSUER", "dwp-approval-system-sla-transport"),
                            ("TRANSPORT_AUDIENCE", "dwp-auth-system-sla-transport"),
                            ("ATTESTATION_ISSUER", "dwp-auth-system-sla-attestation"),
                            ("ATTESTATION_AUDIENCE", "dwp-approval-system-sla-attestation")):
            if not exact_java_string(supports["protocol"], name, value):
                violations.append(f"{prefix} protocol requires exact disjoint {name}")
        fields = executable_literal_matches(r"ATTESTATION_FIELDS\s*=\s*Set\.of\(([^;]+)\);", supports["protocol"])
        expected_fields = {"iss", "aud", "sub", "iat", "nbf", "exp", "jti", "purpose", "sourceProofJti",
                           "transportProofJti", "bodySha256", "bindingsSha256", "sourceDigest", "authority", "recipients"}
        values = re.findall(r'"([A-Za-z0-9]+)"', fields[0].group(1)) if len(fields) == 1 else []
        if len(values) != 15 or set(values) != expected_fields:
            violations.append(f"{prefix} protocol requires the exact closed 15 attestation fields")
        support_patterns["issuer"] = {
            "immutable request storage": r"this\.body\s*=\s*body\.clone\(\)",
            "immutable request accessor": r"byte\[\]\s+body\(\)\s*\{\s*return\s+body\.clone\(\);\s*\}",
            "private issued exchange": r"private\s+Exchange\(",
            "disjoint source purpose": r"standard\(OWNER_ISSUER,\s*OWNER_AUDIENCE,\s*OWNER_PURPOSE,",
            "disjoint transport purpose": r"standard\(TRANSPORT_ISSUER,\s*TRANSPORT_AUDIENCE,\s*TRANSPORT_PURPOSE,",
            "owner signing key": r"sign\(owner,\s*keys\.owner\(\),\s*json\)",
            "transport signing key": r"sign\(transport,\s*keys\.transport\(\),\s*json\)",
            "actual signature": r"token\.sign\(new\s+RSASSASigner\(key\.toRSAPrivateKey\(\)\)\)",
            "immutable issued body": r'json\.bytes\(Map\.of\("sourceProof",\s*proof,\s*"bindings",\s*bindings\)\)',
            "original source JTI": r'transport\.put\("sourceProofJti",\s*ownerId\)',
            "exact body digest": r'transport\.put\("bodySha256",\s*sha\(body\)\)',
            "exact binding digest": r'transport\.put\("bindingsSha256",\s*bindingHash\)',
            "exact POST method": r'transport\.put\("method",\s*"POST"\)',
            "fixed signed path": r'transport\.put\("path",\s*PATH\)',
            "fresh source and transport nonce": r"ownerId\s*=\s*UUID\.randomUUID\(\)\.toString\(\),\s*transportId\s*=\s*UUID\.randomUUID\(\)\.toString\(\)",
            "source expiry bound": r"exp\s*<=\s*now\s*\|\|\s*exp\s*>\s*now\s*\+\s*30",
            "body byte bound": r"body\.length\s*>\s*BODY_LIMIT",
            "transport byte bound": r"token\.length\(\)\s*>\s*2048",
            "actual owner issued time": r"new\s+Exchange\(seal,\s*body,\s*token,\s*ownerId,\s*transportId,\s*bindingHash,\s*Instant\.ofEpochSecond\(now\),\s*Instant\.ofEpochSecond\(exp\)\)",
        }
        support_patterns["response"]["preallocation byte bound"] = (
            r"buffer\.remaining\(\)\s*>\s*SystemSlaSourceProtocol\.BODY_LIMIT\s*-\s*bytes\.size\(\)")
        support_patterns["verification"] = {
            "private verified result": r"private\s+Verified\(",
            "actual signature": r'jwt\.verify\(new\s+RSASSAVerifier\(keys\.attestation\(text\(header,\s*"kid",\s*80\)\)\)\)',
            "closed response": r'SystemSlaJson\.keys\(response,\s*Set\.of\("sourceAttestation"\)\)',
            "closed signed claims": r"SystemSlaJson\.keys\(claims,\s*ATTESTATION_FIELDS\)",
            "disjoint attestation purpose": r'ATTESTATION_PURPOSE\.equals\(text\(claims,\s*"purpose",\s*100\)\)',
            "original source nonce": r'exchange\.ownerId\(\)\.equals\(uuid\(claims,\s*"sourceProofJti"\)\.toString\(\)\)',
            "original transport nonce": r'exchange\.transportId\(\)\.equals\(uuid\(claims,\s*"transportProofJti"\)\.toString\(\)\)',
            "original body": r'sha\(exchange\.body\(\)\)\.equals\(hash\(claims,\s*"bodySha256"\)\)',
            "original bindings": r'exchange\.bindingHash\(\)\.equals\(hash\(claims,\s*"bindingsSha256"\)\)',
            "original owner source": r'hash\(exchange\.seal\(\)\.bindings\(\),\s*"sourceDigest"\)\.equals\(hash\(claims,\s*"sourceDigest"\)\)',
            "fresh attestation expiry": r"expiry\s*<=\s*now\s*\|\|\s*expiry\s*<=\s*issued\s*\|\|\s*expiry\s*-\s*issued\s*>\s*30\s*\|\|\s*expiry\s*>\s*exchange\.expiresAt\(\)\.getEpochSecond\(\)",
            "owner issued lower bound": r"evaluated\.isBefore\(exchange\.ownerIssuedAt\(\)\)",
            "evaluation current upper bound": r"evaluated\.isAfter\(clock\.instant\(\)\)",
            "evaluation expiry": r"!evaluated\.isBefore\(expires\)",
            "actual source vector namespace": r'revision\.equals\("asla-"\s*\+\s*vector\)',
            "immutable verified recipients": r"this\.recipients\s*=\s*recipients\.deepCopy\(\)",
            "complete bounded recipients": r"actual\.size\(\)\s*!=\s*expected\.size\(\)\s*\|\|\s*actual\.isEmpty\(\)\s*\|\|\s*actual\.size\(\)\s*>\s*1000",
        }
        request = executable_literal_matches(r"HttpRequest\.newBuilder\(", source)
        for label in ("request bound", "token bound"):
            bounds = executable_literal_matches(patterns[label], source)
            if not bounds or not request or bounds[0].start() >= request[0].start():
                violations.append(f"{prefix} must bound the issued {label} before building HTTP transport")
    elif profile["verification"] == "ApprovalSignatureAuthority":
        support_patterns["response"].update({"exact byte limit": r"static\s+final\s+int\s+RESPONSE_LIMIT\s*=\s*32768\s*;",
                                              "preallocation byte bound": r"count\s*>\s*RESPONSE_LIMIT"})
        support_patterns["verification"].update({"original nonce": r'nonce\.toString\(\)\.equals\(c\.get\("nonce"\)\)',
                                                 "original body": r'binding\.bodySha256\.equals\(c\.get\("bodySha256"\)\)',
                                                 "current expiry": r"expires\.isAfter\(now\)"})
    else:
        support_patterns["response"].update({"exact byte limit": r"static\s+final\s+int\s+MAXIMUM\s*=\s*524288\s*;",
                                              "preallocation byte bound": r"buffer\.remaining\(\)\s*>\s*MAXIMUM\s*-\s*bytes\.size\(\)"})
        support_patterns["verification"].update({"original nonce": r'nonce\.equals\(uuid\(claims,\s*"requestNonce"\)\)',
                                                 "original body": r'requestBodySha256\.equals\(hash\(text\(claims,\s*"requestBodySha256",\s*64\)\)\)',
                                                 "request expiry": r"expires\s*>\s*requestExpiresAt\.getEpochSecond\(\)"})
    for kind, requirements in support_patterns.items():
        for label, pattern in requirements.items():
            if not executable_literal_matches(pattern, supports[kind]):
                violations.append(f"{prefix} {kind} is missing executable {label}")
    guard = executable_literal_matches(support_patterns["response"]["preallocation byte bound"], supports["response"])
    allocation = executable_literal_matches(r"new\s+byte\[", supports["response"])
    if not guard or not allocation or guard[0].start() >= allocation[0].start():
        violations.append(f"{prefix} must bound response chunks before allocating their copy")
    return violations


def owner_token_manifest_violations(entry: dict[str, Any]) -> list[str]:
    """Register only an exact owner-enforced token contract, never a token exemption."""
    prefix = f"httpClients:{entry.get('id')} ownerToken"
    token = entry.get("ownerToken")
    if not isinstance(token, dict) or set(token) != OWNER_TOKEN_FIELDS:
        return [f"{prefix} must define exactly {sorted(OWNER_TOKEN_FIELDS)}"]
    profile = OWNER_TOKEN_PROFILES.get(token.get("profile")) if isinstance(token.get("profile"), str) else None
    if profile is None:
        return [f"{prefix} profile must name a registered owner credential contract"]
    violations: list[str] = []
    if entry.get("interfaceType") != "internal-http" or "signedWorkload" in entry:
        violations.append(f"{prefix} requires internal-http and cannot replace signedWorkload")
    if (entry.get("sourceService") != token["identity"]
            or entry.get("targetServices") != [profile["targetService"]]
            or entry.get("retryMode") != profile["retryMode"]
            or entry.get("failureMode") != profile["failureMode"]):
        violations.append(f"{prefix} source, owner, retry and failure policy must match the owner contract")
    for key in OWNER_TOKEN_FIELDS - {"profile"}:
        if token.get(key) != profile[key]:
            violations.append(f"{prefix}.{key} must equal the exact owner contract")
    for key, service in (("path", entry.get("sourceService")),
                         ("securitySource", profile["targetService"]),
                         ("endpointSource", profile["targetService"])):
        value = entry.get("path") if key == "path" else token[key]
        relative = validate_relative_path(violations, "httpClients", str(entry.get("id")), value)
        if relative is None:
            continue
        owner_relative = Path(str(service)) / "src/main/java"
        source = ROOT / relative
        if (not relative.is_relative_to(owner_relative) or not source.is_file()
                or source.suffix != ".java"
                or not source.resolve().is_relative_to((ROOT / owner_relative).resolve())):
            violations.append(f"{prefix}.{key} must be a Java source owned by {service}")
    required = {token[key] for key in ("path", "header", "identityHeader", "identity")
                if isinstance(token[key], str)} | {
        "OutboundHttpHeaders.propagateObservability", "HttpClient.Redirect.NEVER",
        "BoundedHttpResponseReader.readBeforeDeadline", ".timeout(requestTimeout)",
    }
    if not required <= set(string_entries(entry.get("requiredMarkers"))):
        violations.append(f"{prefix} must require exact endpoint, credential, tracing and bounded transport markers")
    if not owner_token_forbidden(profile) <= set(string_entries(entry.get("forbiddenMarkers"))):
        violations.append(f"{prefix} must forbid borrowed credentials, Gateway calls and automatic retries")
    return violations


def exact_java_string(source: str, name: str, expected: str) -> bool:
    literals = [match.group(1) for match in executable_literal_matches(
        rf'\bstatic\s+final\s+String\s+{name}\s*=\s*"([^"\\]*)"\s*;', source
    )]
    return literals == [expected]


def owner_token_source_violations(entry: dict[str, Any], source: str) -> list[str]:
    token = entry["ownerToken"]
    prefix = f"{entry['path']} ownerToken"
    violations: list[str] = []
    source = java_without_comments(source)
    code = JAVA_NON_CODE_RE.sub(" ", source)
    for name, key in (("PATH", "path"), ("TOKEN_HEADER", "header"),
                      ("SERVICE_IDENTITY_HEADER", "identityHeader"), ("SERVICE_IDENTITY", "identity")):
        if not exact_java_string(source, name, token[key]):
            violations.append(f"{prefix} {name} must be the declared executable literal exactly once")
    for label, pattern in {
        "canonical endpoint": r"\.resolve\(\s*PATH\s*\)",
        "request endpoint": r"\bHttpRequest\.newBuilder\(\s*endpoint\s*\)",
        "credential header": r"\.header\(\s*TOKEN_HEADER\s*,\s*token\s*\)",
        "service identity": r"\.header\(\s*SERVICE_IDENTITY_HEADER\s*,\s*SERVICE_IDENTITY\s*\)",
        "POST body": r"\.POST\(\s*HttpRequest\.BodyPublishers\.ofByteArray\(\s*body\s*\)\s*\)",
        "request timeout": r"\.timeout\(\s*requestTimeout\s*\)",
        "no redirect": r"\.followRedirects\(\s*HttpClient\.Redirect\.NEVER\s*\)",
        "tracing": r"\bOutboundHttpHeaders\.propagateObservability\s*\(",
        "trace header wiring": r"\bvalues\.forEach\(\s*value\s*->\s*\w+\.header\(\s*name\s*,\s*value\s*\)",
        "single response deadline": r"\blong\s+responseDeadline\s*=\s*System\.nanoTime\(\)\s*\+\s*requestTimeout\.toNanos\(\)",
        "bounded body deadline": r"\bBoundedHttpResponseReader\.readBeforeDeadline\(\s*(?:response|result)\s*,\s*maximumResponseBytes\s*,\s*responseDeadline\s*\)",
    }.items():
        if not re.search(pattern, code):
            violations.append(f"{prefix} is missing executable {label} wiring")
    if not executable_literal_matches(
        r'\breturn\s+"application/json"\.equals\(\s*mediaType\.strip\(\)\s*\)\s*;', source
    ):
        violations.append(f"{prefix} must validate the exact application/json media type")
    response_patterns = {
        "verified acceptance": r"\bif\s*\(\s*!valid\(\s*envelope\s*,\s*status\s*\)\s*\)",
        "single materialized recipient": r"\bresult\.recipientCount\(\)\s*!=\s*1\s*\|\|\s*result\.notificationId\(\)\s*==\s*null",
    } if token["profile"] == "notification-producer" else {
        "null and source evidence rejection": r"\bresponse\s*==\s*null\s*\|\|\s*!response\.matches\(\s*request\s*\)",
        "decision consistency": r"\bresponse\.allowed\(\)\s*==\s*\(\s*response\.denial\(\)\s*!=\s*null\s*\)",
        "unexpired evidence": r"!response\.validUntil\(\)\.isAfter\(\s*now\s*\)",
        "bounded evidence lifetime": r"\bresponse\.validUntil\(\)\.isAfter\(\s*now\.plusSeconds\(\s*90\s*\)\s*\)",
    }
    for label, pattern in response_patterns.items():
        if not re.search(pattern, code):
            violations.append(f"{prefix} is missing executable {label}")
    security = java_without_comments((ROOT / token["securitySource"]).read_text(encoding="utf-8"))
    security_code = JAVA_NON_CODE_RE.sub(" ", security)
    if token["profile"] == "notification-producer":
        constants = {"SERVICE_TOKEN_HEADER": token["header"], "SOURCE_SERVICE_HEADER": token["identityHeader"]}
        patterns = {
            "servlet filter registration": (
                r"@Component\s+@Order\(\s*Ordered\.HIGHEST_PRECEDENCE\s*\+\s*20\s*\)"
                r"\s+public\s+class\s+NotificationSecurityFilter\s+extends\s+OncePerRequestFilter"
            ),
            "source header input": r"\bnormalized\(\s*request\.getHeader\(\s*SOURCE_SERVICE_HEADER\s*\)\s*\)",
            "presented producer credential": r"\bvalidProducerIdentity\(\s*sourceService\s*,\s*request\.getHeader\(\s*SERVICE_TOKEN_HEADER\s*\)\s*\)",
            "producer allowlist": r"\ballowedProducers\.contains\(\s*sourceService\s*\)",
            "producer credential binding": r"\bproducerTokens\.get\(\s*sourceService\s*\)",
            "producer credential comparison": r"\bconstantTimeEquals\(\s*expectedToken\s*,\s*presentedToken\s*\)",
            "conjunctive producer acceptance": (
                r"\breturn\s+expectedToken\s*!=\s*null\s*&&\s*!expectedToken\.isBlank\(\)"
                r"\s*&&\s*constantTimeEquals\(\s*expectedToken\s*,\s*presentedToken\s*\)\s*;"
            ),
            "producer rejection before dispatch": (
                r"\bif\s*\(\s*!validProducerIdentity\(\s*sourceService\s*,"
                r"\s*request\.getHeader\(\s*SERVICE_TOKEN_HEADER\s*\)\s*\)\s*\)\s*\{"
                r"\s*writeError\(\s*response\s*,\s*NotificationErrorCode\.FORBIDDEN\s*\)\s*;"
                r"\s*return\s*;\s*\}"
            ),
            "constant-time producer comparison": (
                r"\breturn\s+MessageDigest\.isEqual\(\s*expected\.getBytes\(\s*StandardCharsets\.UTF_8\s*\)"
                r"\s*,\s*actual\.getBytes\(\s*StandardCharsets\.UTF_8\s*\)\s*\)\s*;"
            ),
            "complete producer bindings": r"\bproducerTokens\.keySet\(\)\.equals\(\s*allowedProducers\s*\)",
            "Gateway credential separation": r"\bdistinctTokens\.contains\(\s*gatewayToken\s*\)",
            "startup identity validation": r"\bvalidateIdentityConfiguration\(\s*\)\s*;",
        }
    else:
        constants = {"MEETING_FOLLOWUP_TOKEN_HEADER": token["header"], "SERVICE_IDENTITY_HEADER": token["identityHeader"],
                     "MEETING_SERVICE_IDENTITY": token["identity"], "MEETING_FOLLOWUP_PATH": token["path"]}
        patterns = {
            "security configuration registration": r"@Configuration\s+public\s+class\s+ProductSurfaceInternalSecurityConfig",
            "security chain bean": r"@Bean\s+@Order\(\s*0\s*\)\s+SecurityFilterChain\s+productSurfaceInternalSecurityFilterChain\(",
            "dedicated filter installation": (
                r"\.addFilterBefore\(\s*new\s+ProductSurfaceTokenFilter\("
                r"\s*productSurfaceToken\s*,\s*meetingFollowupAuthorityToken\s*,\s*objectMapper\s*\)"
                r"\s*,\s*AnonymousAuthenticationFilter\.class\s*\)"
            ),
            "security chain route": r"\.securityMatcher\([^)]*\bMEETING_FOLLOWUP_PATH\s*\)",
            "Meeting identity binding": r"\bMEETING_SERVICE_IDENTITY\.equals\(\s*identity\s*\)",
            "exact owner route": r"\bMEETING_FOLLOWUP_PATH\.equals\(\s*request\.getRequestURI\(\)\s*\)",
            "Gateway credential exclusion": r"\babsentHeader\(\s*request\s*,\s*TOKEN_HEADER\s*\)",
            "dedicated credential comparison": r"\bmatches\(\s*expectedMeetingFollowupToken\s*,\s*meetingFollowupToken\s*\)",
            "dedicated credential input": r"\bexactHeader\(\s*request\s*,\s*MEETING_FOLLOWUP_TOKEN_HEADER\s*\)",
            "conjunctive Gateway exclusion": (
                r"\bboolean\s+gateway\s*=\s*GATEWAY_SERVICE_IDENTITY\.equals\(\s*identity\s*\)"
                r"\s*&&\s*!MEETING_FOLLOWUP_PATH\.equals\(\s*request\.getRequestURI\(\)\s*\)"
                r"\s*&&\s*absentHeader\(\s*request\s*,\s*MEETING_FOLLOWUP_TOKEN_HEADER\s*\)"
                r"\s*&&\s*matches\(\s*expectedToken\s*,\s*productSurfaceToken\s*\)\s*;"
            ),
            "unverified identity rejection": (
                r"\bif\s*\(\s*!gateway\s*&&\s*!meeting\s*\)\s*\{"
                r"\s*response\.setStatus\(\s*ErrorCode\.UNAUTHORIZED\.getHttpStatus\(\)\.value\(\)\s*\)"
                r"[\s\S]*?\breturn\s*;\s*\}\s*filterChain\.doFilter\(\s*request\s*,\s*response\s*\)"
            ),
            "constant-time credential comparison": (
                r"\breturn\s*!expected\.isBlank\(\)\s*&&\s*actual\s*!=\s*null"
                r"\s*&&\s*MessageDigest\.isEqual\(\s*expected\.getBytes\(\s*StandardCharsets\.UTF_8\s*\)"
                r"\s*,\s*actual\.getBytes\(\s*StandardCharsets\.UTF_8\s*\)\s*\)\s*;"
            ),
            "single credential header": r"\bvalues\.size\(\)\s*!=\s*1",
        }
        if not executable_literal_matches(
            r'\bboolean\s+meeting\s*=\s*MEETING_SERVICE_IDENTITY\.equals\(\s*identity\s*\)'
            r'\s*&&\s*"POST"\.equals\(\s*request\.getMethod\(\)\s*\)'
            r'\s*&&\s*MEETING_FOLLOWUP_PATH\.equals\(\s*request\.getRequestURI\(\)\s*\)'
            r'\s*&&\s*absentHeader\(\s*request\s*,\s*TOKEN_HEADER\s*\)'
            r'\s*&&\s*matches\(\s*expectedMeetingFollowupToken\s*,\s*meetingFollowupToken\s*\)\s*;',
            security
        ):
            violations.append(f"{prefix} owner must enforce the complete conjunctive Meeting credential decision")
    for name, expected in constants.items():
        if not exact_java_string(security, name, expected):
            violations.append(f"{prefix} owner {name} does not match the credential declaration")
    for label, pattern in patterns.items():
        if not re.search(pattern, security_code):
            violations.append(f"{prefix} owner is missing executable {label}")
    endpoint = java_without_comments((ROOT / token["endpointSource"]).read_text(encoding="utf-8"))
    if (token["profile"] == "notification-producer" and not re.search(
            r"\bmaterializer\.materialize\(\s*NotificationRequestContext\.requireInternalActor\(\)\s*,",
            JAVA_NON_CODE_RE.sub(" ", endpoint))):
        violations.append(f"{prefix} owner endpoint must require the authenticated internal producer actor")
    bases = [match.group(1) for match in executable_literal_matches(
        r'@RequestMapping\(\s*"([^"\\]+)"\s*\)', endpoint
    )]
    posts = [match.group(1) for match in executable_literal_matches(
        r'@PostMapping\(\s*"([^"\\]+)"\s*\)', endpoint
    )]
    if not any(base + post == token["path"] for base in bases for post in posts):
        violations.append(f"{prefix} owner controller does not expose the declared exact POST endpoint")
    return violations


def load_policy() -> tuple[dict[str, Any] | None, list[str]]:
    if not POLICY_FILE.exists():
        return None, [f"{POLICY_FILE.relative_to(ROOT)} is missing"]
    try:
        policy = json.loads(POLICY_FILE.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return None, [f"{POLICY_FILE.relative_to(ROOT)} is not valid JSON: {exc}"]
    if not isinstance(policy, dict):
        return None, [f"{POLICY_FILE.relative_to(ROOT)} must contain a JSON object"]
    return policy, []


def string_entries(value: Any) -> list[str]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, str)]


def validate_string_list(
    violations: list[str],
    section: str,
    entry_id: str,
    entry: dict[str, Any],
    key: str,
) -> list[str]:
    value = entry.get(key)
    if not isinstance(value, list) or not value:
        violations.append(f"{section}:{entry_id} must define non-empty {key}")
        return []
    if any(not isinstance(item, str) or not item.strip() for item in value):
        violations.append(f"{section}:{entry_id} {key} must contain non-empty strings only")
        return []
    return value


def validate_relative_path(
    violations: list[str],
    section: str,
    entry_id: str,
    path_value: Any,
) -> Path | None:
    if not isinstance(path_value, str) or not path_value.strip():
        violations.append(f"{section}:{entry_id} must define a non-empty path")
        return None
    relative = Path(path_value)
    if relative.is_absolute() or ".." in relative.parts:
        violations.append(f"{section}:{entry_id} path must be repository-relative: {path_value}")
        return None
    if not (ROOT / relative).exists():
        violations.append(f"{section}:{entry_id} path does not exist: {path_value}")
    return relative


def policy_manifest_violations(policy: dict[str, Any]) -> list[str]:
    violations: list[str] = []
    if policy.get("version") != 2:
        violations.append(f"{POLICY_FILE.relative_to(ROOT)} version must be 2")

    resilience = policy.get("resilienceDefaults")
    if not isinstance(resilience, dict):
        violations.append(f"{POLICY_FILE.relative_to(ROOT)} must define resilienceDefaults")
    else:
        bounded_values = {
            "connectTimeoutMs": (1, 30_000),
            "readTimeoutMs": (1, 30_000),
            "bulkheadMaxConcurrentCalls": (1, 1_000),
            "maximumRetryAttempts": (1, 3),
        }
        for key, (minimum, maximum) in bounded_values.items():
            value = resilience.get(key)
            if not isinstance(value, int) or not minimum <= value <= maximum:
                violations.append(
                    f"resilienceDefaults.{key} must be between {minimum} and {maximum}"
                )
        if resilience.get("circuitBreaker") is not True:
            violations.append("resilienceDefaults.circuitBreaker must be true")

    required_sections = {
        "httpClients",
        "crossDatabaseExceptions",
        "metadataScanners",
    }
    for section in sorted(required_sections):
        if not isinstance(policy.get(section), list):
            violations.append(f"{POLICY_FILE.relative_to(ROOT)} must define array section {section}")

    seen_ids: dict[str, str] = {}
    seen_paths: dict[tuple[str, Path], str] = {}

    for section in sorted(required_sections):
        entries = policy.get(section)
        if not isinstance(entries, list):
            continue
        for index, entry in enumerate(entries, start=1):
            if not isinstance(entry, dict):
                violations.append(f"{section}[{index}] must be an object")
                continue
            entry_id = entry.get("id")
            if not isinstance(entry_id, str) or not entry_id.strip():
                entry_id = f"#{index}"
                violations.append(f"{section}[{index}] must define a non-empty id")
            elif entry_id in seen_ids:
                violations.append(f"{section}:{entry_id} duplicates id from {seen_ids[entry_id]}")
            else:
                seen_ids[entry_id] = section

            for key in ("classification", "sourceService", "purpose"):
                value = entry.get(key)
                if not isinstance(value, str) or not value.strip():
                    violations.append(f"{section}:{entry_id} must define non-empty {key}")

            source_service = entry.get("sourceService")
            if isinstance(source_service, str) and source_service not in SERVICE_PACKAGES:
                violations.append(f"{section}:{entry_id} has unknown sourceService {source_service}")

            relative = validate_relative_path(violations, section, str(entry_id), entry.get("path"))
            if relative is not None:
                duplicate_key = (section, relative)
                if duplicate_key in seen_paths:
                    violations.append(
                        f"{section}:{entry_id} duplicates path from {seen_paths[duplicate_key]}: {relative}"
                    )
                else:
                    seen_paths[duplicate_key] = str(entry_id)
                if (
                    isinstance(source_service, str)
                    and source_service in SERVICE_PACKAGES
                    and relative.parts
                    and relative.parts[0] != source_service
                ):
                    violations.append(
                        f"{section}:{entry_id} path must live under sourceService {source_service}: {relative}"
                    )

            if section == "httpClients":
                interface_type = entry.get("interfaceType")
                if interface_type not in VALID_INTERFACE_TYPES:
                    violations.append(
                        f"{section}:{entry_id} interfaceType must be one of {sorted(VALID_INTERFACE_TYPES)}"
                    )
                target_services = validate_string_list(
                    violations, section, str(entry_id), entry, "targetServices"
                )
                if interface_type in {"gateway-verifier", "internal-http"}:
                    for target_service in target_services:
                        if target_service not in SERVICE_PACKAGES:
                            violations.append(
                                f"{section}:{entry_id} has unknown DWP targetService {target_service}"
                            )
                auth = entry.get("auth")
                if not isinstance(auth, str) or not auth.strip():
                    violations.append(f"{section}:{entry_id} must define non-empty auth")
                if entry.get("retryMode") not in {"none", "idempotent-only", "outbox-owned"}:
                    violations.append(
                        f"{section}:{entry_id} must define retryMode as none, idempotent-only, or outbox-owned"
                    )
                if entry.get("failureMode") not in {"fail-closed", "fail-contained"}:
                    violations.append(
                        f"{section}:{entry_id} must define failureMode as fail-closed or fail-contained"
                    )
                required_markers = validate_string_list(
                    violations, section, str(entry_id), entry, "requiredMarkers"
                )
                forbidden_markers = validate_string_list(
                    violations, section, str(entry_id), entry, "forbiddenMarkers"
                )
                if interface_type == "gateway-verifier":
                    if source_service != "dwp-gateway":
                        violations.append(
                            f"{section}:{entry_id} gateway-verifier interfaces must originate from dwp-gateway"
                        )
                    for trace_marker in ("TRACE_PARENT_HEADER", "TRACE_STATE_HEADER"):
                        if trace_marker not in required_markers:
                            violations.append(
                                f"{section}:{entry_id} gateway-verifier must require {trace_marker}"
                            )
                elif interface_type == "internal-http":
                    if "signedWorkload" not in entry and not any("/internal/" in marker for marker in required_markers):
                        violations.append(
                            f"{section}:{entry_id} internal-http contracts must require an /internal/ path marker"
                        )
                    if "OutboundHttpHeaders.propagateObservability" not in required_markers:
                        violations.append(
                            f"{section}:{entry_id} internal-http contracts must propagate observability headers"
                        )
                    if "/api/" not in forbidden_markers:
                        violations.append(
                            f"{section}:{entry_id} internal-http contracts must forbid Gateway /api/ calls"
                        )
                    if ("signedWorkload" not in entry and "ownerToken" not in entry
                            and not INTERNAL_PURPOSE_TOKEN_HEADERS.intersection(required_markers)):
                        violations.append(
                            f"{section}:{entry_id} internal-http contracts must require a purpose-specific service token"
                        )
                elif interface_type == "external-connector":
                    for target_service in target_services:
                        if target_service in SERVICE_PACKAGES:
                            violations.append(
                                f"{section}:{entry_id} external-connector targetService must not be a DWP service: {target_service}"
                            )
                    if "X-DWP-Service-Token" not in forbidden_markers:
                        violations.append(
                            f"{section}:{entry_id} external-connector contracts must forbid X-DWP-Service-Token"
                        )
                    if not any(marker in required_markers for marker in (
                        "requireHost", "requireAllowed", "validatedOrigin"
                    )):
                        violations.append(
                            f"{section}:{entry_id} external-connector contracts must require host allowlist validation"
                        )
                if "signedWorkload" in entry:
                    violations.extend(signed_workload_manifest_violations(entry))
                if "ownerToken" in entry:
                    violations.extend(owner_token_manifest_violations(entry))
                violations.extend(workflow_runtime_manifest_violations(entry))
                violations.extend(policy_impact_manifest_violations(entry))
                violations.extend(information_replay_manifest_violations(entry))
                violations.extend(workflow_planning_manifest_violations(entry))
                violations.extend(current_proof_manifest_violations(entry))
            elif section == "crossDatabaseExceptions":
                allowed_databases = validate_string_list(
                    violations, section, str(entry_id), entry, "allowedDatabases"
                )
                unknown = set(allowed_databases) - set(OWNED_DATABASE_PREFIXES.values())
                if unknown:
                    violations.append(
                        f"{section}:{entry_id} references unknown database prefixes {sorted(unknown)}"
                    )
                if source_service != "dwp-provider-server":
                    violations.append(
                        f"{section}:{entry_id} cross-database metadata exceptions must originate from dwp-provider-server"
                    )
                if entry.get("classification") != "metadata-governance":
                    violations.append(
                        f"{section}:{entry_id} cross-database exceptions must use metadata-governance classification"
                    )
                validate_string_list(violations, section, str(entry_id), entry, "requiredMarkers")
            elif section == "metadataScanners":
                required_markers = validate_string_list(
                    violations, section, str(entry_id), entry, "requiredMarkers"
                )
                forbidden_markers = validate_string_list(
                    violations, section, str(entry_id), entry, "forbiddenMarkers"
                )
                if "connection.setReadOnly(true)" not in required_markers:
                    violations.append(
                        f"{section}:{entry_id} metadata scanners must require connection.setReadOnly(true)"
                    )
                for write_marker in ("INSERT INTO", "UPDATE ", "DELETE FROM", "MERGE ", "TRUNCATE ", "ALTER ", "DROP ", "CREATE "):
                    if write_marker not in forbidden_markers:
                        violations.append(
                            f"{section}:{entry_id} metadata scanners must forbid {write_marker!r}"
                        )

    return violations


def java_import_violations() -> list[str]:
    violations: list[str] = []
    for module, package_prefix in SERVICE_PACKAGES.items():
        source_root = ROOT / module / "src/main/java"
        if not source_root.exists():
            violations.append(f"{module}: expected source root is missing")
            continue
        for source_file in sorted(source_root.rglob("*.java")):
            source = source_file.read_text(encoding="utf-8")
            for imported in IMPORT_RE.findall(source):
                for other_module, other_prefix in SERVICE_PACKAGES.items():
                    if other_module == module:
                        continue
                    if imported == other_prefix or imported.startswith(f"{other_prefix}."):
                        violations.append(
                            f"{source_file.relative_to(ROOT)} imports {imported} from {other_module}"
                        )
    return violations


def application_layer_violations() -> list[str]:
    """Reject direct HTTP-to-persistence and persistence-to-HTTP dependencies."""
    violations: list[str] = []
    for module in SERVICE_PACKAGES:
        source_root = ROOT / module / "src/main/java"
        if not source_root.exists():
            continue
        for source_file in sorted(source_root.rglob("*.java")):
            source = JAVA_NON_CODE_RE.sub(" ", source_file.read_text(encoding="utf-8"))
            if source_file.stem.endswith("Controller"):
                repository_types = sorted(set(REPOSITORY_TYPE_RE.findall(source)))
                if repository_types:
                    violations.append(
                        f"{source_file.relative_to(ROOT)} is an HTTP controller that depends "
                        f"directly on persistence types {repository_types}; depend on an "
                        "application service instead"
                    )
            if source_file.stem.endswith("Repository"):
                controller_types = sorted(set(CONTROLLER_TYPE_RE.findall(source)))
                if controller_types:
                    violations.append(
                        f"{source_file.relative_to(ROOT)} is a persistence type that depends "
                        f"on HTTP controller types {controller_types}"
                    )
    return violations


def gradle_dependency_violations() -> list[str]:
    violations: list[str] = []
    service_modules = set(SERVICE_PACKAGES)
    for module in sorted(service_modules):
        build_file = ROOT / module / "build.gradle"
        if not build_file.exists():
            violations.append(f"{module}: build.gradle is missing")
            continue
        source = build_file.read_text(encoding="utf-8")
        for dependency in PROJECT_DEP_RE.findall(source):
            if dependency in service_modules and dependency != module:
                violations.append(f"{module}/build.gradle depends on service module {dependency}")
            elif dependency not in service_modules and dependency not in SHARED_MODULES:
                violations.append(f"{module}/build.gradle depends on unknown project module {dependency}")
    return violations


def http_client_policy_violations(policy: dict[str, Any]) -> list[str]:
    violations: list[str] = []
    allowed_clients = {
        Path(entry["path"]): entry
        for entry in policy["httpClients"]
    }
    source_roots = [
        ROOT / module / "src/main/java"
        for module in SERVICE_PACKAGES
    ]
    for source_root in source_roots:
        if not source_root.exists():
            continue
        for source_file in sorted(source_root.rglob("*.java")):
            source = source_file.read_text(encoding="utf-8")
            if not HTTP_CLIENT_IMPORT_RE.search(source):
                continue
            relative = source_file.relative_to(ROOT)
            contract = allowed_clients.get(relative)
            if contract is None:
                violations.append(
                    f"{relative} creates an HTTP client but is not in {POLICY_FILE.relative_to(ROOT)}"
                )
                continue
            if "signedWorkload" in contract or "ownerToken" in contract:
                # Validate registered structured clients even if their HTTP import disappears.
                continue
            violations.extend(workflow_runtime_source_violations(contract, source))
            violations.extend(policy_impact_source_violations(contract, source))
            violations.extend(information_replay_source_violations(contract, source))
            violations.extend(workflow_planning_source_violations(contract, source))
            violations.extend(current_proof_source_violations(contract, source))
            for required in string_entries(contract["requiredMarkers"]):
                if required not in source:
                    violations.append(
                        f"{relative} ({contract['classification']}) is missing required contract marker {required!r}"
                    )
            for forbidden in string_entries(contract["forbiddenMarkers"]):
                if forbidden in source:
                    violations.append(
                        f"{relative} ({contract['classification']}) contains forbidden marker {forbidden!r}"
                    )
    for relative in sorted(allowed_clients):
        if not (ROOT / relative).exists():
            violations.append(f"{relative} is registered in {POLICY_FILE.relative_to(ROOT)} but does not exist")
        elif "signedWorkload" in allowed_clients[relative] or "ownerToken" in allowed_clients[relative]:
            contract = allowed_clients[relative]
            source = java_without_comments((ROOT / relative).read_text(encoding="utf-8"))
            if not HTTP_CLIENT_IMPORT_RE.search(JAVA_NON_CODE_RE.sub(" ", source)):
                violations.append(f"{relative} structured contract requires an executable HTTP client import")
            if "signedWorkload" in contract:
                violations.extend(signed_workload_source_violations(contract, source))
            if "ownerToken" in contract:
                violations.extend(owner_token_source_violations(contract, source))
            kind = "signedWorkload" if "signedWorkload" in contract else "ownerToken"
            for required in string_entries(contract["requiredMarkers"]):
                if required not in source:
                    violations.append(f"{relative} {kind} is missing required contract marker {required!r}")
            for forbidden in string_entries(contract["forbiddenMarkers"]):
                if forbidden in source:
                    violations.append(f"{relative} {kind} contains forbidden marker {forbidden!r}")
        elif str(relative) in {profile["path"] for profile in CURRENT_PROOF_CLIENTS.values()}:
            source = (ROOT / relative).read_text(encoding="utf-8")
            if not HTTP_CLIENT_IMPORT_RE.search(java_without_comments(source)):
                violations.extend(current_proof_source_violations(allowed_clients[relative], source))
    return violations


def cross_database_policy_violations(policy: dict[str, Any]) -> list[str]:
    violations: list[str] = []
    allowed_cross_db_configs = {
        Path(entry["path"]): entry
        for entry in policy["crossDatabaseExceptions"]
    }
    for application_file in sorted(ROOT.glob("*/src/main/resources/application.yml")):
        source = application_file.read_text(encoding="utf-8")
        relative = application_file.relative_to(ROOT)
        matches = {match.group("db") for match in APP_YML_CROSS_DB_RE.finditer(source)}
        if not matches:
            continue
        module = relative.parts[0]
        own_prefix = OWNED_DATABASE_PREFIXES.get(module)
        cross_database_refs = matches - ({own_prefix} if own_prefix else set())
        if not cross_database_refs:
            continue
        contract = allowed_cross_db_configs.get(relative)
        if contract is None:
            violations.append(
                f"{relative} references other service databases {sorted(cross_database_refs)} without a policy entry in {POLICY_FILE.relative_to(ROOT)}"
            )
            continue
        allowed_databases = set(string_entries(contract["allowedDatabases"]))
        disallowed = cross_database_refs - allowed_databases
        if disallowed:
            violations.append(
                f"{relative} references disallowed service databases {sorted(disallowed)}"
            )
        for required in string_entries(contract["requiredMarkers"]):
            if required not in source:
                violations.append(
                    f"{relative} cross-database exception is missing required marker {required!r}"
                )

    for scanner_contract in policy["metadataScanners"]:
        relative = Path(scanner_contract["path"])
        scanner = ROOT / relative
        if not scanner.exists():
            violations.append(f"{relative} is required for the metadata scanner policy")
            continue
        source = scanner.read_text(encoding="utf-8")
        for marker in string_entries(scanner_contract["requiredMarkers"]):
            if marker not in source:
                violations.append(
                    f"{relative} metadata exception is missing required marker {marker!r}"
                )
        for marker in string_entries(scanner_contract["forbiddenMarkers"]):
            if marker in source:
                violations.append(
                    f"{relative} metadata scanner contains forbidden write/DDL marker {marker!r}"
                )
    return violations


def main() -> int:
    policy, load_violations = load_policy()
    if policy is None:
        violations = load_violations
    else:
        manifest_violations = policy_manifest_violations(policy)
        if manifest_violations:
            violations = manifest_violations
        else:
            violations = (
                java_import_violations()
                + application_layer_violations()
                + gradle_dependency_violations()
                + http_client_policy_violations(policy)
                + cross_database_policy_violations(policy)
            )
    if violations:
        print("Service boundary violations found.", file=sys.stderr)
        print(
            "Backend services may depend on shared modules only; direct service HTTP and cross-database access require explicit service-interface policy entries.",
            file=sys.stderr,
        )
        for violation in violations:
            print(f"- {violation}", file=sys.stderr)
        return 1
    print(
        f"PASS service boundaries: backend modules, HTTP/application/persistence layering, "
        f"direct service HTTP clients, and cross-database metadata exceptions match "
        f"{POLICY_FILE.relative_to(ROOT)}."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
