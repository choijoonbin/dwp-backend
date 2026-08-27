import copy
import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
CHECKER_PATH = ROOT / "scripts/check-authorization-negative-matrix.py"
SPEC = importlib.util.spec_from_file_location("authorization_negative_matrix", CHECKER_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Cannot load {CHECKER_PATH}")
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class AuthorizationNegativeMatrixTest(unittest.TestCase):
    def setUp(self) -> None:
        self.matrix = CHECKER.read_json(CHECKER.MATRIX_PATH)

    def test_gateway_routes_bind_independent_owner_services(self) -> None:
        resolved = CHECKER.validate_routed_owner_services(self.matrix["products"])

        self.assertEqual(
            resolved,
            {
                "notifications": "dwp-notification-server",
                "spaces": "dwp-space-server",
            },
        )

    def test_platform_owner_is_rejected_for_independently_routed_products(self) -> None:
        for product_id in ("notifications", "spaces"):
            with self.subTest(product_id=product_id):
                products = copy.deepcopy(self.matrix["products"])
                product = next(item for item in products if item["productId"] == product_id)
                product["ownerService"] = "dwp-platform-server"

                with self.assertRaisesRegex(
                    SystemExit,
                    rf"{product_id} ownerService must match Gateway routing owner dwp-",
                ):
                    CHECKER.validate_routed_owner_services(products)

    def test_platform_test_evidence_cannot_escape_the_owner_service(self) -> None:
        for owner_service in ("dwp-notification-server", "dwp-space-server"):
            with self.subTest(owner_service=owner_service):
                reference = (
                    "dwp-platform-server/src/test/java/com/dwp/services/platform/security/"
                    "PlatformSecurityFilterTest.java#rejectsDirectRequestsWithoutGatewayServiceIdentity"
                )

                with self.assertRaisesRegex(
                    SystemExit,
                    rf"owner-service evidence escapes {owner_service}",
                ):
                    CHECKER.validate_test_reference(reference, owner_service)

    def test_owner_scoped_test_evidence_path_is_allowed(self) -> None:
        for owner_service, package_name, class_name in (
            (
                "dwp-notification-server",
                "com/dwp/services/notification/security",
                "NotificationSecurityFilterTest",
            ),
            (
                "dwp-space-server",
                "com/dwp/services/space/security",
                "SpaceSecurityFilterTest",
            ),
        ):
            with self.subTest(owner_service=owner_service), tempfile.TemporaryDirectory() as directory:
                temporary_root = Path(directory).resolve()
                relative_path = (
                    f"{owner_service}/src/test/java/{package_name}/{class_name}.java"
                )
                test_path = temporary_root / relative_path
                test_path.parent.mkdir(parents=True)
                test_path.write_text(
                    f"class {class_name} {{ void rejectsSpoofedIdentity() {{ }} }}\n",
                    encoding="utf-8",
                )

                with patch.object(CHECKER, "ROOT", temporary_root):
                    CHECKER.validate_test_reference(
                        f"{relative_path}#rejectsSpoofedIdentity", owner_service
                    )

    def test_catalog_integrity_contract_rejects_the_legacy_execution_name(self) -> None:
        CHECKER.validate_canonical_fixtures(self.matrix)
        legacy = copy.deepcopy(self.matrix)
        catalog = legacy["canonicalNegativeFixtures"]
        catalog["executionTestReferences"] = catalog.pop("catalogIntegrityTestReferences")

        with self.assertRaisesRegex(
            SystemExit, "canonical negative fixture evidence field set is invalid"
        ):
            CHECKER.validate_canonical_fixtures(legacy)

    def test_contract_status_requires_page_data_and_action_kinds(self) -> None:
        binding = self.matrix["exactContract"]
        contract = CHECKER.read_json(
            CHECKER.repository_path(binding["reference"], "exact contract")
        )
        kinds = CHECKER.product_route_kinds(contract)
        statuses = CHECKER.contract_statuses(
            {product["productId"] for product in self.matrix["products"]}, kinds
        )

        self.assertEqual(
            {
                kind: sum(status == kind for status in statuses.values())
                for kind in CHECKER.CONTRACT_STATUSES
            },
            {"EXACT": 2, "INCOMPLETE_KINDS": 2, "MISSING": 8},
        )
        self.assertEqual(kinds["communications"], {"PAGE", "ACTION"})
        self.assertEqual(kinds["services"], {"PAGE", "ACTION"})
        self.assertEqual(statuses["communications"], "INCOMPLETE_KINDS")
        self.assertEqual(statuses["services"], "INCOMPLETE_KINDS")

    def test_runtime_candidate_catalog_is_bound_to_the_same_required_kinds(self) -> None:
        CHECKER.validate_runtime_required_kinds()

        valid_projection = """
            private static final Set<String> REQUIRED_ROUTE_KINDS =
                    Set.of("PAGE", "DATA", "ACTION");
            if ("ACTIVE".equals(lifecycle)) {
                activeProductRoutes.add(new ActiveProductRoute(product, surface, kind));
            }
            if ("PAGE".equals(route.routeKind())
                    && kinds.containsAll(REQUIRED_ROUTE_KINDS)) { }
        """
        for label, source in (
            ("kind-set", valid_projection.replace(', "DATA"', "")),
            ("candidate-gate", valid_projection.replace(".containsAll", ".contains")),
        ):
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                temporary_root = Path(directory).resolve()
                runtime_path = temporary_root / CHECKER.RUNTIME_CANDIDATE_CATALOG_REFERENCE
                runtime_path.parent.mkdir(parents=True)
                runtime_path.write_text(source, encoding="utf-8")

                with patch.object(CHECKER, "ROOT", temporary_root), self.assertRaisesRegex(
                    SystemExit, "runtime candidate catalog"
                ):
                    CHECKER.validate_runtime_required_kinds()

    def test_unknown_route_kind_is_rejected(self) -> None:
        binding = self.matrix["exactContract"]
        contract = CHECKER.read_json(
            CHECKER.repository_path(binding["reference"], "exact contract")
        )
        contract["routes"][0]["routeKind"] = "FUTURE_KIND"

        with self.assertRaisesRegex(SystemExit, "invalid routeKind"):
            CHECKER.product_route_kinds(contract)

    def test_completion_state_is_derived_in_both_directions(self) -> None:
        current_statuses = {
            product["productId"]: product["contractStatus"]
            for product in self.matrix["products"]
        }
        all_exact = {product_id: "EXACT" for product_id in current_statuses}

        self.assertEqual(
            CHECKER.validate_completion_state("PARTIAL", current_statuses, 47, 12),
            "PARTIAL",
        )
        self.assertEqual(
            CHECKER.validate_completion_state("COMPLETE", all_exact, 0, 0),
            "COMPLETE",
        )
        with self.assertRaisesRegex(SystemExit, "completionState must be PARTIAL"):
            CHECKER.validate_completion_state("COMPLETE", current_statuses, 47, 12)
        with self.assertRaisesRegex(SystemExit, "completionState must be COMPLETE"):
            CHECKER.validate_completion_state("PARTIAL", all_exact, 0, 0)

    def test_semantically_unqualified_scope_evidence_remains_missing(self) -> None:
        products = {product["productId"]: product for product in self.matrix["products"]}

        for product_id in ("communications", "hcm", "services"):
            with self.subTest(product_id=product_id):
                self.assertNotIn("SCOPE_ESCAPE", products[product_id]["attackEvidence"])
                self.assertIn("SCOPE_ESCAPE", products[product_id]["missingAttackIds"])
        self.assertEqual(
            sum(len(product["missingAttackIds"]) for product in self.matrix["products"]),
            47,
        )


if __name__ == "__main__":
    unittest.main()
