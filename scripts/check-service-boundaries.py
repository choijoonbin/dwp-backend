#!/usr/bin/env python3

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

SERVICE_PACKAGES = {
    "dwp-auth-server": "com.dwp.services.auth",
    "dwp-platform-server": "com.dwp.services.platform",
    "dwp-people-server": "com.dwp.services.people",
    "dwp-provider-server": "com.dwp.services.provider",
    "dwp-approval-server": "com.dwp.services.approval",
    "dwp-gateway": "com.dwp.gateway",
}

SHARED_MODULES = {
    "dwp-core",
    "dwp-audit",
    "dwp-observability",
    "dwp-platform-contracts",
}

IMPORT_RE = re.compile(r"^\s*import\s+([^;]+);", re.MULTILINE)
PROJECT_DEP_RE = re.compile(r"^\s*(?:api|implementation|compileOnly|runtimeOnly)\s+project\(['\"]:([^'\"]+)['\"]\)", re.MULTILINE)
HTTP_CLIENT_IMPORT_RE = re.compile(
    r"import\s+org\.springframework\.(?:web\.client\.RestClient|web\.reactive\.function\.client\.WebClient);"
)
APP_YML_CROSS_DB_RE = re.compile(
    r"\$\{(?P<db>AUTH|PLATFORM|PEOPLE|PROVIDER)_DB_NAME:"
)

ALLOWED_HTTP_CLIENTS = {
    Path("dwp-gateway/src/main/java/com/dwp/gateway/security/AuthSessionVerifier.java"): {
        "classification": "gateway-session-verification",
        "required": [
            "${SERVICE_AUTH_URL:http://localhost:8001}",
            "/auth/me",
        ],
        "forbidden": [
            "X-DWP-Service-Token",
        ],
    },
    Path("dwp-gateway/src/main/java/com/dwp/gateway/security/ProviderSupportSessionVerifier.java"): {
        "classification": "gateway-support-session-verification",
        "required": [
            "${SERVICE_PROVIDER_URL:http://localhost:8004}",
            "/v1/internal/support-access/resolve",
            "X-DWP-Service-Token",
            "X-DWP-Support-Validation-Token",
        ],
        "forbidden": [
            "/api/",
        ],
    },
    Path("dwp-provider-server/src/main/java/com/dwp/services/provider/provisioning/DownstreamProvisioningClient.java"): {
        "classification": "provider-tenant-provisioning",
        "required": [
            "${dwp.services.auth-url:http://localhost:8001}",
            "${dwp.services.platform-url:http://localhost:8002}",
            "${dwp.services.people-url:http://localhost:8003}",
            "/internal/provider/v1/",
            "X-DWP-Provisioning-Token",
        ],
        "forbidden": [
            "/api/",
            "X-DWP-Service-Token",
            "X-DWP-Identity-Sync-Token",
        ],
    },
    Path("dwp-provider-server/src/main/java/com/dwp/services/provider/codecatalog/ProductCatalogClient.java"): {
        "classification": "provider-code-contract-catalog",
        "required": [
            "${dwp.services.platform-url:http://localhost:8002}",
            "/internal/provider/v1/code-catalog/",
            "X-DWP-Provisioning-Token",
        ],
        "forbidden": [
            "/api/",
            "X-DWP-Service-Token",
            "X-DWP-Identity-Sync-Token",
        ],
    },
    Path("dwp-platform-server/src/main/java/com/dwp/services/platform/workspace/AuthAppEntitlementProvisioner.java"): {
        "classification": "identity-entitlement-sync",
        "required": [
            "${dwp.identity-sync.auth-url:http://localhost:8001}",
            "/internal/identity/v1/",
            "X-DWP-Identity-Sync-Token",
        ],
        "forbidden": [
            "/api/",
            "X-DWP-Service-Token",
            "X-DWP-Provisioning-Token",
        ],
    },
    Path("dwp-platform-server/src/main/java/com/dwp/services/platform/savedview/AuthSavedViewSubjectDirectory.java"): {
        "classification": "identity-subject-validation",
        "required": [
            "${dwp.identity-sync.auth-url:http://localhost:8001}",
            "/internal/identity/v1/",
            "X-DWP-Identity-Sync-Token",
        ],
        "forbidden": [
            "/api/",
            "X-DWP-Service-Token",
            "X-DWP-Provisioning-Token",
        ],
    },
    Path("dwp-people-server/src/main/java/com/dwp/services/people/identity/IdentitySyncClient.java"): {
        "classification": "workforce-identity-sync",
        "required": [
            "${dwp.identity-sync.auth-url:http://localhost:8001}",
            "/internal/identity/v1/",
            "X-DWP-Identity-Sync-Token",
        ],
        "forbidden": [
            "/api/",
            "X-DWP-Service-Token",
            "X-DWP-Provisioning-Token",
        ],
    },
    Path("dwp-platform-server/src/main/java/com/dwp/services/platform/productivity/MicrosoftGraphClient.java"): {
        "classification": "external-productivity-connector",
        "required": [
            "login.microsoftonline.com",
            "graph.microsoft.com",
            "trustedBase",
            "requireHost",
        ],
        "forbidden": [
            "SERVICE_AUTH_URL",
            "SERVICE_PLATFORM_URL",
            "SERVICE_PEOPLE_URL",
            "SERVICE_PROVIDER_URL",
            "SERVICE_APPROVAL_URL",
            "X-DWP-Service-Token",
        ],
    },
    Path("dwp-people-server/src/main/java/com/dwp/services/people/integration/WorkdayRestAdapter.java"): {
        "classification": "external-hris-connector",
        "required": [
            "dwp.people.hris.allowed-hosts",
            "dwp.people.hris.allow-unlisted-hosts:false",
            "requireAllowed",
        ],
        "forbidden": [
            "SERVICE_AUTH_URL",
            "SERVICE_PLATFORM_URL",
            "SERVICE_PEOPLE_URL",
            "SERVICE_PROVIDER_URL",
            "SERVICE_APPROVAL_URL",
            "X-DWP-Service-Token",
        ],
    },
}

