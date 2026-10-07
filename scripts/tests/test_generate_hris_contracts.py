from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import re
import shutil
import sys
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GENERATOR_PATH = ROOT / "scripts/generate-hris-contracts.py"
SPEC = importlib.util.spec_from_file_location("generate_hris_contracts", GENERATOR_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("cannot load HRIS contract generator")
GENERATOR = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = GENERATOR
SPEC.loader.exec_module(GENERATOR)

EXPECTED_XCON_TYPES = {
    "com.dwp.contracts.hris.xcon.v1.Xcon001WorkerAssignmentSnapshotV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon002CompensationBasisSnapshotV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon003WorkerChangedV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon004EmploymentChangedV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon005AssignmentChangedV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon006OrganizationChangedV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon007CompensationBasisChangedV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon008ClosedTimeResultV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon009TimePeriodClosedV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon010TimePeriodReopenedV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon011PayrollTimeHandoffReadyV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon012PerformanceHomeContributionChangedV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon013PeopleHomeContributionChangedV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon014TimeLeaveHomeContributionChangedV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon015PayrollHomeContributionChangedV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon016WorkplaceAssignmentSnapshotV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon017WorkerDependentEligibilitySnapshotV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon018WorkerTaxIdentitySnapshotV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon019WorkerBankAccountTokenSnapshotV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon020ApprovedCompensationPlanSnapshotV1",
    "com.dwp.contracts.hris.xcon.v1.Xcon021ApprovedCompensationPlanSnapshotPublishedV2",
}

EXPECTED_PLATFORM_TYPES = {
    "com.dwp.platform.contracts.hris.generated.HrisConfigurationVersionV1",
    "com.dwp.platform.contracts.hris.generated.HrisConfigurationPublishedV1",
    "com.dwp.platform.contracts.hris.generated.HrisConfigurationInvalidatedV1",
    "com.dwp.platform.contracts.hris.generated.AutomationHandlerManifestV1",
    "com.dwp.platform.contracts.hris.generated.AutomationInvocationV1",
    "com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1",
    "com.dwp.platform.contracts.hris.generated.ConnectorMappingVersionV1",
    "com.dwp.platform.contracts.hris.generated.ConnectorExecutionRequestV1",
    "com.dwp.platform.contracts.hris.generated.ConnectorReconciliationReceiptV1",
    "com.dwp.platform.contracts.hris.generated.ApprovalRequestV1",
    "com.dwp.platform.contracts.hris.generated.ApprovalDecisionRecordedV1",
    "com.dwp.platform.contracts.hris.generated.HrisNotificationIntentV1",
    "com.dwp.platform.contracts.hris.generated.HrisNotificationReceiptV1",
    "com.dwp.platform.contracts.hris.generated.HrisAuditOutboxEventV1",
}

EXPECTED_FIELDS = {
    "Xcon001WorkerAssignmentSnapshotV1": ("asOf", "assignments", "personPublicId", "relationships", "snapshotVersion", "workerPublicId", "workerStatus"),
    "Xcon002CompensationBasisSnapshotV1": ("amount", "asOf", "assignmentPublicId", "basisType", "compensationBasisPublicId", "currency", "frequency", "legalEntityPublicId", "payGroupPublicId", "payloadDigest", "snapshotVersion", "sourceRevision", "validFrom", "validTo", "workerPublicId"),
    "Xcon003WorkerChangedV1": ("changeType", "effectiveDate", "newAggregateVersion", "workerPublicId"),
    "Xcon004EmploymentChangedV1": ("changeType", "effectiveDate", "newAggregateVersion", "relationshipPublicId"),
    "Xcon005AssignmentChangedV1": ("assignmentPublicId", "changedReferences", "effectiveDate", "eventPublicId", "eventType", "newAggregateVersion"),
    "Xcon006OrganizationChangedV1": ("changedOrganizationPublicIds", "effectiveDate", "revisionPublicId", "snapshotDigest"),
    "Xcon007CompensationBasisChangedV1": ("assignmentPublicId", "basisType", "compensationBasisPublicId", "newAggregateVersion", "validFrom", "validTo"),
    "Xcon008ClosedTimeResultV1": ("closePeriodId", "closeRevision", "closedTimeResultId", "ledgerRevisionFrom", "ledgerRevisionTo", "lineCount", "lines", "payPeriodId", "payloadDigest", "payrollEntityId", "ruleDigest", "sourceDigest", "supersedesResultId", "workerCount"),
    "Xcon009TimePeriodClosedV1": ("closePeriodId", "closeRevision", "ledgerRevision", "periodEnd", "periodStart", "ruleDigest", "scopeId", "sourceDigest"),
    "Xcon010TimePeriodReopenedV1": ("closePeriodId", "newRevision", "priorCloseRevision", "reasonCode"),
    "Xcon011PayrollTimeHandoffReadyV1": ("closePeriodId", "closeRevision", "closedTimeResultId", "handoffId", "lineCount", "payPeriodId", "payloadDigest", "payrollEntityId", "ruleDigest", "sourceDigest", "workerCount"),
    "Xcon012PerformanceHomeContributionChangedV1": ("ownerPopulationScope", "freshUntil", "generatedAt", "module", "payloadDigest", "payloadRef", "scopeRevision", "staleAfter", "state", "widgetKey"),
    "Xcon013PeopleHomeContributionChangedV1": ("ownerPopulationScope", "freshUntil", "generatedAt", "module", "payloadDigest", "payloadRef", "scopeRevision", "staleAfter", "state", "widgetKey"),
    "Xcon014TimeLeaveHomeContributionChangedV1": ("ownerPopulationScope", "freshUntil", "generatedAt", "module", "payloadDigest", "payloadRef", "scopeRevision", "staleAfter", "state", "widgetKey"),
    "Xcon015PayrollHomeContributionChangedV1": ("ownerPopulationScope", "freshUntil", "generatedAt", "module", "payloadDigest", "payloadRef", "scopeRevision", "staleAfter", "state", "widgetKey"),
    "Xcon016WorkplaceAssignmentSnapshotV1": ("assignmentId", "calendarId", "effectiveFrom", "effectiveTo", "payloadDigest", "snapshotId", "snapshotRevision", "sourceVersion", "timeZone", "workLocationCode", "workerId", "workplaceId"),
    "Xcon017WorkerDependentEligibilitySnapshotV1": ("dependentPublicId", "effectiveFrom", "effectiveTo", "eligibilityFacts", "payloadDigest", "relationshipType", "snapshotId", "snapshotRevision", "sourceVersion", "workerId"),
    "Xcon018WorkerTaxIdentitySnapshotV1": ("effectiveFrom", "effectiveTo", "payloadDigest", "residencyStatus", "snapshotId", "snapshotRevision", "sourceVersion", "taxIdentifierToken", "taxJurisdictionCode", "workerClassification", "workerId"),
    "Xcon019WorkerBankAccountTokenSnapshotV1": ("bankAccountToken", "currency", "effectiveFrom", "effectiveTo", "maskedAccountDisplay", "payloadDigest", "paymentPriority", "snapshotId", "snapshotRevision", "sourceVersion", "workerId"),
    "Xcon020ApprovedCompensationPlanSnapshotV1": ("approvalReceiptId", "cycleId", "effectiveFrom", "effectiveTo", "lineCount", "lines", "payloadDigest", "planId", "snapshotId", "snapshotRevision", "sourceVersion"),
    "Xcon021ApprovedCompensationPlanSnapshotPublishedV2": ("aggregateId", "aggregateVersion", "approvalReceiptId", "approvalRevision", "correlationId", "cycleId", "effectiveDate", "fromState", "lineCount", "occurredAt", "payloadDigest", "planId", "snapshotId", "snapshotRevision", "sourceVersion", "toState"),
    "HrisConfigurationVersionV1": ("configurationId", "configurationType", "scopeType", "scopeId", "version", "state", "effectiveFrom", "effectiveTo", "payloadSchemaVersion", "payloadDigest", "approvalReceiptId"),
    "HrisConfigurationPublishedV1": ("configurationId", "configurationType", "scopeType", "scopeId", "version", "effectiveFrom", "effectiveTo", "payloadSchemaVersion", "payloadDigest", "approvalReceiptId", "publishedAt", "eventSequence"),
    "HrisConfigurationInvalidatedV1": ("configurationId", "configurationType", "scopeType", "scopeId", "invalidatedVersion", "replacementVersion", "reasonCode", "invalidatedAt", "eventSequence"),
    "AutomationHandlerManifestV1": ("handlerKey", "ownerService", "contractVersion", "inputSchemaRef", "receiptSchemaRef", "signatureDigest", "allowedScopeTypes", "retryMode", "maxAttempts", "state"),
    "AutomationInvocationV1": ("invocationId", "automationId", "automationVersion", "handlerKey", "handlerContractVersion", "scopeType", "scopeId", "requestedAt", "requestedByActorRef", "idempotencyKey", "inputRef", "inputDigest", "expectedItemCount", "correlationId"),
    "AutomationRunItemReceiptV1": ("runId", "runRevision", "itemId", "itemSequence", "subjectRef", "state", "attemptNumber", "acceptedAt", "completedAt", "resultRef", "resultDigest", "errorCode", "retryable", "nextAttemptAt", "correlationId"),
    "ConnectorMappingVersionV1": ("connectorId", "mappingId", "version", "state", "sourceSchemaVersion", "targetSchemaVersion", "mappingDigest", "dryRunDigest", "authoredByActorRef", "approvedByActorRef", "approvalReceiptId", "effectiveFrom", "effectiveTo", "compatibilityMode"),
    "ConnectorExecutionRequestV1": ("executionId", "connectorId", "mappingId", "mappingVersion", "mode", "direction", "scopeType", "scopeId", "sourceRef", "sourceDigest", "requestedAt", "requestedByActorRef", "idempotencyKey", "correlationId"),
    "ConnectorReconciliationReceiptV1": ("executionId", "receiptId", "mappingVersion", "state", "sourceCount", "acceptedCount", "rejectedCount", "quarantinedCount", "sourceDigest", "resultDigest", "errorReportRef", "reconciliationDigest", "completedAt", "correlationId"),
    "ApprovalRequestV1": ("requestId", "workflowKey", "workflowVersion", "subjectRef", "subjectRevision", "requesterRef", "requestedAction", "payloadRef", "payloadDigest", "state", "requestedAt", "correlationId", "idempotencyKey"),
    "ApprovalDecisionRecordedV1": ("eventId", "requestId", "workflowVersion", "subjectRef", "subjectRevision", "requestRevision", "decision", "decisionActorRef", "decisionAt", "approvalReceiptId", "reasonCode", "decisionDigest", "correlationId"),
    "HrisNotificationIntentV1": ("intentId", "sourceEventId", "tenantId", "producerAppKey", "typeKey", "purposeCode", "recipientUserIds", "excludedUserIds", "threadRef", "locale", "reasonCode", "actorRef", "subjectRef", "targetRef", "templateKey", "templateVersion", "templateModelDigest", "classification", "actionRequired", "dueAt", "requestedAt", "idempotencyKey", "correlationId"),
    "HrisNotificationReceiptV1": ("receiptId", "intentId", "sourceEventId", "tenantId", "recipientUserId", "receiptSequence", "state", "channel", "decisionCode", "reasonCode", "notificationId", "policyRevision", "templateVersion", "materializedDigest", "providerReceiptDigest", "attemptNumber", "occurredAt", "errorCode", "retryable", "nextAttemptAt", "correlationId"),
    "HrisAuditOutboxEventV1": ("eventId", "tenantId", "occurredAt", "sourceService", "sourceModule", "aggregateType", "aggregateRef", "aggregateRevision", "action", "outcome", "actorType", "actorRef", "effectiveCapability", "purposeCode", "authorizationDecisionRef", "scopeRevision", "fieldPolicyRevision", "delegationRef", "supportSessionRef", "stepUpReceiptRef", "approvalReceiptId", "targetRef", "reasonCode", "errorCode", "beforeDigest", "afterDigest", "evidenceDigest", "classification", "retentionClass", "causationId", "correlationId"),
}

EXPECTED_SOURCE_DIGESTS = {
    "xcon-v1": "f6fb63f52e2b6043b62f8afdd87b7acdbd89fe9db3330d8a2818be9e5c8326c4",
    "platform-dependency-v1": "5778f82aa3b76511a19dc3df47f4fb1d2f51502d96906197abbe143359eddfd4",
}

EXPECTED_MANIFEST_DIGEST = "7b43a95aa815cd622b12b6b2fa19c3695501917bcd88dc08a900ff58f23fd2fa"

SESSION_MODULES = {
    "HRIS-HRM": "dwp-people-server",
    "HRIS-PER": "dwp-people-server",
    "HRIS-TIM": "dwp-time-server",
    "HRIS-PAY": "dwp-payroll-server",
    "HRIS-SYS": "dwp-platform-server",
    "SYS_PLATFORM": "dwp-platform-server",
    "DWP_APPROVAL": "dwp-approval-server",
    "DWP_NOTIFICATION": "dwp-notification-server",
    "DWP_AUDIT": "dwp-core",
}


class HrisContractGeneratorTest(unittest.TestCase):

    def temporary_root(self) -> tempfile.TemporaryDirectory[str]:
        temporary = tempfile.TemporaryDirectory()
        destination = Path(temporary.name) / "contracts/hris/canonical"
        destination.parent.mkdir(parents=True)
        shutil.copytree(ROOT / "contracts/hris/canonical", destination)
        return temporary

    @staticmethod
    def manifest_path(root: Path) -> Path:
        return root / GENERATOR.MANIFEST_PATH

    def mutate_manifest(self, root: Path, mutation: object) -> None:
        path = self.manifest_path(root)
        manifest = json.loads(path.read_text(encoding="utf-8"))
        mutation(manifest)
        path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    def test_source_digests_and_exact_import_and_field_closure_are_pinned(self) -> None:
        manifest = json.loads(
            (ROOT / GENERATOR.MANIFEST_PATH).read_text(encoding="utf-8")
        )
        self.assertEqual(EXPECTED_MANIFEST_DIGEST, GENERATOR.canonical_digest(manifest))
        sources, contracts = GENERATOR.load_contracts(ROOT)
        self.assertEqual(EXPECTED_SOURCE_DIGESTS, {
            source.source_id: hashlib.sha256(source.schema_path.read_bytes()).hexdigest()
            for source in sources
        })
        xcon = {contract.java_type for contract in contracts if contract.source.source_id == "xcon-v1"}
        platform = {
            contract.java_type
            for contract in contracts
            if contract.source.source_id == "platform-dependency-v1"
        }
        self.assertEqual(EXPECTED_XCON_TYPES, xcon)
        self.assertEqual(EXPECTED_PLATFORM_TYPES, platform)
        self.assertEqual(35, len(contracts))

        outputs = GENERATOR.expected_outputs(ROOT)
        self.assertEqual(37, len(outputs))
        for contract in contracts:
            with self.subTest(java_type=contract.java_type):
                expected_fields = EXPECTED_FIELDS[contract.class_name]
                self.assertEqual(expected_fields, tuple(contract.schema["properties"]))
                self.assertEqual(set(expected_fields), set(contract.schema["required"]))
                relative = (
                    GENERATOR.JAVA_OUTPUT_ROOT
                    / Path(*contract.package_name.split("."))
                    / f"{contract.class_name}.java"
                )
                generated = outputs[relative]
                self.assertIn(f"package {contract.package_name};", generated)
                match = re.search(
                    rf"public record {re.escape(contract.class_name)}\(\n(?P<body>.*?)\) \{{",
                    generated,
                    re.DOTALL,
                )
                self.assertIsNotNone(match)
                component_names = tuple(
                    line.strip().rstrip(",").rsplit(" ", 1)[1]
                    for line in match.group("body").splitlines()
                )
                self.assertEqual(expected_fields, component_names)

    def test_write_is_deterministic_and_check_is_read_only(self) -> None:
        with self.temporary_root() as directory:
            root = Path(directory)
            self.assertEqual(0, GENERATOR.write_outputs(root))
            first = {
                path.relative_to(root): path.read_bytes()
                for path in root.glob("dwp-platform-contracts/src/main/java/**/*.java")
            }
            self.assertEqual(37, len(first))
            self.assertEqual([], GENERATOR.check_outputs(root))
            self.assertEqual(0, GENERATOR.write_outputs(root))
            second = {
                path.relative_to(root): path.read_bytes()
                for path in root.glob("dwp-platform-contracts/src/main/java/**/*.java")
            }
            self.assertEqual(first, second)

    def test_every_registered_producer_and_consumer_has_one_shared_dependency(self) -> None:
        manifest = json.loads(
            (ROOT / GENERATOR.MANIFEST_PATH).read_text(encoding="utf-8")
        )
        participants = {
            participant
            for contract in manifest["contracts"]
            for participant in [contract["producer"], *contract["consumers"]]
        }
        self.assertEqual(set(), participants - set(SESSION_MODULES))
        required_modules = {SESSION_MODULES[participant] for participant in participants}
        self.assertEqual(
            {
                "dwp-people-server",
                "dwp-time-server",
                "dwp-payroll-server",
                "dwp-platform-server",
                "dwp-approval-server",
                "dwp-notification-server",
                "dwp-core",
            },
            required_modules,
        )
        dependency = "implementation project(':dwp-platform-contracts')"
        for module in sorted(required_modules):
            with self.subTest(module=module):
                build = (ROOT / module / "build.gradle").read_text(encoding="utf-8")
                self.assertEqual(1, build.count(dependency))

    def test_tampered_schema_digest_is_rejected(self) -> None:
        with self.temporary_root() as directory:
            root = Path(directory)
            schema = root / "contracts/hris/canonical/schemas/cross-module-canonical-schemas.v1.json"
            schema.write_text(schema.read_text(encoding="utf-8") + "\n", encoding="utf-8")
            with self.assertRaisesRegex(GENERATOR.GenerationFailure, "schema digest drift"):
                GENERATOR.load_contracts(root)

    def test_supported_conditional_is_bound_and_unsupported_shapes_fail_closed(self) -> None:
        sources, _ = GENERATOR.load_contracts(ROOT)
        source = next(item for item in sources if item.source_id == "xcon-v1")
        schema = source.document["$defs"]["ClosedTimeLine"]
        specs = GENERATOR.conditional_specs(schema, source, "ClosedTimeLine")
        self.assertEqual(1, len(specs))
        self.assertEqual(("unit", "AMOUNT"), specs[0][:2])
        rendered = "\n".join(
            GENERATOR.extension_validation_lines(
                schema, source, "ClosedTimeLine", ""
            )
        )
        self.assertIn("Currency.getInstance(currency)", rendered)
        self.assertIn("recognized ISO 4217 code", rendered)

        invalid_schemas = []
        unsupported_keyword = copy.deepcopy(schema)
        unsupported_keyword["oneOf"] = []
        invalid_schemas.append(unsupported_keyword)
        missing_else = copy.deepcopy(schema)
        del missing_else["allOf"][0]["else"]
        invalid_schemas.append(missing_else)
        unknown_selector = copy.deepcopy(schema)
        unknown_selector["allOf"][0]["if"]["properties"] = {
            "unknown": {"const": "AMOUNT"}
        }
        unknown_selector["allOf"][0]["if"]["required"] = ["unknown"]
        invalid_schemas.append(unknown_selector)
        incompatible_branch = copy.deepcopy(schema)
        incompatible_branch["allOf"][0]["then"]["properties"]["currency"] = {
            "type": "integer"
        }
        invalid_schemas.append(incompatible_branch)

        for invalid in invalid_schemas:
            with self.subTest(schema=invalid):
                with self.assertRaises(GENERATOR.GenerationFailure):
                    GENERATOR.conditional_specs(invalid, source, "ClosedTimeLine")

    def test_java_type_mapping_covers_canonical_scalars_collections_and_maps(self) -> None:
        sources, _ = GENERATOR.load_contracts(ROOT)
        source = next(item for item in sources if item.source_id == "xcon-v1")
        mappings = [
            ({"$ref": "#/$defs/Uuid"}, "UUID"),
            ({"$ref": "#/$defs/Instant"}, "Instant"),
            ({"$ref": "#/$defs/Date"}, "LocalDate"),
            ({"$ref": "#/$defs/NullableDate"}, "LocalDate"),
            ({"$ref": "#/$defs/InputAmount"}, "BigDecimal"),
            ({"$ref": "#/$defs/PositiveRevision"}, "long"),
            ({"type": "integer", "minimum": 0, "maximum": 10}, "int"),
            ({"type": "boolean"}, "boolean"),
            ({"type": "array", "items": {"type": "string"}}, "List<String>"),
            (
                {
                    "type": "object",
                    "additionalProperties": {
                        "type": ["integer", "null"],
                        "minimum": 0,
                        "maximum": 10,
                    },
                },
                "Map<String, Integer>",
            ),
        ]
        for schema, expected in mappings:
            with self.subTest(schema=schema):
                self.assertEqual(expected, GENERATOR.java_type(schema, source))

    def test_array_item_constraints_are_generated_and_item_validator_tamper_is_rejected(self) -> None:
        sources, contracts = GENERATOR.load_contracts(ROOT)
        outputs = GENERATOR.expected_outputs(ROOT)
        by_definition = {contract.definition: contract for contract in contracts}

        assignment = by_definition["XCON_005"]
        assignment_source = outputs[
            GENERATOR.JAVA_OUTPUT_ROOT
            / Path(*assignment.package_name.split("."))
            / f"{assignment.class_name}.java"
        ]
        self.assertIn(
            "for (String changedReferencesItem : changedReferences)",
            assignment_source,
        )
        self.assertIn(
            'Pattern.matches("^[A-Z0-9][A-Z0-9._-]{0,79}$", changedReferencesItem)',
            assignment_source,
        )

        automation = by_definition["PDX_004"]
        automation_source = outputs[
            GENERATOR.JAVA_OUTPUT_ROOT
            / Path(*automation.package_name.split("."))
            / f"{automation.class_name}.java"
        ]
        self.assertIn(
            "for (String allowedScopeTypesItem : allowedScopeTypes)",
            automation_source,
        )
        self.assertIn(
            'Set.of("GLOBAL", "TENANT", "LEGAL_ENTITY", "ORGANIZATION", "POPULATION", "SUBJECT").contains(allowedScopeTypesItem)',
            automation_source,
        )

        with self.temporary_root() as directory:
            root = Path(directory)
            GENERATOR.write_outputs(root)
            target = (
                root
                / GENERATOR.JAVA_OUTPUT_ROOT
                / Path(*assignment.package_name.split("."))
                / f"{assignment.class_name}.java"
            )
            content = target.read_text(encoding="utf-8")
            item_guard = (
                "        for (String changedReferencesItem : changedReferences) {\n"
                "            Objects.requireNonNull(changedReferencesItem, \"changedReferencesItem must not be null\");\n"
                "            if (!Pattern.matches(\"^[A-Z0-9][A-Z0-9._-]{0,79}$\", changedReferencesItem)) {\n"
                "                throw new IllegalArgumentException(\"changedReferencesItem does not match its canonical format\");\n"
                "            }\n"
                "        }\n"
            )
            self.assertIn(item_guard, content)
            target.write_text(content.replace(item_guard, "", 1), encoding="utf-8")
            self.assertTrue(
                any(
                    "source drift" in violation
                    for violation in GENERATOR.check_outputs(root)
                )
            )

    def test_decimal_wire_codec_is_generated_from_schema_and_tamper_is_rejected(self) -> None:
        sources, contracts = GENERATOR.load_contracts(ROOT)
        outputs = GENERATOR.expected_outputs(ROOT)
        codec = outputs[GENERATOR.DECIMAL_CODEC_PATH]
        xcon_source = next(item for item in sources if item.source_id == "xcon-v1")
        for value_type, definition_name in {
            "InputAmount": "InputAmount",
            "QuantityDecimal": "QuantityDecimal",
            "PostedMoney": "PostedMoneyAmount",
        }.items():
            with self.subTest(value_type=value_type):
                pattern = xcon_source.document["$defs"][definition_name]["pattern"]
                self.assertIn(
                    f"Pattern.compile({GENERATOR.java_string(pattern)})",
                    codec,
                )
                self.assertIn(
                    f"class {GENERATOR.DECIMAL_DESERIALIZERS[value_type]}",
                    codec,
                )
        self.assertIn("parser.hasToken(JsonToken.VALUE_STRING)", codec)
        self.assertIn("generator.writeString(canonicalString(value))", codec)
        self.assertIn("return BigDecimal.ZERO", codec)
        self.assertIn("return value.stripTrailingZeros()", codec)

        for contract in contracts:
            relative = (
                GENERATOR.JAVA_OUTPUT_ROOT
                / Path(*contract.package_name.split("."))
                / f"{contract.class_name}.java"
            )
            generated = outputs[relative]
            for name, raw_schema in contract.schema["properties"].items():
                value_type = GENERATOR.decimal_value_type(raw_schema, contract.source)
                if value_type is not None:
                    self.assertIn(
                        f"CanonicalDecimalJson.{GENERATOR.DECIMAL_DESERIALIZERS[value_type]}.class",
                        generated,
                    )
                    self.assertIn(
                        f"{name} = CanonicalDecimalJson.normalize({name});",
                        generated,
                    )

        by_definition = {contract.definition: contract for contract in contracts}
        for definition, field_name, value_type in (
            ("XCON_002", "amount", "InputAmount"),
            ("XCON_008", "quantity", "QuantityDecimal"),
            ("XCON_020", "approvedAmount", "PostedMoney"),
        ):
            contract = by_definition[definition]
            relative = (
                GENERATOR.JAVA_OUTPUT_ROOT
                / Path(*contract.package_name.split("."))
                / f"{contract.class_name}.java"
            )
            generated = outputs[relative]
            self.assertIn(
                f"CanonicalDecimalJson.{GENERATOR.DECIMAL_DESERIALIZERS[value_type]}.class",
                generated,
            )
            self.assertIn(
                f"{field_name} = CanonicalDecimalJson.normalize({field_name});",
                generated,
            )

        with self.assertRaisesRegex(
            GENERATOR.GenerationFailure,
            "unsupported canonical decimal value type",
        ):
            GENERATOR.java_type(
                {
                    "type": "string",
                    "pattern": "^[0-9]+$",
                    "x-valueType": "BinaryFloat",
                },
                xcon_source,
            )

        with self.temporary_root() as directory:
            root = Path(directory)
            GENERATOR.write_outputs(root)
            target = root / GENERATOR.DECIMAL_CODEC_PATH
            content = target.read_text(encoding="utf-8")
            self.assertIn("JsonToken.VALUE_STRING", content)
            target.write_text(
                content.replace(
                    "JsonToken.VALUE_STRING",
                    "JsonToken.VALUE_NUMBER_FLOAT",
                    1,
                ),
                encoding="utf-8",
            )
            self.assertTrue(
                any(
                    "source drift" in violation
                    for violation in GENERATOR.check_outputs(root)
                )
            )

    def test_notification_and_audit_domain_invariants_are_generated_and_tamper_detected(self) -> None:
        _, contracts = GENERATOR.load_contracts(ROOT)
        outputs = GENERATOR.expected_outputs(ROOT)
        by_definition = {contract.definition: contract for contract in contracts}
        expected_guards = {
            "PDX_012": "recipientUserIds.stream().allMatch(excludedUserIds::contains)",
            "PDX_013": 'boolean postMaterialization = Set.of("MATERIALIZED", "QUEUED", "DELIVERED",',
            "PDX_014": 'boolean unsuccessful = "DENIED".equals(outcome)',
        }
        for definition, guard in expected_guards.items():
            contract = by_definition[definition]
            path = (
                GENERATOR.JAVA_OUTPUT_ROOT
                / Path(*contract.package_name.split("."))
                / f"{contract.class_name}.java"
            )
            with self.subTest(definition=definition):
                self.assertIn(guard, outputs[path])

        tamper_guards = {
            "PDX_012": (
                "        if (recipientUserIds.stream().allMatch(excludedUserIds::contains)) {\n"
            ),
            "PDX_013": (
                '        boolean postMaterialization = Set.of("MATERIALIZED", "QUEUED", "DELIVERED",\n'
            ),
            "PDX_014": (
                '        boolean unsuccessful = "DENIED".equals(outcome) || "FAILED".equals(outcome);\n'
            ),
        }
        for definition, guard in tamper_guards.items():
            with self.subTest(tamper=definition), self.temporary_root() as directory:
                root = Path(directory)
                GENERATOR.write_outputs(root)
                contract = by_definition[definition]
                target = (
                    root
                    / GENERATOR.JAVA_OUTPUT_ROOT
                    / Path(*contract.package_name.split("."))
                    / f"{contract.class_name}.java"
                )
                content = target.read_text(encoding="utf-8")
                self.assertEqual(1, content.count(guard))
                target.write_text(content.replace(guard, "", 1), encoding="utf-8")
                self.assertTrue(
                    any(
                        "source drift" in violation
                        for violation in GENERATOR.check_outputs(root)
                    )
                )

    def test_strict_wire_codecs_cover_every_property_once_and_detect_tamper(self) -> None:
        sources, contracts = GENERATOR.load_contracts(ROOT)
        outputs = GENERATOR.expected_outputs(ROOT)
        codec = outputs[GENERATOR.STRICT_JSON_CODEC_PATH]
        for serializer, expression in {
            "UuidSerializer": "generator.writeString(value.toString())",
            "LocalDateSerializer": "generator.writeString(value.toString())",
            "InstantSerializer": "generator.writeString(value.toString())",
        }.items():
            with self.subTest(serializer=serializer):
                self.assertEqual(1, codec.count(f"class {serializer}"))
                self.assertIn(expression, codec)
        for reader, token in {
            "readString": "JsonToken.VALUE_STRING",
            "readInt": "JsonToken.VALUE_NUMBER_INT",
            "readLong": "JsonToken.VALUE_NUMBER_INT",
            "readNumber": "JsonToken.VALUE_NUMBER_FLOAT",
            "readBoolean": "JsonToken.VALUE_TRUE",
            "readObject": "JsonToken.START_OBJECT",
        }.items():
            with self.subTest(reader=reader):
                self.assertIn(f" {reader}(", codec)
                self.assertIn(token, codec)

        for contract in contracts:
            schemas = [(contract.class_name, contract.definition, contract.schema)]
            schemas.extend(
                (
                    name,
                    name,
                    GENERATOR.require_object(contract.source.document["$defs"][name], name),
                )
                for name in GENERATOR.referenced_object_definitions(
                    contract.schema, contract.source
                )
            )
            for class_name, schema_key, schema in schemas:
                with self.subTest(contract=contract.definition, record=class_name):
                    rendered = "\n".join(GENERATOR.render_record(
                        class_name,
                        schema_key,
                        schema,
                        contract.source,
                        "",
                        True,
                    ))
                    self.assertEqual(
                        1,
                        rendered.count("@JsonInclude(JsonInclude.Include.ALWAYS)"),
                    )
                    self.assertEqual(
                        1,
                        rendered.count(
                            f"@JsonDeserialize(using = {class_name}.Deserializer.class)"
                        ),
                    )
                    required = set(schema["required"])
                    self.assertEqual(set(schema["properties"]), required)
                    for name, raw_schema in schema["properties"].items():
                        self.assertEqual(1, rendered.count(f'case "{name}" -> {{'))
                        self.assertEqual(1, rendered.count(f"{name}Seen = true;"))
                        self.assertEqual(1, rendered.count(f"if (!{name}Seen) {{"))
                        resolved, _ = GENERATOR.dereference(
                            GENERATOR.require_object(raw_schema, name),
                            contract.source,
                        )
                        if "array" in GENERATOR.accepted_types(resolved):
                            assignment = f"{name} = List.copyOf({name}Values);"
                        else:
                            expression = GENERATOR.strict_read_expression(
                                name,
                                raw_schema,
                                contract.source,
                                True,
                            )
                            assignment = f"{name} = {expression};"
                        self.assertEqual(
                            1,
                            rendered.count(assignment),
                            f"{contract.definition}.{class_name}.{name}",
                        )
                        serializer_by_format = {
                            "uuid": "UuidSerializer",
                            "date": "LocalDateSerializer",
                            "date-time": "InstantSerializer",
                        }
                        if resolved.get("format") in serializer_by_format:
                            serializer = serializer_by_format[resolved["format"]]
                            rendered_type = GENERATOR.java_type(
                                raw_schema,
                                contract.source,
                                True,
                            )
                            self.assertEqual(
                                1,
                                rendered.count(
                                    "@JsonSerialize(using = "
                                    f"CanonicalHrisJson.{serializer}.class) "
                                    f"{rendered_type} {name}"
                                ),
                            )
                        if "array" in GENERATOR.accepted_types(resolved):
                            item_schema = GENERATOR.require_object(
                                resolved.get("items"), f"{name}.items"
                            )
                            item_resolved, _ = GENERATOR.dereference(
                                item_schema, contract.source
                            )
                            if item_resolved.get("format") in serializer_by_format:
                                serializer = serializer_by_format[
                                    item_resolved["format"]
                                ]
                                rendered_type = GENERATOR.java_type(
                                    raw_schema,
                                    contract.source,
                                    True,
                                )
                                self.assertEqual(
                                    1,
                                    rendered.count(
                                        "@JsonSerialize(contentUsing = "
                                        f"CanonicalHrisJson.{serializer}.class) "
                                        f"{rendered_type} {name}"
                                    ),
                                )

        source = next(item for item in sources if item.source_id == "platform-dependency-v1")
        incomplete = copy.deepcopy(source.document["$defs"]["PDX_005"])
        incomplete["required"].remove("scopeId")
        with self.assertRaisesRegex(
            GENERATOR.GenerationFailure,
            "required fields must exactly close properties",
        ):
            GENERATOR.render_record(
                "AutomationInvocationV1",
                "PDX_005",
                incomplete,
                source,
                "",
                True,
            )

        with self.temporary_root() as directory:
            root = Path(directory)
            GENERATOR.write_outputs(root)
            target = (
                root
                / GENERATOR.JAVA_OUTPUT_ROOT
                / "com/dwp/platform/contracts/hris/generated/AutomationInvocationV1.java"
            )
            content = target.read_text(encoding="utf-8")
            guard = "                    scopeIdSeen = true;\n"
            self.assertEqual(1, content.count(guard))
            target.write_text(content.replace(guard, "", 1), encoding="utf-8")
            self.assertTrue(
                any(
                    "source drift" in violation
                    for violation in GENERATOR.check_outputs(root)
                )
            )

    def test_all_schema_extensions_are_digested_owned_and_classified(self) -> None:
        manifest = json.loads(
            (ROOT / GENERATOR.MANIFEST_PATH).read_text(encoding="utf-8")
        )
        sources, _ = GENERATOR.load_contracts(ROOT)
        by_id = {source.source_id: source for source in sources}
        bindings = manifest["extensionBindings"]
        self.assertEqual(39, len(bindings))
        GENERATOR.validate_extension_bindings(bindings, by_id)
        causal = next(
            binding
            for binding in bindings
            if binding["schemaPointer"] == "#/$defs/XCON_021/x-causal-contract"
        )
        self.assertEqual("HRIS-PER|HRIS-PAY", causal["owner"])
        self.assertEqual(
            {name: ["DOMAIN_RUNTIME"] for name in (
                "emission", "runtimeVersion", "dualPublish", "payTrigger"
            )},
            causal["memberClassifications"],
        )

        runtime_members = 0
        generated_members = 0
        for binding in bindings:
            for classifications in binding["memberClassifications"].values():
                runtime_members += "DOMAIN_RUNTIME" in classifications
                generated_members += "GENERATED_CONSTRUCTOR" in classifications
        self.assertGreater(runtime_members, 0)
        self.assertEqual(len(GENERATOR.LOCAL_EXTENSION_VALUES), generated_members)

        missing = copy.deepcopy(bindings)
        missing.pop()
        duplicate = copy.deepcopy(bindings)
        duplicate.append(copy.deepcopy(duplicate[0]))
        unclassified = copy.deepcopy(bindings)
        first_member = next(iter(unclassified[-1]["memberClassifications"]))
        unclassified[-1]["memberClassifications"][first_member] = []
        falsely_local = copy.deepcopy(bindings)
        falsely_local[1]["memberClassifications"]["$value"] = [
            "GENERATED_CONSTRUCTOR"
        ]
        for name, mutated in {
            "missing": missing,
            "duplicate": duplicate,
            "unclassified": unclassified,
            "no-local-handler": falsely_local,
        }.items():
            with self.subTest(mutation=name):
                with self.assertRaises(GENERATOR.GenerationFailure):
                    GENERATOR.validate_extension_bindings(mutated, by_id)

        changed_document = copy.deepcopy(by_id["xcon-v1"].document)
        changed_document["$defs"]["XCON_001"]["x-newInvariant"] = "MUST_NOT_BE_IGNORED"
        changed_sources = dict(by_id)
        changed_sources["xcon-v1"] = replace(
            by_id["xcon-v1"], document=changed_document
        )
        with self.assertRaisesRegex(GENERATOR.GenerationFailure, "extension closure"):
            GENERATOR.validate_extension_bindings(bindings, changed_sources)

    def test_missing_extra_and_duplicate_mappings_are_rejected(self) -> None:
        mutations = {
            "missing": lambda manifest: manifest["contracts"].pop(),
            "extra": lambda manifest: manifest["contracts"].append({
                **copy.deepcopy(manifest["contracts"][-1]),
                "schemaDefinition": "PDX_999",
                "javaType": "com.dwp.platform.contracts.hris.generated.UnmappedV1",
            }),
            "duplicate": lambda manifest: manifest["contracts"].append(
                copy.deepcopy(manifest["contracts"][0])
            ),
        }
        for name, mutation in mutations.items():
            with self.subTest(mutation=name), self.temporary_root() as directory:
                root = Path(directory)
                self.mutate_manifest(root, mutation)
                with self.assertRaises(GENERATOR.GenerationFailure):
                    GENERATOR.load_contracts(root)

    def test_ownership_and_provenance_mapping_tampering_is_rejected(self) -> None:
        mutations = {
            "producer": lambda manifest: manifest["contracts"][0].update(
                {"producer": "HRIS-PER"}
            ),
            "consumers": lambda manifest: manifest["contracts"][0].update(
                {"consumers": ["HRIS-PAY"]}
            ),
            "binding-provenance": lambda manifest: manifest["sources"][0][
                "bindingRegisterProvenance"
            ].update({"sha256": "0" * 64}),
        }
        for name, mutation in mutations.items():
            with self.subTest(mutation=name), self.temporary_root() as directory:
                root = Path(directory)
                self.mutate_manifest(root, mutation)
                with self.assertRaisesRegex(
                    GENERATOR.GenerationFailure, "manifest snapshot digest drift"
                ):
                    GENERATOR.load_contracts(root)

    def test_check_rejects_tampered_missing_and_extra_generated_sources(self) -> None:
        with self.temporary_root() as directory:
            root = Path(directory)
            GENERATOR.write_outputs(root)
            outputs = sorted(root.glob("dwp-platform-contracts/src/main/java/**/*.java"))
            target = outputs[0]
            target.write_text(target.read_text(encoding="utf-8") + "// tampered\n", encoding="utf-8")
            self.assertTrue(any("source drift" in item for item in GENERATOR.check_outputs(root)))

            GENERATOR.write_outputs(root)
            target.unlink()
            self.assertTrue(any("missing generated" in item for item in GENERATOR.check_outputs(root)))

            GENERATOR.write_outputs(root)
            extra = target.parent / "UnexpectedContract.java"
            extra.write_text("package invalid;\n", encoding="utf-8")
            self.assertTrue(any("extra generated" in item for item in GENERATOR.check_outputs(root)))


if __name__ == "__main__":
    unittest.main()
