import copy
import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "recovery11_projection", ROOT / "scripts/generate-approval-recovery11-projection-contracts.py")
PROJECTION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PROJECTION)


class ApprovalRecovery11ProjectionContractsTest(unittest.TestCase):
    def setUp(self):
        self.api = {"paths": {}, "components": {"schemas": {}}}
        components = self.api["components"]["schemas"]
        for name, fields in PROJECTION.SCHEMAS.items():
            components[name] = {"type": "object", "additionalProperties": False,
                                "properties": {field: {"type": "string"} for field in sorted(fields)},
                                "required": sorted(fields)}
        receipt = components["ApprovalRetentionCommandReceipt"]["properties"]
        for field, values in {
            "operation": ["INITIALIZE_POLICY", "SAVE_POLICY", "PUBLISH_POLICY", "CLAIM_RECORD"],
            "status": ["COMMITTED"], "originAuthorityProfile": ["POLICY_UPDATE_TRUSTED",
                "POLICY_PUBLISH_SIGNED_HIGH_INDEPENDENT_CHECKER", "RETENTION_RECORD_EXECUTE_SIGNED_HIGH"],
            "profileVersion": ["RETENTION_COMMAND_RECEIPT_STEP_UP_TYPED_JSON_V1"],
        }.items():
            receipt[field]["enum"] = values
        selection = components["ApprovalWorkflowPlanningSelection"]["properties"]
        selection["policy"] = {"$ref": "#/components/schemas/ApprovalWorkflowPlanningPolicyPin"}
        selection["forms"] = {"type": "array", "items": {"$ref": "#/components/schemas/ApprovalWorkflowPlanningFormPin"}}
        selection["selectedFormId"] = {"type": ["string", "null"], "format": "uuid"}
        for index, (path, name) in enumerate(PROJECTION.OPERATIONS.values()):
            envelope = f"Envelope{index}"
            components[envelope] = {"type": "object", "properties": {"data": {"$ref": f"#/components/schemas/{name}"}}}
            self.api["paths"][path] = {"get": {"responses": {"200": {"content": {
                "application/json": {"schema": {"$ref": f"#/components/schemas/{envelope}"}}}}}}}
        self.baseline = {"version": 10, "routes": [{"routeContractKey": "route.approvals.work.old.data"}]}
        self.wave = PROJECTION.build_wave(self.api)
        self.snapshot = {"version": 11, "bundleKey": "product-surfaces", "checksum": "a" * 64,
                         "routes": copy.deepcopy(self.baseline["routes"] + self.wave["routes"])}

    def test_five_bodyless_data_routes_have_closed_actual_graph_and_original_permission_profiles(self):
        result = PROJECTION.build_contracts(self.api, self.snapshot, baseline=self.baseline)
        PROJECTION.validate_projection_bindings(self.snapshot, result)
        self.assertEqual(5, result["bindingCount"])
        self.assertEqual(set(PROJECTION.SCHEMAS), set(result["schemas"]))
        self.assertEqual([], self.wave["capabilities"])
        self.assertEqual(2, len(self.wave["predicatePolicies"]))
        receipt_routes = self.wave["routes"][:4]
        self.assertEqual(["approvals.policy.update", "approvals.policy.update", "approvals.policy.publish",
                          "approvals.operations.execute"],
                         [route["accessProfiles"][0]["requiredAccess"]["capabilityContractKey"] for route in receipt_routes])
        self.assertTrue(all(route["accessProfiles"][0]["readOnly"] and not route.get("stepUpCommandBindings")
                            for route in self.wave["routes"]))
        planning = self.wave["routes"][4]["accessProfiles"][0]["requiredAccess"]
        self.assertEqual("ALL", planning["mode"])
        self.assertEqual(2, len(planning["capabilityContractKeys"]))
        self.assertEqual(PROJECTION.sha256(result["schemas"]), result["schemaClosureSha256"])

    def test_native_graph_hash_does_not_claim_request_or_operation_id_material_as_response_evidence(self):
        before = PROJECTION.response_graph(self.api)
        api = copy.deepcopy(self.api)
        for item in api["paths"].values():
            item["get"].update(operationId="renumbered", parameters=[{"in": "header", "name": "X-Test"}])
        api["components"]["schemas"]["UnrelatedInput"] = {"type": "object"}
        self.assertEqual(before, PROJECTION.response_graph(api))

    def test_only_five_get_additions_and_unchanged_old_components_are_admitted(self):
        previous = {"paths": {"/v1/old": {"get": {"operationId": "old", "responses": {}}}},
                    "components": {"schemas": {"Old": {"type": "object"}}}}
        api = copy.deepcopy(self.api)
        api["paths"]["/v1/old"] = {"get": {"operationId": "old_2", "responses": {}}}
        api["components"]["schemas"].update(previous["components"]["schemas"])
        PROJECTION.validate_operation_superset(api, previous)
        for mutation in ("extra", "missing", "old-body", "old-component"):
            with self.subTest(mutation=mutation):
                changed = copy.deepcopy(api)
                if mutation == "extra":
                    changed["paths"]["/v1/future"] = {"post": {}}
                elif mutation == "missing":
                    changed["paths"].pop(next(iter(self.api["paths"])))
                elif mutation == "old-body":
                    changed["paths"]["/v1/old"]["get"]["requestBody"] = {}
                else:
                    changed["components"]["schemas"]["Old"]["additionalProperties"] = False
                with self.assertRaises(PROJECTION.ContractError):
                    PROJECTION.validate_operation_superset(changed, previous)

    def test_open_extra_missing_and_nested_response_drift_are_rejected(self):
        for name in PROJECTION.SCHEMAS:
            for mutation in ("open", "extra", "missing"):
                with self.subTest(name=name, mutation=mutation):
                    api = copy.deepcopy(self.api)
                    schema = api["components"]["schemas"][name]
                    if mutation == "open":
                        schema["additionalProperties"] = True
                    elif mutation == "extra":
                        schema["properties"]["privateAuthority"] = {"type": "string"}
                    else:
                        schema["properties"].pop(next(iter(schema["properties"])))
                    with self.assertRaises(PROJECTION.ContractError):
                        PROJECTION.response_graph(api)

    def test_new_mutation_step_up_binding_or_writable_receipt_profile_is_rejected(self):
        for mutation in ("step-up", "writable", "wrong-profile", "wrong-method", "wrong-path", "missing", "duplicate"):
            with self.subTest(mutation=mutation):
                snapshot = copy.deepcopy(self.snapshot)
                route = snapshot["routes"][1]
                if mutation == "step-up":
                    route["stepUpCommandBindings"] = [{"targetType": "RETENTION_POLICY"}]
                elif mutation == "writable":
                    route["accessProfiles"][0]["readOnly"] = False
                elif mutation == "wrong-profile":
                    route["accessProfiles"][0]["profileKey"] = "full-management"
                elif mutation == "wrong-method":
                    route["servicePepBindings"][0]["method"] = "POST"
                elif mutation == "wrong-path":
                    route["gatewayApiBindings"][0]["path"] += "/alias"
                elif mutation == "missing":
                    snapshot["routes"].pop()
                else:
                    snapshot["routes"].append(copy.deepcopy(route))
                with self.assertRaises(PROJECTION.ContractError):
                    PROJECTION.build_contracts(self.api, snapshot, baseline=self.baseline)

    def test_all_native_receipt_enum_profiles_are_exact_and_get_never_accepts_a_body(self):
        for field in ("operation", "status", "originAuthorityProfile", "profileVersion"):
            api = copy.deepcopy(self.api)
            api["components"]["schemas"]["ApprovalRetentionCommandReceipt"]["properties"][field]["enum"].append("BORROWED")
            with self.assertRaises(PROJECTION.ContractError):
                PROJECTION.response_graph(api)
        api = copy.deepcopy(self.api)
        api["paths"][next(iter(api["paths"]))]["get"]["requestBody"] = {}
        with self.assertRaises(PROJECTION.ContractError):
            PROJECTION.response_graph(api)


if __name__ == "__main__":
    unittest.main()
