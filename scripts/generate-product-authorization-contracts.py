#!/usr/bin/env python3
"""Generate deterministic CORE-006 authorization registry artifacts.

The canonical source uses the JSON-compatible profile of YAML 1.2 so the
generator has no environment-dependent YAML parser. Generated files are never
inputs to this script.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import pathlib
import re
import sys
from collections import defaultdict
from typing import Any


ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE = ROOT / "contracts/product-authorization/product-surfaces-v1.yaml"
CONTRACT_DIRECTORY = ROOT / "contracts/product-authorization"
AUTH_SEED_DIRECTORY = (
    ROOT / "dwp-auth-server/src/main/resources/product-authorization"
)
LATEST_CONTRACT_OUTPUT = CONTRACT_DIRECTORY / "product-surfaces-v1.json"
LATEST_AUTH_SEED_OUTPUT = AUTH_SEED_DIRECTORY / "product-surfaces-v1.generated.json"
CONTRACT_INDEX_OUTPUT = CONTRACT_DIRECTORY / "product-surfaces-v1.index.json"
AUTH_SEED_INDEX_OUTPUT = AUTH_SEED_DIRECTORY / "product-surfaces-v1.index.generated.json"
ROLLOUT_INVENTORY_OUTPUT = (
    CONTRACT_DIRECTORY / "product-surface-rollout-inventory.v1.generated.json"
)
GATEWAY_ROLLOUT_INVENTORY_OUTPUT = (
    ROOT
    / "dwp-gateway/src/main/resources/product-authorization/"
    / "product-surface-rollout-inventory.v1.generated.json"
)
PLATFORM_CANARY_PEP_OUTPUT = (
    ROOT
    / "dwp-platform-server/src/main/resources/product-authorization/"
    / "platform-canary-pep-v1.generated.json"
)
APPROVAL_PILOT_PEP_OUTPUT = (
    ROOT
    / "dwp-approval-server/src/main/resources/product-authorization/"
    / "approval-pilot-pep-v2.generated.json"
)
APPROVAL_WORK_PEP_OUTPUT = APPROVAL_PILOT_PEP_OUTPUT.with_name(
    "approval-pilot-pep-v7.generated.json"
)
APPROVAL_DOCUMENT_PEP_OUTPUT = APPROVAL_PILOT_PEP_OUTPUT.with_name(
    "approval-pilot-pep-v8.generated.json"
)
APPROVAL_EXTENSION_PEP_OUTPUT = APPROVAL_PILOT_PEP_OUTPUT.with_name(
    "approval-pilot-pep-v9.generated.json"
)
APPROVAL_RELEASE10_PEP_OUTPUT = APPROVAL_PILOT_PEP_OUTPUT.with_name(
    "approval-pilot-pep-v10.generated.json"
)
APPROVAL_RECOVERY11_PEP_OUTPUT = APPROVAL_PILOT_PEP_OUTPUT.with_name(
    "approval-pilot-pep-v11.generated.json"
)
APPROVAL_RELEASE12_PEP_OUTPUT = APPROVAL_PILOT_PEP_OUTPUT.with_name(
    "approval-pilot-pep-v12.generated.json"
)
APPROVAL_RELEASE13_PEP_OUTPUT = APPROVAL_PILOT_PEP_OUTPUT.with_name(
    "approval-pilot-pep-v13.generated.json"
)
APPROVAL_RELEASE14_PEP_OUTPUT = APPROVAL_PILOT_PEP_OUTPUT.with_name(
    "approval-pilot-pep-v14.generated.json"
)
PLATFORM_APPROVALS_PEP_OUTPUT = (
    ROOT
    / "dwp-platform-server/src/main/resources/product-authorization/"
    / "platform-approvals-pep-v2.generated.json"
)
HCM_PEOPLE_PEP_OUTPUT = (
    ROOT
    / "dwp-people-server/src/main/resources/product-authorization/"
    / "hcm-people-pep-v3.generated.json"
)
PLATFORM_TELEMETRY_DIMENSIONS_OUTPUT = (
    ROOT
    / "dwp-platform-server/src/main/resources/product-authorization/"
    / "platform-telemetry-dimensions-v3.generated.json"
)
BUNDLE_VERSIONS = (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14)
VERSIONED_CONTRACT_OUTPUTS = {
    version: CONTRACT_DIRECTORY / f"product-surfaces-v1.bundle-v{version}.json"
    for version in BUNDLE_VERSIONS
}
VERSIONED_AUTH_SEED_OUTPUTS = {
    version: AUTH_SEED_DIRECTORY / f"product-surfaces-v1.bundle-v{version}.generated.json"
    for version in BUNDLE_VERSIONS
}

SECTION_KEYS = {
    "capabilities": "contractKey",
    "accessPolicies": "accessPolicyKey",
    "entitlementExpressions": "expressionKey",
    "predicatePolicies": "predicatePolicyKey",
    "routes": "routeContractKey",
}
APPROVAL_FIELD_MASK_SCHEMA_PROFILES = {
    "ApprovalOversightAdminPulseV1": "legacy-oversight",
    "ApprovalOversightWorkflowV1": "legacy-oversight",
    "ApprovalOversightFormV1": "legacy-oversight",
    "ApprovalOversightPolicyV1": "legacy-oversight",
    "ApprovalAuditorOperationsV1": "auditor",
    "ApprovalOversightOperationsV1": "legacy-oversight",
    "ApprovalOversightSignatureV1": "legacy-oversight",
}
PROJECTION_BASE_FIELDS = {
    "apiBindingKey", "projectionPolicyKey", "responseSchemaKey"
}
PROJECTION_METADATA_FIELDS = {
    "schemaVersion", "openApiSchemaSha256", "additionalProperties"
}
LOWERCASE_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
EXPECTED_RELEASE_COUNTS = {
    1: {"capabilities": 10, "accessPolicies": 5, "entitlementExpressions": 2,
        "predicatePolicies": 6, "routes": 35, "PAGE": 18, "DATA": 1, "ACTION": 16},
    2: {"capabilities": 34, "accessPolicies": 6, "entitlementExpressions": 3,
        "predicatePolicies": 13, "routes": 76, "PAGE": 33, "DATA": 6, "ACTION": 37},
    3: {"capabilities": 62, "accessPolicies": 14, "entitlementExpressions": 8,
        "predicatePolicies": 25, "routes": 129, "PAGE": 58, "DATA": 12, "ACTION": 59},
    4: {"capabilities": 71, "accessPolicies": 22, "entitlementExpressions": 16,
        "predicatePolicies": 33, "routes": 155, "PAGE": 66, "DATA": 22, "ACTION": 67},
    5: {"capabilities": 72, "accessPolicies": 22, "entitlementExpressions": 16,
        "predicatePolicies": 33, "routes": 160, "PAGE": 66, "DATA": 27, "ACTION": 67},
    6: {"capabilities": 119, "accessPolicies": 22, "entitlementExpressions": 16,
        "predicatePolicies": 34, "routes": 250, "PAGE": 79, "DATA": 45, "ACTION": 126},
    7: {"capabilities": 119, "accessPolicies": 22, "entitlementExpressions": 16,
        "predicatePolicies": 35, "routes": 258, "PAGE": 79, "DATA": 50, "ACTION": 129},
    8: {"capabilities": 123, "accessPolicies": 22, "entitlementExpressions": 16,
        "predicatePolicies": 38, "routes": 275, "PAGE": 79, "DATA": 58, "ACTION": 138},
    9: {"capabilities": 127, "accessPolicies": 22, "entitlementExpressions": 16,
        "predicatePolicies": 41, "routes": 302, "PAGE": 79, "DATA": 70, "ACTION": 153},
    10: {"capabilities": 132, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 44, "routes": 318, "PAGE": 79, "DATA": 78, "ACTION": 161},
    11: {"capabilities": 132, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 323, "PAGE": 79, "DATA": 83, "ACTION": 161},
    12: {"capabilities": 134, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 352, "PAGE": 79, "DATA": 92, "ACTION": 181},
    13: {"capabilities": 134, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 355, "PAGE": 79, "DATA": 93, "ACTION": 183},
    14: {"capabilities": 134, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 360, "PAGE": 79, "DATA": 96, "ACTION": 185},
}
IMMUTABLE_RELEASE_CHECKSUMS = {
    1: "bc34f47b0ad783d27aa7979f25f75e2fdf29506a12a23c0088f94837abad0b67",
    2: "5b634a35472ef98ecdd5ca9efe7a716020d8f3ae0d8f5025d76bbf072692c12c",
    3: "f90c4e3a734204a4619ae77d3476ebc7cc802c43ed8574fcf4f3fc85def67a8e",
    4: "a9cd08260fd9a11dd7c612f2db6f03bb312f1e7843a2eb10b4082660da151137",
    5: "c69816a06349fcbd45a0d946debfbce1d67e09b3ed87a8b056ec8a43f852109f",
    6: "7cf8602aa2da5f7a0464b23cfd84a8f381e2d3eb85333ed8a8e483865b2b0abe",
    7: "fe9721ef01164c64e03f8798f89765bdf35e55993cf98ad1f6f9c3611dd8d61a",
    8: "9449a516a2dbd96106e71963cbda764b80d83f0f61fa861d110d85517adac942",
    9: "02b19c4119e560b63d4054ec317fe7e4d694e402a5af03960c63b20db4b41ab7",
    10: "1f97638c95a192f0ec7f01053c3965f79b7a3ee4eb9781ea56e3cf8eccc6889b",
    11: "e9a32c9312feb325db1294e3c00d34a110474a48fba16399eb1fc52b39fc9043",
    12: "65155dcc88f454a0ad2530518f8ec9b0c070afd31d583a19f980dd3d10f78a74",
    13: "3bd67d7b145c5b7c845788c70f8884c8afadedd9920de419ecd1e1d0e8a4c8b0",
    14: "7ee0bac12ddfbc72dda55a5014c67b0798caa68a5ffc73b4be479d06a4590336",
}
APPROVAL_DOCUMENT_V8_SCHEMAS = {
    "route.approvals.work.request-document-tools.data": ("ApprovalDocumentTools",
        "104ee05c1735728ba812d7cb15b3ffd09910fb2e0ef0981c9463f19d1a67eb91"),
    "route.approvals.work.task-document-tools.data": ("ApprovalDocumentTools",
        "104ee05c1735728ba812d7cb15b3ffd09910fb2e0ef0981c9463f19d1a67eb91"),
    "route.approvals.work.request-comments.data": ("ApprovalDocumentComments",
        "d657efaebafbe054c93df2a04095059388a0566c4a95fa8ec05753193b9e8922"),
    "route.approvals.work.task-comments.data": ("ApprovalDocumentComments",
        "d657efaebafbe054c93df2a04095059388a0566c4a95fa8ec05753193b9e8922"),
    "route.approvals.admin.document-policy.data": ("ApprovalDocumentPolicy",
        "e517f31c85c577a1ac61745dce9c165eec9b7c827912ae4cd7a868b2393f0f86"),
    "route.approvals.admin.document-hold.data": ("ApprovalDocumentHold",
        "316249dbd4512224e0891bc255ca4232c9e0f47e428f130acda812103b491ece"),
    "route.approvals.work.form-field-candidates.data": ("ApprovalFormUserCandidates",
        "dac88c0850351501e32155d1608441572a85b30107d96848dc4e9ee9094a5861"),
    "route.approvals.admin.form-field-candidates.data": ("ApprovalFormUserCandidates",
        "dac88c0850351501e32155d1608441572a85b30107d96848dc4e9ee9094a5861"),
}
APPROVAL_EXTENSION_V9_PROJECTIONS = {
    "route.approvals.admin.attachment-policy.data": {
        "profileKey": "full-management",
        "apiBindingKey": "route.approvals.admin.attachment-policy.data.binding.01",
        "projectionPolicyKey": "route.approvals.admin.attachment-policy.data.full-management.projection.v1",
        "responseSchemaKey": "ApprovalAttachmentPolicy",
        "schemaVersion": 1,
        "openApiSchemaSha256": "2cb1a0df8b68b16f49f6ae27ac9858c31d11ce278d295934a8b8521a76bd7c8d",
        "additionalProperties": False
    },
    "route.approvals.admin.form-publish-review.data": {
        "profileKey": "full-management",
        "apiBindingKey": "route.approvals.admin.form-publish-review.data.binding.01",
        "projectionPolicyKey": "route.approvals.admin.form-publish-review.data.full-management.projection.v1",
        "responseSchemaKey": "ApprovalFormLifecycleReview",
        "schemaVersion": 1,
        "openApiSchemaSha256": "cf84f33d56d1affbf78db19e557f13b2e3fc533961d37d5850b2dab8d10b84c2",
        "additionalProperties": False
    },
    "route.approvals.admin.form-version-detail.data": {
        "profileKey": "full-management",
        "apiBindingKey": "route.approvals.admin.form-version-detail.data.binding.01",
        "projectionPolicyKey": "route.approvals.admin.form-version-detail.data.full-management.projection.v1",
        "responseSchemaKey": "ApprovalFormLifecycleVersion",
        "schemaVersion": 1,
        "openApiSchemaSha256": "fab88e34e8445ed5f9face5688f89661e527e5d34c2963dc930557c9db1a3cdf",
        "additionalProperties": False
    },
    "route.approvals.admin.form-version-diff.data": {
        "profileKey": "full-management",
        "apiBindingKey": "route.approvals.admin.form-version-diff.data.binding.01",
        "projectionPolicyKey": "route.approvals.admin.form-version-diff.data.full-management.projection.v1",
        "responseSchemaKey": "ApprovalFormLifecycleDiff",
        "schemaVersion": 1,
        "openApiSchemaSha256": "0701251b9a276bba2791aa4fdcbb8767680ac95d35aaaa2164e4e30cec181fbf",
        "additionalProperties": False
    },
    "route.approvals.admin.form-version-history.data": {
        "profileKey": "full-management",
        "apiBindingKey": "route.approvals.admin.form-version-history.data.binding.01",
        "projectionPolicyKey": "route.approvals.admin.form-version-history.data.full-management.projection.v1",
        "responseSchemaKey": "ApprovalFormLifecycleHistory",
        "schemaVersion": 1,
        "openApiSchemaSha256": "6f066bce11bab60d5d7d8db4c51aae0dc3789e06bd7af2ab8682bf78cd7739c2",
        "additionalProperties": False
    },
    "route.approvals.admin.form-working-draft.data": {
        "profileKey": "full-management",
        "apiBindingKey": "route.approvals.admin.form-working-draft.data.binding.01",
        "projectionPolicyKey": "route.approvals.admin.form-working-draft.data.full-management.projection.v1",
        "responseSchemaKey": "ApprovalFormLifecycleWorkspace",
        "schemaVersion": 1,
        "openApiSchemaSha256": "472ca94cc33d0bc2a85ca8f8ac6689a9801e3c3916b1d1d33c3d3ec21046d5c5",
        "additionalProperties": False
    },
    "route.approvals.admin.policy-impact.data": {
        "profileKey": "full-management",
        "apiBindingKey": "route.approvals.admin.policy-impact.data.binding.01",
        "projectionPolicyKey": "route.approvals.admin.policy-impact.data.full-management.projection.v1",
        "responseSchemaKey": "ApprovalPolicyImpactResult",
        "schemaVersion": 1,
        "openApiSchemaSha256": "652af4b87f0d35380d22136bfb37c7381241defb1175433561bc32368a4363e0",
        "additionalProperties": False
    },
    "route.approvals.work.attachment-download-content.data": {
        "profileKey": "full-work",
        "apiBindingKey": "route.approvals.work.attachment-download-content.data.binding.01",
        "projectionPolicyKey": "route.approvals.work.attachment-download-content.data.full-work.projection.v1",
        "responseSchemaKey": "ApprovalAttachmentDownloadBytesV1"
    },
    "route.approvals.work.attachment-upload.data": {
        "profileKey": "full-work",
        "apiBindingKey": "route.approvals.work.attachment-upload.data.binding.01",
        "projectionPolicyKey": "route.approvals.work.attachment-upload.data.full-work.projection.v1",
        "responseSchemaKey": "ApprovalAttachmentUpload",
        "schemaVersion": 1,
        "openApiSchemaSha256": "0385b3eadafb5bb57c215262f4a4297a3ee33a572f1f2cc203d20c4eeb7dd20c",
        "additionalProperties": False
    },
    "route.approvals.work.information-command-receipt.data": {
        "profileKey": "full-work",
        "apiBindingKey": "route.approvals.work.information-command-receipt.data.binding.01",
        "projectionPolicyKey": "route.approvals.work.information-command-receipt.data.full-work.projection.v1",
        "responseSchemaKey": "ApprovalInformationCommandReceipt",
        "schemaVersion": 1,
        "openApiSchemaSha256": "eff26ae76e19c6dd359e3c7941af868ed8df1aa61f84fa141a6e7701a3164458",
        "additionalProperties": False
    },
    "route.approvals.work.request-attachments.data": {
        "profileKey": "full-work",
        "apiBindingKey": "route.approvals.work.request-attachments.data.binding.01",
        "projectionPolicyKey": "route.approvals.work.request-attachments.data.full-work.projection.v1",
        "responseSchemaKey": "ApprovalAttachmentAttachments",
        "schemaVersion": 1,
        "openApiSchemaSha256": "46026087765a71b0ad5ddab65dd73d048f4ffe96862cbde6688f5b3859e9dcc4",
        "additionalProperties": False
    },
    "route.approvals.work.task-attachments.data": {
        "profileKey": "full-work",
        "apiBindingKey": "route.approvals.work.task-attachments.data.binding.01",
        "projectionPolicyKey": "route.approvals.work.task-attachments.data.full-work.projection.v1",
        "responseSchemaKey": "ApprovalAttachmentAttachments",
        "schemaVersion": 1,
        "openApiSchemaSha256": "46026087765a71b0ad5ddab65dd73d048f4ffe96862cbde6688f5b3859e9dcc4",
        "additionalProperties": False
    }
}
PLATFORM_CANARY_PRODUCTS = {"communications", "services"}
APPROVAL_RELEASE10_PROJECTIONS = {'route.approvals.admin.retention-claim.data': {'additionalProperties': False,
                                                'apiBindingKey': 'route.approvals.admin.retention-claim.data.binding.01',
                                                'openApiSchemaSha256': '5c106536ba24f7054be87e382798275cafc1e81e6e9637549097d1316fb05719',
                                                'profileKey': 'full-management',
                                                'projectionPolicyKey': 'route.approvals.admin.retention-claim.data.full-management.projection.v1',
                                                'responseSchemaKey': 'ApprovalRetentionClaim',
                                                'schemaVersion': 1},
 'route.approvals.admin.retention-policy.data': {'additionalProperties': False,
                                                 'apiBindingKey': 'route.approvals.admin.retention-policy.data.binding.01',
                                                 'openApiSchemaSha256': 'd3ad46a0ef742210a3bba4b9a1482ab79dd7e7afc227b86571dc8dfbd44705e3',
                                                 'profileKey': 'full-management',
                                                 'projectionPolicyKey': 'route.approvals.admin.retention-policy.data.full-management.projection.v1',
                                                 'responseSchemaKey': 'ApprovalRetentionPolicy',
                                                 'schemaVersion': 1},
 'route.approvals.admin.retention-record.data': {'additionalProperties': False,
                                                 'apiBindingKey': 'route.approvals.admin.retention-record.data.binding.01',
                                                 'openApiSchemaSha256': 'bd37568321e12a5bcdcc3d7e65ff2de8579ffabebad91a8b4851656b264f327b',
                                                 'profileKey': 'full-management',
                                                 'projectionPolicyKey': 'route.approvals.admin.retention-record.data.full-management.projection.v1',
                                                 'responseSchemaKey': 'ApprovalRetentionRecord',
                                                 'schemaVersion': 1},
 'route.approvals.admin.workflow-planning-simulation.data': {'additionalProperties': False,
                                                             'apiBindingKey': 'route.approvals.admin.workflow-planning-simulation.data.binding.01',
                                                             'openApiSchemaSha256': 'e42aa72885d0cf219bfcd037689a35c93d198009ca8524af48030f341ee5f070',
                                                             'profileKey': 'full-management',
                                                             'projectionPolicyKey': 'route.approvals.admin.workflow-planning-simulation.data.full-management.projection.v1',
                                                             'responseSchemaKey': 'ApprovalWorkflowPlanningResult',
                                                             'schemaVersion': 1},
 'route.approvals.work.signature-audit.data': {'additionalProperties': False,
                                               'apiBindingKey': 'route.approvals.work.signature-audit.data.binding.01',
                                               'openApiSchemaSha256': '928789cc010f28497c7b7ab76139325b77f2b13afe7a11890536c6bb7380037c',
                                               'profileKey': 'full-work',
                                               'projectionPolicyKey': 'route.approvals.work.signature-audit.data.full-work.projection.v1',
                                               'responseSchemaKey': 'ApprovalSignatureAudit',
                                               'schemaVersion': 1},
 'route.approvals.work.signature-command-receipt.data': {'additionalProperties': False,
                                                         'apiBindingKey': 'route.approvals.work.signature-command-receipt.data.binding.01',
                                                         'openApiSchemaSha256': '90ca054ab5d043930c07e62b263630e9165e90f692f26cee352c7ee232de5012',
                                                         'profileKey': 'approval.signature.command-receipt.v1',
                                                         'projectionPolicyKey': 'route.approvals.work.signature-command-receipt.data.approval.signature.command-receipt.v1.projection.v1',
                                                         'responseSchemaKey': 'ApprovalSignatureCommandReceiptMetadata',
                                                         'schemaVersion': 1},
 'route.approvals.work.signature-context.data': {'additionalProperties': False,
                                                 'apiBindingKey': 'route.approvals.work.signature-context.data.binding.01',
                                                 'openApiSchemaSha256': '1fc3f80e18997f235440d0125fe039a0c1b6ccfb52897b0ff614356df213f5bd',
                                                 'profileKey': 'full-work',
                                                 'projectionPolicyKey': 'route.approvals.work.signature-context.data.full-work.projection.v1',
                                                 'responseSchemaKey': 'ApprovalSignatureContext',
                                                 'schemaVersion': 1},
 'route.approvals.work.signature-request.data': {'additionalProperties': False,
                                                 'apiBindingKey': 'route.approvals.work.signature-request.data.binding.01',
                                                 'openApiSchemaSha256': '41ea1a14b49147ef04a54649f82637cef45220879768d3b8ff84ddfc8fdf4489',
                                                 'profileKey': 'full-work',
                                                 'projectionPolicyKey': 'route.approvals.work.signature-request.data.full-work.projection.v1',
                                                 'responseSchemaKey': 'ApprovalSignatureCeremony',
                                                 'schemaVersion': 1}}

APPROVAL_RECOVERY11_PROJECTIONS = {
    "route.approvals.admin.retention-policy-initialization-command.data": {
        "profileKey": "approval.retention.command-receipt.original-authority.v1",
        "apiBindingKey": "route.approvals.admin.retention-policy-initialization-command.data.binding.01",
        "projectionPolicyKey": "route.approvals.admin.retention-policy-initialization-command.data.approval.retention.command-receipt.original-authority.v1.projection.v1",
        "responseSchemaKey": "ApprovalRetentionCommandReceipt",
        "schemaVersion": 1,
        "openApiSchemaSha256": "95d520bbcaee4a7c3d2283cb18599d457b441e07e516cf7e335987d696eb8fbd",
        "additionalProperties": False,
    },
    "route.approvals.admin.retention-policy-draft-command.data": {
        "profileKey": "approval.retention.command-receipt.original-authority.v1",
        "apiBindingKey": "route.approvals.admin.retention-policy-draft-command.data.binding.01",
        "projectionPolicyKey": "route.approvals.admin.retention-policy-draft-command.data.approval.retention.command-receipt.original-authority.v1.projection.v1",
        "responseSchemaKey": "ApprovalRetentionCommandReceipt",
        "schemaVersion": 1,
        "openApiSchemaSha256": "95d520bbcaee4a7c3d2283cb18599d457b441e07e516cf7e335987d696eb8fbd",
        "additionalProperties": False,
    },
    "route.approvals.admin.retention-policy-publication-command.data": {
        "profileKey": "approval.retention.command-receipt.original-authority.v1",
        "apiBindingKey": "route.approvals.admin.retention-policy-publication-command.data.binding.01",
        "projectionPolicyKey": "route.approvals.admin.retention-policy-publication-command.data.approval.retention.command-receipt.original-authority.v1.projection.v1",
        "responseSchemaKey": "ApprovalRetentionCommandReceipt",
        "schemaVersion": 1,
        "openApiSchemaSha256": "95d520bbcaee4a7c3d2283cb18599d457b441e07e516cf7e335987d696eb8fbd",
        "additionalProperties": False,
    },
    "route.approvals.admin.retention-record-command.data": {
        "profileKey": "approval.retention.command-receipt.original-authority.v1",
        "apiBindingKey": "route.approvals.admin.retention-record-command.data.binding.01",
        "projectionPolicyKey": "route.approvals.admin.retention-record-command.data.approval.retention.command-receipt.original-authority.v1.projection.v1",
        "responseSchemaKey": "ApprovalRetentionCommandReceipt",
        "schemaVersion": 1,
        "openApiSchemaSha256": "95d520bbcaee4a7c3d2283cb18599d457b441e07e516cf7e335987d696eb8fbd",
        "additionalProperties": False,
    },
    "route.approvals.admin.workflow-planning-selection.data": {
        "profileKey": "full-management",
        "apiBindingKey": "route.approvals.admin.workflow-planning-selection.data.binding.01",
        "projectionPolicyKey": "route.approvals.admin.workflow-planning-selection.data.full-management.projection.v1",
        "responseSchemaKey": "ApprovalWorkflowPlanningSelection",
        "schemaVersion": 1,
        "openApiSchemaSha256": "ca0ac253c38917b2d75c1793ffda73de9797fd9840edf0c5b558c62f6bd039f3",
        "additionalProperties": False,
    },
}
APPROVAL_RECOVERY11_RECEIPT_ROUTES = {
    key for key, projection in APPROVAL_RECOVERY11_PROJECTIONS.items()
    if projection["profileKey"] == "approval.retention.command-receipt.original-authority.v1"
}
APPROVAL_RECOVERY11_RECEIPT_CAPABILITIES = {
    "route.approvals.admin.retention-policy-initialization-command.data":
        "approvals.policy.update",
    "route.approvals.admin.retention-policy-draft-command.data":
        "approvals.policy.update",
    "route.approvals.admin.retention-policy-publication-command.data":
        "approvals.policy.publish",
    "route.approvals.admin.retention-record-command.data":
        "approvals.operations.execute",
}

APPROVAL_WORK_V7_SCHEMAS = {
    "route.approvals.work.draft-command-reconciliation.data": ("DraftReconciliation",
        "5f077c0c323ef1821cbc5f3afdfd120899ef576d57099973a4f3883cb3a120cd"),
    "route.approvals.work.request-draft-revision.data": ("DraftRevisionDetail",
        "953f4fa55851c3e2ee3b4cafb406ab0a01fd96556d8cbc545f326949d8f9822a"),
    "route.approvals.work.request-draft-revisions.data": ("PageDraftRevision",
        "bccf9505e49ba2d0ad81b3ab7263751a511529764dd87e6e2e7161fe167f6ce6"),
    "route.approvals.work.requests-search.data": ("PageRequestSummary",
        "6d9c0c9fd273bee297f97d44f91b7147f3f966bc0c090c6712b2ebe62aaba46a"),
    "route.approvals.work.tasks-search.data": ("PageTaskSummary",
        "27ab4bbfb93395f73d300d67ad423f7cf5f4eb04a6b9025ab665711b6334b54a"),
}
APPROVAL_WORK_V7_BINDINGS = {
    "route.approvals.work.tasks-search.data": ("GET", "/v1/tasks/search",
        "approvals.work.task.read", ("predicate.approval-task-readable.v1",)),
    "route.approvals.work.requests-search.data": ("GET", "/v1/requests/search",
        "approvals.work.request.read", ("predicate.approval.own-request.v1",)),
    "route.approvals.work.request-draft-revisions.data": ("GET", "/v1/requests/{requestId}/draft/revisions",
        "approvals.work.request.read", ("predicate.approval.own-request.v1",)),
    "route.approvals.work.request-draft-revision.data": ("GET", "/v1/requests/{requestId}/draft/revisions/{revision}",
        "approvals.work.request.read", ("predicate.approval.own-request.v1",)),
    "route.approvals.work.draft-command-reconciliation.data": ("GET", "/v1/draft-commands/{idempotencyKey}",
        "approvals.work.request.read", ("predicate.approval.own-draft-receipt.v1",)),
    "route.approvals.work.request-draft-recover.action": ("POST", "/v1/requests/{requestId}/draft/recover",
        "approvals.work.request.update", ("predicate.approval.own-request.v1","predicate.approval.object-version.v1",)),
    "route.approvals.work.request-draft-delete.action": ("POST", "/v1/requests/{requestId}/draft/delete",
        "approvals.work.request.update", ("predicate.approval.own-request.v1","predicate.approval.object-version.v1",)),
    "route.approvals.work.request-draft-restore.action": ("POST", "/v1/requests/{requestId}/draft/restore",
        "approvals.work.request.update", ("predicate.approval.own-request.v1","predicate.approval.object-version.v1",)),
}
PLATFORM_TELEMETRY_SURFACE_DIMENSIONS = {
    "approvals.admin": {
        "scopeKinds": ["RESOURCE_SET"],
        "taskKinds": ["ADMINISTRATION", "OPERATIONS"],
    },
    "approvals.work": {
        "scopeKinds": ["SELF"],
        "taskKinds": ["WORK"],
    },
    "communications.management": {
        "scopeKinds": ["RESOURCE_SET", "SUPPORT_SESSION"],
        "taskKinds": ["OPERATIONS"],
    },
    "communications.work": {
        "scopeKinds": ["SELF"],
        "taskKinds": ["WORK"],
    },
    "calendar.management": {
        "scopeKinds": ["RESOURCE_SET"],
        "taskKinds": ["ADMINISTRATION", "OPERATIONS"],
    },
    "calendar.work": {
        "scopeKinds": ["SELF"],
        "taskKinds": ["WORK"],
    },
    "dwaion.management": {
        "scopeKinds": ["RESOURCE_SET"],
        "taskKinds": ["ADMINISTRATION", "OPERATIONS"],
    },
    "dwaion.work": {
        "scopeKinds": ["SELF"],
        "taskKinds": ["WORK"],
    },
    "hcm.management": {
        "scopeKinds": ["LEGAL_ENTITY", "POLICY_NODE", "RESOURCE", "RESOURCE_SET"],
        "taskKinds": [
            "ADMINISTRATION",
            "CONFIGURATION",
            "DESIGN",
            "INTEGRATION",
            "OPERATIONS",
            "REPORTING",
        ],
    },
    "hcm.operations": {
        "scopeKinds": [
            "LEGAL_ENTITY",
            "ORG_UNIT",
            "SUPPORT_SESSION",
            "TARGET_POPULATION",
        ],
        "taskKinds": ["OPERATIONS"],
    },
    "hcm.personal": {
        "scopeKinds": ["SELF"],
        "taskKinds": ["WORK"],
    },
    "hcm.team": {
        "scopeKinds": ["ORG_UNIT", "TARGET_POPULATION", "TEAM"],
        "taskKinds": ["REVIEW", "WORK"],
    },
    "mail.management": {
        "scopeKinds": ["RESOURCE_SET"],
        "taskKinds": ["ADMINISTRATION", "OPERATIONS"],
    },
    "mail.work": {
        "scopeKinds": ["SELF"],
        "taskKinds": ["WORK"],
    },
    "meetings.management": {
        "scopeKinds": ["RESOURCE_SET"],
        "taskKinds": ["ADMINISTRATION", "OPERATIONS"],
    },
    "meetings.work": {
        "scopeKinds": ["SELF"],
        "taskKinds": ["WORK"],
    },
    "messaging.management": {
        "scopeKinds": ["RESOURCE_SET"],
        "taskKinds": ["ADMINISTRATION", "OPERATIONS"],
    },
    "messaging.work": {
        "scopeKinds": ["SELF"],
        "taskKinds": ["WORK"],
    },
    "notifications.management": {
        "scopeKinds": ["RESOURCE_SET"],
        "taskKinds": ["ADMINISTRATION", "OPERATIONS"],
    },
    "notifications.work": {
        "scopeKinds": ["SELF"],
        "taskKinds": ["WORK"],
    },
    "services.management": {
        "scopeKinds": ["RESOURCE_SET"],
        "taskKinds": ["ADMINISTRATION", "OPERATIONS"],
    },
    "services.work": {
        "scopeKinds": ["SELF"],
        "taskKinds": ["WORK"],
    },
    "spaces.management": {
        "scopeKinds": ["RESOURCE_SET"],
        "taskKinds": ["ADMINISTRATION", "OPERATIONS"],
    },
    "spaces.work": {
        "scopeKinds": ["SELF"],
        "taskKinds": ["WORK"],
    },
    "workplace.management": {
        "scopeKinds": ["RESOURCE", "RESOURCE_SET"],
        "taskKinds": ["ADMINISTRATION", "OPERATIONS"],
    },
    "workplace.work": {
        "scopeKinds": ["SELF"],
        "taskKinds": ["WORK"],
    },
}
PLATFORM_TELEMETRY_COMPATIBILITY_ROUTE_IDS = {
    "calendar.management": [
        "calendar.management.overview",
        "calendar.management.policies",
    ],
    "calendar.work": [
        "calendar.work.availability",
        "calendar.work.home",
        "calendar.work.insights",
        "calendar.work.schedule",
    ],
    "dwaion.management": [
        "dwaion.management.actions",
        "dwaion.management.agents",
        "dwaion.management.audit",
        "dwaion.management.evaluation",
        "dwaion.management.gates",
        "dwaion.management.overview",
        "dwaion.management.safety",
        "dwaion.management.sources",
    ],
    "dwaion.work": [
        "dwaion.work.actions",
        "dwaion.work.agents",
        "dwaion.work.conversations",
        "dwaion.work.home",
        "dwaion.work.new",
    ],
    "mail.management": [
        "mail.management.connections",
        "mail.management.overview",
        "mail.management.policies",
        "mail.management.shared-inboxes",
    ],
    "mail.work": [
        "mail.work.accounts",
        "mail.work.drafts",
        "mail.work.home",
        "mail.work.inbox",
        "mail.work.sent",
        "mail.work.shared",
    ],
    "meetings.management": [
        "meetings.management.operations",
        "meetings.management.policies",
    ],
    "meetings.work": [
        "meetings.work.history",
        "meetings.work.home",
        "meetings.work.join",
        "meetings.work.mine",
        "meetings.work.room",
    ],
    "messaging.management": [
        "messaging.management.overview",
        "messaging.management.policy",
    ],
    "messaging.work": [
        "messaging.work.direct",
        "messaging.work.home",
        "messaging.work.inbox",
        "messaging.work.later",
        "messaging.work.people",
        "messaging.work.spaces",
    ],
    "notifications.management": [
        "notifications.management.contracts",
        "notifications.management.operations",
        "notifications.management.overview",
        "notifications.management.policies",
        "notifications.management.suppressions",
        "notifications.management.templates",
    ],
    "notifications.work": [
        "notifications.work.center",
        "notifications.work.home",
        "notifications.work.settings",
    ],
    "spaces.management": [
        "spaces.management.content-reviews",
        "spaces.management.directory",
        "spaces.management.lifecycle",
        "spaces.management.operations",
        "spaces.management.overview",
        "spaces.management.requests",
        "spaces.management.templates",
    ],
    "spaces.work": [
        "spaces.work.discover",
        "spaces.work.home",
        "spaces.work.my-spaces",
        "spaces.work.requests",
    ],
    "workplace.management": [
        "workplace.management.governance",
        "workplace.management.locations",
        "workplace.management.operations",
        "workplace.management.overview",
        "workplace.management.policy",
        "workplace.management.room-operations",
        "workplace.management.room-policy",
    ],
    "workplace.work": [
        "workplace.work.explore",
        "workplace.work.find-rooms",
        "workplace.work.home",
        "workplace.work.my-bookings",
        "workplace.work.my-meetings",
    ],
}
PRODUCT_SURFACE_ROLLOUT_PRODUCTS = {
    "approvals",
    "calendar",
    "communications",
    "dwaion",
    "hcm",
    "mail",
    "meetings",
    "messaging",
    "notifications",
    "services",
    "spaces",
    "workplace",
}

ROUTE_KINDS = {"PAGE", "DATA", "ACTION"}
ACCESS_MODES = {"NORMAL", "ELEVATED", "PROVIDER_SUPPORT"}
TARGET_KINDS = {"SELF", "OBJECT", "RELATIONSHIP", "TARGET_POPULATION", "CONFIG_SCOPE"}
LIFECYCLE_STATES = {"ACTIVE", "RETIRED"}
BUNDLE_STATES = {"DRAFT", "APPROVED", "ACTIVE", "RETIRED"}
SERVICE_PATH_PREFIXES = {
    "agent": "/v1/",
    "auth": "/auth/",
    "meeting": "/v1/",
    "messaging": "/v1/",
    "notification": "/v1/",
    "platform": "/v1/",
    "approval": "/v1/",
    "people": "/v1/",
    "space": "/v1/",
}


class ContractError(ValueError):
    """Raised when the canonical registry is not total or internally closed."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractError(message)


