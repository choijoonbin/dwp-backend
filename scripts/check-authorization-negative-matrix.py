#!/usr/bin/env python3
"""Fail-closed validation for the X-03 authorization negative-test inventory."""

from __future__ import annotations

import ast
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = ROOT / "contracts/product-authorization/authorization-negative-matrix.v1.json"
VECTOR_IDS = {
    "CROSS_TENANT",
    "SCOPE_ESCAPE",
    "STALE_AUTHORITY_REVISION",
    "CONFUSED_DEPUTY",
    "INTERNAL_HEADER_SPOOF",
}
REQUIRED_ROUTE_KINDS = {"PAGE", "DATA", "ACTION"}
CONTRACT_STATUSES = {"EXACT", "INCOMPLETE_KINDS", "MISSING"}
EXPECTED_MATRIX_FIELDS = {
    "schemaVersion", "matrixId", "completionState", "rolloutInventory",
    "exactContract", "canonicalNegativeFixtures", "missingContractGuard",
    "attackVectors", "products",
}
EXPECTED_PRODUCT_FIELDS = {
    "productId", "contractStatus", "ownerService", "rolloutCeiling",
    "attackEvidence", "missingAttackIds", "blocker",
}
GATEWAY_CONFIG_REFERENCE = "dwp-gateway/src/main/resources/application.yml"
RUNTIME_CANDIDATE_CATALOG_REFERENCE = (
    "dwp-gateway/src/main/java/com/dwp/gateway/productsurface/"
    "GeneratedProductSurfaceCandidateCatalog.java"
)
AGENT_EVIDENCE_ROOT_ENV = "DWP_AGENT_EVIDENCE_ROOT"
AGENT_ATTESTATION_FIELDS = {
    "schemaVersion", "attestationId", "repository", "revision", "testRoot",
    "pepSourceReferences", "files", "pytestNodeIds", "command", "result",
    "executionWorkflow", "sourceCiRun", "checksum",
}
AGENT_CI_RUN_FIELDS = {
    "provider", "workflow", "runId", "url", "headSha", "conclusion",
}
ROUTED_PRODUCT_PEPS = {
    "approvals": {
        "routes": {"approval-server": "/api/approvals/**"},
        "securityFilter": (
            "dwp-approval-server/src/main/java/com/dwp/services/approval/security/"
            "ApprovalSecurityFilter.java"
        ),
        "productPepFilter": (
            "dwp-approval-server/src/main/java/com/dwp/services/approval/security/"
            "ApprovalSecurityFilter.java"
        ),
        "productPepComponent": "ApprovalPilotPepRegistry",
        "fullChainTestReference": (
            "dwp-approval-server/src/test/java/com/dwp/services/approval/security/"
            "ApprovalProductSurfacePepEvidenceTest.java"
            "#rejectsCrossTenantOpaqueScopeAtOwnerServicePep"
        ),
        "chainInvariant": {
            "kind": "INTEGRATED_FILTER",
            "filterVariable": "filter",
            "requestHarness": "execute",
        },
    },
    "calendar": {
        "routes": {"platform-server": "/api/platform/**"},
        "securityFilter": (
            "dwp-platform-server/src/main/java/com/dwp/services/platform/security/"
            "PlatformSecurityFilter.java"
        ),
        "productPepFilter": (
            "dwp-platform-server/src/main/java/com/dwp/services/platform/calendar/"
            "CalendarProductSurfacePepFilter.java"
        ),
        "fullChainTestReference": (
            "dwp-platform-server/src/test/java/com/dwp/services/platform/calendar/"
            "CalendarProductSurfacePepEvidenceTest.java"
            "#pageDataAndActionExecuteThroughPlatformSecurityAndCalendarOwnerPep"
        ),
        "chainInvariant": {
            "kind": "MOCK_MVC_FILTER_CHAIN",
            "requestHarness": "mvc",
            "members": {
                "platformSecurity": "PlatformSecurityFilter",
                "calendarPep": "CalendarProductSurfacePepFilter",
            },
        },
    },
    "communications": {
        "routes": {"platform-server": "/api/platform/**"},
        "securityFilter": (
            "dwp-platform-server/src/main/java/com/dwp/services/platform/security/"
            "PlatformSecurityFilter.java"
        ),
        "productPepFilter": (
            "dwp-platform-server/src/main/java/com/dwp/services/platform/communication/"
            "CommunicationProductSurfacePepFilter.java"
        ),
        "fullChainTestReference": (
            "dwp-platform-server/src/test/java/com/dwp/services/platform/communication/"
            "CommunicationProductSurfacePepEvidenceTest.java"
            "#executesPageDataAndActionConsumersThroughCommunicationsOwnerChain"
        ),
        "chainInvariant": {
            "kind": "MOCK_MVC_FILTER_CHAIN",
            "requestHarness": "mvc",
            "members": {
                "platformSecurity": "PlatformSecurityFilter",
                "ownerPep": "CommunicationProductSurfacePepFilter",
            },
        },
    },
    "dwaion": {
        "routes": {"agent-runtime": "/api/agent/**"},
        "ownerService": "dwp-agent-runtime",
        "ownerRepository": "https://github.com/choijoonbin/aura_agent",
        "testRoot": "tests",
        "attestation": (
            "contracts/product-authorization/dwaion-agent-pep-attestation.v1.json"
        ),
    },
    "hcm": {
        "routes": {"people-server": "/api/people/**"},
        "securityFilter": (
            "dwp-people-server/src/main/java/com/dwp/services/people/security/"
            "PeopleSecurityFilter.java"
        ),
        "productPepFilter": (
            "dwp-people-server/src/main/java/com/dwp/services/people/security/"
            "HcmProductSurfacePepFilter.java"
        ),
        "fullChainTestReference": (
            "dwp-people-server/src/test/java/com/dwp/services/people/security/"
            "HcmOwnerPepExecutionTest.java"
            "#pHcmPageDataAndActionBindingsReachTheirActualPublicControllers"
        ),
        "chainInvariant": {
            "kind": "MOCK_MVC_FILTER_CHAIN",
            "requestHarness": "mvc",
            "members": {
                "PeopleSecurityFilter": "PeopleSecurityFilter",
                "HcmProductSurfacePepFilter": "HcmProductSurfacePepFilter",
            },
        },
    },
    "mail": {
        "routes": {"platform-server": "/api/platform/**"},
        "securityFilter": (
            "dwp-platform-server/src/main/java/com/dwp/services/platform/security/"
            "PlatformSecurityFilter.java"
        ),
        "productPepFilter": (
            "dwp-platform-server/src/main/java/com/dwp/services/platform/mail/"
            "MailProductSurfacePepFilter.java"
        ),
        "fullChainTestReference": (
            "dwp-platform-server/src/test/java/com/dwp/services/platform/mail/"
            "MailProductSurfacePepEvidenceTest.java"
            "#pageDataAndActionExecuteThroughPlatformSecurityAndMailOwnerPep"
        ),
        "chainInvariant": {
            "kind": "MOCK_MVC_FILTER_CHAIN",
            "requestHarness": "mvc",
            "members": {
                "platformSecurity": "PlatformSecurityFilter",
                "mailPep": "MailProductSurfacePepFilter",
            },
        },
    },
    "meetings": {
        "routes": {"meeting-server": "/api/meetings/**"},
        "securityFilter": (
            "dwp-meeting-server/src/main/java/com/dwp/services/meeting/security/"
            "MeetingSecurityFilter.java"
        ),
        "productPepFilter": (
            "dwp-meeting-server/src/main/java/com/dwp/services/meeting/security/"
            "MeetingSecurityFilter.java"
        ),
        "productPepComponent": "MeetingProductAccessPolicy",
        "fullChainTestReference": (
            "dwp-meeting-server/src/test/java/com/dwp/services/meeting/"
            "videomeeting/domain/MeetingProductSurfacePepPostgresTest.java"
            "#crossTenantAttackCannotSubstituteActorTenantAtTheDataEndpoint"
        ),
        "chainInvariant": {
            "kind": "MOCK_MVC_FILTER_CHAIN",
            "requestHarness": "mvc",
            "members": {"filter": "MeetingSecurityFilter"},
        },
    },
    "messaging": {
        "routes": {"messaging-server": "/api/messaging/**"},
        "securityFilter": (
            "dwp-messaging-server/src/main/java/com/dwp/services/messaging/security/"
            "MessagingSecurityFilter.java"
        ),
        "productPepFilter": (
            "dwp-messaging-server/src/main/java/com/dwp/services/messaging/security/"
            "MessagingProductSurfacePepFilter.java"
        ),
        "fullChainTestReference": (
            "dwp-messaging-server/src/test/java/com/dwp/services/messaging/security/"
            "MessagingProductSurfacePepPostgresIntegrationTest.java"
            "#pageDataAndActionBindingsReachMessagingOwnerOnlyWithExactEvidence"
        ),
        "chainInvariant": {
            "kind": "MOCK_MVC_FILTER_CHAIN",
            "requestHarness": "mvc",
            "members": {
                "identityFilter": "MessagingSecurityFilter",
                "pepFilter": "MessagingProductSurfacePepFilter",
            },
        },
    },
    "notifications": {
        "routes": {
            "notification-stream": "/api/notifications/v1/stream",
            "notification-server": "/api/notifications/v1/**",
        },
        "securityFilter": (
            "dwp-notification-server/src/main/java/com/dwp/services/notification/"
            "security/NotificationSecurityFilter.java"
        ),
        "productPepFilter": (
            "dwp-notification-server/src/main/java/com/dwp/services/notification/"
            "security/NotificationProductSurfacePepFilter.java"
        ),
        "fullChainTestReference": (
            "dwp-notification-server/src/test/java/com/dwp/services/notification/"
            "security/NotificationProductSurfacePepEvidenceTest.java"
            "#v4DraftPageDataAndActionBindingsReachActualNotificationRoutes"
        ),
        "chainInvariant": {
            "kind": "MOCK_MVC_FILTER_CHAIN",
            "requestHarness": "mvc",
            "members": {
                "identityFilter": "NotificationSecurityFilter",
                "pepFilter": "NotificationProductSurfacePepFilter",
            },
        },
    },
    "spaces": {
        "routes": {"space-server": "/api/spaces/**"},
        "securityFilter": (
            "dwp-space-server/src/main/java/com/dwp/services/space/security/"
            "SpaceSecurityFilter.java"
        ),
        "productPepFilter": (
            "dwp-space-server/src/main/java/com/dwp/services/space/security/"
            "SpaceProductSurfacePepFilter.java"
        ),
        "fullChainTestReference": (
            "dwp-space-server/src/test/java/com/dwp/services/space/security/"
            "SpaceProductSurfacePepContractTest.java"
            "#v4DraftPageDataAndActionBindingsReachActualSpaceRoutes"
        ),
        "chainInvariant": {
            "kind": "MOCK_MVC_FILTER_CHAIN",
            "requestHarness": "mvc",
            "members": {
                "SpaceSecurityFilter": "SpaceSecurityFilter",
                "SpaceProductSurfacePepFilter": "SpaceProductSurfacePepFilter",
            },
        },
    },
    "services": {
        "routes": {"platform-server": "/api/platform/**"},
        "securityFilter": (
            "dwp-platform-server/src/main/java/com/dwp/services/platform/security/"
            "PlatformSecurityFilter.java"
        ),
        "productPepFilter": (
            "dwp-platform-server/src/main/java/com/dwp/services/platform/servicecenter/"
            "ServicesProductSurfacePepFilter.java"
        ),
        "fullChainTestReference": (
            "dwp-platform-server/src/test/java/com/dwp/services/platform/servicecenter/"
            "ServicesProductSurfacePepEvidenceTest.java"
            "#executesPageDataAndActionConsumersThroughServicesOwnerChain"
        ),
        "chainInvariant": {
            "kind": "MOCK_MVC_FILTER_CHAIN",
            "requestHarness": "mvc",
            "members": {
                "platformSecurity": "PlatformSecurityFilter",
                "ownerPep": "ServicesProductSurfacePepFilter",
            },
        },
    },
    "workplace": {
        "routes": {"platform-server": "/api/platform/**"},
        "securityFilter": (
            "dwp-platform-server/src/main/java/com/dwp/services/platform/security/"
            "PlatformSecurityFilter.java"
        ),
        "productPepFilter": (
            "dwp-platform-server/src/main/java/com/dwp/services/platform/security/"
            "PlatformWorkplaceProductPepFilter.java"
        ),
        "fullChainTestReference": (
            "dwp-platform-server/src/test/java/com/dwp/services/platform/security/"
            "WorkplaceProductSurfacePepContractTest.java"
            "#v4DraftPageDataAndActionBindingsReachActualWorkplaceRoutes"
        ),
        "chainInvariant": {
            "kind": "MOCK_MVC_FILTER_CHAIN",
            "requestHarness": "mvc",
            "members": {
                "platformSecurity": "PlatformSecurityFilter",
                "workplacePep": "PlatformWorkplaceProductPepFilter",
            },
        },
    },
}


