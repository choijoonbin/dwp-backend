import copy
import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    "extension_projection", ROOT / "scripts/generate-approval-extension-projection-contracts.py")
PROJECTION = importlib.util.module_from_spec(spec)
spec.loader.exec_module(PROJECTION)


class ApprovalExtensionProjectionContractsTest(unittest.TestCase):
    def setUp(self):
        self.snapshot = {"version": 9, "bundleKey": "product-surfaces", "checksum": "a" * 64, "routes": []}
        self.api = {"paths": {}, "components": {"schemas": {}}}
        for index, (key, (schema_key, fields)) in enumerate(PROJECTION.DEFINITIONS.items()):
            method = "POST" if "information-command-receipt" in key else "GET"
            path = f"/v1/actual-test-{index}"
            self.add_route(key, path, method)
            self.api["components"]["schemas"][schema_key] = {
                "type": "object", "additionalProperties": False,
                "properties": {field: {"type": "string"} for field in sorted(fields)},
            }
            envelope_key = f"Envelope{index}"
            self.api["components"]["schemas"][envelope_key] = {
                "type": "object", "properties": {"data": {"$ref": f"#/components/schemas/{schema_key}"}},
            }
            self.api["paths"][path] = {method.lower(): {"responses": {"200": {"content": {
                "application/json": {"schema": {"$ref": f"#/components/schemas/{envelope_key}"}},
            }}}}}
        self.binary_path = "/v1/attachment-downloads/{grantId}/content"
        self.add_route(PROJECTION.BINARY_ROUTE, self.binary_path, "GET")
        self.api["paths"][self.binary_path] = {"get": {"responses": {"200": {"content": {
            "application/octet-stream": {"schema": {"type": "string", "format": "binary"}},
        }}}}}

    def add_route(self, key, path, method):
        self.snapshot["routes"].append({
            "routeContractKey": key, "routeKind": "DATA", "accessProfiles": [{
                "profileKey": "full-management" if ".admin." in key else "full-work", "readOnly": True,
            }], "servicePepBindings": [{"bindingKey": f"{key}.binding.01", "serviceKey": "approval",
                                        "method": method, "path": path}],
        })

    def test_extracts_exact_json_and_readonly_post_and_binary_without_fabricated_object_schema(self):
        result = PROJECTION.build_contracts(self.api, self.snapshot)
        self.assertEqual(result, PROJECTION.build_contracts(copy.deepcopy(self.api), copy.deepcopy(self.snapshot)))
        self.assertEqual(12, result["bindingCount"])
        self.assertEqual(1, len(result["binaryResponses"]))
        binary = next(value for value in result["bindings"] if value["schemaKind"] == "RAW_BINARY")
        self.assertNotIn("additionalProperties", binary)
        self.assertNotIn(PROJECTION.BINARY_SCHEMA_KEY, result["schemas"])
        self.assertEqual(3, len(PROJECTION.projection_metadata(binary)))
        receipt = next(value for value in result["bindings"] if "information-command-receipt" in value["routeContractKey"])
        self.assertEqual("POST", receipt["method"])
        self.assertEqual(6, len(PROJECTION.projection_metadata(receipt)))

    def test_rejects_missing_duplicate_foreign_or_wrong_kind_routes(self):
        for mutation in ("missing", "duplicate", "foreign", "action", "version"):
            with self.subTest(mutation=mutation):
                snapshot = copy.deepcopy(self.snapshot)
                if mutation == "missing":
                    snapshot["routes"].pop()
                elif mutation == "duplicate":
                    snapshot["routes"].append(snapshot["routes"][0])
                elif mutation == "foreign":
                    snapshot["routes"][0]["servicePepBindings"][0]["serviceKey"] = "platform"
                elif mutation == "action":
                    snapshot["routes"][0]["routeKind"] = "ACTION"
                else:
                    snapshot["version"] = 10
                with self.assertRaises(PROJECTION.ContractError):
                    PROJECTION.build_contracts(self.api, snapshot)

    def test_rejects_schema_collision_open_record_or_field_drift(self):
        key, _ = next(iter(PROJECTION.DEFINITIONS.values()))
        for mutation in ("collision", "open", "field", "unresolved", "stale"):
            with self.subTest(mutation=mutation):
                api = copy.deepcopy(self.api)
                schema = api["components"]["schemas"][key]
                if mutation == "collision":
                    api["components"]["schemas"]["Envelope0"]["properties"]["data"] = {"$ref": "#/components/schemas/ApprovalPolicyImpactResult"}
                elif mutation == "open":
                    schema.pop("additionalProperties")
                elif mutation == "field":
                    schema["properties"]["secret"] = {"type": "string"}
                elif mutation == "unresolved":
                    schema["properties"]["published"] = {"$ref": "#/components/schemas/Missing"}
                else:
                    api["paths"].pop("/v1/actual-test-0")
                with self.assertRaises(PROJECTION.ContractError):
                    PROJECTION.build_contracts(api, self.snapshot)

    def test_binary_is_raw_octet_stream_not_base64_or_json_or_ambiguous_media(self):
        for contents in (
            {"application/octet-stream": {"schema": {"type": "string", "format": "byte"}}},
            {"application/json": {"schema": {"type": "string", "format": "binary"}}},
            {"*/*": {"schema": {"type": "string", "format": "binary"}}},
            {"application/octet-stream": {"schema": {"type": "object", "additionalProperties": False}}},
        ):
            with self.subTest(contents=contents):
                api = copy.deepcopy(self.api)
                api["paths"][self.binary_path]["get"]["responses"]["200"]["content"] = contents
                with self.assertRaises(PROJECTION.ContractError):
                    PROJECTION.build_contracts(api, self.snapshot)

    def test_transitive_schema_and_binary_integrity_are_in_full_response_closure(self):
        api = copy.deepcopy(self.api)
        api["components"]["schemas"]["ApprovalPolicyImpactResult"]["properties"]["authority"] = {
            "$ref": "#/components/schemas/ActualCurrentAuthority"}
        api["components"]["schemas"]["ActualCurrentAuthority"] = {
            "type": "object", "additionalProperties": False, "properties": {"expiresAt": {"type": "string"}}}
        first = PROJECTION.build_contracts(api, self.snapshot)
        api["components"]["schemas"]["ActualCurrentAuthority"]["properties"]["expiresAt"]["format"] = "date-time"
        second = PROJECTION.build_contracts(api, self.snapshot)
        self.assertIn("ActualCurrentAuthority", first["schemas"])
        self.assertNotEqual(first["schemaClosureSha256"], second["schemaClosureSha256"])
        self.assertNotEqual(first["checksum"], second["checksum"])

    def test_source_metadata_must_match_actual_extracted_projection(self):
        result = PROJECTION.build_contracts(self.api, self.snapshot)
        by_key = {value["routeContractKey"]: value for value in result["bindings"]}
        for route in self.snapshot["routes"]:
            route["accessProfiles"][0]["responseProjectionBindings"] = [PROJECTION.projection_metadata(by_key[route["routeContractKey"]])]
        PROJECTION.validate_projection_bindings(self.snapshot, result)
        self.snapshot["routes"][0]["accessProfiles"][0]["responseProjectionBindings"][0]["openApiSchemaSha256"] = "f" * 64
        with self.assertRaises(PROJECTION.ContractError):
            PROJECTION.validate_projection_bindings(self.snapshot, result)


if __name__ == "__main__":
    unittest.main()