def stable_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
        sort_keys=True,
    )


def checksum(value: dict[str, Any]) -> str:
    payload = copy.deepcopy(value)
    payload.pop("checksum", None)
    payload.pop("bundleStatus", None)
    return hashlib.sha256(stable_json(payload).encode("utf-8")).hexdigest()


def unique(items: list[dict[str, Any]], key: str, section: str) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for item in items:
        value = item.get(key)
        require(isinstance(value, str) and value, f"{section}: missing {key}")
        require(value not in result, f"{section}: duplicate {key} {value}")
        result[value] = item
    return result


def normalize_source(source: dict[str, Any]) -> dict[str, Any]:
    required_sections = (
        "capabilities",
        "accessPolicies",
        "entitlementExpressions",
        "predicatePolicies",
        "routes",
    )
    require(source.get("schemaVersion") == 1, "schemaVersion must be 1")
    require(source.get("bundleKey") == "product-surfaces", "bundleKey must be product-surfaces")
    require(
        source.get("version") in set(BUNDLE_VERSIONS),
        "bundle version must be one of the declared lineage versions",
    )
    require(source.get("bundleStatus") in BUNDLE_STATES, "invalid bundleStatus")
    rollout_products = source.get("rolloutProducts")
    require(
        isinstance(rollout_products, list)
        and len(rollout_products) == len(set(rollout_products))
        and set(rollout_products) == PRODUCT_SURFACE_ROLLOUT_PRODUCTS,
        "rolloutProducts must contain the exact twelve product rollout keys",
    )
    for section in required_sections:
        require(isinstance(source.get(section), list), f"{section} must be an array")

    generated = copy.deepcopy(source)
    # Rollout participation is an operational inventory, not an authorization-bundle
    # field. Keeping it out of snapshots preserves the immutable v1-v3 bytes and the
    # Flyway manifests that already bind those checksums.
    generated.pop("rolloutProducts", None)
    generated["checksumAlgorithm"] = "SHA-256"

    capabilities = unique(generated["capabilities"], "contractKey", "capabilities")
    policies = unique(generated["accessPolicies"], "accessPolicyKey", "accessPolicies")
    expressions = unique(
        generated["entitlementExpressions"], "expressionKey", "entitlementExpressions"
    )
    predicates = unique(
        generated["predicatePolicies"], "predicatePolicyKey", "predicatePolicies"
    )
    routes = unique(generated["routes"], "routeContractKey", "routes")

    capability_routes: dict[str, set[str]] = defaultdict(set)
    policy_routes: dict[str, set[str]] = defaultdict(set)
    predicate_routes: dict[str, set[str]] = defaultdict(set)

    for expression in expressions.values():
        _validate_expression(expression.get("expression"), expression["expressionKey"])
        _validate_revision(expression, expression["expressionKey"])

    for predicate in predicates.values():
        _validate_revision(predicate, predicate["predicatePolicyKey"])
        target_kinds = predicate.get("targetBindingKinds")
        require(
            isinstance(target_kinds, list)
            and target_kinds
            and len(target_kinds) == len(set(target_kinds))
            and set(target_kinds) <= TARGET_KINDS,
            f"{predicate['predicatePolicyKey']}: invalid targetBindingKinds",
        )
        require(
            predicate.get("ownerServiceKey") in SERVICE_PATH_PREFIXES,
            f"{predicate['predicatePolicyKey']}: unknown ownerServiceKey",
        )
        schema = predicate.get("parameterSchema")
        require(
            isinstance(schema, dict)
            and schema.get("type") == "object"
            and schema.get("additionalProperties") is False,
            f"{predicate['predicatePolicyKey']}: closed parameterSchema required",
        )

    for policy in policies.values():
        _validate_policy(policy, capabilities, expressions)
        _validate_revision(policy, policy["accessPolicyKey"])

    for capability in capabilities.values():
        _validate_capability(capability, generated["version"])
        _validate_revision(capability, capability["contractKey"])

    for route in routes.values():
        _validate_route(
            route,
            capabilities,
            policies,
            predicates,
            capability_routes,
            policy_routes,
            predicate_routes,
            generated["version"],
        )
        _validate_revision(route, route["routeContractKey"])

    _validate_wire_authority_equivalence(routes)

    # MODE_BRANCH capabilities are consumers of every route that references
    # that policy. This closes the descriptor-to-route reverse index.
    for policy_key, route_keys in policy_routes.items():
        policy = policies[policy_key]
        for branch in policy.get("modeBranches") or []:
            if branch.get("resultGrantKind") == "CAPABILITY":
                for capability_key in branch["capabilityContractKeys"]:
                    capability_routes[capability_key].update(route_keys)

    for key, item in capabilities.items():
        item["routeContractKeys"] = sorted(capability_routes[key])
    for key, item in policies.items():
        item["routeContractKeys"] = sorted(policy_routes[key])
    for key, item in predicates.items():
        item["routeContractKeys"] = sorted(predicate_routes[key])

    generated["capabilities"] = sorted(capabilities.values(), key=lambda item: item["contractKey"])
    generated["accessPolicies"] = sorted(policies.values(), key=lambda item: item["accessPolicyKey"])
    generated["entitlementExpressions"] = sorted(
        expressions.values(), key=lambda item: item["expressionKey"]
    )
    generated["predicatePolicies"] = sorted(
        predicates.values(), key=lambda item: item["predicatePolicyKey"]
    )
    generated["routes"] = sorted(routes.values(), key=lambda item: item["routeContractKey"])
    generated["checksum"] = checksum(generated)
    return generated