def fail(message: str) -> None:
    raise SystemExit(f"Authorization negative matrix invalid: {message}")


def repository_path(reference: object, label: str) -> Path:
    if not isinstance(reference, str) or not reference:
        fail(f"{label} reference is required")
    relative = Path(reference)
    if relative.is_absolute() or ".." in relative.parts:
        fail(f"{label} reference escapes the repository: {reference}")
    path = (ROOT / relative).resolve()
    if ROOT not in path.parents or not path.is_file():
        fail(f"{label} reference does not exist: {reference}")
    return path


def read_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exception:
        try:
            label = path.relative_to(ROOT)
        except ValueError:
            label = path
        fail(f"cannot read {label}: {exception}")
    if not isinstance(value, dict):
        fail(f"{path.relative_to(ROOT)} must contain an object")
    return value


def checksum(document: dict, *excluded_fields: str) -> str:
    payload = dict(document)
    for field in excluded_fields:
        payload.pop(field, None)
    canonical = json.dumps(
        payload, ensure_ascii=False, allow_nan=False,
        separators=(",", ":"), sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def external_repository_path(root: Path, reference: object, label: str) -> Path:
    if not isinstance(reference, str) or not reference:
        fail(f"{label} reference is required")
    relative = Path(reference)
    if relative.is_absolute() or ".." in relative.parts:
        fail(f"{label} reference escapes the external repository: {reference}")
    resolved_root = root.resolve()
    path = (resolved_root / relative).resolve()
    if resolved_root not in path.parents or not path.is_file():
        fail(f"{label} reference does not exist: {reference}")
    return path


def agent_evidence_root() -> Path:
    configured = os.environ.get(AGENT_EVIDENCE_ROOT_ENV)
    return Path(configured).resolve() if configured else (ROOT.parent / "dwp_agent").resolve()


def validate_agent_attestation(descriptor: dict) -> tuple[dict, Path]:
    attestation_reference = descriptor.get("attestation")
    attestation = read_json(repository_path(attestation_reference, "Agent attestation"))
    if set(attestation) != AGENT_ATTESTATION_FIELDS:
        fail("Agent PEP attestation field set is invalid")
    if attestation.get("schemaVersion") != 1 or attestation.get("attestationId") != (
            "dwaion-agent-owner-pep.v1"):
        fail("Agent PEP attestation identity drift")
    if attestation.get("repository") != descriptor.get("ownerRepository"):
        fail("Agent PEP attestation repository is not canonical")
    if attestation.get("testRoot") != descriptor.get("testRoot"):
        fail("Agent PEP attestation test root drift")
    if attestation.get("checksum") != checksum(attestation, "checksum"):
        fail("Agent PEP attestation checksum drift")
    revision = attestation.get("revision")
    if not isinstance(revision, str) or not re.fullmatch(r"[a-f0-9]{40}", revision):
        fail("Agent PEP attestation requires an exact Git revision")
    source_ci_run = attestation.get("sourceCiRun")
    if not isinstance(source_ci_run, dict) or set(source_ci_run) != AGENT_CI_RUN_FIELDS:
        fail("Agent PEP source CI run field set is invalid")
    run_id = source_ci_run.get("runId")
    expected_run_url = f"{descriptor.get('ownerRepository')}/actions/runs/{run_id}"
    if (
        source_ci_run.get("provider") != "GITHUB_ACTIONS"
        or source_ci_run.get("workflow") != "Agent quality"
        or not isinstance(run_id, str)
        or not re.fullmatch(r"[1-9][0-9]*", run_id)
        or source_ci_run.get("url") != expected_run_url
        or source_ci_run.get("headSha") != revision
        or source_ci_run.get("conclusion") != "success"
    ):
        fail("Agent PEP source CI run is not bound to the immutable revision")

    root = agent_evidence_root()
    try:
        actual_revision = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError) as exception:
        fail(f"Agent PEP evidence checkout is unavailable: {exception}")
    if actual_revision != revision:
        fail(
            "Agent PEP evidence checkout revision does not match the checksummed attestation"
        )

    files = attestation.get("files")
    if not isinstance(files, list) or not files:
        fail("Agent PEP attestation requires checksummed source and test files")
    file_hashes: dict[str, str] = {}
    for item in files:
        if not isinstance(item, dict) or set(item) != {"path", "sha256"}:
            fail("Agent PEP attestation file entry is invalid")
        reference = item.get("path")
        expected_hash = item.get("sha256")
        if not isinstance(expected_hash, str) or not re.fullmatch(r"[a-f0-9]{64}", expected_hash):
            fail(f"Agent PEP attestation file hash is invalid: {reference!r}")
        if reference in file_hashes:
            fail(f"Agent PEP attestation file is duplicated: {reference!r}")
        path = external_repository_path(root, reference, "Agent evidence")
        if file_sha256(path) != expected_hash:
            fail(f"Agent PEP evidence file checksum drift: {reference}")
        file_hashes[reference] = expected_hash
    for dependency_lock in ("pyproject.toml", "uv.lock"):
        if dependency_lock not in file_hashes:
            fail(f"Agent PEP attestation must checksum {dependency_lock}")
    sources = attestation.get("pepSourceReferences")
    if not isinstance(sources, list) or not sources or len(sources) != len(set(sources)):
        fail("Agent PEP attestation requires unique PEP source references")
    if any(source not in file_hashes for source in sources):
        fail("Agent PEP source reference is not checksummed")

    node_ids = attestation.get("pytestNodeIds")
    if not isinstance(node_ids, list) or len(node_ids) < len(VECTOR_IDS) \
            or len(node_ids) != len(set(node_ids)):
        fail("Agent PEP attestation requires unique executable pytest nodes")
    command = attestation.get("command")
    if not isinstance(command, list) or command[:3] != ["uv", "run", "pytest"] \
            or any(node_id not in command for node_id in node_ids):
        fail("Agent PEP attestation command must execute every declared pytest node")
    result = attestation.get("result")
    if not isinstance(result, dict) or set(result) != {"status", "passed", "failed"} \
            or result.get("status") != "PASS" or result.get("failed") != 0 \
            or not isinstance(result.get("passed"), int) \
            or result.get("passed") < len(node_ids):
        fail("Agent PEP attestation does not record a complete passing execution")

    workflow_reference = attestation.get("executionWorkflow")
    workflow = repository_path(workflow_reference, "Agent evidence workflow").read_text(
        encoding="utf-8"
    )
    required_workflow_fragments = (
        descriptor.get("ownerRepository"),
        revision,
        AGENT_EVIDENCE_ROOT_ENV,
        "uv sync --locked",
        "uv run pytest",
    )
    if any(not isinstance(fragment, str) or fragment not in workflow
           for fragment in required_workflow_fragments):
        fail("Agent PEP execution workflow is not pinned to the attested checkout and command")
    if any(node_id not in workflow for node_id in node_ids):
        fail("Agent PEP execution workflow does not execute every attested pytest node")
    return attestation, root