ALLOWED_CROSS_DB_CONFIGS = {
    Path("dwp-provider-server/src/main/resources/application.yml"): {
        "allowed_databases": {"AUTH", "PLATFORM", "PEOPLE", "PROVIDER"},
        "required": [
            "dwp:",
            "data-governance:",
            "sources:",
            "${DWP_METADATA_DB_USERNAME:${DB_USERNAME:dwp_user}}",
            "${DWP_METADATA_DB_PASSWORD:${DB_PASSWORD:dwp_password}}",
        ],
    }
}

DATA_GOVERNANCE_SCANNER = Path(
    "dwp-provider-server/src/main/java/com/dwp/services/provider/governance/DataGovernanceScanner.java"
)


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


def http_client_policy_violations() -> list[str]:
    violations: list[str] = []
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
            policy = ALLOWED_HTTP_CLIENTS.get(relative)
            if policy is None:
                violations.append(
                    f"{relative} creates an HTTP client but is not in the service-interface allowlist"
                )
                continue
            for required in policy["required"]:
                if required not in source:
                    violations.append(
                        f"{relative} ({policy['classification']}) is missing required contract marker {required!r}"
                    )
            for forbidden in policy["forbidden"]:
                if forbidden in source:
                    violations.append(
                        f"{relative} ({policy['classification']}) contains forbidden marker {forbidden!r}"
                    )
    for relative in sorted(ALLOWED_HTTP_CLIENTS):
        if not (ROOT / relative).exists():
            violations.append(f"{relative} is registered in the HTTP client allowlist but does not exist")
    return violations


def cross_database_policy_violations() -> list[str]:
    violations: list[str] = []
    for application_file in sorted(ROOT.glob("*/src/main/resources/application.yml")):
        source = application_file.read_text(encoding="utf-8")
        relative = application_file.relative_to(ROOT)
        matches = {match.group("db") for match in APP_YML_CROSS_DB_RE.finditer(source)}
        if not matches:
            continue
        module = relative.parts[0]
        own_prefix = {
            "dwp-auth-server": "AUTH",
            "dwp-platform-server": "PLATFORM",
            "dwp-people-server": "PEOPLE",
            "dwp-provider-server": "PROVIDER",
        }.get(module)
        cross_database_refs = matches - ({own_prefix} if own_prefix else set())
        if not cross_database_refs:
            continue
        policy = ALLOWED_CROSS_DB_CONFIGS.get(relative)
        if policy is None:
            violations.append(
                f"{relative} references other service databases {sorted(cross_database_refs)} without an allowlist entry"
            )
            continue
        disallowed = cross_database_refs - policy["allowed_databases"]
        if disallowed:
            violations.append(
                f"{relative} references disallowed service databases {sorted(disallowed)}"
            )
        for required in policy["required"]:
            if required not in source:
                violations.append(
                    f"{relative} cross-database exception is missing required marker {required!r}"
                )

    scanner = ROOT / DATA_GOVERNANCE_SCANNER
    if not scanner.exists():
        violations.append(f"{DATA_GOVERNANCE_SCANNER} is required for the data-governance metadata exception")
    else:
        source = scanner.read_text(encoding="utf-8")
        required = [
            "connection.setReadOnly(true)",
            "pg_class",
            "pg_namespace",
            "pg_constraint",
            "pg_index",
        ]
        for marker in required:
            if marker not in source:
                violations.append(
                    f"{DATA_GOVERNANCE_SCANNER} metadata exception is missing required marker {marker!r}"
                )
        forbidden = [
            "INSERT INTO",
            "UPDATE ",
            "DELETE FROM",
            "MERGE ",
            "TRUNCATE ",
            "ALTER ",
            "DROP ",
            "CREATE ",
        ]
        for marker in forbidden:
            if marker in source:
                violations.append(
                    f"{DATA_GOVERNANCE_SCANNER} metadata scanner contains forbidden write/DDL marker {marker!r}"
                )
    return violations


def main() -> int:
    violations = (
        java_import_violations()
        + gradle_dependency_violations()
        + http_client_policy_violations()
        + cross_database_policy_violations()
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
        "PASS service boundaries: backend modules, direct service HTTP clients, and cross-database metadata exceptions match policy."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