def build_snapshots(source: dict[str, Any]) -> list[dict[str, Any]]:
    """Expand gate-isolated declarations into immutable complete DRAFT snapshots."""
    canonical = copy.deepcopy(source)
    waves = canonical.pop("waves", None)
    descriptor_enrichments = canonical.pop("descriptorEnrichments", None)
    require(isinstance(waves, list), "waves must be an array")
    require(
        [wave.get("version") for wave in waves if isinstance(wave, dict)]
        == list(BUNDLE_VERSIONS[1:]),
        "waves must contain every post-base lineage version in order",
    )
    require(canonical.get("version") == 1, "canonical base must be bundle version 1")
    _validate_descriptor_enrichment_envelope(canonical, waves, descriptor_enrichments)

    snapshots = [normalize_source(
        _apply_descriptor_enrichments(canonical, descriptor_enrichments)
    )]
    accumulated = copy.deepcopy(canonical)
    for wave in waves:
        require(
            set(wave) == {"version", *SECTION_KEYS},
            f"wave {wave.get('version')}: unexpected or missing section",
        )
        version = wave["version"]
        require(version == accumulated["version"] + 1, f"wave {version}: non-contiguous version")
        for section, key in SECTION_KEYS.items():
            require(isinstance(wave[section], list), f"wave {version}/{section}: array required")
            prior_keys = {item[key] for item in accumulated[section]}
            additions = unique(wave[section], key, f"wave {version}/{section}")
            overlap = prior_keys & additions.keys()
            require(not overlap, f"wave {version}/{section}: descriptor redefinition {sorted(overlap)}")
            accumulated[section].extend(copy.deepcopy(wave[section]))
        accumulated["version"] = version
        snapshots.append(normalize_source(
            _apply_descriptor_enrichments(accumulated, descriptor_enrichments)
        ))

    for previous, current in zip(snapshots, snapshots[1:]):
        _validate_exact_superset(previous, current)
    for snapshot in snapshots:
        _validate_release_snapshot(snapshot)
    return snapshots