def python_test_function_names(source: str, reference: str) -> set[str]:
    try:
        tree = ast.parse(source)
    except SyntaxError as exception:
        fail(f"Agent pytest source is invalid for {reference}: {exception}")
    return {
        node.name
        for node in tree.body
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
        and node.name.startswith("test_")
    }


def validate_agent_pytest_reference(reference: str, descriptor: dict) -> None:
    relative_path, method = reference.split("#")
    test_root = descriptor.get("testRoot")
    if not isinstance(test_root, str) or not relative_path.startswith(f"{test_root}/"):
        fail(f"Agent owner-service evidence escapes the declared test root: {reference}")
    if not re.fullmatch(r"test_[a-zA-Z0-9_]+", method):
        fail(f"invalid Agent pytest function name: {reference}")
    attestation, root = validate_agent_attestation(descriptor)
    path = external_repository_path(root, relative_path, "Agent pytest")
    if method not in python_test_function_names(path.read_text(encoding="utf-8"), reference):
        fail(f"referenced Agent pytest function does not exist: {reference}")
    if relative_path not in {item["path"] for item in attestation["files"]}:
        fail(f"referenced Agent pytest file is not checksummed: {reference}")
    node_id = f"{relative_path}::{method}"
    if node_id not in attestation["pytestNodeIds"]:
        fail(f"referenced Agent pytest node is not executable evidence: {reference}")


