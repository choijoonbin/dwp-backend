import copy
import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from contextlib import contextmanager
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
                "approvals": "dwp-approval-server",
                "calendar": "dwp-platform-server",
                "communications": "dwp-platform-server",
                "dwaion": "dwp-agent-runtime",
                "hcm": "dwp-people-server",
                "mail": "dwp-platform-server",
                "meetings": "dwp-meeting-server",
                "messaging": "dwp-messaging-server",
                "notifications": "dwp-notification-server",
                "spaces": "dwp-space-server",
                "services": "dwp-platform-server",
                "workplace": "dwp-platform-server",
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
                    "PlatformSecurityFilterTest.java"
                    "#rejectsDirectRequestsWithoutGatewayServiceIdentity"
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
            with self.subTest(owner_service=owner_service), \
                    tempfile.TemporaryDirectory() as directory:
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
            {"EXACT": 12, "INCOMPLETE_KINDS": 0, "MISSING": 0},
        )
        self.assertTrue(all(kinds[product] == CHECKER.REQUIRED_ROUTE_KINDS for product in statuses))
        self.assertTrue(all(status == "EXACT" for status in statuses.values()))

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

    def test_missing_vector_accounting_is_derived_from_the_current_matrix(self) -> None:
        products = {product["productId"]: product for product in self.matrix["products"]}

        for product_id, product in products.items():
            with self.subTest(product_id=product_id):
                self.assertEqual(
                    set(product["attackEvidence"]) | set(product["missingAttackIds"]),
                    CHECKER.VECTOR_IDS,
                )
                self.assertFalse(
                    set(product["attackEvidence"]) & set(product["missingAttackIds"])
                )

    @contextmanager
    def agent_attestation_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory).resolve()
            backend_root = workspace / "dwp-backend"
            agent_root = workspace / "dwp_agent"
            backend_root.mkdir()
            (agent_root / "src/dwp_agent").mkdir(parents=True)
            (agent_root / "tests").mkdir()
            (agent_root / "pyproject.toml").write_text(
                "[project]\nname='agent'\n", encoding="utf-8"
            )
            (agent_root / "uv.lock").write_text("version = 1\n", encoding="utf-8")
            (agent_root / "src/dwp_agent/product_pep.py").write_text(
                "def authorize():\n    return False\n", encoding="utf-8"
            )
            test_reference = "tests/test_product_pep.py"
            test_method = "test_cross_tenant_fails_closed"
            (agent_root / test_reference).write_text(
                f"def {test_method}():\n    assert True\n", encoding="utf-8"
            )
            subprocess.run(["git", "init", "-q", str(agent_root)], check=True)
            subprocess.run(
                ["git", "-C", str(agent_root), "config", "user.email", "test@example.com"],
                check=True,
            )
            subprocess.run(
                ["git", "-C", str(agent_root), "config", "user.name", "Test"], check=True
            )
            subprocess.run(["git", "-C", str(agent_root), "add", "."], check=True)
            subprocess.run(
                ["git", "-C", str(agent_root), "commit", "-qm", "fixture"], check=True
            )
            revision = subprocess.run(
                ["git", "-C", str(agent_root), "rev-parse", "HEAD"],
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            node_id = f"{test_reference}::{test_method}"
            workflow_reference = ".github/workflows/backend-quality-gates.yml"
            workflow = backend_root / workflow_reference
            workflow.parent.mkdir(parents=True)
            workflow.write_text(
                "\n".join((
                    "repository: https://github.com/choijoonbin/aura_agent",
                    f"ref: {revision}",
                    f"env: {CHECKER.AGENT_EVIDENCE_ROOT_ENV}",
                    "run: uv sync --locked",
                    f"run: uv run pytest {node_id}",
                )),
                encoding="utf-8",
            )
            attestation_reference = (
                "contracts/product-authorization/dwaion-agent-pep-attestation.v1.json"
            )
            attestation_path = backend_root / attestation_reference
            attestation_path.parent.mkdir(parents=True)
            checksummed_files = [
                "pyproject.toml",
                "uv.lock",
                "src/dwp_agent/product_pep.py",
                test_reference,
            ]
            attestation = {
                "schemaVersion": 1,
                "attestationId": "dwaion-agent-owner-pep.v1",
                "repository": "https://github.com/choijoonbin/aura_agent",
                "revision": revision,
                "testRoot": "tests",
                "pepSourceReferences": ["src/dwp_agent/product_pep.py"],
                "files": [
                    {"path": path, "sha256": CHECKER.file_sha256(agent_root / path)}
                    for path in checksummed_files
                ],
                "pytestNodeIds": [node_id] * 5,
                "command": ["uv", "run", "pytest", node_id],
                "result": {"status": "PASS", "passed": 5, "failed": 0},
                "executionWorkflow": workflow_reference,
                "sourceCiRun": {
                    "provider": "GITHUB_ACTIONS",
                    "workflow": "Agent quality",
                    "runId": "123456789",
                    "url": "https://github.com/choijoonbin/aura_agent/actions/runs/123456789",
                    "headSha": revision,
                    "conclusion": "success",
                },
                "checksum": "",
            }
            # Five vectors need distinct executable nodes; use four additional real tests.
            methods = [test_method] + [f"test_vector_{index}" for index in range(2, 7)]
            (agent_root / test_reference).write_text(
                "".join(f"def {method}():\n    assert True\n\n" for method in methods),
                encoding="utf-8",
            )
            subprocess.run(["git", "-C", str(agent_root), "add", "."], check=True)
            subprocess.run(
                ["git", "-C", str(agent_root), "commit", "--amend", "--no-edit", "-q"],
                check=True,
            )
            revision = subprocess.run(
                ["git", "-C", str(agent_root), "rev-parse", "HEAD"],
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            node_ids = [f"{test_reference}::{method}" for method in methods]
            workflow.write_text(
                "\n".join((
                    "repository: https://github.com/choijoonbin/aura_agent",
                    f"ref: {revision}",
                    f"env: {CHECKER.AGENT_EVIDENCE_ROOT_ENV}",
                    "run: uv sync --locked",
                    f"run: uv run pytest {' '.join(node_ids)}",
                )),
                encoding="utf-8",
            )
            attestation["revision"] = revision
            attestation["sourceCiRun"]["headSha"] = revision
            attestation["files"] = [
                {"path": path, "sha256": CHECKER.file_sha256(agent_root / path)}
                for path in checksummed_files
            ]
            attestation["pytestNodeIds"] = node_ids
            attestation["command"] = ["uv", "run", "pytest", *node_ids]
            attestation["result"] = {
                "status": "PASS", "passed": len(node_ids), "failed": 0
            }
            attestation["checksum"] = CHECKER.checksum(attestation, "checksum")
            attestation_path.write_text(
                json.dumps(attestation, indent=2) + "\n", encoding="utf-8"
            )
            descriptor = {
                "ownerRepository": "https://github.com/choijoonbin/aura_agent",
                "testRoot": "tests",
                "attestation": attestation_reference,
            }
            with patch.object(CHECKER, "ROOT", backend_root), patch.dict(
                os.environ, {CHECKER.AGENT_EVIDENCE_ROOT_ENV: str(agent_root)}
            ):
                yield descriptor, attestation_path, agent_root, test_reference, methods

    def test_agent_pytest_reference_requires_ast_function_and_attested_node(self) -> None:
        with self.agent_attestation_fixture() as fixture:
            descriptor, _, _, test_reference, methods = fixture
            with patch.dict(CHECKER.ROUTED_PRODUCT_PEPS, {"dwaion": descriptor}):
                CHECKER.validate_test_reference(
                    f"{test_reference}#{methods[0]}", "dwp-agent-runtime"
                )
                with self.assertRaisesRegex(SystemExit, "invalid Agent pytest function"):
                    CHECKER.validate_test_reference(
                        f"{test_reference}#javaStyleMethod", "dwp-agent-runtime"
                    )
                with self.assertRaisesRegex(SystemExit, "does not exist"):
                    CHECKER.validate_test_reference(
                        f"{test_reference}#test_missing", "dwp-agent-runtime"
                    )

    def test_agent_pytest_reference_rejects_test_root_escape(self) -> None:
        with self.assertRaisesRegex(SystemExit, "escapes the declared test root"):
            CHECKER.validate_agent_pytest_reference(
                "src/dwp_agent/product_pep.py#test_escape",
                {"testRoot": "tests"},
            )

    def test_agent_attestation_rejects_source_checksum_drift(self) -> None:
        with self.agent_attestation_fixture() as fixture:
            descriptor, _, agent_root, test_reference, methods = fixture
            (agent_root / test_reference).write_text(
                "def test_tampered():\n    assert False\n", encoding="utf-8"
            )
            with self.assertRaisesRegex(SystemExit, "file checksum drift"):
                CHECKER.validate_agent_pytest_reference(
                    f"{test_reference}#{methods[0]}", descriptor
                )

    def test_agent_attestation_rejects_ci_run_revision_drift(self) -> None:
        with self.agent_attestation_fixture() as fixture:
            descriptor, attestation_path, _, _, _ = fixture
            attestation = json.loads(attestation_path.read_text(encoding="utf-8"))
            attestation["sourceCiRun"]["headSha"] = "0" * 40
            attestation["checksum"] = CHECKER.checksum(attestation, "checksum")
            attestation_path.write_text(
                json.dumps(attestation, indent=2) + "\n", encoding="utf-8"
            )
            with self.assertRaisesRegex(SystemExit, "not bound to the immutable revision"):
                CHECKER.validate_agent_attestation(descriptor)

    def test_agent_attestation_rejects_workflow_that_omits_a_node(self) -> None:
        with self.agent_attestation_fixture() as fixture:
            descriptor, attestation_path, _, _, _ = fixture
            attestation = json.loads(attestation_path.read_text(encoding="utf-8"))
            workflow = CHECKER.ROOT / attestation["executionWorkflow"]
            workflow.write_text("uv sync --locked\nuv run pytest\n", encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "workflow is not pinned"):
                CHECKER.validate_agent_attestation(descriptor)

    def test_agent_attestation_rejects_reference_missing_from_executed_nodes(self) -> None:
        with self.agent_attestation_fixture() as fixture:
            descriptor, attestation_path, _, test_reference, methods = fixture
            attestation = json.loads(attestation_path.read_text(encoding="utf-8"))
            removed = f"{test_reference}::{methods[0]}"
            attestation["pytestNodeIds"].remove(removed)
            attestation["command"].remove(removed)
            attestation["result"]["passed"] -= 1
            workflow = CHECKER.ROOT / attestation["executionWorkflow"]
            workflow.write_text(
                workflow.read_text(encoding="utf-8").replace(removed, ""),
                encoding="utf-8",
            )
            attestation["checksum"] = CHECKER.checksum(attestation, "checksum")
            attestation_path.write_text(
                json.dumps(attestation, indent=2) + "\n", encoding="utf-8"
            )
            with self.assertRaisesRegex(SystemExit, "not executable evidence"):
                CHECKER.validate_agent_pytest_reference(
                    f"{test_reference}#{methods[0]}", descriptor
                )

    @contextmanager
    def java_chain_fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            owner = "dwp-platform-server"
            main = f"{owner}/src/main/java/example"
            tests = f"{owner}/src/test/java/example"
            generic_reference = f"{main}/PlatformSecurityFilter.java"
            product_reference = f"{main}/CalendarProductSurfacePepFilter.java"
            test_reference = f"{tests}/CalendarPepTest.java#executesThroughOwnerChain"
            for reference, source in (
                (generic_reference, "class PlatformSecurityFilter {}\n"),
                (product_reference, "class CalendarProductSurfacePepFilter {}\n"),
                (
                    test_reference.split("#")[0],
                    """
                    class CalendarPepTest {
                        void setUp() {
                            PlatformSecurityFilter identity = new PlatformSecurityFilter();
                            CalendarProductSurfacePepFilter pep =
                                    new CalendarProductSurfacePepFilter();
                            builder.addFilters(identity, pep);
                        }
                        void executesThroughOwnerChain() { mvc.perform(request); }
                    }
                    """,
                ),
            ):
                path = root / reference
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source, encoding="utf-8")
            descriptor = {
                "securityFilter": generic_reference,
                "productPepFilter": product_reference,
                "fullChainTestReference": test_reference,
                "chainInvariant": {
                    "kind": "MOCK_MVC_FILTER_CHAIN",
                    "requestHarness": "mvc",
                    "members": {
                        "identity": "PlatformSecurityFilter",
                        "pep": "CalendarProductSurfacePepFilter",
                    },
                },
            }
            with patch.object(CHECKER, "ROOT", root):
                yield root, owner, descriptor

    def test_product_pep_requires_generic_and_product_filters_in_one_chain(self) -> None:
        with self.java_chain_fixture() as fixture:
            _, owner, descriptor = fixture
            CHECKER.validate_product_pep_closure("calendar", owner, descriptor)

            split = copy.deepcopy(descriptor)
            split["chainInvariant"]["members"] = {
                "identity": "PlatformSecurityFilter",
            }
            with self.assertRaisesRegex(SystemExit, "omits the generic or product"):
                CHECKER.validate_product_pep_closure("calendar", owner, split)

    def test_product_pep_rejects_decoy_chain_in_comment_or_string(self) -> None:
        with self.java_chain_fixture() as fixture:
            root, owner, descriptor = fixture
            test_path = root / descriptor["fullChainTestReference"].split("#")[0]
            test_path.write_text(
                """
                class CalendarPepTest {
                    void setUp() {
                        PlatformSecurityFilter identity = new PlatformSecurityFilter();
                        CalendarProductSurfacePepFilter pep =
                                new CalendarProductSurfacePepFilter();
                        // builder.addFilters(identity, pep);
                        String decoy = "builder.addFilters(identity, pep)";
                    }
                    void executesThroughOwnerChain() { mvc.perform(request); }
                }
                """,
                encoding="utf-8",
            )
            with self.assertRaisesRegex(SystemExit, "not in one request chain"):
                CHECKER.validate_product_pep_closure("calendar", owner, descriptor)

    def test_generic_platform_filter_cannot_pose_as_product_pep(self) -> None:
        with self.java_chain_fixture() as fixture:
            _, owner, descriptor = fixture
            generic_only = copy.deepcopy(descriptor)
            generic_only["productPepFilter"] = generic_only["securityFilter"]
            generic_only["chainInvariant"]["members"] = {
                "identity": "PlatformSecurityFilter",
            }
            with self.assertRaisesRegex(SystemExit, "integrated filter requires"):
                CHECKER.validate_product_pep_closure("calendar", owner, generic_only)

    def test_product_pep_filter_cannot_escape_the_owner_service(self) -> None:
        with self.java_chain_fixture() as fixture:
            _, owner, descriptor = fixture
            escaped = copy.deepcopy(descriptor)
            escaped["productPepFilter"] = (
                "dwp-space-server/src/main/java/example/CalendarProductSurfacePepFilter.java"
            )
            with self.assertRaisesRegex(SystemExit, "product PEP filter escapes"):
                CHECKER.validate_product_pep_closure("calendar", owner, escaped)


if __name__ == "__main__":
    unittest.main()
