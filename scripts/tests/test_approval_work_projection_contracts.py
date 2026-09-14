import copy
import importlib.util
import pathlib
import unittest


SCRIPTS = pathlib.Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


canonical = load("canonical", "generate-product-authorization-contracts.py")
projection = load("work_projection", "generate-approval-work-projection-contracts.py")


class ApprovalWorkProjectionContractsTest(unittest.TestCase):
    def setUp(self):
        self.snapshot = canonical.build_snapshots(canonical.load_source())[6]
        self.api = {"paths": {}, "components": {"schemas": {}}}
        self.schemas = self.api["components"]["schemas"]
        for key, (method, path, _, _) in canonical.APPROVAL_WORK_V7_BINDINGS.items():
            if method != "GET":
                continue
            short = key.removeprefix("route.approvals.work.").removesuffix(".data")
            data_key = short.replace("-", "_")
            self.schemas[data_key] = {
                "type": "object", "additionalProperties": False,
                "properties": {field: {"type": "string"}
                               for field in projection.DATA_FIELDS.get(short, projection.PAGE_FIELDS)},
            }
            response = {"$ref": f"#/components/schemas/response_{data_key}"}
            self.schemas[f"response_{data_key}"] = {
                "type": "object", "properties": {"data": {"$ref": f"#/components/schemas/{data_key}"}},
            }
            self.api["paths"][path] = {"get": {
                "responses": {"200": {"content": {"*/*": {"schema": response}}}}}}

    def test_extracts_five_exact_closed_actual_schemas_deterministically(self):
        first = projection.build_contracts(self.api, self.snapshot)
        second = projection.build_contracts(copy.deepcopy(self.api), self.snapshot)
        self.assertEqual(first, second)
        self.assertEqual(5, first["bindingCount"])
        self.assertEqual(5, len(first["schemas"]))
        self.assertEqual(self.snapshot["checksum"], first["registryRef"]["sha256"])

    def test_fails_closed_for_stale_export_open_record_and_dto_drift(self):
        for mutation in ("stale", "open", "fields", "unresolved"):
            with self.subTest(mutation=mutation):
                api = copy.deepcopy(self.api)
                if mutation == "stale":
                    api["paths"].pop("/v1/tasks/search")
                elif mutation == "unresolved":
                    api["components"]["schemas"].pop("tasks_search")
                elif mutation == "open":
                    api["components"]["schemas"]["tasks_search"].pop("additionalProperties")
                else:
                    api["components"]["schemas"]["tasks_search"]["properties"]["secret"] = {}
                with self.assertRaises(projection.ContractError):
                    projection.build_contracts(api, self.snapshot)

    def test_includes_transitive_schemas_in_full_closure_digest(self):
        schema = self.schemas["request_draft_revision"]
        schema["properties"]["revision"] = {"$ref": "#/components/schemas/Revision"}
        self.schemas["Revision"] = {"type": "object", "additionalProperties": False,
                                   "properties": {"revision": {"type": "integer"}}}
        first = projection.build_contracts(self.api, self.snapshot)
        self.assertIn("Revision", first["schemas"])
        self.schemas["Revision"]["properties"]["revision"]["minimum"] = 1
        second = projection.build_contracts(self.api, self.snapshot)
        self.assertNotEqual(first["schemaClosureSha256"], second["schemaClosureSha256"])
        self.assertNotEqual(first["checksum"], second["checksum"])


if __name__ == "__main__":
    unittest.main()