def validate_test_reference(reference: object, owner_service: str | None = None) -> None:
    if not isinstance(reference, str) or reference.count("#") != 1:
        fail(f"invalid test reference {reference!r}")
    relative_path, method = reference.split("#")
    if owner_service == "dwp-agent-runtime":
        validate_agent_pytest_reference(reference, ROUTED_PRODUCT_PEPS["dwaion"])
        return
    if owner_service is not None and not relative_path.startswith(
            f"{owner_service}/src/test/"):
        fail(f"owner-service evidence escapes {owner_service}: {reference}")
    path = repository_path(relative_path, "test")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", method):
        fail(f"invalid Java test method name: {reference}")
    if not re.search(
            rf"\bvoid\s+{re.escape(method)}\s*\(", path.read_text(encoding="utf-8")):
        fail(f"referenced test method does not exist: {reference}")


def scrub_java_non_code(source: str) -> str:
    """Replace comments and literals while preserving indices for structural scans."""
    output = list(source)
    index = 0
    state = "CODE"
    while index < len(source):
        current = source[index]
        following = source[index + 1] if index + 1 < len(source) else ""
        if state == "CODE":
            if current == "/" and following == "/":
                output[index] = output[index + 1] = " "
                index += 2
                state = "LINE_COMMENT"
                continue
            if current == "/" and following == "*":
                output[index] = output[index + 1] = " "
                index += 2
                state = "BLOCK_COMMENT"
                continue
            if current == '"':
                output[index] = " "
                index += 1
                state = "STRING"
                continue
            if current == "'":
                output[index] = " "
                index += 1
                state = "CHAR"
                continue
        elif state == "LINE_COMMENT":
            if current == "\n":
                state = "CODE"
            else:
                output[index] = " "
            index += 1
            continue
        elif state == "BLOCK_COMMENT":
            if current == "*" and following == "/":
                output[index] = output[index + 1] = " "
                index += 2
                state = "CODE"
                continue
            if current != "\n":
                output[index] = " "
            index += 1
            continue
        elif state in {"STRING", "CHAR"}:
            output[index] = " " if current != "\n" else "\n"
            if current == "\\" and following:
                output[index + 1] = " " if following != "\n" else "\n"
                index += 2
                continue
            if (state == "STRING" and current == '"') \
                    or (state == "CHAR" and current == "'"):
                state = "CODE"
            index += 1
            continue
        index += 1
    return "".join(output)


