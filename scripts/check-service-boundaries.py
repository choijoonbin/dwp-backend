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
}
SIGNED_WORKLOAD_FORBIDDEN = PURPOSE_TOKEN_HEADERS | {
    "/api/", "X-DWP-Service-Token", "X-DWP-Service-Identity", "X-DWP-Product-Surface-Token",
    "Authorization", "@Retry",
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
                    if "signedWorkload" not in entry and not PURPOSE_TOKEN_HEADERS.intersection(required_markers):
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
            if "signedWorkload" in contract:
                # Validate registered signed clients below even if their HTTP import disappears.
                continue
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
        elif "signedWorkload" in allowed_clients[relative]:
            contract = allowed_clients[relative]
            source = java_without_comments((ROOT / relative).read_text(encoding="utf-8"))
            if not HTTP_CLIENT_IMPORT_RE.search(JAVA_NON_CODE_RE.sub(" ", source)):
                violations.append(f"{relative} signedWorkload requires an executable HTTP client import")
            violations.extend(signed_workload_source_violations(contract, source))
            for required in string_entries(contract["requiredMarkers"]):
                if required not in source:
                    violations.append(f"{relative} signedWorkload is missing required contract marker {required!r}")
            for forbidden in string_entries(contract["forbiddenMarkers"]):
                if forbidden in source:
                    violations.append(f"{relative} signedWorkload contains forbidden marker {forbidden!r}")
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
