import copy
import importlib.util
import json
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    "release10_projection", ROOT / "scripts/generate-approval-release10-projection-contracts.py")
PROJECTION = importlib.util.module_from_spec(spec)
spec.loader.exec_module(PROJECTION)
SOURCE = PROJECTION.module("release10_wave_test", "build-approval-release10-wave.py")


class ApprovalRelease10ProjectionContractsTest(unittest.TestCase):
    def setUp(self):
        self.baseline = {"version": 9, "routes": [{"routeContractKey": "route.approvals.work.old.data"}]}
        self.snapshot = {"version": 10, "bundleKey": "product-surfaces", "checksum": "a" * 64,
                         "routes": copy.deepcopy(self.baseline["routes"])}
        self.api = {"paths": {}, "components": {"schemas": {}}}
        for schema_key, fields in PROJECTION.SCHEMAS.items():
            self.api["components"]["schemas"][schema_key] = {
                "type": "object", "additionalProperties": False,
                "properties": {field: {"type": "string"} for field in sorted(fields)},
                "required": sorted(fields),
            }
        ceremony = self.api["components"]["schemas"]["ApprovalSignatureCeremony"]
        ceremony["properties"]["evidence"] = {"oneOf": [
            {"$ref": "#/components/schemas/ApprovalSignatureEvidence"}, {"type": "null"}]}
        ceremony["required"].remove("evidence")
        self.api["components"]["schemas"]["ApprovalSignatureEvidence"] = {
            "type": "object", "additionalProperties": False, "properties": {"evidenceId": {"type": "string"}}}
        metadata = self.api["components"]["schemas"]["ApprovalSignatureCommandReceiptMetadata"]["properties"]
        for field, (kind, fmt) in PROJECTION.COMMAND_RECEIPT_TYPES.items():
            metadata[field] = {"type": kind, **({"format": fmt} if fmt else {})}
        metadata["originalOperation"]["enum"] = ["CREATE", "CONSENT", "SIGN", "CANCEL"]
        metadata["resultState"]["enum"] = ["AWAITING_CONSENT", "CONSENTED", "ATTESTED", "CANCELLED"]
        planning = self.api["components"]["schemas"]["ApprovalWorkflowPlanningResult"]["properties"]
        for field, value in (("mode", "ROLE_POOL_PREVIEW"), ("runtimeEligibility", "NOT_EVALUATED"),
                             ("requesterExclusion", "NOT_EVALUATED")):
            planning[field]["enum"] = [value]
        for index, (key, (method, path, kind, schema_key)) in enumerate(PROJECTION.OPERATIONS.items()):
            binding = {"bindingKey": f"{key}.binding.01", "method": method, "path": path}
            self.snapshot["routes"].append({
                "routeContractKey": key, "routeKind": kind, "sideEffectFree": True if kind == "DATA" else None,
                "accessProfiles": [{"profileKey": "full-management" if ".admin." in key else "full-work"}],
                "servicePepBindings": [{**binding, "serviceKey": "approval"}],
                "gatewayApiBindings": [{**binding, "path": "/api/approvals" + path}],
            })
            envelope_key = f"Envelope{index}"
            self.api["components"]["schemas"][envelope_key] = {
                "type": "object", "properties": {"data": {"$ref": f"#/components/schemas/{schema_key}"}},
            }
            self.api["paths"].setdefault(path, {})[method.lower()] = {"responses": {"200": {"content": {
                "application/json": {"schema": {"$ref": f"#/components/schemas/{envelope_key}"}},
            }}}}

    def build(self, api=None, snapshot=None):
        return PROJECTION.build_contracts(api or self.api, snapshot or self.snapshot, baseline=self.baseline)

    def test_exact_eight_data_projections_include_readonly_post_and_all_action_response_graph(self):
        result = self.build()
        self.assertEqual(16, len(PROJECTION.OPERATIONS))
        self.assertEqual(8, result["bindingCount"])
        self.assertEqual(PROJECTION.DATA_KEYS, {binding["routeContractKey"] for binding in result["bindings"]})
        self.assertIn("ApprovalSignatureReceipt", result["schemas"])
        planning = next(value for value in result["bindings"] if "workflow-planning" in value["routeContractKey"])
        self.assertEqual("POST", planning["method"])
        self.assertEqual(6, len(PROJECTION.projection_metadata(planning)))
        self.assertEqual(result, self.build(copy.deepcopy(self.api), copy.deepcopy(self.snapshot)))
        self.assertEqual(PROJECTION.sha256(result["schemas"]), result["schemaClosureSha256"])
        material = {key: value for key, value in result.items() if key != "checksum"}
        self.assertEqual(PROJECTION.sha256(material), result["checksum"])

    def test_response_hash_is_unaffected_by_params_operation_ids_or_request_schema(self):
        before = self.build()
        api = copy.deepcopy(self.api)
        for path in api["paths"].values():
            for operation in path.values():
                operation.update(parameters=[{"name": "X-DWP-Policy-Revision", "in": "header"}],
                                 operationId="renumbered", requestBody={"content": {}})
        api["components"]["schemas"]["UnrelatedInput"] = {"type": "object"}
        self.assertEqual(before, self.build(api))

    def test_actual_operation_superset_preserves_old_components_and_only_renumbers_operation_ids(self):
        baseline = {"paths": {"/v1/old": {"get": {"operationId": "old", "responses": {}}}},
                    "components": {"schemas": {"OldRecord": {"type": "object"}}}}
        api = copy.deepcopy(self.api)
        api["paths"]["/v1/old"] = {"get": {"operationId": "old_3", "responses": {}}}
        api["components"]["schemas"].update(baseline["components"]["schemas"])
        PROJECTION.validate_operation_superset(api, baseline)
        for mutation in ("extra", "missing", "old-body", "old-component"):
            with self.subTest(mutation=mutation):
                changed = copy.deepcopy(api)
                if mutation == "extra":
                    changed["paths"]["/v1/unknown"] = {"get": {}}
                elif mutation == "missing":
                    changed["paths"].pop("/v1/old")
                elif mutation == "old-body":
                    changed["paths"]["/v1/old"]["get"]["requestBody"] = {}
                else:
                    changed["components"]["schemas"]["OldRecord"]["additionalProperties"] = False
                with self.assertRaises(PROJECTION.ContractError):
                    PROJECTION.validate_operation_superset(changed, baseline)

    def test_requires_exact_release_group_without_alias_extra_duplicate_or_missing(self):
        for mutation in ("alias", "extra", "duplicate", "missing", "old-missing", "version"):
            with self.subTest(mutation=mutation):
                snapshot = copy.deepcopy(self.snapshot)
                if mutation == "alias":
                    snapshot["routes"][-1]["routeContractKey"] += ".alias"
                elif mutation == "extra":
                    snapshot["routes"].append({"routeContractKey": "route.approvals.work.future.data"})
                elif mutation == "duplicate":
                    snapshot["routes"].append(snapshot["routes"][-1])
                elif mutation == "missing":
                    snapshot["routes"].pop()
                elif mutation == "old-missing":
                    snapshot["routes"].pop(0)
                else:
                    snapshot["version"] = 11
                with self.assertRaises(PROJECTION.ContractError):
                    self.build(snapshot=snapshot)

    def test_rejects_native_public_method_owner_kind_and_side_effect_drift(self):
        for target, field, value in (
            ("servicePepBindings", "path", "/v1/admin/retention/policies/{id}/draft"),
            ("servicePepBindings", "method", "HEAD"),
            ("servicePepBindings", "serviceKey", "auth"),
            ("gatewayApiBindings", "path", "/v1/admin/retention/policy"),
            ("gatewayApiBindings", "method", "POST"),
            ("gatewayApiBindings", "bindingKey", "borrowed"),
        ):
            with self.subTest(target=target, field=field):
                snapshot = copy.deepcopy(self.snapshot)
                snapshot["routes"][1][target][0][field] = value
                with self.assertRaises(PROJECTION.ContractError):
                    self.build(snapshot=snapshot)
        for field, value in (("routeKind", "ACTION"), ("sideEffectFree", False)):
            snapshot = copy.deepcopy(self.snapshot)
            snapshot["routes"][1][field] = value
            with self.assertRaises(PROJECTION.ContractError):
                self.build(snapshot=snapshot)

    def test_top_and_nested_actual_records_must_be_closed_and_exact(self):
        for schema_key in PROJECTION.SCHEMAS:
            for mutation in ("absent", "open", "extra-field", "missing-field", "wrong-type"):
                with self.subTest(schema=schema_key, mutation=mutation):
                    api = copy.deepcopy(self.api)
                    schema = api["components"]["schemas"][schema_key]
                    if mutation == "absent":
                        schema.pop("additionalProperties")
                    elif mutation == "open":
                        schema["additionalProperties"] = True
                    elif mutation == "extra-field":
                        schema["properties"]["privateAuthority"] = {"type": "string"}
                    elif mutation == "missing-field":
                        schema["properties"].pop(next(iter(schema["properties"])))
                    else:
                        schema["type"] = "string"
                    with self.assertRaises(PROJECTION.ContractError):
                        self.build(api)
        api = copy.deepcopy(self.api)
        api["components"]["schemas"]["ApprovalSignatureContext"]["properties"]["source"] = {
            "$ref": "#/components/schemas/ApprovalSignatureSourcePin"}
        api["components"]["schemas"]["ApprovalSignatureSourcePin"] = {"type": "object", "properties": {"requestId": {"type": "string"}}}
        with self.assertRaises(PROJECTION.ContractError):
            self.build(api)

    def test_nested_mutation_changes_closure_not_parent_reference_hash(self):
        api = copy.deepcopy(self.api)
        api["components"]["schemas"]["ApprovalSignatureContext"]["properties"]["source"] = {
            "$ref": "#/components/schemas/ApprovalSignatureSourcePin"}
        api["components"]["schemas"]["ApprovalSignatureSourcePin"] = {
            "type": "object", "additionalProperties": False, "properties": {"requestId": {"type": "string"}}}
        first = self.build(api)
        api["components"]["schemas"]["ApprovalSignatureSourcePin"]["properties"]["requestId"]["format"] = "uuid"
        second = self.build(api)
        self.assertNotEqual(first["schemaClosureSha256"], second["schemaClosureSha256"])
        self.assertNotEqual(first["checksum"], second["checksum"])
        before = next(value for value in first["bindings"] if "signature-context" in value["routeContractKey"])
        after = next(value for value in second["bindings"] if "signature-context" in value["routeContractKey"])
        self.assertEqual(before["openApiSchemaSha256"], after["openApiSchemaSha256"])

    def test_metadata_and_planning_required_fields_cannot_be_dropped(self):
        for key in ("ApprovalSignatureCommandReceiptMetadata", "ApprovalWorkflowPlanningResult"):
            api = copy.deepcopy(self.api)
            api["components"]["schemas"][key]["required"].pop()
            with self.assertRaises(PROJECTION.ContractError):
                self.build(api)

    def test_receipt_nonnullable_types_and_preview_enums_are_not_hash_only_semantics(self):
        for field in PROJECTION.COMMAND_RECEIPT_FIELDS:
            api = copy.deepcopy(self.api)
            prop = api["components"]["schemas"]["ApprovalSignatureCommandReceiptMetadata"]["properties"][field]
            prop["type"] = [prop["type"], "null"]
            with self.assertRaises(PROJECTION.ContractError):
                self.build(api)
        for field in ("mode", "runtimeEligibility", "requesterExclusion"):
            api = copy.deepcopy(self.api)
            api["components"]["schemas"]["ApprovalWorkflowPlanningResult"]["properties"][field]["enum"] = ["READY"]
            with self.assertRaises(PROJECTION.ContractError):
                self.build(api)

    def test_evidence_requires_explicit_31_ref_or_null_union_not_impossible_intersection(self):
        valid = copy.deepcopy(self.api)
        evidence = valid["components"]["schemas"]["ApprovalSignatureCeremony"]["properties"]["evidence"]
        evidence["anyOf"] = evidence.pop("oneOf")
        self.build(valid)
        for material in (
            {"$ref": "#/components/schemas/ApprovalSignatureEvidence", "type": "null"},
            {"$ref": "#/components/schemas/ApprovalSignatureEvidence", "type": ["object", "null"]},
            {"$ref": "#/components/schemas/ApprovalSignatureEvidence"},
            {"allOf": [{"$ref": "#/components/schemas/ApprovalSignatureEvidence"}, {"type": "null"}]},
            {"oneOf": [{"$ref": "#/components/schemas/ApprovalSignatureEvidence"}, {"type": "null"}], "type": "null"},
        ):
            with self.subTest(material=material):
                api = copy.deepcopy(self.api)
                api["components"]["schemas"]["ApprovalSignatureCeremony"]["properties"]["evidence"] = material
                with self.assertRaises(PROJECTION.ContractError):
                    self.build(api)
        api = copy.deepcopy(self.api)
        api["components"]["schemas"]["ApprovalSignatureCeremony"]["required"].append("evidence")
        with self.assertRaises(PROJECTION.ContractError):
            self.build(api)

    def test_nested_reference_null_type_sibling_cannot_hide_an_invalid_intersection(self):
        api = copy.deepcopy(self.api)
        api["components"]["schemas"]["ApprovalSignatureContext"]["properties"]["artifact"] = {
            "$ref": "#/components/schemas/ApprovalSignatureEvidence", "type": "null"}
        with self.assertRaises(PROJECTION.ContractError):
            self.build(api)

    def test_nullable_union_can_resolve_an_actual_null_only_component_not_object_or_unresolved_alias(self):
        api = copy.deepcopy(self.api)
        api["components"]["schemas"]["ActualNullEvidence"] = {"type": "null"}
        api["components"]["schemas"]["ApprovalSignatureCeremony"]["properties"]["evidence"] = {
            "oneOf": [{"$ref": "#/components/schemas/ApprovalSignatureEvidence"},
                      {"$ref": "#/components/schemas/ActualNullEvidence"}]}
        self.assertEqual({"type": "null"}, self.build(api)["schemas"]["ActualNullEvidence"])
        for material in ({}, {"type": "object", "additionalProperties": False, "properties": {}},
                         {"type": ["object", "null"]}, {"$ref": "#/components/schemas/Missing"}):
            changed = copy.deepcopy(api)
            changed["components"]["schemas"]["ActualNullEvidence"] = material
            with self.assertRaises(PROJECTION.ContractError):
                self.build(changed)

    def test_rejects_missing_response_unresolved_refs_binary_and_ambiguous_media(self):
        method, path, _, _ = next(iter(PROJECTION.OPERATIONS.values()))
        for mutation in ("missing", "unresolved", "binary", "ambiguous"):
            with self.subTest(mutation=mutation):
                api = copy.deepcopy(self.api)
                content = api["paths"][path][method.lower()]["responses"]["200"]["content"]
                if mutation == "missing":
                    api["paths"].pop(path)
                elif mutation == "unresolved":
                    content["application/json"]["schema"] = {"$ref": "#/components/schemas/Missing"}
                elif mutation == "binary":
                    content["application/json"]["schema"] = {"type": "string", "format": "binary"}
                else:
                    content["application/octet-stream"] = {"schema": {"type": "string", "format": "binary"}}
                with self.assertRaises(PROJECTION.ContractError):
                    self.build(api)
        api = copy.deepcopy(self.api)
        content = api["paths"][path][method.lower()]["responses"]["200"]["content"]
        content["application/octet-stream"] = content.pop("application/json")
        with self.assertRaises(PROJECTION.ContractError):
            self.build(api)

    def test_source_metadata_is_exact_and_actions_cannot_borrow_data_projection(self):
        result = self.build()
        by_key = {value["routeContractKey"]: value for value in result["bindings"]}
        for route in self.snapshot["routes"]:
            if route["routeContractKey"] in by_key:
                route["accessProfiles"][0]["responseProjectionBindings"] = [
                    PROJECTION.projection_metadata(by_key[route["routeContractKey"]])]
        PROJECTION.validate_projection_bindings(self.snapshot, result)
        self.snapshot["routes"][1]["accessProfiles"][0]["responseProjectionBindings"][0]["openApiSchemaSha256"] = "f" * 64
        with self.assertRaises(PROJECTION.ContractError):
            PROJECTION.validate_projection_bindings(self.snapshot, result)
        snapshot = copy.deepcopy(self.snapshot)
        snapshot["routes"][2]["accessProfiles"][0]["responseProjectionBindings"] = [{"responseSchemaKey": "borrowed"}]
        with self.assertRaises(PROJECTION.ContractError):
            self.build(snapshot=snapshot)

    def test_duplicate_json_keys_and_trailing_content_reject_before_generation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "source.json"
            for material in ('{"paths": {}, "paths": {}}', '{} {}'):
                path.write_text(material)
                with self.assertRaises((PROJECTION.ContractError, json.JSONDecodeError)):
                    PROJECTION.load_json(path)

    def test_unsealed_wave_exact_counts_and_independent_planning_form_authority(self):
        wave = SOURCE.build_wave(self.api)
        self.assertEqual(5, len(wave["capabilities"]))
        self.assertEqual(3, len(wave["predicatePolicies"]))
        self.assertEqual([], wave["accessPolicies"])
        self.assertEqual([], wave["entitlementExpressions"])
        self.assertEqual(16, len(wave["routes"]))
        self.assertEqual(8, sum(route["routeKind"] == "DATA" for route in wave["routes"]))
        self.assertEqual(8, sum(route["routeKind"] == "ACTION" for route in wave["routes"]))
        caps = {cap["contractKey"]: cap for cap in wave["capabilities"]}
        planning = next(route for route in wave["routes"] if route["routeContractKey"] == SOURCE.PLANNING)
        self.assertEqual({"type": "CAPABILITY_EXPRESSION", "mode": "ALL",
                          "capabilityContractKeys": sorted([SOURCE.PLANNING_FORM_READ, SOURCE.PLANNING_READ])},
                         planning["accessProfiles"][0]["requiredAccess"])
        self.assertEqual("ACTION.APPROVAL_FORM:VIEW", caps[SOURCE.PLANNING_FORM_READ]["resolvedCapabilityCode"])
        self.assertEqual("ADMIN.APPROVAL_WORKFLOW:UPDATE", caps[SOURCE.PLANNING_READ]["resolvedCapabilityCode"])
        self.assertTrue(planning["accessProfiles"][0]["readOnly"])
        self.assertNotIn("grants", wave)

    def test_unsealed_high_bindings_are_exact_native_targets_body_cas_not_fabricated_headers(self):
        wave = SOURCE.build_wave(self.api)
        high = {route["routeContractKey"]: route["stepUpCommandBindings"][0]
                for route in wave["routes"] if route.get("stepUpCommandBindings")}
        expected = {
            "route.approvals.work.signature-sign.action": ("APPROVAL_SIGNATURE_REQUEST", "signatureRequestId"),
            "route.approvals.admin.retention-policy-publish.action": ("RETENTION_POLICY", "policyId"),
            "route.approvals.admin.retention-record-claim.action": ("RETENTION_RECORD", "requestId"),
        }
        self.assertEqual(set(expected), set(high))
        for key, (target, parameter) in expected.items():
            self.assertEqual(target, high[key]["targetType"])
            self.assertEqual(parameter, high[key]["targetIdPathParameter"])
            self.assertEqual("COMMAND_BODY", high[key]["expectedObjectVersionSource"])
            self.assertEqual("expectedVersion", high[key]["expectedObjectVersionName"])
            self.assertEqual("dwp-approval-server", high[key]["audience"])
        for route in wave["routes"]:
            self.assertNotIn("X-DWP-Expected-Object-Version", json.dumps(route))

    def test_unsealed_signature_receipt_has_disjoint_readonly_original_command_predicate(self):
        wave = SOURCE.build_wave(self.api)
        caps = {cap["contractKey"]: cap for cap in wave["capabilities"]}
        self.assertEqual("ACTOR_OWNED_SIGNATURE_SOURCE_OR_ORIGINAL_COMMAND", caps[SOURCE.SIGNATURE_READ]["scopeResolver"])
        for route in wave["routes"]:
            if not route["routeContractKey"].startswith("route.approvals.work."):
                continue
            profile = route["accessProfiles"][0]
            self.assertNotIn("predicate.approval.own-request.v1", profile["predicatePolicyKeys"])
            if route["routeContractKey"] == SOURCE.SIGNATURE_RECEIPT:
                self.assertEqual("approval.signature.command-receipt.v1", profile["profileKey"])
                self.assertTrue(profile["readOnly"])
                self.assertEqual([SOURCE.RECEIPT_SOURCE], profile["predicatePolicyKeys"])
                self.assertNotIn("stepUpCommandBindings", route)
            else:
                self.assertEqual("full-work", profile["profileKey"])
                self.assertFalse(profile["readOnly"])
                self.assertIn(SOURCE.SIGNATURE_SOURCE, profile["predicatePolicyKeys"])


if __name__ == "__main__":
    unittest.main()