def balanced_call_arguments(source: str, call_name: str) -> list[str]:
    scrubbed = scrub_java_non_code(source)
    marker = re.compile(rf"\.{re.escape(call_name)}\s*\(")
    arguments = []
    for match in marker.finditer(scrubbed):
        opening = scrubbed.find("(", match.start())
        depth = 0
        for index in range(opening, len(scrubbed)):
            character = scrubbed[index]
            if character == "(":
                depth += 1
            elif character == ")":
                depth -= 1
                if depth == 0:
                    arguments.append(scrubbed[opening + 1:index])
                    break
        else:
            fail(f"Java {call_name} invocation is not balanced")
    return arguments


def java_method_body(source: str, method: str, reference: str) -> str:
    scrubbed = scrub_java_non_code(source)
    declaration = re.search(
        rf"\bvoid\s+{re.escape(method)}\s*\([^)]*\)\s*(?:throws\s+[^{{]+)?{{",
        scrubbed,
    )
    if declaration is None:
        fail(f"referenced full-chain test method does not exist: {reference}")
    opening = scrubbed.find("{", declaration.start())
    depth = 0
    for index in range(opening, len(scrubbed)):
        if scrubbed[index] == "{":
            depth += 1
        elif scrubbed[index] == "}":
            depth -= 1
            if depth == 0:
                return scrubbed[opening + 1:index]
    fail(f"referenced full-chain test method is not balanced: {reference}")
    raise AssertionError("unreachable")


def validate_mock_mvc_chain(
        product_id: str, source: str, invariant: dict,
        generic_class: str, product_class: str) -> None:
    members = invariant.get("members")
    if not isinstance(members, dict) or not members:
        fail(f"{product_id} full-chain invariant has no filter members")
    if generic_class not in set(members.values()) or product_class not in set(members.values()):
        fail(f"{product_id} full-chain invariant omits the generic or product PEP filter")
    for variable, class_name in members.items():
        if not isinstance(variable, str) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", variable) \
                or not isinstance(class_name, str) \
                or not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", class_name):
            fail(f"{product_id} full-chain member declaration is invalid")
        if variable == class_name:
            constructor = rf"\bnew\s+{re.escape(class_name)}\s*\("
        else:
            constructor = (
                rf"\b{re.escape(class_name)}\s+{re.escape(variable)}\s*=\s*"
                rf"new\s+{re.escape(class_name)}\s*\("
            )
        if not re.search(constructor, scrub_java_non_code(source)):
            fail(f"{product_id} full-chain member {variable} is not an actual {class_name}")
    invocations = balanced_call_arguments(source, "addFilters")
    if not any(all(re.search(rf"\b{re.escape(member)}\b", arguments)
                       for member in members)
               for arguments in invocations):
        fail(f"{product_id} generic and product PEP filters are not in one request chain")


def validate_product_pep_closure(product_id: str, owner_service: str, descriptor: dict) -> None:
    if owner_service == "dwp-agent-runtime":
        validate_agent_attestation(descriptor)
        return
    generic_reference = descriptor.get("securityFilter")
    product_reference = descriptor.get("productPepFilter")
    full_chain_reference = descriptor.get("fullChainTestReference")
    invariant = descriptor.get("chainInvariant")
    if not all(isinstance(value, str) and value
               for value in (generic_reference, product_reference, full_chain_reference)) \
            or not isinstance(invariant, dict):
        fail(f"{product_id} complete evidence requires a product PEP and full-chain invariant")
    if not product_reference.startswith(f"{owner_service}/src/main/"):
        fail(f"{product_id} product PEP filter escapes {owner_service}")
    generic_path = repository_path(generic_reference, f"{product_id} SecurityFilter")
    product_path = repository_path(product_reference, f"{product_id} product PEP filter")
    generic_class = generic_path.stem
    product_class = product_path.stem
    product_source = product_path.read_text(encoding="utf-8")
    if not re.search(rf"\bclass\s+{re.escape(product_class)}\b", product_source):
        fail(f"{product_id} product PEP filter class does not exist")
    component = descriptor.get("productPepComponent")
    if generic_path == product_path:
        if not isinstance(component, str) or not re.fullmatch(
                r"[A-Za-z][A-Za-z0-9]*", component):
            fail(f"{product_id} integrated filter requires an explicit product PEP component")
        if not re.search(rf"\b{re.escape(component)}\b", product_source):
            fail(f"{product_id} integrated filter is not bound to {component}")
    elif generic_class == "PlatformSecurityFilter" and product_class == generic_class:
        fail(f"{product_id} cannot use generic PlatformSecurityFilter as its product PEP")

    validate_test_reference(full_chain_reference, owner_service)
    test_relative, method = full_chain_reference.split("#")
    test_source = repository_path(test_relative, f"{product_id} full-chain test").read_text(
        encoding="utf-8"
    )
    method_body = java_method_body(test_source, method, full_chain_reference)
    request_harness = invariant.get("requestHarness")
    if not isinstance(request_harness, str) or not re.search(
            rf"\b{re.escape(request_harness)}\s*(?:\.|\()", method_body):
        fail(f"{product_id} full-chain test does not execute its declared request harness")
    kind = invariant.get("kind")
    if kind == "MOCK_MVC_FILTER_CHAIN":
        validate_mock_mvc_chain(
            product_id, test_source, invariant, generic_class, product_class)
    elif kind == "INTEGRATED_FILTER":
        variable = invariant.get("filterVariable")
        if not isinstance(variable, str) or not re.search(
                rf"\b{re.escape(generic_class)}\s+{re.escape(variable)}\s*=\s*"
                rf"new\s+{re.escape(generic_class)}\s*\(",
                scrub_java_non_code(test_source)):
            fail(f"{product_id} integrated product PEP filter is not constructed")
        if not re.search(rf"\b{re.escape(variable)}\.doFilter\s*\(",
                         scrub_java_non_code(test_source)):
            fail(f"{product_id} integrated product PEP filter is not executed")
    else:
        fail(f"{product_id} full-chain invariant kind is invalid")