def _validate_descriptor_enrichment_envelope(
    base: dict[str, Any], waves: list[dict[str, Any]], enrichment: Any
) -> None:
    """Require every descriptor patch to land in the snapshot that introduces its key."""
    require(isinstance(enrichment, dict), "descriptorEnrichments must be an object")
    require(
        set(enrichment) == {"capabilities", "routes", "authorityEndpoints"},
        "descriptorEnrichments envelope is invalid",
    )
    authority = enrichment["authorityEndpoints"]
    require(
        isinstance(authority, dict)
        and set(authority) == {"introducedInVersion", "values"}
        and authority["introducedInVersion"] == 2
        and isinstance(authority["values"], list),
        "authorityEndpoints must be introduced exactly in registry v2",
    )

    accumulated = copy.deepcopy(base)
    first_versions: dict[str, dict[str, int]] = {
        "capabilities": {}, "routes": {}
    }
    for section, key in (("capabilities", "contractKey"),
                         ("routes", "routeContractKey")):
        first_versions[section].update({
            item[key]: 1 for item in accumulated[section]
        })
    for wave in waves:
        for section, key in (("capabilities", "contractKey"),
                             ("routes", "routeContractKey")):
            for item in wave[section]:
                first_versions[section][item[key]] = wave["version"]

    for section, key in (("capabilities", "contractKey"),
                         ("routes", "routeContractKey")):
        patches = unique(enrichment[section], key, f"{section} enrichments")
        require(
            patches.keys() <= first_versions[section].keys(),
            f"{section}: enrichment references an unknown descriptor",
        )

    all_capabilities = {
        item["contractKey"]: item
        for item in base["capabilities"]
    }
    for wave in waves:
        all_capabilities.update({
            item["contractKey"]: item for item in wave["capabilities"]
        })
    capability_patches = unique(
        enrichment["capabilities"], "contractKey", "capability enrichments"
    )
    require(
        set(capability_patches) == {
            key for key, value in all_capabilities.items()
            if value.get("responsibilityRequirement") == "REQUIRED"
        },
        "every and only REQUIRED capability must declare responsibility enrichment",
    )


def _apply_descriptor_enrichments(
    source: dict[str, Any], enrichment: dict[str, Any]
) -> dict[str, Any]:
    """Apply only enrichments whose descriptors exist in this gate snapshot."""
    candidate = copy.deepcopy(source)
    version = candidate["version"]
    authority = enrichment["authorityEndpoints"]
    if version >= authority["introducedInVersion"]:
        candidate["authorityEndpoints"] = copy.deepcopy(authority["values"])
    else:
        candidate.pop("authorityEndpoints", None)

    capabilities = unique(
        candidate["capabilities"], "contractKey", f"v{version} capabilities"
    )
    capability_patches = unique(
        enrichment["capabilities"], "contractKey", "capability enrichments"
    )
    for key, patch in capability_patches.items():
        if key not in capabilities:
            continue
        require(
            set(patch) <= {"contractKey", "requiredResponsibilityCode", "scopeResolver"}
            and isinstance(patch.get("requiredResponsibilityCode"), str)
            and patch["requiredResponsibilityCode"],
            f"{key}: invalid capability enrichment",
        )
        capabilities[key]["requiredResponsibilityCode"] = patch["requiredResponsibilityCode"]
        if "scopeResolver" in patch:
            capabilities[key]["scopeResolver"] = patch["scopeResolver"]

    routes = unique(candidate["routes"], "routeContractKey", f"v{version} routes")
    route_patches = unique(
        enrichment["routes"], "routeContractKey", "route enrichments"
    )
    for key, patch in route_patches.items():
        if key not in routes:
            continue
        require(
            set(patch) <= {
                "routeContractKey", "authorizationEquivalenceKey",
                "queryParameterConstraintsByBinding", "projectionBindings",
                "stepUpCommandBindings"
            },
            f"{key}: invalid route enrichment fields",
        )
        route = routes[key]
        if "authorizationEquivalenceKey" in patch:
            route["authorizationEquivalenceKey"] = patch["authorizationEquivalenceKey"]
        if "stepUpCommandBindings" in patch:
            route["stepUpCommandBindings"] = copy.deepcopy(patch["stepUpCommandBindings"])
        for binding_key, constraints in patch.get(
            "queryParameterConstraintsByBinding", {}
        ).items():
            _apply_query_constraints(route, binding_key, constraints)
        for projection in patch.get("projectionBindings", []):
            _apply_projection_binding(route, projection)
    return candidate


def _apply_query_constraints(
    route: dict[str, Any], binding_key: str, constraints: Any
) -> None:
    require(isinstance(constraints, dict) and constraints,
            f"{binding_key}: query constraints required")
    matched = 0
    for field in ("gatewayApiBindings", "servicePepBindings"):
        for binding in route[field]:
            if binding["bindingKey"] == binding_key:
                binding["queryParameterConstraints"] = copy.deepcopy(constraints)
                matched += 1
    require(matched == 2, f"{binding_key}: public/service enrichment mismatch")


def _apply_projection_binding(route: dict[str, Any], patch: Any) -> None:
    patch_fields = set(patch) if isinstance(patch, dict) else set()
    expected_base = PROJECTION_BASE_FIELDS | {"profileKey"}
    has_metadata = patch_fields == expected_base | PROJECTION_METADATA_FIELDS
    require(
        isinstance(patch, dict)
        and (patch_fields == expected_base or has_metadata),
        f"{route['routeContractKey']}: invalid projection enrichment",
    )
    if has_metadata:
        require(
            patch["schemaVersion"] == 1
            and isinstance(patch["openApiSchemaSha256"], str)
            and LOWERCASE_SHA256_PATTERN.fullmatch(
                patch["openApiSchemaSha256"]
            ) is not None
            and patch["additionalProperties"] is False,
            f"{route['routeContractKey']}: invalid projection schema metadata",
        )
    profiles = [
        profile for profile in route["accessProfiles"]
        if profile["profileKey"] == patch["profileKey"]
    ]
    require(len(profiles) == 1, f"{route['routeContractKey']}: unknown projection profile")
    projections = profiles[0].setdefault("responseProjectionBindings", [])
    projections = [
        value for value in projections
        if value["apiBindingKey"] != patch["apiBindingKey"]
    ]
    projection = {
        "apiBindingKey": patch["apiBindingKey"],
        "projectionPolicyKey": patch["projectionPolicyKey"],
        "responseSchemaKey": patch["responseSchemaKey"],
    }
    if has_metadata:
        projection.update({
            field: patch[field] for field in PROJECTION_METADATA_FIELDS
        })
    projections.append(projection)
    profiles[0]["responseProjectionBindings"] = projections


def _validate_exact_superset(previous: dict[str, Any], current: dict[str, Any]) -> None:
    require(current["version"] == previous["version"] + 1, "snapshot versions must be contiguous")
    added_descriptor = False
    for section, key in SECTION_KEYS.items():
        prior = {item[key]: item for item in previous[section]}
        candidate = {item[key]: item for item in current[section]}
        require(prior.keys() <= candidate.keys(),
                f"v{current['version']}/{section}: not an exact monotonic superset")
        added_descriptor = added_descriptor or prior.keys() < candidate.keys()
        drift = []
        for descriptor_key, descriptor in prior.items():
            prior_descriptor = copy.deepcopy(descriptor)
            candidate_descriptor = copy.deepcopy(candidate[descriptor_key])
            prior_routes = set(prior_descriptor.pop("routeContractKeys", []))
            candidate_routes = set(candidate_descriptor.pop("routeContractKeys", []))
            if prior_descriptor != candidate_descriptor or not prior_routes <= candidate_routes:
                drift.append(descriptor_key)
        require(
            not drift,
            f"v{current['version']}/{section}: prior descriptor or reverse-reference drift {drift}",
        )
    require(added_descriptor,
            f"v{current['version']}: append-only wave must add a descriptor")
    prior_endpoints = previous.get("authorityEndpoints", [])
    current_endpoints = current.get("authorityEndpoints", [])
    if current["version"] == 2:
        require(not prior_endpoints and current_endpoints,
                "v2 must introduce the step-up authority endpoint")
    else:
        require(prior_endpoints == current_endpoints,
                f"v{current['version']}: authority endpoint drift")


def _validate_release_snapshot(snapshot: dict[str, Any]) -> None:
    version = snapshot["version"]
    expected = EXPECTED_RELEASE_COUNTS[version]
    actual = {section: len(snapshot[section]) for section in SECTION_KEYS}
    for kind in ROUTE_KINDS:
        actual[kind] = sum(route["routeKind"] == kind for route in snapshot["routes"])
    require(actual == expected, f"v{version}: release count drift expected={expected} actual={actual}")
    require(snapshot["bundleStatus"] == "DRAFT", f"v{version}: generated seed must remain DRAFT")
    immutable_checksum = IMMUTABLE_RELEASE_CHECKSUMS.get(version)
    if immutable_checksum is not None:
        require(snapshot["checksum"] == immutable_checksum,
                f"v{version}: immutable release checksum drift")

    _validate_approval_projection_schema_metadata(snapshot)
    if version >= 7:
        _validate_approval_work_v7(snapshot)

    endpoints = unique(
        snapshot.get("authorityEndpoints", []), "endpointKey", "authorityEndpoints"
    )
    expected_endpoints = {} if version == 1 else {
        "product-surface-step-up-challenge.issue": {
            "endpointKey": "product-surface-step-up-challenge.issue",
            "method": "POST",
            "publicPath": "/api/auth/product-surface-step-up-challenges",
            "serviceKey": "auth",
            "servicePath": "/auth/product-surface-step-up-challenges",
            "requiresAuthentication": True,
            "requiresCsrf": True,
            "expectedDecisionRevisionHeader":
                "X-DWP-Expected-Decision-Revision",
        }
    }
    require(endpoints == expected_endpoints,
            f"v{version}: authority endpoint contract drift")

    if version == 2:
        hcm_capabilities = [
            item for item in snapshot["capabilities"]
            if item["contractKey"].startswith("hcm.")
            or item.get("productKey") == "hcm"
        ]
        hcm_routes = [
            item for item in snapshot["routes"]
            if item["routeContractKey"].startswith("route.hcm.")
            or item["subject"].get("productKey") == "hcm"
        ]
        hcm_policies = [
            item for item in snapshot["accessPolicies"]
            if item["accessPolicyKey"].startswith("hcm.")
            or item.get("productKey") == "hcm"
        ]
        require(
            not hcm_capabilities and not hcm_routes and not hcm_policies,
            "v2 W1a snapshot must contain exactly zero HCM product descriptors",
        )
        approval_high_bindings = [
            binding
            for route in snapshot["routes"]
            if route["subject"].get("productKey") == "approvals"
            for binding in route.get("stepUpCommandBindings", [])
        ]
        require(
            len(approval_high_bindings) == 4
            and len({binding["bindingKey"] for binding in approval_high_bindings}) == 4,
            "v2 W1a snapshot must close exactly four Approval HIGH bindings",
        )

    if version < 3:
        return
    capability_keys = {item["contractKey"] for item in snapshot["capabilities"]}
    route_keys = {item["routeContractKey"] for item in snapshot["routes"]}
    binding_paths = {
        binding["path"]
        for route in snapshot["routes"]
        for field in ("gatewayApiBindings", "servicePepBindings")
        for binding in route[field]
    }
    require("hcm.reference.publish" not in capability_keys, "reserved reference publish capability forbidden")
    require("hcm.integration.rotate-secret" not in capability_keys, "reserved rotate-secret capability forbidden")
    require(not any("sample-import" in path for path in binding_paths), "sample-import binding forbidden")
    require(not any("credential" in key.lower() for key in route_keys), "credential writer route forbidden")
    for route in snapshot["routes"]:
        if route["routeContractKey"] in {
            "route.hcm.management.integration-create.action",
            "route.hcm.management.integration-update.action",
        }:
            predicate_keys = {
                key
                for profile in route["accessProfiles"]
                for key in profile["predicatePolicyKeys"]
            }
            require(
                "predicate.hcm-integration-nonsecret-update.v1" in predicate_keys,
                f"{route['routeContractKey']}: credentialReference deny predicate required",
            )


def _validate_approval_work_v7(snapshot: dict[str, Any]) -> None:
    routes = unique(snapshot["routes"], "routeContractKey", "v7 routes")
    for key, (method, path, capability, predicate_keys) in APPROVAL_WORK_V7_BINDINGS.items():
        route = routes.get(key)
        require(route is not None, f"{key}: v7 exact route is absent")
        require(len(route["gatewayApiBindings"]) == len(route["servicePepBindings"]) == 1,
                f"{key}: v7 requires one exact binding pair")
        public = route["gatewayApiBindings"][0]
        service = route["servicePepBindings"][0]
        expected_key = f"{key}.binding.01"
        require(public == {"bindingKey": expected_key, "method": method,
                           "path": "/api/approvals" + path, "pathParameterConstraints": {}}
                and service == {"bindingKey": expected_key, "serviceKey": "approval",
                                "method": method, "path": path, "pathParameterConstraints": {}},
                f"{key}: v7 exact binding drift")
        require(route["routeKind"] == ("DATA" if method == "GET" else "ACTION")
                and len(route["accessProfiles"]) == 1, f"{key}: v7 route/profile drift")
        profile = route["accessProfiles"][0]
        require(profile["profileKey"] == "full-work"
                and profile["precedence"] == 300
                and profile["activeAccessModes"] == ["NORMAL", "ELEVATED"]
                and profile["requiredAccess"] == {
                    "type": "CAPABILITY", "capabilityContractKey": capability}
                and profile["predicatePolicyKeys"] == list(predicate_keys)
                and profile["targetBindingKinds"] == (
                    ["OBJECT"] if key == "route.approvals.work.tasks-search.data"
                    else ["SELF", "OBJECT"])
                and profile["readOnly"] == (method == "GET"),
                f"{key}: v7 exact authority drift")


