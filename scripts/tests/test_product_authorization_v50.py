from __future__ import annotations

import copy
import hashlib
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts/product-authorization"
AUTH = ROOT / "dwp-auth-server/src/main/resources/product-authorization"
V49_CHECKSUM = "5c1dccdc27d8b260fbf001de5695e983a7107072824a8087038340d0d84cbc60"
V50_CHECKSUM = "c52222caad2749c4048c694f6582e157f6ab1184f73237f0944c3d88807a2760"
V49_FILE_SHA256 = "18793822b574b3bda2389585ad8b77b490019e1f3ef82f784c0178c15286f2f9"

EXPECTED = {
    "route.hcm.operations.time-command-center.page": (
        "TimeCommandCenterProjectionV1",
        "b9f8e20f38dc265df0b65ae498c5728536e9634b719bae11dad4aeb53f3afd8e",
    ),
    "route.hcm.operations.time-daily-close.page": (
        "TimeDailyCloseProjectionV1",
        "7ad4402e9fd26c2d76fc38f9554a6f8370562e536879e516084c32d57c85e039",
    ),
    "route.hcm.operations.time-monthly-close.page": (
        "TimeMonthlyCloseProjectionV1",
        "985741661b4d879f242bfcbeb9310af40ae917411ae817bf1eb6239766e580c9",
    ),
    "route.hcm.operations.time-payroll-handoff.data": (
        "PayrollTimeHandoffStatusV1", None,
    ),
    "route.hcm.time.command-receipt.data": (
        "TimeCommandReceiptLookupV1", None,
    ),
}


class ProductAuthorizationV50Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.v49_path = CONTRACTS / "product-surfaces-v1.bundle-v49.json"
        cls.v49 = json.loads(cls.v49_path.read_text(encoding="utf-8"))
        cls.v50 = json.loads(
            (CONTRACTS / "product-surfaces-v1.bundle-v50.json").read_text(
                encoding="utf-8"
            )
        )

    def test_v49_bytes_remain_immutable_and_v50_is_the_reserved_successor(self) -> None:
        self.assertEqual(V49_CHECKSUM, self.v49["checksum"])
        self.assertEqual(
            V49_FILE_SHA256, hashlib.sha256(self.v49_path.read_bytes()).hexdigest()
        )
        self.assertEqual((50, "DRAFT", V50_CHECKSUM), (
            self.v50["version"], self.v50["bundleStatus"], self.v50["checksum"],
        ))
        self.assertEqual((262, 24, 17, 81, 1079), (
            len(self.v50["capabilities"]), len(self.v50["accessPolicies"]),
            len(self.v50["entitlementExpressions"]),
            len(self.v50["predicatePolicies"]), len(self.v50["routes"]),
        ))

    def test_v50_changes_only_the_five_forward_response_bindings(self) -> None:
        previous = {route["routeContractKey"]: route for route in self.v49["routes"]}
        current = {route["routeContractKey"]: route for route in self.v50["routes"]}
        self.assertEqual(set(previous), set(current))
        self.assertEqual(
            {key: self.v49[key] for key in (
                "capabilities", "accessPolicies", "entitlementExpressions",
                "predicatePolicies", "authorityEndpoints",
            )},
            {key: self.v50[key] for key in (
                "capabilities", "accessPolicies", "entitlementExpressions",
                "predicatePolicies", "authorityEndpoints",
            )},
        )
        changed = {key for key in current if current[key] != previous[key]}
        self.assertEqual(set(EXPECTED), changed)

        for key, (schema, schema_hash) in EXPECTED.items():
            with self.subTest(route=key):
                before = previous[key]
                after = current[key]
                self.assertEqual(1, len(before["accessProfiles"]))
                self.assertEqual(1, len(after["accessProfiles"]))
                old_bindings = before["accessProfiles"][0]["responseProjectionBindings"]
                new_bindings = after["accessProfiles"][0]["responseProjectionBindings"]
                self.assertEqual(1, len(old_bindings))
                self.assertEqual(1, len(new_bindings))
                expected = copy.deepcopy(old_bindings[0])
                expected["responseSchemaKey"] = schema
                if schema_hash is not None:
                    expected.update({
                        "schemaVersion": 1,
                        "openApiSchemaSha256": schema_hash,
                        "additionalProperties": False,
                    })
                self.assertEqual(expected, new_bindings[0])
                normalized = copy.deepcopy(after)
                normalized["accessProfiles"][0]["responseProjectionBindings"] = old_bindings
                self.assertEqual(before, normalized)

    def test_latest_alias_index_auth_seed_and_service_peps_pin_v50_draft(self) -> None:
        alias = json.loads((CONTRACTS / "product-surfaces-v1.json").read_text())
        seed = json.loads((AUTH / "product-surfaces-v1.generated.json").read_text())
        index = json.loads((CONTRACTS / "product-surfaces-v1.index.json").read_text())
        auth_index = json.loads(
            (AUTH / "product-surfaces-v1.index.generated.json").read_text()
        )
        self.assertEqual(self.v50, alias)
        self.assertEqual(self.v50, seed)
        self.assertEqual(index, auth_index)
        self.assertEqual((50, V50_CHECKSUM, 50), (
            index["latestVersion"], index["latestChecksum"], len(index["versions"]),
        ))
        self.assertEqual("DRAFT", index["versions"][-1]["bundleStatus"])
        for service in ("people", "time", "platform", "payroll"):
            pep = json.loads((
                ROOT / f"dwp-{service}-server/src/main/resources/product-authorization/"
                f"hcm-{service}-pep-v50.generated.json"
            ).read_text())
            self.assertEqual({
                "bundleKey": "product-surfaces",
                "version": 50,
                "sha256": V50_CHECKSUM,
            }, pep["registryRef"])


if __name__ == "__main__":
    unittest.main()
