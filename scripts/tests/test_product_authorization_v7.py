import copy
import importlib.util
import json
import pathlib
import unittest


SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "generate-product-authorization-contracts.py"
SPEC = importlib.util.spec_from_file_location("product_authorization", SCRIPT)
generator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generator)

V2_PROJECTION_SHA = "b6b3d9fa5b4d296d333d05b03c001252a93b229d112322ee49425d26f7f6f83f"
V7_CLOSURE = {
    "routes": 47, "bindings": 55,
    "routeKinds": {"ACTION": 23, "DATA": 9, "PAGE": 15},
    "capabilities": 24, "accessPolicies": 1,
    "entitlementExpressions": 1, "predicatePolicies": 7,
}


class ProductAuthorizationV7Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.snapshots = generator.build_snapshots(generator.load_source())

    def test_v1_through_v6_remain_pinned_and_byte_identical(self):
        for snapshot in self.snapshots[:6]:
            with self.subTest(version=snapshot["version"]):
                version = snapshot["version"]
                self.assertEqual(generator.IMMUTABLE_RELEASE_CHECKSUMS[version], snapshot["checksum"])
                expected = generator.render(snapshot).encode("utf-8")
                self.assertEqual(expected, generator.VERSIONED_CONTRACT_OUTPUTS[version].read_bytes())
                self.assertEqual(expected, generator.VERSIONED_AUTH_SEED_OUTPUTS[version].read_bytes())

    def test_v7_exact_append_only_counts_and_reverse_refs(self):
        v6, v7 = self.snapshots[5:7]
        prior = {route["routeContractKey"] for route in v6["routes"]}
        added = {route["routeContractKey"] for route in v7["routes"]} - prior
        self.assertEqual(set(generator.APPROVAL_WORK_V7_BINDINGS), added)
        self.assertEqual("DRAFT", v7["bundleStatus"])
        for section, key, forward in (
                ("capabilities", "contractKey", "capabilityContractKey"),
                ("predicatePolicies", "predicatePolicyKey", "predicatePolicyKeys")):
            for descriptor in v7[section]:
                actual = set()
                for route in v7["routes"]:
                    for profile in route["accessProfiles"]:
                        if section == "predicatePolicies":
                            used = descriptor[key] in profile[forward]
                        else:
                            access = profile["requiredAccess"]
                            used = descriptor[key] == access.get(forward) or descriptor[key] in (
                                access.get("capabilityContractKeys") or [])
                        if used:
                            actual.add(route["routeContractKey"])
                # Mode-branch policy consumers are also canonical capability refs.
                if section == "capabilities":
                    policies = {p["accessPolicyKey"] for p in v7["accessPolicies"]
                                if any(descriptor[key] in b.get("capabilityContractKeys", [])
                                       for b in p.get("modeBranches") or [])}
                    actual.update(route["routeContractKey"] for route in v7["routes"]
                                  if any(p["requiredAccess"].get("accessPolicyKey") in policies
                                         for p in route["accessProfiles"]))
                self.assertEqual(actual, set(descriptor["routeContractKeys"]), descriptor[key])

    def test_v2_projection_bytes_checksum_and_counts_stay_immutable(self):
        path = generator.APPROVAL_PILOT_PEP_OUTPUT
        projection = json.loads(path.read_text())
        self.assertEqual(V2_PROJECTION_SHA, projection["projectionChecksum"])
        self.assertEqual(39, projection["projectedRouteContractCount"])
        self.assertEqual(47, projection["bindingPairCount"])
        generator.verify_approvals_pep(path, generator.build_approvals_pep(
            self.snapshots[1], "approval", "approval-pilot-pep-v2",
            {**V7_CLOSURE, "routes": 39, "bindings": 47,
             "routeKinds": {"ACTION": 20, "DATA": 4, "PAGE": 15}, "predicatePolicies": 6}))

    def test_v7_pep_has_no_foreign_reverse_refs_or_lost_baseline_binding(self):
        projection = generator.build_approvals_pep(
            self.snapshots[6], "approval", "approval-pilot-pep-v7", V7_CLOSURE, version=7)
        keys = {route["routeContractKey"] for route in projection["routes"]}
        for section in ("capabilities", "accessPolicies", "predicatePolicies"):
            for descriptor in projection[section]:
                self.assertLessEqual(set(descriptor["routeContractKeys"]), keys)
        active = {route["routeContractKey"]: route for route in projection["routes"]}
        baseline = json.loads(generator.APPROVAL_PILOT_PEP_OUTPUT.read_text())
        for route in baseline["routes"]:
            self.assertEqual(route, active[route["routeContractKey"]])

    def test_v7_rejects_path_profile_capability_owner_and_predicate_swaps(self):
        for key in generator.APPROVAL_WORK_V7_BINDINGS:
            for mutation in ("path", "capability", "predicate", "mode", "owner", "target"):
                with self.subTest(key=key, mutation=mutation):
                    snapshot = copy.deepcopy(self.snapshots[6])
                    route = next(r for r in snapshot["routes"] if r["routeContractKey"] == key)
                    profile = route["accessProfiles"][0]
                    if mutation == "path":
                        route["gatewayApiBindings"][0]["path"] += "/extra"
                        route["servicePepBindings"][0]["path"] += "/extra"
                    elif mutation == "capability":
                        profile["requiredAccess"]["capabilityContractKey"] = "approvals.work.request.create"
                    elif mutation == "predicate":
                        profile["predicatePolicyKeys"] = []
                    elif mutation == "mode":
                        profile["activeAccessModes"].append("PROVIDER_SUPPORT")
                    elif mutation == "owner":
                        route["servicePepBindings"][0]["serviceKey"] = "platform"
                    else:
                        profile["targetBindingKinds"] = []
                    with self.assertRaises(generator.ContractError):
                        generator._validate_approval_work_v7(snapshot)

    def test_v6_cannot_be_redefined_or_modified_by_a_later_wave(self):
        source = generator.load_source()
        source["waves"][-1]["routes"][0] = copy.deepcopy(source["waves"][-2]["routes"][0])
        with self.assertRaises(generator.ContractError):
            generator.build_snapshots(source)


if __name__ == "__main__":
    unittest.main()