def _validate_approval_projection_schema_metadata(
    snapshot: dict[str, Any]
) -> None:
    seen_schemas: set[str] = set()
    seen_work_routes: set[str] = set()
    seen_document_routes: set[str] = set()
    seen_extension_routes: set[str] = set()
    seen_release10_routes: set[str] = set()
    seen_recovery11_routes: set[str] = set()
    for route in snapshot["routes"]:
        subject = route["subject"]
        approval_route = (
            subject.get("type") == "PRODUCT"
            and subject.get("productKey") == "approvals"
        )
        for profile in route["accessProfiles"]:
            profile_key = profile["profileKey"]
            target_profile = (
                snapshot["version"] >= 2
                and approval_route
                and profile_key in {"auditor", "legacy-oversight"}
            )
            for projection in profile.get("responseProjectionBindings", []):
                fields = set(projection)
                work_schema = APPROVAL_WORK_V7_SCHEMAS.get(route["routeContractKey"])
                document_schema = APPROVAL_DOCUMENT_V8_SCHEMAS.get(route["routeContractKey"])
                extension = APPROVAL_EXTENSION_V9_PROJECTIONS.get(route["routeContractKey"])
                release10 = APPROVAL_RELEASE10_PROJECTIONS.get(route["routeContractKey"])
                recovery11 = APPROVAL_RECOVERY11_PROJECTIONS.get(route["routeContractKey"])
                if snapshot["version"] >= 11 and recovery11 is not None:
                    key = route["routeContractKey"]
                    require(profile_key == recovery11["profileKey"] and projection == {
                        field: value for field, value in recovery11.items() if field != "profileKey"
                    }, f"{key}: invalid recovery11 projection metadata")
                    seen_recovery11_routes.add(key)
                elif snapshot["version"] >= 10 and release10 is not None:
                    key = route["routeContractKey"]
                    require(profile_key == release10["profileKey"] and projection == {
                        field: value for field, value in release10.items() if field != "profileKey"
                    }, f"{key}: invalid release10 projection metadata")
                    seen_release10_routes.add(key)
                elif snapshot["version"] >= 9 and extension is not None:
                    key = route["routeContractKey"]
                    expected_projection = {field: value for field, value in extension.items()
                                           if field != "profileKey"}
                    require(profile_key == extension["profileKey"]
                            and projection == expected_projection,
                            f"{key}: invalid v9 extension projection metadata")
                    seen_extension_routes.add(key)
                elif snapshot["version"] >= 7 and work_schema is not None:
                    key = route["routeContractKey"]
                    require(profile_key == "full-work" and projection == {
                        "apiBindingKey": f"{key}.binding.01",
                        "projectionPolicyKey": f"{key}.full-work.projection.v1",
                        "responseSchemaKey": work_schema[0], "schemaVersion": 1,
                        "openApiSchemaSha256": work_schema[1], "additionalProperties": False,
                    }, f"{key}: invalid v7 work projection schema metadata")
                    seen_work_routes.add(key)
                elif snapshot["version"] >= 8 and document_schema is not None:
                    key = route["routeContractKey"]
                    expected_profile = ("full-work" if subject["surfaceKey"] == "approvals.work"
                                        else "full-management")
                    require(profile_key == expected_profile and projection == {
                        "apiBindingKey": f"{key}.binding.01",
                        "projectionPolicyKey": f"{key}.{expected_profile}.projection.v1",
                        "responseSchemaKey": document_schema[0], "schemaVersion": 1,
                        "openApiSchemaSha256": document_schema[1], "additionalProperties": False,
                    }, f"{key}: invalid v8 document/source projection schema metadata")
                    seen_document_routes.add(key)
                elif target_profile:
                    schema_key = projection.get("responseSchemaKey")
                    require(
                        fields == PROJECTION_BASE_FIELDS | PROJECTION_METADATA_FIELDS
                        and APPROVAL_FIELD_MASK_SCHEMA_PROFILES.get(schema_key)
                        == profile_key
                        and projection.get("schemaVersion") == 1
                        and isinstance(projection.get("openApiSchemaSha256"), str)
                        and LOWERCASE_SHA256_PATTERN.fullmatch(
                            projection["openApiSchemaSha256"]
                        ) is not None
                        and projection.get("additionalProperties") is False,
                        f"{route['routeContractKey']}/{profile_key}: "
                        "invalid Approval projection schema metadata",
                    )
                    seen_schemas.add(schema_key)
                else:
                    require(
                        fields == PROJECTION_BASE_FIELDS,
                        f"{route['routeContractKey']}/{profile_key}: "
                        "projection schema metadata is forbidden",
                    )
    expected = (
        set(APPROVAL_FIELD_MASK_SCHEMA_PROFILES)
        if snapshot["version"] >= 2 else set()
    )
    require(
        seen_schemas == expected,
        f"v{snapshot['version']}: Approval projection schema coverage drift",
    )
    require(seen_work_routes == (set(APPROVAL_WORK_V7_SCHEMAS)
                                if snapshot["version"] >= 7 else set()),
            "Approval v7 work projection schema coverage drift")
    require(seen_document_routes == (set(APPROVAL_DOCUMENT_V8_SCHEMAS)
                                    if snapshot["version"] >= 8 else set()),
            "Approval v8 document/source projection schema coverage drift")
    require(seen_extension_routes == (set(APPROVAL_EXTENSION_V9_PROJECTIONS)
                                     if snapshot["version"] >= 9 else set()),
            "Approval v9 extension projection schema coverage drift")
    require(seen_release10_routes == (set(APPROVAL_RELEASE10_PROJECTIONS)
                                     if snapshot["version"] >= 10 else set()),
            "Approval release10 projection schema coverage drift")
    require(seen_recovery11_routes == (set(APPROVAL_RECOVERY11_PROJECTIONS)
                                      if snapshot["version"] >= 11 else set()),
            "Approval recovery11 projection schema coverage drift")


def _validate_revision(item: dict[str, Any], key: str) -> None:
    require(item.get("policyVersion") == 1, f"{key}: policyVersion must be 1")
    require(item.get("lifecycleState") in LIFECYCLE_STATES, f"{key}: invalid lifecycleState")
    require(isinstance(item.get("owner"), str) and item["owner"], f"{key}: owner required")


def _validate_expression(node: Any, key: str) -> None:
    require(isinstance(node, dict), f"{key}: expression must be an object")
    node_type = node.get("type")
    require(node_type in {"LEAF", "ANY", "ALL"}, f"{key}: invalid expression node")
    if node_type == "LEAF":
        entitlement = node.get("entitlement")
        require(
            isinstance(entitlement, str)
            and entitlement.startswith("APP.")
            and ":" in entitlement,
            f"{key}: invalid entitlement leaf",
        )
        require(set(node) == {"type", "entitlement"}, f"{key}: invalid LEAF fields")
        return
    children = node.get("children")
    require(isinstance(children, list) and children, f"{key}: empty {node_type} expression")
    require(set(node) == {"type", "children"}, f"{key}: invalid {node_type} fields")
    for child in children:
        _validate_expression(child, key)


def _validate_capability(capability: dict[str, Any], bundle_version: int) -> None:
    key = capability["contractKey"]
    code = capability.get("resolvedCapabilityCode")
    require(isinstance(code, str) and code.count(":") == 1, f"{key}: exact capability code required")
    resource, action = code.split(":", 1)
    require(capability.get("resourceKey") == resource, f"{key}: resource mapping drift")
    require(capability.get("action") == action, f"{key}: action mapping drift")
    require(capability.get("mappingVersion") == 1, f"{key}: mappingVersion must be 1")
    require(
        capability.get("authorityMode")
        in {"PERMISSION", "PERMISSION_AND_RELATIONSHIP", "PERMISSION_OR_RELATIONSHIP"},
        f"{key}: invalid authorityMode",
    )
    require(
        capability.get("responsibilityRequirement")
        in {"REQUIRED", "NOT_REQUIRED", "LEGACY_OVERSIGHT"},
        f"{key}: invalid responsibilityRequirement",
    )
    required_responsibility = capability.get("requiredResponsibilityCode")
    if capability["responsibilityRequirement"] == "REQUIRED":
        require(
            isinstance(required_responsibility, str)
            and required_responsibility == "APP_CONFIG_ADMIN",
            f"{key}: exact requiredResponsibilityCode required",
        )
    else:
        require(
            required_responsibility is None,
            f"{key}: requiredResponsibilityCode forbidden",
        )
    require(capability.get("riskTier") in {"LOW", "MEDIUM", "HIGH", "CRITICAL"}, f"{key}: invalid riskTier")
    require(isinstance(capability.get("scopeResolver"), str) and capability["scopeResolver"], f"{key}: scopeResolver required")
    require(isinstance(capability.get("requiresProductEntitlement"), bool), f"{key}: entitlement flag required")


def _validate_policy(
    policy: dict[str, Any],
    capabilities: dict[str, dict[str, Any]],
    expressions: dict[str, dict[str, Any]],
) -> None:
    key = policy["accessPolicyKey"]
    product_key = policy.get("productKey")
    surface_key = policy.get("surfaceKey")
    entries = policy.get("surfaceEntryKeys")
    require((product_key is None) == (surface_key is None), f"{key}: incomplete policy subject")
    require(isinstance(entries, list), f"{key}: surfaceEntryKeys required")
    if product_key is None:
        require(not entries, f"{key}: governed context cannot own a surface entry")
    else:
        require(entries, f"{key}: product policy requires a surface entry")
    require(isinstance(policy.get("scopeResolver"), str) and policy["scopeResolver"], f"{key}: scopeResolver required")
    require(isinstance(policy.get("requiresProductEntitlement"), bool), f"{key}: entitlement flag required")
    evaluation = policy.get("evaluationType")
    require(evaluation in {"SINGLE", "MODE_BRANCH"}, f"{key}: invalid evaluationType")
    if evaluation == "SINGLE":
        mode = policy.get("authorityMode")
        require(mode in {"ENTITLEMENT", "RELATIONSHIP", "ENTITLEMENT_AND_RELATIONSHIP", "SUPPORT_SESSION"}, f"{key}: invalid authorityMode")
        require(not policy.get("modeBranches"), f"{key}: SINGLE cannot have modeBranches")
        expression_key = policy.get("entitlementExpressionKey")
        if mode in {"ENTITLEMENT", "ENTITLEMENT_AND_RELATIONSHIP"}:
            require(expression_key in expressions, f"{key}: unknown entitlement expression")
        else:
            require(expression_key is None, f"{key}: expression forbidden")
        support_scopes = policy.get("supportScopes")
        if mode == "SUPPORT_SESSION":
            require(isinstance(support_scopes, list) and support_scopes, f"{key}: supportScopes required")
        else:
            require(not support_scopes, f"{key}: supportScopes forbidden")
        return
    require(policy.get("authorityMode") is None, f"{key}: MODE_BRANCH authorityMode forbidden")
    require(policy.get("entitlementExpressionKey") is None, f"{key}: MODE_BRANCH expression forbidden")
    require(not policy.get("supportScopes"), f"{key}: MODE_BRANCH top-level supportScopes forbidden")
    branches = policy.get("modeBranches")
    require(isinstance(branches, list) and branches, f"{key}: branches required")
    modes: set[str] = set()
    for branch in branches:
        mode = branch.get("activeAccessMode")
        require(mode in ACCESS_MODES and mode not in modes, f"{key}: duplicate/invalid branch mode")
        modes.add(mode)
        if branch.get("resultGrantKind") == "CAPABILITY":
            keys = branch.get("capabilityContractKeys")
            require(mode != "PROVIDER_SUPPORT", f"{key}: support capability branch forbidden")
            require(isinstance(keys, list) and keys and all(value in capabilities for value in keys), f"{key}: invalid capability branch")
            require(branch.get("capabilityMode") in {"ANY", "ALL"}, f"{key}: capabilityMode required")
            require(branch.get("responsibilityRequirement") in {"REQUIRED", "NOT_REQUIRED", "LEGACY_OVERSIGHT"}, f"{key}: responsibility required")
            require(branch.get("authorityMode") is None and not branch.get("supportScopes"), f"{key}: capability support union fields forbidden")
        else:
            require(
                branch.get("resultGrantKind") == "POLICY"
                and mode == "PROVIDER_SUPPORT"
                and branch.get("authorityMode") == "SUPPORT_SESSION"
                and branch.get("capabilityMode") is None
                and not branch.get("capabilityContractKeys")
                and branch.get("responsibilityRequirement") is None
                and isinstance(branch.get("supportScopes"), list)
                and branch["supportScopes"],
                f"{key}: invalid support branch",
            )


