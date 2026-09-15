import copy
import importlib.util
import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "v11_authorization",
    ROOT / "scripts" / "generate-product-authorization-contracts.py",
)
GENERATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GENERATOR)


class ProductAuthorizationV11Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = GENERATOR.load_source()
        cls.snapshots = GENERATOR.build_snapshots(cls.source)
        cls.v10 = cls.snapshots[9]
        cls.v11 = cls.snapshots[10]

    def test_v11_is_an_exact_five_route_append_only_recovery_wave(self):
        prior = {route["routeContractKey"] for route in self.v10["routes"]}
        added = [route for route in self.v11["routes"]
                 if route["routeContractKey"] not in prior]
        self.assertEqual(set(GENERATOR.APPROVAL_RECOVERY11_PROJECTIONS),
                         {route["routeContractKey"] for route in added})
        self.assertEqual(4, sum(route["routeContractKey"] in
                                GENERATOR.APPROVAL_RECOVERY11_RECEIPT_ROUTES
                                for route in added))
        self.assertEqual(GENERATOR.render(self.v11).encode(),
                         GENERATOR.VERSIONED_CONTRACT_OUTPUTS[11].read_bytes())
        for snapshot in self.snapshots:
            version = snapshot["version"]
            self.assertEqual(GENERATOR.IMMUTABLE_RELEASE_CHECKSUMS[version],
                             snapshot["checksum"])

    def test_receipt_exception_cannot_be_borrowed_by_profile_predicate_or_capability(self):
        for mutation in ("profile", "predicate", "capability", "writable", "access-mode"):
            with self.subTest(mutation=mutation):
                source = copy.deepcopy(self.source)
                wave = next(item for item in source["waves"] if item["version"] == 11)
                route = next(item for item in wave["routes"]
                             if "publication-command" in item["routeContractKey"])
                profile = route["accessProfiles"][0]
                if mutation == "profile":
                    profile["profileKey"] = "full-management"
                elif mutation == "predicate":
                    profile["predicatePolicyKeys"] = [
                        "predicate.approval.retention-policy.v1"
                    ]
                elif mutation == "capability":
                    profile["requiredAccess"]["capabilityContractKey"] = (
                        "approvals.operations.execute"
                    )
                elif mutation == "writable":
                    profile["readOnly"] = False
                else:
                    profile["activeAccessModes"] = ["NORMAL"]
                with self.assertRaises(GENERATOR.ContractError):
                    GENERATOR.build_snapshots(source)


if __name__ == "__main__":
    unittest.main()