def gateway_route_block(source: str, route_id: str) -> str:
    lines = source.splitlines()
    marker = re.compile(rf"^(\s*)- id:\s*{re.escape(route_id)}\s*$")
    start = None
    indentation = None
    for index, line in enumerate(lines):
        match = marker.match(line)
        if match:
            start = index
            indentation = match.group(1)
            break
    if start is None or indentation is None:
        fail(f"Gateway route {route_id} does not exist")
    next_route = re.compile(rf"^{re.escape(indentation)}- id:\s*")
    end = len(lines)
    for index in range(start + 1, len(lines)):
        if next_route.match(lines[index]):
            end = index
            break
    return "\n".join(lines[start:end])


def owner_service_from_gateway_route(route_id: str, block: str) -> str:
    uri = re.search(r"\buri:\s*\$\{SERVICE_([A-Z0-9_]+)_URL:", block)
    if uri is None:
        fail(f"Gateway route {route_id} must target a SERVICE_*_URL")
    service_key = uri.group(1).lower().replace("_", "-")
    if service_key == "agent":
        return "dwp-agent-runtime"
    return f"dwp-{service_key}-server"


def validate_routed_owner_services(products: list[dict]) -> dict[str, str]:
    gateway_config = repository_path(GATEWAY_CONFIG_REFERENCE, "Gateway routing").read_text(
        encoding="utf-8"
    )
    products_by_id = {
        product.get("productId"): product for product in products if isinstance(product, dict)
    }
    resolved: dict[str, str] = {}
    for product_id, descriptor in ROUTED_PRODUCT_PEPS.items():
        route_owners = set()
        for route_id, expected_path in descriptor["routes"].items():
            block = gateway_route_block(gateway_config, route_id)
            if not re.search(
                    rf"^\s*- Path={re.escape(expected_path)}\s*$", block, re.MULTILINE):
                fail(f"Gateway route {route_id} no longer owns {expected_path}")
            route_owners.add(owner_service_from_gateway_route(route_id, block))
        if len(route_owners) != 1:
            fail(f"{product_id} Gateway routes disagree on owner service")
        owner_service = route_owners.pop()
        expected_owner = descriptor.get("ownerService", owner_service)
        if owner_service != expected_owner:
            fail(
                f"{product_id} Gateway route resolves to {owner_service}, "
                f"not canonical owner {expected_owner}"
            )
        security_filter = descriptor.get("securityFilter")
        if security_filter is not None:
            security_filter = str(security_filter)
            if not security_filter.startswith(f"{owner_service}/src/main/"):
                fail(f"{product_id} SecurityFilter does not belong to {owner_service}")
            filter_path = repository_path(security_filter, f"{product_id} SecurityFilter")
            if not re.search(
                    r"\bclass\s+\w*SecurityFilter\b",
                    filter_path.read_text(encoding="utf-8")):
                fail(f"{product_id} owner service has no SecurityFilter class")
        elif descriptor.get("ownerRepository") != (
                "https://github.com/choijoonbin/aura_agent"):
            fail(f"{product_id} non-Java owner repository is not canonical")
        product = products_by_id.get(product_id)
        if product is None or product.get("ownerService") != owner_service:
            actual = product.get("ownerService") if product is not None else None
            fail(
                f"{product_id} ownerService must match Gateway routing owner "
                f"{owner_service}, got {actual!r}"
            )
        resolved[product_id] = owner_service
    return resolved


def validate_runtime_required_kinds() -> None:
    source = repository_path(
        RUNTIME_CANDIDATE_CATALOG_REFERENCE, "runtime candidate catalog"
    ).read_text(encoding="utf-8")
    literal = re.search(
        r"\bREQUIRED_ROUTE_KINDS\s*=\s*Set\.of\(([^;]+)\);", source, re.DOTALL
    )
    if literal is None:
        fail("runtime candidate catalog has no required route-kind invariant")
    values = re.findall(r'"([A-Z]+)"', literal.group(1))
    if len(values) != len(set(values)) or set(values) != REQUIRED_ROUTE_KINDS:
        fail("runtime candidate catalog required route kinds drifted")
    required_fragments = (
        'if ("ACTIVE".equals(lifecycle))',
        "activeProductRoutes.add(new ActiveProductRoute",
        '"PAGE".equals(route.routeKind())',
        ".containsAll(REQUIRED_ROUTE_KINDS)",
    )
    if any(fragment not in source for fragment in required_fragments):
        fail("runtime candidate catalog no longer gates active pages by required route kinds")


