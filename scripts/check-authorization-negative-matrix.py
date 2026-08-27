#!/usr/bin/env python3
"""Fail-closed validation for the X-03 authorization negative-test inventory."""

from __future__ import annotations

import hashlib
import json
import re
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
ROUTED_PRODUCT_PEPS = {
    "notifications": {
        "routes": {
            "notification-stream": "/api/notifications/v1/stream",
            "notification-server": "/api/notifications/v1/**",
        },
        "securityFilter": (
            "dwp-notification-server/src/main/java/com/dwp/services/notification/"
            "security/NotificationSecurityFilter.java"
        ),
    },
    "spaces": {
        "routes": {"space-server": "/api/spaces/**"},
        "securityFilter": (
            "dwp-space-server/src/main/java/com/dwp/services/space/security/"
            "SpaceSecurityFilter.java"
        ),
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


def validate_test_reference(reference: object, owner_service: str | None = None) -> None:
    if not isinstance(reference, str) or reference.count("#") != 1:
        fail(f"invalid test reference {reference!r}")
    relative_path, method = reference.split("#")
    if owner_service is not None and not relative_path.startswith(
            f"{owner_service}/src/test/"):
        fail(f"owner-service evidence escapes {owner_service}: {reference}")
    path = repository_path(relative_path, "test")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", method):
        fail(f"invalid Java test method name: {reference}")
    if not re.search(
            rf"\bvoid\s+{re.escape(method)}\s*\(", path.read_text(encoding="utf-8")):
            fail(f"referenced test method does not exist: {reference}")


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
        security_filter = str(descriptor["securityFilter"])
        if not security_filter.startswith(f"{owner_service}/src/main/"):
            fail(f"{product_id} SecurityFilter does not belong to {owner_service}")
        filter_path = repository_path(security_filter, f"{product_id} SecurityFilter")
        if not re.search(r"\bclass\s+\w*SecurityFilter\b", filter_path.read_text(encoding="utf-8")):
            fail(f"{product_id} owner service has no SecurityFilter class")
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
