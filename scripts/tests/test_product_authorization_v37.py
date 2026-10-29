from __future__ import annotations

import copy
import importlib.util
import json
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "v37_authorization",
    ROOT / "scripts" / "generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)

VOID_ROUTE = "route.hcm.operations.time-clock-event-void.action"
PERSONAL_TIME_CAPABILITY = "hcm.personal.time.submit"


class ProductAuthorizationV37Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        snapshots = GENERATOR.build_snapshots(GENERATOR.load_source())
        cls.v36, cls.v37 = snapshots[35:37]
        cls.by_section = {
            section: (
                {value[key]: value for value in cls.v36[section]},
                {value[key]: value for value in cls.v37[section]},
            )
            for section, key in GENERATOR.SECTION_KEYS.items()
        }

    def test_v36_is_immutable_and_v37_has_identical_closed_counts(self) -> None:
        self.assertEqual(36, self.v36["version"])
        self.assertEqual(
            "12e4b9329ab53ab1efd6c5f6e23ccc7869c4184a79e5a64f614fe9c40a9e704b",
            self.v36["checksum"],
        )
        self.assertEqual(37, self.v37["version"])
        self.assertEqual(
            "1f23280116bc5b29f1a7ea6cc4ccbd2092a91673979d6bb46164a0dbb95ae10c",
            self.v37["checksum"],
        )
        for section in GENERATOR.SECTION_KEYS:
            previous, current = self.by_section[section]
            self.assertEqual(set(previous), set(current), section)
        self.assertEqual(
            {"PAGE": 124, "DATA": 295, "ACTION": 555},
            {
                kind: sum(route["routeKind"] == kind for route in self.v37["routes"])
                for kind in ("PAGE", "DATA", "ACTION")
            },
        )

    def test_only_the_exact_void_route_and_personal_reverse_reference_change(self) -> None:
        expected_changes = {
            "capabilities": {PERSONAL_TIME_CAPABILITY},
            "accessPolicies": set(),
            "entitlementExpressions": set(),
            "predicatePolicies": set(),
            "routes": {VOID_ROUTE},
        }
        for section, expected in expected_changes.items():
            previous, current = self.by_section[section]
            changed = {
                key for key in previous if previous[key] != current[key]
            }
            self.assertEqual(expected, changed, section)

        previous_route, current_route = (
            values[VOID_ROUTE] for values in self.by_section["routes"]
        )
        previous_profiles = {
            profile["profileKey"]: profile
            for profile in previous_route["accessProfiles"]
        }
        current_profiles = {
            profile["profileKey"]: profile
            for profile in current_route["accessProfiles"]
        }
        self.assertEqual(
            {"full-management", "self-service"}, set(previous_profiles)
        )
        self.assertEqual({"full-management"}, set(current_profiles))
        self.assertEqual(
            previous_profiles["full-management"],
            current_profiles["full-management"],
        )
        self.assertEqual(
            "hcm.operations.time.update",
            current_profiles["full-management"]["requiredAccess"][
                "capabilityContractKey"
            ],
        )
        self.assertEqual(
            "hcm.personal.time.submit",
            previous_profiles["self-service"]["requiredAccess"][
                "capabilityContractKey"
            ],
        )
        normalized_route = copy.deepcopy(current_route)
        normalized_route["accessProfiles"] = copy.deepcopy(
            previous_route["accessProfiles"]
        )
        self.assertEqual(previous_route, normalized_route)
        self.assertEqual(
            [{
                "audience": "dwp-time-server",
                "bindingKey": VOID_ROUTE + ".binding.01",
                "expectedObjectVersionName": "X-DWP-Expected-Object-Version",
                "expectedObjectVersionSource": "COMMAND_HEADER",
                "ownerServiceKey": "time",
                "targetIdPathParameter": "eventId",
                "targetType": "CLOCK_EVENT",
            }],
            current_route["stepUpCommandBindings"],
        )

        previous_capability, current_capability = (
            values[PERSONAL_TIME_CAPABILITY]
            for values in self.by_section["capabilities"]
        )
        self.assertIn(VOID_ROUTE, previous_capability["routeContractKeys"])
        self.assertNotIn(VOID_ROUTE, current_capability["routeContractKeys"])
        self.assertIn(
            "route.hcm.personal.time-clock-event-record.action",
            current_capability["routeContractKeys"],
        )
        normalized_capability = copy.deepcopy(current_capability)
        normalized_capability["routeContractKeys"] = copy.deepcopy(
            previous_capability["routeContractKeys"]
        )
        self.assertEqual(previous_capability, normalized_capability)

    def test_removal_contract_is_closed_to_one_route_profile_and_v37(self) -> None:
        source = GENERATOR.load_source()
        declared = {
            (
                patch["routeContractKey"],
                removal["profileKey"],
                removal["introducedInVersion"],
            )
            for patch in source["descriptorEnrichments"]["routes"]
            for removal in patch.get("versionedAccessProfileRemovals", [])
        }
        self.assertEqual(
            {(VOID_ROUTE, "self-service", 37)}, declared
        )
        self.assertEqual(GENERATOR.V37_PROFILE_REMOVALS, frozenset(declared))

        previous_route, current_route = (
            values[VOID_ROUTE] for values in self.by_section["routes"]
        )
        self.assertTrue(GENERATOR._is_v37_time_void_profile_removal(
            VOID_ROUTE, previous_route, current_route
        ))
        changed_full_management = copy.deepcopy(current_route)
        changed_full_management["accessProfiles"][0]["precedence"] += 1
        self.assertFalse(GENERATOR._is_v37_time_void_profile_removal(
            VOID_ROUTE, previous_route, changed_full_management
        ))
        changed_step_up = copy.deepcopy(current_route)
        changed_step_up["stepUpCommandBindings"][0]["targetType"] = "OTHER"
        self.assertFalse(GENERATOR._is_v37_time_void_profile_removal(
            VOID_ROUTE, previous_route, changed_step_up
        ))
        self.assertFalse(GENERATOR._is_v37_time_void_profile_removal(
            VOID_ROUTE + ".other", previous_route, current_route
        ))

    def test_time_pep_projects_the_exact_v37_correction_and_preserves_v36(self) -> None:
        projection_root = (
            ROOT
            / "dwp-time-server/src/main/resources/product-authorization"
        )
        v36_projection = json.loads(
            (projection_root / "hcm-time-pep-v36.generated.json").read_text()
        )
        v37_projection = json.loads(
            (projection_root / "hcm-time-pep-v37.generated.json").read_text()
        )
        self.assertEqual(36, v36_projection["registryRef"]["version"])
        self.assertEqual(self.v36["checksum"], v36_projection["registryRef"]["sha256"])
        self.assertEqual(
            {
                "bundleKey": "product-surfaces",
                "sha256": self.v37["checksum"],
                "version": 37,
            },
            v37_projection["registryRef"],
        )
        projected_routes = {
            route["routeContractKey"]: route for route in v37_projection["routes"]
        }
        self.assertEqual(
            self.by_section["routes"][1][VOID_ROUTE],
            projected_routes[VOID_ROUTE],
        )


if __name__ == "__main__":
    unittest.main()