def product_route_kinds(contract: dict) -> dict[str, set[str]]:
    products: dict[str, set[str]] = {}
    routes = contract.get("routes")
    if not isinstance(routes, list) or not routes:
        fail("exact contract routes are unavailable")
    for route in routes:
        if not isinstance(route, dict):
            fail("exact contract route must be an object")
        subject = route.get("subject")
        if isinstance(subject, dict) and subject.get("type") == "PRODUCT":
            product = subject.get("productKey")
            if not isinstance(product, str) or not product:
                fail("exact product route has no productKey")
            route_kind = route.get("routeKind")
            if route_kind not in REQUIRED_ROUTE_KINDS:
                fail(f"{product} exact product route has invalid routeKind {route_kind!r}")
            products.setdefault(product, set()).add(route_kind)
    if not products:
        fail("exact contract has no product routes")
    return products


def contract_statuses(
        expected_products: set[str], route_kinds: dict[str, set[str]]) -> dict[str, str]:
    unknown_products = set(route_kinds) - expected_products
    if unknown_products:
        fail(f"exact contract has products outside rollout inventory: {sorted(unknown_products)}")
    return {
        product_id: (
            "MISSING" if product_id not in route_kinds
            else "EXACT" if route_kinds[product_id] == REQUIRED_ROUTE_KINDS
            else "INCOMPLETE_KINDS"
        )
        for product_id in expected_products
    }


def derived_completion_state(
        statuses: dict[str, str], missing_count: int, blocker_count: int) -> str:
    complete = (
        bool(statuses)
        and set(statuses.values()) == {"EXACT"}
        and missing_count == 0
        and blocker_count == 0
    )
    return "COMPLETE" if complete else "PARTIAL"


def validate_completion_state(
        declared: object,
        statuses: dict[str, str],
        missing_count: int,
        blocker_count: int) -> str:
    if declared not in {"PARTIAL", "COMPLETE"}:
        fail(f"completionState must be PARTIAL or COMPLETE, got {declared!r}")
    expected = derived_completion_state(statuses, missing_count, blocker_count)
    if declared != expected:
        fail(f"completionState must be {expected} for the derived closure state")
    return expected


def validate_canonical_fixtures(matrix: dict) -> None:
    evidence = matrix.get("canonicalNegativeFixtures")
    if not isinstance(evidence, dict) or set(evidence) != {
            "reference", "checksum", "expectedCount", "catalogIntegrityTestReferences"}:
        fail("canonical negative fixture evidence field set is invalid")
    fixture = read_json(repository_path(evidence.get("reference"), "fixture"))
    if evidence.get("checksum") != fixture.get("fixtureChecksum") \
            or evidence.get("checksum") != checksum(fixture, "fixtureChecksum"):
        fail("canonical negative fixture checksum drift")
    negatives = fixture.get("negativeCases")
    if evidence.get("expectedCount") != 46 or not isinstance(negatives, list) \
            or len(negatives) != 46:
        fail("canonical negative fixture count must remain exactly 46")
    fixture_ids = []
    for item in negatives:
        if not isinstance(item, dict) or set(item) != {"fixtureId", "input", "expected"}:
            fail("canonical negative fixture shape is invalid")
        fixture_id = item.get("fixtureId")
        if not isinstance(fixture_id, str) or not fixture_id.startswith("FX-N-") \
                or not isinstance(item.get("input"), str) or not item.get("input") \
                or not isinstance(item.get("expected"), str) or not item.get("expected"):
            fail(f"canonical negative fixture is invalid: {fixture_id!r}")
        fixture_ids.append(fixture_id)
    if len(set(fixture_ids)) != 46:
        fail("canonical negative fixture IDs must be unique")
    references = evidence.get("catalogIntegrityTestReferences")
    if not isinstance(references, list) or not references:
        fail("canonical negative fixtures require a catalog-integrity test")
    for reference in references:
        validate_test_reference(reference)