def _validate_route(
    route: dict[str, Any],
    capabilities: dict[str, dict[str, Any]],
    policies: dict[str, dict[str, Any]],
    predicates: dict[str, dict[str, Any]],
    capability_routes: dict[str, set[str]],
    policy_routes: dict[str, set[str]],
    predicate_routes: dict[str, set[str]],
    bundle_version: int,
) -> None:
    key = route["routeContractKey"]
    kind = route.get("routeKind")
    require(kind in ROUTE_KINDS, f"{key}: invalid routeKind")
    subject = route.get("subject")
    require(isinstance(subject, dict), f"{key}: subject required")
    if subject.get("type") == "PRODUCT":
        require(subject.get("productKey") and subject.get("surfaceKey"), f"{key}: product subject incomplete")
        require(not key.startswith("route.context."), f"{key}: product route namespace mismatch")
    else:
        require(subject == {"type": "GOVERNED_CONTEXT"}, f"{key}: invalid governed context subject")
        context_id = route.get("navigationContextId")
        token = context_id.replace(".", "__")
        require(key.startswith(f"route.context.{token}."), f"{key}: non-product context token mismatch")
        require("_" not in context_id, f"{key}: non-canonical navigationContextId")
    if kind == "PAGE":
        require(route.get("uiRouteId") and route.get("uiRoutePattern"), f"{key}: PAGE UI contract required")
    else:
        require(route.get("uiRouteId") is None and route.get("uiRoutePattern") is None, f"{key}: non-PAGE UI fields forbidden")
    if kind == "DATA" and any(binding["method"] == "POST" for binding in route.get("gatewayApiBindings", [])):
        require(route.get("sideEffectFree") is True, f"{key}: POST DATA must be sideEffectFree")
    if kind == "ACTION":
        require(route.get("sideEffectFree") is None, f"{key}: ACTION sideEffectFree forbidden")

    gateway = unique(route.get("gatewayApiBindings", []), "bindingKey", key + ".gatewayApiBindings")
    service = unique(route.get("servicePepBindings", []), "bindingKey", key + ".servicePepBindings")
    require(gateway and gateway.keys() == service.keys(), f"{key}: public/service binding mismatch")
    for binding_key, public_binding in gateway.items():
        service_binding = service[binding_key]
        require(public_binding["method"] == service_binding["method"], f"{binding_key}: method mismatch")
        require(public_binding.get("pathParameterConstraints", {}) == service_binding.get("pathParameterConstraints", {}), f"{binding_key}: constraint mismatch")
        require(public_binding.get("queryParameterConstraints", {}) == service_binding.get("queryParameterConstraints", {}), f"{binding_key}: query constraint mismatch")
        _validate_binding_constraints(public_binding, binding_key)
        _validate_binding_constraints(service_binding, binding_key)
        service_key = service_binding.get("serviceKey")
        prefix = SERVICE_PATH_PREFIXES.get(service_key)
        require(prefix and service_binding["path"].startswith(prefix), f"{binding_key}: service path grammar mismatch")
        require("/**" not in public_binding["path"] and "/**" not in service_binding["path"], f"{binding_key}: wildcard binding forbidden")

    profiles = route.get("accessProfiles")
    require(isinstance(profiles, list) and profiles, f"{key}: accessProfiles required")
    profile_keys: set[str] = set()
    precedences: set[int] = set()
    for profile in profiles:
        profile_key = profile.get("profileKey")
        precedence = profile.get("precedence")
        require(profile_key and profile_key not in profile_keys, f"{key}: duplicate profile")
        require(isinstance(precedence, int) and precedence not in precedences, f"{key}: duplicate precedence")
        profile_keys.add(profile_key)
        precedences.add(precedence)
        modes = profile.get("activeAccessModes")
        require(isinstance(modes, list) and modes and len(modes) == len(set(modes)) and set(modes) <= ACCESS_MODES, f"{key}/{profile_key}: invalid activeAccessModes")
        require(isinstance(profile.get("readOnly"), bool), f"{key}/{profile_key}: readOnly required")
        access = profile.get("requiredAccess")
        require(isinstance(access, dict), f"{key}/{profile_key}: requiredAccess required")
        if access.get("type") == "CAPABILITY":
            capability_key = access.get("capabilityContractKey")
            require(capability_key in capabilities, f"{key}: unknown capability {capability_key}")
            capability_routes[capability_key].add(key)
        elif access.get("type") == "CAPABILITY_EXPRESSION":
            capability_keys = access.get("capabilityContractKeys")
            require(access.get("mode") in {"ANY", "ALL"} and isinstance(capability_keys, list) and capability_keys, f"{key}: invalid capability expression")
            for capability_key in capability_keys:
                require(capability_key in capabilities, f"{key}: unknown capability {capability_key}")
                capability_routes[capability_key].add(key)
        else:
            require(access.get("type") == "POLICY", f"{key}: invalid access union")
            policy_key = access.get("accessPolicyKey")
            require(policy_key in policies, f"{key}: unknown policy {policy_key}")
            policy_routes[policy_key].add(key)

        target_kinds = profile.get("targetBindingKinds", [])
        predicate_keys = profile.get("predicatePolicyKeys", [])
        require(len(target_kinds) == len(set(target_kinds)) and set(target_kinds) <= TARGET_KINDS, f"{key}/{profile_key}: invalid targets")
        require(len(predicate_keys) == len(set(predicate_keys)), f"{key}/{profile_key}: duplicate predicates")
        covered: set[str] = set()
        for predicate_key in predicate_keys:
            require(predicate_key in predicates, f"{key}: unknown predicate {predicate_key}")
            effective = set(target_kinds) & set(predicates[predicate_key]["targetBindingKinds"])
            require(effective, f"{key}/{profile_key}: predicate target mismatch")
            covered.update(effective)
            predicate_routes[predicate_key].add(key)
        if predicate_keys:
            require(covered == set(target_kinds), f"{key}/{profile_key}: predicate target union does not cover profile")

        projections = profile.get("responseProjectionBindings", [])
        if kind == "ACTION":
            require(not projections, f"{key}/{profile_key}: ACTION projection forbidden")
        else:
            if not projections:
                projections = [
                    {
                        "apiBindingKey": binding_key,
                        "projectionPolicyKey": profile.get(
                            "projectionPolicyKey",
                            f"{key}.{profile_key}.projection.v1",
                        ),
                        "responseSchemaKey": profile.get(
                            "responseSchemaKey",
                            f"{key}.response.v1",
                        ),
                    }
                    for binding_key in gateway
                ]
                profile["responseProjectionBindings"] = projections
            require(
                {projection["apiBindingKey"] for projection in projections} == set(gateway),
                f"{key}/{profile_key}: incomplete response projections",
            )
        profile.pop("projectionPolicyKey", None)
        profile.pop("responseSchemaKey", None)

    elevated_step_up = any(
        profile["requiredAccess"].get("type") == "CAPABILITY"
        and capabilities[profile["requiredAccess"]["capabilityContractKey"]]
            .get("riskTier") in {"HIGH", "CRITICAL"}
        and str(capabilities[profile["requiredAccess"]["capabilityContractKey"]]
            .get("activationPolicy", "")).startswith("STEPUP-")
        for profile in profiles
    )
    receipt_capability = APPROVAL_RECOVERY11_RECEIPT_CAPABILITIES.get(key)
    original_authority_receipt = (
        receipt_capability is not None
        and kind == "DATA"
        and route.get("sideEffectFree") is True
        and len(profiles) == 1
        and profiles[0]["profileKey"]
            == "approval.retention.command-receipt.original-authority.v1"
        and profiles[0]["precedence"] == 300
        and profiles[0]["activeAccessModes"] == ["NORMAL", "ELEVATED"]
        and profiles[0]["requiredAccess"] == {
            "type": "CAPABILITY",
            "capabilityContractKey": receipt_capability,
        }
        and profiles[0]["targetBindingKinds"] == ["OBJECT"]
        and profiles[0]["predicatePolicyKeys"] == [
            "predicate.approval.retention-command-original-authority.v1"
        ]
        and profiles[0]["readOnly"] is True
    )
    non_publishing_review_rejection = (
        key == "route.approvals.admin.form-publish-review-reject.action"
        and kind == "ACTION"
        and len(gateway) == 1
        and next(iter(gateway.values()))["method"] == "POST"
        and next(iter(gateway.values()))["path"]
            == "/api/approvals/v1/admin/forms/{formId}/publish-review-requests/{requestId}/reject"
        and len(service) == 1
        and next(iter(service.values()))["method"] == "POST"
        and next(iter(service.values()))["path"]
            == "/v1/admin/forms/{formId}/publish-review-requests/{requestId}/reject"
        and len(profiles) == 1
        and profiles[0]["profileKey"] == "full-management"
        and profiles[0]["requiredAccess"] == {
            "type": "CAPABILITY",
            "capabilityContractKey": "approvals.design.publish",
        }
        and profiles[0]["targetBindingKinds"] == ["OBJECT"]
        and profiles[0]["predicatePolicyKeys"] == [
            "predicate.approval.object-version.v1"
        ]
        and profiles[0]["readOnly"] is False
    )
    if elevated_step_up and not original_authority_receipt \
            and not non_publishing_review_rejection:
        _validate_step_up_command_binding(route, service)
    else:
        require(route.get("stepUpCommandBindings") is None,
                f"{key}: stepUpCommandBindings forbidden")


def _validate_step_up_command_binding(
    route: dict[str, Any], service_bindings: dict[str, dict[str, Any]]
) -> None:
    key = route["routeContractKey"]
    values = route.get("stepUpCommandBindings")
    require(
        isinstance(values, list) and values,
        f"{key}: exact stepUpCommandBindings required",
    )
    bindings = unique(values, "bindingKey", key + ".stepUpCommandBindings")
    require(bindings.keys() == service_bindings.keys(),
            f"{key}: incomplete step-up command bindings")
    for binding_key, value in bindings.items():
        common_fields = {
            "bindingKey", "targetType", "expectedObjectVersionSource",
            "expectedObjectVersionName", "ownerServiceKey", "audience"
        }
        target_fields = set(value) - common_fields
        require(target_fields in ({"targetIdPathParameter"}, {"targetIdBodyFields"}),
                f"{key}: exactly one step-up target source required")
        require(isinstance(value["targetType"], str) and value["targetType"]
                and value["targetType"].replace("_", "").isalnum()
                and value["targetType"] == value["targetType"].upper(),
                f"{key}: invalid step-up target type")
        require(value["expectedObjectVersionSource"] in {"COMMAND_BODY", "COMMAND_HEADER"}
                and isinstance(value["expectedObjectVersionName"], str)
                and value["expectedObjectVersionName"],
                f"{key}: invalid expected object version binding")
        service = service_bindings[binding_key]
        require(service["serviceKey"] == value["ownerServiceKey"],
                f"{key}: step-up owner service mismatch")
        require(value["audience"] == f"dwp-{value['ownerServiceKey']}-server",
                f"{key}: step-up audience mismatch")
        if "targetIdPathParameter" in value:
            require(isinstance(value["targetIdPathParameter"], str)
                    and value["targetIdPathParameter"],
                    f"{key}: invalid step-up target path parameter")
            placeholder = "{" + value["targetIdPathParameter"] + "}"
            require(placeholder in service["path"],
                    f"{key}: step-up target placeholder mismatch")
        else:
            fields = value["targetIdBodyFields"]
            require(isinstance(fields, list) and fields
                    and all(isinstance(field, str) and field for field in fields)
                    and len(fields) == len(set(fields)),
                    f"{key}: invalid step-up target body fields")


def _validate_binding_constraints(binding: dict[str, Any], key: str) -> None:
    placeholders = set(__import__("re").findall(r"\{([^/{}]+)}", binding["path"]))
    path_constraints = binding.get("pathParameterConstraints", {})
    require(
        isinstance(path_constraints, dict) and set(path_constraints) <= placeholders,
        f"{key}: invalid path constraint keys",
    )
    for parameter, constraint in path_constraints.items():
        _validate_parameter_constraint(constraint, f"{key}/path/{parameter}", False)
    query_constraints = binding.get("queryParameterConstraints", {})
    require(isinstance(query_constraints, dict), f"{key}: query constraints must be an object")
    for parameter, constraint in query_constraints.items():
        require(
            isinstance(parameter, str) and parameter
            and all(character.isalnum() or character in "_-" for character in parameter),
            f"{key}: invalid query parameter name",
        )
        _validate_parameter_constraint(constraint, f"{key}/query/{parameter}", True)


def _validate_parameter_constraint(constraint: Any, label: str, allow_absent: bool) -> None:
    require(isinstance(constraint, dict), f"{label}: constraint must be an object")
    kind = constraint.get("kind")
    if kind == "FIXED":
        require(set(constraint) == {"kind", "value"}
                and isinstance(constraint.get("value"), str)
                and constraint["value"], f"{label}: invalid FIXED constraint")
    elif kind == "ALLOWLIST":
        values = constraint.get("values")
        require(set(constraint) == {"kind", "values"}
                and isinstance(values, list) and values
                and len(values) == len(set(values))
                and all(isinstance(value, str) and value for value in values),
                f"{label}: invalid ALLOWLIST constraint")
    else:
        require(allow_absent and constraint == {"kind": "ABSENT"},
                f"{label}: invalid constraint kind")


def _validate_wire_authority_equivalence(
    routes: dict[str, dict[str, Any]]
) -> None:
    wire: dict[str, list[tuple[dict[str, Any], dict[str, Any]]]] = defaultdict(list)
    equivalence_members: dict[str, set[str]] = defaultdict(set)
    for route in routes.values():
        equivalence = route.get("authorizationEquivalenceKey")
        if equivalence is not None:
            require(
                isinstance(equivalence, str)
                and equivalence.startswith("wire-authority.")
                and equivalence.endswith(".v1"),
                f"{route['routeContractKey']}: invalid authorizationEquivalenceKey",
            )
            equivalence_members[equivalence].add(route["routeContractKey"])
        for binding in route["gatewayApiBindings"]:
            key = stable_json({
                "method": binding["method"],
                "path": binding["path"],
                "pathParameterConstraints": binding.get("pathParameterConstraints", {}),
                "queryParameterConstraints": binding.get("queryParameterConstraints", {}),
            })
            wire[key].append((route, binding))
    for wire_key, members in wire.items():
        if len(members) == 1:
            continue
        equivalence_keys = {
            route.get("authorizationEquivalenceKey") for route, _ in members
        }
        require(
            len(equivalence_keys) == 1 and None not in equivalence_keys,
            f"duplicate wire binding lacks one authorization equivalence: {wire_key}",
        )
        semantics = {
            stable_json(_wire_authority_semantics(route, binding))
            for route, binding in members
        }
        require(
            len(semantics) == 1,
            f"duplicate wire binding semantic drift: {wire_key}",
        )
    for equivalence, members in equivalence_members.items():
        require(len(members) >= 2, f"{equivalence}: equivalence group must have aliases")


def _wire_authority_semantics(
    route: dict[str, Any], public_binding: dict[str, Any]
) -> dict[str, Any]:
    binding_key = public_binding["bindingKey"]
    service = next(
        binding for binding in route["servicePepBindings"]
        if binding["bindingKey"] == binding_key
    )
    profiles = []
    for profile in route["accessProfiles"]:
        projection = next(
            value for value in profile.get("responseProjectionBindings", [])
            if value["apiBindingKey"] == binding_key
        ) if route["routeKind"] != "ACTION" else None
        profiles.append({
            "profileKey": profile["profileKey"],
            "precedence": profile["precedence"],
            "activeAccessModes": profile["activeAccessModes"],
            "requiredAccess": profile["requiredAccess"],
            "targetBindingKinds": profile["targetBindingKinds"],
            "predicatePolicyKeys": profile["predicatePolicyKeys"],
            "readOnly": profile["readOnly"],
            "projection": None if projection is None else {
                field: value for field, value in projection.items()
                if field != "apiBindingKey"
            },
        })
    return {
        "subject": route["subject"],
        "navigationContextId": route["navigationContextId"],
        "routeKind": route["routeKind"],
        "profiles": profiles,
        "serviceKey": service["serviceKey"],
        "servicePath": service["path"],
        "owner": route["owner"],
    }


