from __future__ import annotations

import copy
import json
import runpy
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GENERATOR = runpy.run_path(
    str(ROOT / "scripts/generate-hcm-time-close-projection-contracts.py")
)
CONTRACT = (
    ROOT / "contracts/product-authorization/hcm-time-close-projections-v50.generated.json"
)
RUNTIME = (
    ROOT
    / "dwp-time-server/src/main/resources/product-authorization"
    / CONTRACT.name
)
EXPECTED_CHECKSUM = "f90550903aeea52aeba79593fe8be63d002f03767bebd7ec677d0fb0b98513d0"


class HcmTimeCloseProjectionContractsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.openapi = json.loads(
            (ROOT / "contracts/openapi/time.json").read_text(encoding="utf-8")
        )
        cls.bundle = json.loads(
            (
                ROOT
                / "contracts/product-authorization/product-surfaces-v1.bundle-v50.json"
            ).read_text(encoding="utf-8")
        )
        cls.contract = json.loads(CONTRACT.read_text(encoding="utf-8"))

    def test_generated_and_runtime_projection_artifacts_are_identical(self) -> None:
        self.assertEqual(CONTRACT.read_bytes(), RUNTIME.read_bytes())
        self.assertEqual(
            self.contract, GENERATOR["build_contract"](self.openapi, self.bundle)
        )
        self.assertEqual(EXPECTED_CHECKSUM, self.contract["checksum"])
        self.assertEqual(3, self.contract["bindingCount"])
        self.assertEqual(17, len(self.contract["schemas"]))
        self.assertEqual(
            {
                "bundleKey": "product-surfaces",
                "version": 50,
                "sha256": "c52222caad2749c4048c694f6582e157f6ab1184f73237f0944c3d88807a2760",
            },
            self.contract["registryRef"],
        )

    def test_each_binding_pins_source_and_materialized_openapi_lineage(self) -> None:
        expected = {
            "IAQ-039": (
                "route.hcm.operations.time-command-center.page",
                "TimeCommandCenterProjectionV1",
                "9d9cdcc8167d834c225201a85171688cbc4abab9cffea93cf402e45dc0c16882",
                "b6ec3f072228de6e2cbd26ef7cd280342d02dae807d6a712ab4227cf65c266d8",
                "b9f8e20f38dc265df0b65ae498c5728536e9634b719bae11dad4aeb53f3afd8e",
            ),
            "IAQ-043": (
                "route.hcm.operations.time-daily-close.page",
                "TimeDailyCloseProjectionV1",
                "88e86bfced9b5f766de79d3937a0fa99061d517c4a4a6bc34d31c3a66e8a4e92",
                "2f630f7e883e9889552f12345d8c9c7c3a758e998fddef5bac58e55daae14a7c",
                "7ad4402e9fd26c2d76fc38f9554a6f8370562e536879e516084c32d57c85e039",
            ),
            "IAQ-044": (
                "route.hcm.operations.time-monthly-close.page",
                "TimeMonthlyCloseProjectionV1",
                "12d54d5ef6749483c1d22eecb110199dedd6a8cac609e73c47af2f0ef4fb652f",
                "b89467f686abde5193b310ee2f9c4e0c967df2d31534a2bb471311ff63ede492",
                "985741661b4d879f242bfcbeb9310af40ae917411ae817bf1eb6239766e580c9",
            ),
        }
        bindings = {
            binding["sourceBindingId"]: binding
            for binding in self.contract["bindings"]
        }
        self.assertEqual(set(expected), set(bindings))
        for source_id, values in expected.items():
            route, response, request_sha, response_sha, openapi_sha = values
            with self.subTest(source_id=source_id):
                binding = bindings[source_id]
                self.assertEqual(route, binding["routeContractKey"])
                self.assertEqual(response, binding["responseSchemaKey"])
                self.assertEqual(request_sha, binding["sourceRequestShapeSha256"])
                self.assertEqual(response_sha, binding["sourceResponseShapeSha256"])
                self.assertEqual(openapi_sha, binding["openApiSchemaSha256"])
                self.assertFalse(binding["additionalProperties"])
                self.assertEqual(1, binding["schemaVersion"])

    def test_generator_rejects_a_projection_binding_that_does_not_match_openapi(self) -> None:
        tampered = copy.deepcopy(self.bundle)
        route = next(
            value
            for value in tampered["routes"]
            if value["routeContractKey"]
            == "route.hcm.operations.time-command-center.page"
        )
        route["accessProfiles"][0]["responseProjectionBindings"][0][
            "openApiSchemaSha256"
        ] = "0" * 64
        with self.assertRaisesRegex(
            GENERATOR["ContractError"], "v50 projection metadata drift"
        ):
            GENERATOR["build_contract"](self.openapi, tampered)


if __name__ == "__main__":
    unittest.main()