def main() -> None:
    matrix = read_json(MATRIX_PATH)
    if set(matrix) != EXPECTED_MATRIX_FIELDS:
        fail("matrix field set is invalid")
    if matrix.get("schemaVersion") != 1 or matrix.get("matrixId") != (
            "product-authorization-negative-matrix.v1"):
        fail("schema identity drift")

    inventory_binding = matrix.get("rolloutInventory")
    if not isinstance(inventory_binding, dict) or set(inventory_binding) != {
            "reference", "checksum"}:
        fail("rollout inventory binding is invalid")
    inventory = read_json(repository_path(inventory_binding.get("reference"), "inventory"))
    if inventory_binding.get("checksum") != inventory.get("checksum") \
            or inventory_binding.get("checksum") != checksum(
                inventory, "checksum", "bundleStatus"):
        fail("rollout inventory checksum drift")
    inventory_products = inventory.get("products")
    if not isinstance(inventory_products, list) or len(inventory_products) != 12 \
            or len(set(inventory_products)) != 12:
        fail("rollout inventory must contain exactly 12 unique products")
    expected_products = set(inventory_products)

    contract_binding = matrix.get("exactContract")
    if not isinstance(contract_binding, dict) or set(contract_binding) != {
            "reference", "checksum", "products"}:
        fail("exact contract binding is invalid")
    contract = read_json(repository_path(contract_binding.get("reference"), "exact contract"))
    if contract_binding.get("checksum") != contract.get("checksum") \
            or contract_binding.get("checksum") != checksum(
                contract, "checksum", "bundleStatus"):
        fail("exact contract checksum drift")
    bound_contract_products = contract_binding.get("products")
    route_kinds = product_route_kinds(contract)
    contract_products = set(route_kinds)
    statuses = contract_statuses(expected_products, route_kinds)
    validate_runtime_required_kinds()
    if not isinstance(bound_contract_products, list) \
            or set(bound_contract_products) != contract_products \
            or len(bound_contract_products) != len(contract_products):
        fail("contract product set must be derived from the bound contract")

    validate_canonical_fixtures(matrix)

    guard = matrix.get("missingContractGuard")
    if not isinstance(guard, dict) or set(guard) != {
            "contractStatus", "rolloutCeiling", "testReferences"} \
            or guard.get("contractStatus") != "MISSING" \
            or guard.get("rolloutCeiling") != "100":
        fail("missing-contract guard must cap rollout at 100")
    guard_references = guard.get("testReferences")
    if not isinstance(guard_references, list) or len(guard_references) < 2:
        fail("missing-contract guard requires runtime fail-closed tests")
    for reference in guard_references:
        validate_test_reference(reference)

    vectors = matrix.get("attackVectors")
    if not isinstance(vectors, list) or len(vectors) != len(VECTOR_IDS) \
            or {item.get("id") for item in vectors if isinstance(item, dict)} != VECTOR_IDS:
        fail("attack vector set must be exact")
    for vector in vectors:
        if not isinstance(vector, dict) or set(vector) != {"id", "gatewayTestReferences"}:
            fail("attack vector field set is invalid")
        references = vector.get("gatewayTestReferences")
        if not isinstance(references, list) or not references:
            fail(f"{vector.get('id')} requires Gateway PEP evidence")
        for reference in references:
            validate_test_reference(reference, "dwp-gateway")

    products = matrix.get("products")
    if not isinstance(products, list) or len(products) != len(expected_products) \
            or {item.get("productId") for item in products if isinstance(item, dict)} \
            != expected_products:
        fail("product set must exactly match the checksummed rollout inventory")
    validate_routed_owner_services(products)

    missing_count = 0
    missing_contract_count = 0
    incomplete_kind_count = 0
    blocker_count = 0
    for product in products:
        if not isinstance(product, dict) or set(product) != EXPECTED_PRODUCT_FIELDS:
            fail("product evidence field set is invalid")
        product_id = product.get("productId")
        expected_status = statuses[product_id]
        if product.get("contractStatus") != expected_status:
            fail(f"{product_id} contract status must be {expected_status}")
        if expected_status not in CONTRACT_STATUSES:
            fail(f"{product_id} contract status is invalid")
        owner_service = product.get("ownerService")
        if not isinstance(owner_service, str) or not owner_service.startswith("dwp-"):
            fail(f"{product_id} ownerService is invalid")
        evidence = product.get("attackEvidence")
        missing = product.get("missingAttackIds")
        if not isinstance(evidence, dict) or not isinstance(missing, list) \
                or len(missing) != len(set(missing)):
            fail(f"{product_id} attack evidence is invalid")
        proven = set(evidence)
        missing_set = set(missing)
        if proven & missing_set or proven | missing_set != VECTOR_IDS:
            fail(f"{product_id} must classify every vector exactly once")
        for vector_id, references in evidence.items():
            if vector_id not in VECTOR_IDS or not isinstance(references, list) or not references:
                fail(f"{product_id} {vector_id} requires owner-service PEP evidence")
            for reference in references:
                validate_test_reference(reference, owner_service)
        if not missing_set:
            descriptor = ROUTED_PRODUCT_PEPS.get(product_id)
            if not isinstance(descriptor, dict):
                fail(f"{product_id} complete evidence has no routed product PEP descriptor")
            validate_product_pep_closure(product_id, owner_service, descriptor)
        if expected_status == "MISSING":
            missing_contract_count += 1
            if evidence or missing_set != VECTOR_IDS or product.get("rolloutCeiling") != "100":
                fail(f"{product_id} missing contract must stay unproven and <=100")
        elif expected_status == "INCOMPLETE_KINDS":
            incomplete_kind_count += 1
            if product.get("rolloutCeiling") != "100":
                fail(f"{product_id} incomplete route kinds must stay <=100")
        elif product.get("rolloutCeiling") != "111":
            fail(f"{product_id} exact contract must use the explicit pilot ceiling")
        missing_count += len(missing_set)
        blocker = product.get("blocker")
        if missing_set or expected_status != "EXACT":
            if not isinstance(blocker, str) or not blocker.startswith("MISSING_"):
                fail(f"{product_id} incomplete closure requires a MISSING_ blocker")
            blocker_count += 1
        elif blocker is not None:
            fail(f"{product_id} has no missing vector and cannot retain a blocker")

    if missing_contract_count != sum(status == "MISSING" for status in statuses.values()):
        fail("missing-contract product count drift")
    if incomplete_kind_count != sum(
            status == "INCOMPLETE_KINDS" for status in statuses.values()):
        fail("incomplete-kind product count drift")
    completion_state = validate_completion_state(
        matrix.get("completionState"), statuses, missing_count, blocker_count)
    exact_count = sum(status == "EXACT" for status in statuses.values())
    print(
        "Authorization negative matrix valid: "
        f"46 canonical fixture records integrity-validated, 5 vectors, 12 products "
        f"({exact_count} EXACT, {incomplete_kind_count} INCOMPLETE_KINDS, "
        f"{missing_contract_count} MISSING), {missing_count} missing product-vector cells, "
        f"state {completion_state}"
    )


if __name__ == "__main__":
    main()