def load_source() -> dict[str, Any]:
    try:
        value = json.loads(SOURCE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ContractError(
            f"{SOURCE}: expected the dependency-free JSON profile of YAML 1.2: {exc}"
        ) from exc
    require(isinstance(value, dict), "canonical source must be an object")
    return value


def render(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def build_rollout_inventory(source: dict[str, Any]) -> dict[str, Any]:
    products = source.get("rolloutProducts")
    require(
        isinstance(products, list)
        and len(products) == len(set(products))
        and set(products) == PRODUCT_SURFACE_ROLLOUT_PRODUCTS,
        "rolloutProducts must contain the exact twelve product rollout keys",
    )
    inventory = {
        "schemaVersion": 1,
        "inventoryKey": "product-surface-rollout-products.v1",
        "products": sorted(products),
        "checksumAlgorithm": "SHA-256",
    }
    inventory["checksum"] = checksum(inventory)
    return inventory


def build_index(snapshots: list[dict[str, Any]]) -> dict[str, Any]:
    versions = []
    for snapshot in snapshots:
        version = snapshot["version"]
        versions.append(
            {
                "version": version,
                "bundleStatus": snapshot["bundleStatus"],
                "checksum": snapshot["checksum"],
                "artifact": VERSIONED_CONTRACT_OUTPUTS[version].name,
                "authSeedArtifact": VERSIONED_AUTH_SEED_OUTPUTS[version].name,
                "counts": {
                    section: len(snapshot[section])
                    for section in SECTION_KEYS
                },
            }
        )
    latest = snapshots[-1]
    index = {
        "schemaVersion": 1,
        "bundleKey": latest["bundleKey"],
        "latestVersion": latest["version"],
        "latestChecksum": latest["checksum"],
        "latestArtifact": VERSIONED_CONTRACT_OUTPUTS[latest["version"]].name,
        "latestAuthSeedArtifact": VERSIONED_AUTH_SEED_OUTPUTS[latest["version"]].name,
        "versions": versions,
        "indexChecksumAlgorithm": "SHA-256",
    }
    index["indexChecksum"] = index_checksum(index)
    return index


def index_checksum(index: dict[str, Any]) -> str:
    payload = copy.deepcopy(index)
    payload.pop("indexChecksum", None)
    return hashlib.sha256(stable_json(payload).encode("utf-8")).hexdigest()


def build_platform_canary_pep(snapshot: dict[str, Any]) -> dict[str, Any]:
    """Project the immutable v1 Platform Canary PEP from the registry graph."""
    require(snapshot["version"] == 1, "Platform Canary PEP must project immutable v1")
    routes = [
        copy.deepcopy(route)
        for route in snapshot["routes"]
        if route["subject"].get("productKey") in PLATFORM_CANARY_PRODUCTS
    ]
    require(len(routes) == 33, "Platform Canary PEP must contain exactly 33 product routes")
    require(
        all(
            binding.get("serviceKey") == "platform"
            for route in routes
            for binding in route["servicePepBindings"]
        ),
        "Platform Canary PEP cannot contain a non-platform service binding",
    )

    capability_keys: set[str] = set()
    policy_keys: set[str] = set()
    predicate_keys: set[str] = set()
    for route in routes:
        for profile in route["accessProfiles"]:
            access = profile["requiredAccess"]
            if access["type"] == "CAPABILITY":
                capability_keys.add(access["capabilityContractKey"])
            elif access["type"] == "CAPABILITY_EXPRESSION":
                capability_keys.update(access["capabilityContractKeys"])
            else:
                policy_keys.add(access["accessPolicyKey"])
            predicate_keys.update(profile["predicatePolicyKeys"])

    policies_by_key = {
        policy["accessPolicyKey"]: policy for policy in snapshot["accessPolicies"]
    }
    expression_keys: set[str] = set()
    for policy_key in policy_keys:
        policy = policies_by_key[policy_key]
        expression_key = policy.get("entitlementExpressionKey")
        if expression_key:
            expression_keys.add(expression_key)
        for branch in policy.get("modeBranches") or []:
            capability_keys.update(branch.get("capabilityContractKeys") or [])

    def owned_path_root(path: str) -> str:
        segments = path.strip("/").split("/")
        require(len(segments) >= 2 and segments[0] == "v1", "invalid Platform Canary path")
        width = 3 if segments[1] == "admin" else 2
        require(len(segments) >= width, "incomplete Platform Canary path")
        return "/" + "/".join(segments[:width])

    owned_path_roots = sorted({
        owned_path_root(binding["path"])
        for route in routes
        for binding in route["servicePepBindings"]
    })
    require(len(owned_path_roots) == 4, "Platform Canary owned path root count drift")

    projection = {
        "schemaVersion": 1,
        "projectionKey": "platform-canary-pep-v1",
        "ownerServiceKey": "platform",
        "ownedPathRoots": owned_path_roots,
        "registryRef": {
            "bundleKey": snapshot["bundleKey"],
            "version": snapshot["version"],
            "sha256": snapshot["checksum"],
        },
        "sourceRegistryRouteCount": len(snapshot["routes"]),
        "projectedRouteContractCount": len(routes),
        "bindingPairCount": sum(len(route["servicePepBindings"]) for route in routes),
        "routeKindCounts": {
            kind: sum(route["routeKind"] == kind for route in routes)
            for kind in sorted(ROUTE_KINDS)
        },
        "capabilities": [
            copy.deepcopy(capability)
            for capability in snapshot["capabilities"]
            if capability["contractKey"] in capability_keys
        ],
        "accessPolicies": [
            copy.deepcopy(policy)
            for policy in snapshot["accessPolicies"]
            if policy["accessPolicyKey"] in policy_keys
        ],
        "entitlementExpressions": [
            copy.deepcopy(expression)
            for expression in snapshot["entitlementExpressions"]
            if expression["expressionKey"] in expression_keys
        ],
        "predicatePolicies": [
            copy.deepcopy(predicate)
            for predicate in snapshot["predicatePolicies"]
            if predicate["predicatePolicyKey"] in predicate_keys
        ],
        "routes": routes,
        "projectionChecksumAlgorithm": "SHA-256",
    }
    require(projection["bindingPairCount"] == 36, "Platform Canary PEP binding count drift")
    require(
        projection["routeKindCounts"] == {"ACTION": 15, "DATA": 0, "PAGE": 18},
        "Platform Canary PEP route kind count drift",
    )
    require(len(projection["capabilities"]) == 10, "Platform Canary capability closure drift")
    require(len(projection["accessPolicies"]) == 3, "Platform Canary policy closure drift")
    require(len(projection["entitlementExpressions"]) == 2, "Platform Canary expression closure drift")
    require(len(projection["predicatePolicies"]) == 5, "Platform Canary predicate closure drift")
    projection["projectionChecksum"] = hashlib.sha256(
        stable_json(projection).encode("utf-8")
    ).hexdigest()
    return projection


def verify_platform_canary_pep(projection: dict[str, Any]) -> None:
    document = json.loads(PLATFORM_CANARY_PEP_OUTPUT.read_text(encoding="utf-8"))
    require(document == projection, "Platform Canary PEP generated artifact drift")
    payload = copy.deepcopy(document)
    actual_checksum = payload.pop("projectionChecksum", None)
    require(
        actual_checksum == hashlib.sha256(stable_json(payload).encode("utf-8")).hexdigest(),
        "Platform Canary PEP checksum mismatch",
    )
    route_keys = {route["routeContractKey"] for route in document["routes"]}
    for descriptor in document["capabilities"]:
        require(
            set(descriptor["routeContractKeys"]) <= route_keys,
            f"{descriptor['contractKey']}: Platform Canary reverse-reference escaped projection",
        )
    for descriptor in document["accessPolicies"]:
        require(
            set(descriptor["routeContractKeys"]) <= route_keys,
            f"{descriptor['accessPolicyKey']}: Platform Canary reverse-reference escaped projection",
        )
    for descriptor in document["predicatePolicies"]:
        require(
            set(descriptor["routeContractKeys"]) <= route_keys,
            f"{descriptor['predicatePolicyKey']}: Platform Canary reverse-reference escaped projection",
        )


def build_approvals_pep(
    snapshot: dict[str, Any],
    service_key: str,
    projection_key: str,
    expected: dict[str, Any],
    *,
    version: int = 2,
    product_key: str = "approvals",
) -> dict[str, Any]:
    """Project one closed product/service PEP from an immutable registry snapshot."""
    require(snapshot["version"] == version,
            f"{projection_key} must project registry v{version}")
    routes: list[dict[str, Any]] = []
    for source_route in snapshot["routes"]:
        if source_route["subject"].get("productKey") != product_key:
            continue
        bindings = [
            copy.deepcopy(binding)
            for binding in source_route["servicePepBindings"]
            if binding["serviceKey"] == service_key
        ]
        if not bindings:
            continue
        route = copy.deepcopy(source_route)
        route["servicePepBindings"] = bindings
        routes.append(route)

    capability_keys: set[str] = set()
    policy_keys: set[str] = set()
    predicate_keys: set[str] = set()
    for route in routes:
        for profile in route["accessProfiles"]:
            access = profile["requiredAccess"]
            if access["type"] == "CAPABILITY":
                capability_keys.add(access["capabilityContractKey"])
            elif access["type"] == "CAPABILITY_EXPRESSION":
                capability_keys.update(access["capabilityContractKeys"])
            else:
                policy_keys.add(access["accessPolicyKey"])
            predicate_keys.update(profile["predicatePolicyKeys"])

    policies_by_key = {
        policy["accessPolicyKey"]: policy for policy in snapshot["accessPolicies"]
    }
    expression_keys: set[str] = set()
    for policy_key in policy_keys:
        policy = policies_by_key[policy_key]
        expression_key = policy.get("entitlementExpressionKey")
        if expression_key:
            expression_keys.add(expression_key)
        for branch in policy.get("modeBranches") or []:
            capability_keys.update(branch.get("capabilityContractKeys") or [])

    projection = {
        "schemaVersion": 1,
        "projectionKey": projection_key,
        "ownerServiceKey": service_key,
        "registryRef": {
            "bundleKey": snapshot["bundleKey"],
            "version": snapshot["version"],
            "sha256": snapshot["checksum"],
        },
        "sourceRegistryRouteCount": len(snapshot["routes"]),
        "projectedRouteContractCount": len(routes),
        "bindingPairCount": sum(len(route["servicePepBindings"]) for route in routes),
        "routeKindCounts": {
            kind: sum(route["routeKind"] == kind for route in routes)
            for kind in sorted(ROUTE_KINDS)
        },
        "capabilities": [
            copy.deepcopy(capability)
            for capability in snapshot["capabilities"]
            if capability["contractKey"] in capability_keys
        ],
        "accessPolicies": [
            copy.deepcopy(policy)
            for policy in snapshot["accessPolicies"]
            if policy["accessPolicyKey"] in policy_keys
        ],
        "entitlementExpressions": [
            copy.deepcopy(expression)
            for expression in snapshot["entitlementExpressions"]
            if expression["expressionKey"] in expression_keys
        ],
        "predicatePolicies": [
            copy.deepcopy(predicate)
            for predicate in snapshot["predicatePolicies"]
            if predicate["predicatePolicyKey"] in predicate_keys
        ],
        "routes": routes,
        "projectionChecksumAlgorithm": "SHA-256",
    }
    if version == 7:
        route_keys = {route["routeContractKey"] for route in routes}
        for section in ("capabilities", "accessPolicies", "predicatePolicies"):
            for descriptor in projection[section]:
                descriptor["routeContractKeys"] = sorted(
                    set(descriptor["routeContractKeys"]) & route_keys
                )
    actual = {
        "routes": projection["projectedRouteContractCount"],
        "bindings": projection["bindingPairCount"],
        "routeKinds": projection["routeKindCounts"],
        "capabilities": len(projection["capabilities"]),
        "accessPolicies": len(projection["accessPolicies"]),
        "entitlementExpressions": len(projection["entitlementExpressions"]),
        "predicatePolicies": len(projection["predicatePolicies"]),
    }
    require(actual == expected, f"{projection_key}: projection closure drift: {actual}")
    projection["projectionChecksum"] = hashlib.sha256(
        stable_json(projection).encode("utf-8")
    ).hexdigest()
    return projection


def verify_approvals_pep(path: pathlib.Path, projection: dict[str, Any]) -> None:
    document = json.loads(path.read_text(encoding="utf-8"))
    require(document == projection, f"{projection['projectionKey']}: generated artifact drift")
    payload = copy.deepcopy(document)
    actual_checksum = payload.pop("projectionChecksum", None)
    require(
        actual_checksum == hashlib.sha256(stable_json(payload).encode("utf-8")).hexdigest(),
        f"{projection['projectionKey']}: projection checksum mismatch",
    )
    require(
        all(
            binding["serviceKey"] == projection["ownerServiceKey"]
            for route in document["routes"]
            for binding in route["servicePepBindings"]
        ),
        f"{projection['projectionKey']}: foreign service binding escaped projection",
    )


def build_platform_telemetry_dimensions(snapshot: dict[str, Any]) -> dict[str, Any]:
    """Project closed governed-manifest dimensions anchored to registry v3."""
    require(snapshot["version"] == 3, "Telemetry dimensions must project registry v3")
    surfaces: dict[tuple[str, str], set[str]] = defaultdict(set)
    for policy in snapshot["accessPolicies"]:
        product_key = policy.get("productKey")
        surface_key = policy.get("surfaceKey")
        if product_key is not None:
            surfaces[(product_key, surface_key)]
    for route in snapshot["routes"]:
        subject = route["subject"]
        if subject.get("type") != "PRODUCT":
            continue
        product_key = subject.get("productKey")
        surface_key = subject.get("surfaceKey")
        route_ids = surfaces[(product_key, surface_key)]
        if route.get("uiRouteId") is not None:
            route_ids.add(route["uiRouteId"])
    for surface_key, route_ids in PLATFORM_TELEMETRY_COMPATIBILITY_ROUTE_IDS.items():
        product_key = surface_key.partition(".")[0]
        require(
            (product_key, surface_key) not in surfaces,
            "Compatibility telemetry surface overlaps the active registry",
        )
        surfaces[(product_key, surface_key)].update(route_ids)

    product_documents: list[dict[str, Any]] = []
    for product_key in sorted({key[0] for key in surfaces}):
        product_documents.append({
            "productKey": product_key,
            "surfaces": [
                {
                    "surfaceKey": surface_key,
                    "routeIds": sorted(surfaces[(product_key, surface_key)]),
                    **PLATFORM_TELEMETRY_SURFACE_DIMENSIONS[surface_key],
                }
                for owner, surface_key in sorted(surfaces)
                if owner == product_key
            ],
        })

    projection = {
        "schemaVersion": 2,
        "projectionKey": "platform-telemetry-dimensions-v3",
        "ownerServiceKey": "platform",
        "registryRef": {
            "bundleKey": snapshot["bundleKey"],
            "version": snapshot["version"],
            "sha256": snapshot["checksum"],
        },
        "sourceRegistryRouteCount": len(snapshot["routes"]),
        "productCount": len(product_documents),
        "surfaceCount": sum(len(product["surfaces"]) for product in product_documents),
        "routeIdCount": sum(
            len(surface["routeIds"])
            for product in product_documents
            for surface in product["surfaces"]
        ),
        "products": product_documents,
        "projectionChecksumAlgorithm": "SHA-256",
    }
    require(
        (projection["productCount"], projection["surfaceCount"], projection["routeIdCount"])
        == (12, 26, 134),
        "Platform telemetry dimension closure drift",
    )
    require(
        {surface[1] for surface in surfaces}
        == set(PLATFORM_TELEMETRY_SURFACE_DIMENSIONS),
        "Platform telemetry task/scope allowlist drift",
    )
    require(
        {surface.partition(".")[0] for surface in PLATFORM_TELEMETRY_SURFACE_DIMENSIONS}
        == PRODUCT_SURFACE_ROLLOUT_PRODUCTS,
        "Platform telemetry products drifted from rollout inventory",
    )
    projection["projectionChecksum"] = hashlib.sha256(
        stable_json(projection).encode("utf-8")
    ).hexdigest()
    return projection


def verify_platform_telemetry_dimensions(projection: dict[str, Any]) -> None:
    document = json.loads(
        PLATFORM_TELEMETRY_DIMENSIONS_OUTPUT.read_text(encoding="utf-8")
    )
    require(document == projection, "Platform telemetry dimension artifact drift")
    payload = copy.deepcopy(document)
    actual_checksum = payload.pop("projectionChecksum", None)
    require(
        actual_checksum == hashlib.sha256(stable_json(payload).encode("utf-8")).hexdigest(),
        "Platform telemetry dimension checksum mismatch",
    )
    registry = document["registryRef"]
    require(
        registry["version"] == 3
        and LOWERCASE_SHA256_PATTERN.fullmatch(registry["sha256"]) is not None,
        "Platform telemetry dimensions must bind exact W1b registry v3",
    )
    route_ids: set[str] = set()
    for product in document["products"]:
        product_key = product["productKey"]
        require(product_key in PRODUCT_SURFACE_ROLLOUT_PRODUCTS,
                "Unknown telemetry product escaped projection")
        for surface in product["surfaces"]:
            require(
                surface["surfaceKey"].startswith(product_key + "."),
                "Cross-product telemetry surface escaped projection",
            )
            for route_id in surface["routeIds"]:
                require(
                    route_id.startswith(surface["surfaceKey"] + ".")
                    and route_id not in route_ids,
                    "Cross-surface or duplicate telemetry route escaped projection",
                )
                route_ids.add(route_id)
            require(
                {
                    "scopeKinds": surface["scopeKinds"],
                    "taskKinds": surface["taskKinds"],
                }
                == PLATFORM_TELEMETRY_SURFACE_DIMENSIONS[surface["surfaceKey"]],
                "Telemetry task/scope allowlist drift",
            )


def write_or_check(path: pathlib.Path, content: str, check: bool) -> bool:
    if check:
        try:
            current = path.read_text(encoding="utf-8")
        except OSError:
            print(f"missing generated artifact: {path.relative_to(ROOT)}", file=sys.stderr)
            return False
        if current != content:
            print(f"generated artifact drift: {path.relative_to(ROOT)}", file=sys.stderr)
            return False
        return True
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return True


def verify_artifact_set(index: dict[str, Any]) -> None:
    require(index_checksum(index) == index["indexChecksum"], "index checksum mismatch")
    require(
        [entry["version"] for entry in index["versions"]] == list(BUNDLE_VERSIONS)
        and all(entry["bundleStatus"] == "DRAFT" for entry in index["versions"]),
        "seed index must contain only the declared DRAFT lineage versions",
    )
    latest_version = index["latestVersion"]
    latest_contract = VERSIONED_CONTRACT_OUTPUTS[latest_version].read_bytes()
    latest_seed = VERSIONED_AUTH_SEED_OUTPUTS[latest_version].read_bytes()
    require(LATEST_CONTRACT_OUTPUT.read_bytes() == latest_contract, "latest contract alias drift")
    require(LATEST_AUTH_SEED_OUTPUT.read_bytes() == latest_seed, "latest auth seed alias drift")
    require(latest_contract == latest_seed, "latest contract/auth seed bytes differ")
    require(
        json.loads(CONTRACT_INDEX_OUTPUT.read_text(encoding="utf-8")) == index,
        "contract index drift",
    )
    require(
        json.loads(AUTH_SEED_INDEX_OUTPUT.read_text(encoding="utf-8")) == index,
        "auth seed index drift",
    )
    for entry in index["versions"]:
        version = entry["version"]
        contract_path = VERSIONED_CONTRACT_OUTPUTS[version]
        seed_path = VERSIONED_AUTH_SEED_OUTPUTS[version]
        require(contract_path.exists() and seed_path.exists(), f"v{version}: artifact missing")
        require(contract_path.read_bytes() == seed_path.read_bytes(), f"v{version}: artifact bytes differ")
        document = json.loads(contract_path.read_text(encoding="utf-8"))
        require(document["version"] == version, f"v{version}: artifact version mismatch")
        require(document["checksum"] == entry["checksum"], f"v{version}: index checksum drift")
        require(checksum(document) == entry["checksum"], f"v{version}: artifact checksum invalid")


def verify_no_out_of_lineage_artifacts() -> None:
    allowed = {
        *VERSIONED_CONTRACT_OUTPUTS.values(),
        *VERSIONED_AUTH_SEED_OUTPUTS.values(),
        APPROVAL_PILOT_PEP_OUTPUT,
        APPROVAL_WORK_PEP_OUTPUT,
        APPROVAL_DOCUMENT_PEP_OUTPUT,
        APPROVAL_EXTENSION_PEP_OUTPUT,
        APPROVAL_RELEASE10_PEP_OUTPUT,
        APPROVAL_RECOVERY11_PEP_OUTPUT,
        APPROVAL_RELEASE12_PEP_OUTPUT,
        APPROVAL_RELEASE13_PEP_OUTPUT,
        APPROVAL_RELEASE14_PEP_OUTPUT,
        PLATFORM_APPROVALS_PEP_OUTPUT,
        PLATFORM_TELEMETRY_DIMENSIONS_OUTPUT,
        HCM_PEOPLE_PEP_OUTPUT,
    }
    candidates = {
        *CONTRACT_DIRECTORY.glob("product-surfaces-v1.bundle-v*.json"),
        *AUTH_SEED_DIRECTORY.glob("product-surfaces-v1.bundle-v*.generated.json"),
        *APPROVAL_PILOT_PEP_OUTPUT.parent.glob("approval-pilot-pep-v*.generated.json"),
        *PLATFORM_APPROVALS_PEP_OUTPUT.parent.glob(
            "platform-approvals-pep-v*.generated.json"
        ),
        *PLATFORM_TELEMETRY_DIMENSIONS_OUTPUT.parent.glob(
            "platform-telemetry-dimensions-v*.generated.json"
        ),
        *HCM_PEOPLE_PEP_OUTPUT.parent.glob("hcm-people-pep-v*.generated.json"),
    }
    stale = sorted(path.relative_to(ROOT) for path in candidates - allowed)
    require(not stale, f"out-of-lineage generated artifacts are forbidden: {stale}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail if generated artifacts drift")
    args = parser.parse_args()
    try:
        source = load_source()
        snapshots = build_snapshots(source)
        rollout_inventory = build_rollout_inventory(source)
        index = build_index(snapshots)
        platform_canary_pep = build_platform_canary_pep(snapshots[0])
        approval_pilot_pep = build_approvals_pep(
            snapshots[1],
            "approval",
            "approval-pilot-pep-v2",
            {
                "routes": 39,
                "bindings": 47,
                "routeKinds": {"ACTION": 20, "DATA": 4, "PAGE": 15},
                "capabilities": 24,
                "accessPolicies": 1,
                "entitlementExpressions": 1,
                "predicatePolicies": 6,
            },
        )
        approval_work_pep = build_approvals_pep(
            snapshots[6], "approval", "approval-pilot-pep-v7",
            {
                "routes": 47, "bindings": 55,
                "routeKinds": {"ACTION": 23, "DATA": 9, "PAGE": 15},
                "capabilities": 24, "accessPolicies": 1,
                "entitlementExpressions": 1, "predicatePolicies": 7,
            },
            version=7,
        )
        approval_document_pep = build_approvals_pep(
            snapshots[7], "approval", "approval-pilot-pep-v8",
            {
                "routes": 64, "bindings": 72,
                "routeKinds": {"ACTION": 32, "DATA": 17, "PAGE": 15},
                "capabilities": 28, "accessPolicies": 1,
                "entitlementExpressions": 1, "predicatePolicies": 10,
            },
            version=8,
        )
        approval_extension_pep = build_approvals_pep(
            snapshots[8], "approval", "approval-pilot-pep-v9",
            {
                "routes": 91, "bindings": 99,
                "routeKinds": {"ACTION": 47, "DATA": 29, "PAGE": 15},
                "capabilities": 32, "accessPolicies": 1,
                "entitlementExpressions": 1, "predicatePolicies": 13,
            }, version=9,
        )
        approval_release10_pep = build_approvals_pep(
            snapshots[9], "approval", "approval-pilot-pep-v10",
            {
                "routes": 107, "bindings": 115,
                "routeKinds": {"ACTION": 55, "DATA": 37, "PAGE": 15},
                "capabilities": 37, "accessPolicies": 1,
                "entitlementExpressions": 1, "predicatePolicies": 16,
            }, version=10,
        )
        approval_recovery11_pep = build_approvals_pep(
            snapshots[10], "approval", "approval-pilot-pep-v11",
            {
                "routes": 112, "bindings": 120,
                "routeKinds": {"ACTION": 55, "DATA": 42, "PAGE": 15},
                "capabilities": 37, "accessPolicies": 1,
                "entitlementExpressions": 1, "predicatePolicies": 18,
            }, version=11,
        )
        approval_release12_pep = build_approvals_pep(
            snapshots[11], "approval", "approval-pilot-pep-v12",
            {
                "routes": 141, "bindings": 149,
                "routeKinds": {"ACTION": 75, "DATA": 51, "PAGE": 15},
                "capabilities": 39, "accessPolicies": 1,
                "entitlementExpressions": 1, "predicatePolicies": 18,
            }, version=12,
        )
        approval_release13_pep = build_approvals_pep(
            snapshots[12], "approval", "approval-pilot-pep-v13",
            {
                "routes": 144, "bindings": 152,
                "routeKinds": {"ACTION": 77, "DATA": 52, "PAGE": 15},
                "capabilities": 39, "accessPolicies": 1,
                "entitlementExpressions": 1, "predicatePolicies": 18,
            }, version=13,
        )
        approval_release14_pep = build_approvals_pep(
            snapshots[13], "approval", "approval-pilot-pep-v14",
            {
                "routes": 149, "bindings": 157,
                "routeKinds": {"ACTION": 79, "DATA": 55, "PAGE": 15},
                "capabilities": 39, "accessPolicies": 1,
                "entitlementExpressions": 1, "predicatePolicies": 18,
            }, version=14,
        )
        platform_approvals_pep = build_approvals_pep(
            snapshots[1],
            "platform",
            "platform-approvals-pep-v2",
            {
                "routes": 2,
                "bindings": 2,
                "routeKinds": {"ACTION": 1, "DATA": 1, "PAGE": 0},
                "capabilities": 0,
                "accessPolicies": 1,
                "entitlementExpressions": 1,
                "predicatePolicies": 1,
            },
        )
        platform_telemetry_dimensions = build_platform_telemetry_dimensions(
            snapshots[2]
        )
        hcm_people_pep = build_approvals_pep(
            snapshots[2],
            "people",
            "hcm-people-pep-v3",
            {
                "routes": 48,
                "bindings": 75,
                "routeKinds": {"ACTION": 21, "DATA": 3, "PAGE": 24},
                "capabilities": 28,
                "accessPolicies": 5,
                "entitlementExpressions": 3,
                "predicatePolicies": 11,
            },
            version=3,
            product_key="hcm",
        )
    except ContractError as exc:
        print(f"authorization contract error: {exc}", file=sys.stderr)
        return 1
    outcomes = []
    for snapshot in snapshots:
        version = snapshot["version"]
        content = render(snapshot)
        outcomes.extend(
            [
                write_or_check(VERSIONED_CONTRACT_OUTPUTS[version], content, args.check),
                write_or_check(VERSIONED_AUTH_SEED_OUTPUTS[version], content, args.check),
            ]
        )
    latest_content = render(snapshots[-1])
    index_content = render(index)
    outcomes.extend(
        [
            write_or_check(
                ROLLOUT_INVENTORY_OUTPUT,
                render(rollout_inventory),
                args.check,
            ),
            write_or_check(
                GATEWAY_ROLLOUT_INVENTORY_OUTPUT,
                render(rollout_inventory),
                args.check,
            ),
            write_or_check(LATEST_CONTRACT_OUTPUT, latest_content, args.check),
            write_or_check(LATEST_AUTH_SEED_OUTPUT, latest_content, args.check),
            write_or_check(CONTRACT_INDEX_OUTPUT, index_content, args.check),
            write_or_check(AUTH_SEED_INDEX_OUTPUT, index_content, args.check),
            write_or_check(
                PLATFORM_CANARY_PEP_OUTPUT,
                render(platform_canary_pep),
                args.check,
            ),
            write_or_check(
                APPROVAL_PILOT_PEP_OUTPUT,
                render(approval_pilot_pep),
                args.check,
            ),
            write_or_check(
                APPROVAL_WORK_PEP_OUTPUT,
                render(approval_work_pep),
                args.check,
            ),
            write_or_check(
                APPROVAL_DOCUMENT_PEP_OUTPUT,
                render(approval_document_pep),
                args.check,
            ),
            write_or_check(APPROVAL_EXTENSION_PEP_OUTPUT, render(approval_extension_pep), args.check),
            write_or_check(APPROVAL_RELEASE10_PEP_OUTPUT, render(approval_release10_pep), args.check),
            write_or_check(APPROVAL_RECOVERY11_PEP_OUTPUT, render(approval_recovery11_pep), args.check),
            write_or_check(APPROVAL_RELEASE12_PEP_OUTPUT, render(approval_release12_pep), args.check),
            write_or_check(APPROVAL_RELEASE13_PEP_OUTPUT, render(approval_release13_pep), args.check),
            write_or_check(APPROVAL_RELEASE14_PEP_OUTPUT, render(approval_release14_pep), args.check),
            write_or_check(
                PLATFORM_APPROVALS_PEP_OUTPUT,
                render(platform_approvals_pep),
                args.check,
            ),
            write_or_check(
                PLATFORM_TELEMETRY_DIMENSIONS_OUTPUT,
                render(platform_telemetry_dimensions),
                args.check,
            ),
            write_or_check(
                HCM_PEOPLE_PEP_OUTPUT,
                render(hcm_people_pep),
                args.check,
            ),
        ]
    )
    if not all(outcomes):
        return 1
    try:
        verify_artifact_set(index)
        require(
            ROLLOUT_INVENTORY_OUTPUT.read_bytes()
            == GATEWAY_ROLLOUT_INVENTORY_OUTPUT.read_bytes(),
            "rollout inventory contract/gateway bytes differ",
        )
        require(
            checksum(rollout_inventory) == rollout_inventory["checksum"],
            "rollout inventory checksum mismatch",
        )
        verify_no_out_of_lineage_artifacts()
        verify_platform_canary_pep(platform_canary_pep)
        verify_approvals_pep(APPROVAL_PILOT_PEP_OUTPUT, approval_pilot_pep)
        verify_approvals_pep(APPROVAL_WORK_PEP_OUTPUT, approval_work_pep)
        verify_approvals_pep(APPROVAL_DOCUMENT_PEP_OUTPUT, approval_document_pep)
        verify_approvals_pep(APPROVAL_EXTENSION_PEP_OUTPUT, approval_extension_pep)
        verify_approvals_pep(APPROVAL_RELEASE10_PEP_OUTPUT, approval_release10_pep)
        verify_approvals_pep(APPROVAL_RECOVERY11_PEP_OUTPUT, approval_recovery11_pep)
        verify_approvals_pep(APPROVAL_RELEASE12_PEP_OUTPUT, approval_release12_pep)
        verify_approvals_pep(APPROVAL_RELEASE13_PEP_OUTPUT, approval_release13_pep)
        verify_approvals_pep(APPROVAL_RELEASE14_PEP_OUTPUT, approval_release14_pep)
        verify_approvals_pep(PLATFORM_APPROVALS_PEP_OUTPUT, platform_approvals_pep)
        verify_platform_telemetry_dimensions(platform_telemetry_dimensions)
        verify_approvals_pep(HCM_PEOPLE_PEP_OUTPUT, hcm_people_pep)
    except (ContractError, OSError, json.JSONDecodeError) as exc:
        print(f"authorization artifact error: {exc}", file=sys.stderr)
        return 1
    verb = "verified" if args.check else "generated"
    summary = ", ".join(
        f"v{snapshot['version']}={snapshot['checksum']} routes={len(snapshot['routes'])}"
        for snapshot in snapshots
    )
    print(f"{verb} product-surfaces snapshots: {summary}; latest=v{snapshots[-1]['version']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
