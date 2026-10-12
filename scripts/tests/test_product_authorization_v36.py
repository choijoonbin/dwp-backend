from __future__ import annotations

import importlib.util
import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "v36_authorization",
    ROOT / "scripts" / "generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class ProductAuthorizationV36Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        snapshots = GENERATOR.build_snapshots(GENERATOR.load_source())
        cls.v35 = snapshots[34]
        cls.v36 = snapshots[35]
        cls.v35_routes = {
            route["routeContractKey"]: route for route in cls.v35["routes"]
        }
        cls.v36_routes = {
            route["routeContractKey"]: route for route in cls.v36["routes"]
        }
        cls.v36_capabilities = {
            capability["contractKey"]: capability
            for capability in cls.v36["capabilities"]
        }

    def test_v35_remains_immutable_and_v36_adds_only_the_freeze_capability(self) -> None:
        self.assertEqual(
            "38ef33070a33b5da9a7825c764bd24daacd24c8ca4008f3a926727321d3d098d",
            self.v35["checksum"],
        )
        self.assertEqual(36, self.v36["version"])
        self.assertEqual(
            "12e4b9329ab53ab1efd6c5f6e23ccc7869c4184a79e5a64f614fe9c40a9e704b",
            self.v36["checksum"],
        )
        self.assertEqual(237, len(self.v36["capabilities"]))
        self.assertEqual(974, len(self.v36["routes"]))
        added = {
            capability["contractKey"] for capability in self.v36["capabilities"]
        } - {
            capability["contractKey"] for capability in self.v35["capabilities"]
        }
        self.assertEqual({"hcm.operations.performance.freeze"}, added)

    def test_population_freeze_is_a_dedicated_high_risk_step_up_command(self) -> None:
        capability = self.v36_capabilities["hcm.operations.performance.freeze"]
        self.assertEqual("DATA.HR_TALENT:APPROVE", capability["resolvedCapabilityCode"])
        self.assertEqual("HIGH", capability["riskTier"])
        self.assertEqual("STEPUP-MGMT-HIGH-V1", capability["activationPolicy"])

        key = "route.hcm.operations.performance-population-freeze.action"
        route = self.v36_routes[key]
        self.assertEqual(
            "hcm.operations.performance.freeze",
            route["accessProfiles"][0]["requiredAccess"]["capabilityContractKey"],
        )
        self.assertEqual(
            [{
                "audience": "dwp-people-server",
                "bindingKey": key + ".binding.01",
                "expectedObjectVersionName": "X-DWP-Expected-Object-Version",
                "expectedObjectVersionSource": "COMMAND_HEADER",
                "ownerServiceKey": "people",
                "targetIdPathParameter": "cycleId",
                "targetType": "PERFORMANCE_CYCLE",
            }],
            route["stepUpCommandBindings"],
        )

        v35_route = self.v35_routes[key]
        self.assertEqual(
            "hcm.operations.performance.update",
            v35_route["accessProfiles"][0]["requiredAccess"]["capabilityContractKey"],
        )
        self.assertNotIn("stepUpCommandBindings", v35_route)

    def test_people_pep_projects_the_exact_v36_freeze_contract(self) -> None:
        projection = json.loads((
            ROOT
            / "dwp-people-server/src/main/resources/product-authorization/"
            / "hcm-people-pep-v36.generated.json"
        ).read_text())
        self.assertEqual(
            {
                "bundleKey": "product-surfaces",
                "sha256": self.v36["checksum"],
                "version": 36,
            },
            projection["registryRef"],
        )
        projected_routes = {
            route["routeContractKey"]: route for route in projection["routes"]
        }
        key = "route.hcm.operations.performance-population-freeze.action"
        self.assertEqual(self.v36_routes[key], projected_routes[key])


if __name__ == "__main__":
    unittest.main()
