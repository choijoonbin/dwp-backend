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
PLATFORM_CANARY_PEP_V1_OUTPUT = (
    ROOT
    / "dwp-platform-server/src/main/resources/product-authorization/"
    / "platform-canary-pep-v1.generated.json"
)
PLATFORM_CANARY_PEP_OUTPUT = PLATFORM_CANARY_PEP_V1_OUTPUT.with_name(
    "platform-canary-pep-v2.generated.json"
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
APPROVAL_RELEASE15_PEP_OUTPUT = APPROVAL_PILOT_PEP_OUTPUT.with_name(
    "approval-pilot-pep-v15.generated.json"
)
APPROVAL_RELEASE19_PEP_OUTPUT = APPROVAL_PILOT_PEP_OUTPUT.with_name(
    "approval-pilot-pep-v19.generated.json"
)
APPROVAL_RELEASE31_PEP_OUTPUT = APPROVAL_PILOT_PEP_OUTPUT.with_name(
    "approval-pilot-pep-v31.generated.json"
)
APPROVAL_RELEASE32_PEP_OUTPUT = APPROVAL_PILOT_PEP_OUTPUT.with_name(
    "approval-pilot-pep-v32.generated.json"
)
PLATFORM_APPROVALS_PEP_OUTPUT = (
    ROOT
    / "dwp-platform-server/src/main/resources/product-authorization/"
    / "platform-approvals-pep-v2.generated.json"
)
LEGACY_PLATFORM_WORKPLACE_PEP_OUTPUT = (
    ROOT
    / "dwp-platform-server/src/main/resources/product-authorization/"
    / "platform-workplace-pep-v15.generated.json"
)
LEGACY_PLATFORM_WORKPLACE_PEP_V16_OUTPUT = LEGACY_PLATFORM_WORKPLACE_PEP_OUTPUT.with_name(
    "platform-workplace-pep-v16.generated.json"
)
LEGACY_PLATFORM_WORKPLACE_PEP_V17_OUTPUT = LEGACY_PLATFORM_WORKPLACE_PEP_OUTPUT.with_name(
    "platform-workplace-pep-v17.generated.json"
)
LEGACY_PLATFORM_WORKPLACE_PEP_V18_OUTPUT = LEGACY_PLATFORM_WORKPLACE_PEP_OUTPUT.with_name(
    "platform-workplace-pep-v18.generated.json"
)
LEGACY_PLATFORM_WORKPLACE_PEP_V20_OUTPUT = LEGACY_PLATFORM_WORKPLACE_PEP_OUTPUT.with_name(
    "platform-workplace-pep-v20.generated.json"
)
LEGACY_PLATFORM_WORKPLACE_PEP_V21_OUTPUT = LEGACY_PLATFORM_WORKPLACE_PEP_OUTPUT.with_name(
    "platform-workplace-pep-v21.generated.json"
)
LEGACY_PLATFORM_WORKPLACE_PEP_V22_OUTPUT = LEGACY_PLATFORM_WORKPLACE_PEP_OUTPUT.with_name(
    "platform-workplace-pep-v22.generated.json"
)
LEGACY_PLATFORM_WORKPLACE_PEP_V23_OUTPUT = LEGACY_PLATFORM_WORKPLACE_PEP_OUTPUT.with_name(
    "platform-workplace-pep-v23.generated.json"
)
PLATFORM_WORKPLACE_PEP_OUTPUT = LEGACY_PLATFORM_WORKPLACE_PEP_OUTPUT.with_name(
    "platform-workplace-pep-v24.generated.json"
)
LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT = (
    ROOT
    / "dwp-people-server/src/main/resources/product-authorization/"
    / "hcm-people-pep-v3.generated.json"
)
LEGACY_HCM_PEOPLE_PEP_V33_OUTPUT = LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT.with_name(
    "hcm-people-pep-v33.generated.json"
)
LEGACY_HCM_PEOPLE_PEP_V34_OUTPUT = LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT.with_name(
    "hcm-people-pep-v34.generated.json"
)
LEGACY_HCM_PEOPLE_PEP_V35_OUTPUT = LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT.with_name(
    "hcm-people-pep-v35.generated.json"
)
LEGACY_HCM_PEOPLE_PEP_V36_OUTPUT = LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT.with_name(
    "hcm-people-pep-v36.generated.json"
)
LEGACY_HCM_PEOPLE_PEP_V37_OUTPUT = LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT.with_name(
    "hcm-people-pep-v37.generated.json"
)
LEGACY_HCM_PEOPLE_PEP_V38_OUTPUT = LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT.with_name(
    "hcm-people-pep-v38.generated.json"
)
LEGACY_HCM_PEOPLE_PEP_V39_OUTPUT = LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT.with_name(
    "hcm-people-pep-v39.generated.json"
)
HCM_PEOPLE_PEP_OUTPUT = LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT.with_name(
    "hcm-people-pep-v42.generated.json"
)
LEGACY_HCM_TIME_PEP_V35_OUTPUT = (
    ROOT
    / "dwp-time-server/src/main/resources/product-authorization/"
    / "hcm-time-pep-v35.generated.json"
)
LEGACY_HCM_TIME_PEP_V36_OUTPUT = LEGACY_HCM_TIME_PEP_V35_OUTPUT.with_name(
    "hcm-time-pep-v36.generated.json"
)
LEGACY_HCM_TIME_PEP_V37_OUTPUT = LEGACY_HCM_TIME_PEP_V35_OUTPUT.with_name(
    "hcm-time-pep-v37.generated.json"
)
LEGACY_HCM_TIME_PEP_V38_OUTPUT = LEGACY_HCM_TIME_PEP_V35_OUTPUT.with_name(
    "hcm-time-pep-v38.generated.json"
)
HCM_TIME_PEP_OUTPUT = LEGACY_HCM_TIME_PEP_V35_OUTPUT.with_name(
    "hcm-time-pep-v42.generated.json"
)
LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT = (
    ROOT
    / "dwp-platform-server/src/main/resources/product-authorization/"
    / "hcm-platform-pep-v35.generated.json"
)
LEGACY_HCM_PLATFORM_PEP_V36_OUTPUT = LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT.with_name(
    "hcm-platform-pep-v36.generated.json"
)
LEGACY_HCM_PLATFORM_PEP_V37_OUTPUT = LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT.with_name(
    "hcm-platform-pep-v37.generated.json"
)
LEGACY_HCM_PLATFORM_PEP_V38_OUTPUT = LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT.with_name(
    "hcm-platform-pep-v38.generated.json"
)
LEGACY_HCM_PLATFORM_PEP_V40_OUTPUT = LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT.with_name(
    "hcm-platform-pep-v40.generated.json"
)
LEGACY_HCM_PLATFORM_PEP_V42_OUTPUT = LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT.with_name(
    "hcm-platform-pep-v42.generated.json"
)
LEGACY_HCM_PLATFORM_PEP_V43_OUTPUT = LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT.with_name(
    "hcm-platform-pep-v43.generated.json"
)
LEGACY_HCM_PLATFORM_PEP_V44_OUTPUT = LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT.with_name(
    "hcm-platform-pep-v44.generated.json"
)
HCM_PLATFORM_PEP_OUTPUT = LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT.with_name(
    "hcm-platform-pep-v45.generated.json"
)
HCM_PLATFORM_PEP_V46_OUTPUT = LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT.with_name(
    "hcm-platform-pep-v46.generated.json"
)
HCM_PLATFORM_PEP_V47_OUTPUT = LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT.with_name(
    "hcm-platform-pep-v47.generated.json"
)
LEGACY_HCM_PAYROLL_PEP_V38_OUTPUT = (
    ROOT
    / "dwp-payroll-server/src/main/resources/product-authorization/"
    / "hcm-payroll-pep-v38.generated.json"
)
HCM_PAYROLL_PEP_OUTPUT = LEGACY_HCM_PAYROLL_PEP_V38_OUTPUT.with_name(
    "hcm-payroll-pep-v41.generated.json"
)
HCM_PAYROLL_PEP_V48_OUTPUT = LEGACY_HCM_PAYROLL_PEP_V38_OUTPUT.with_name(
    "hcm-payroll-pep-v48.generated.json"
)
HCM_PEOPLE_PEP_V49_OUTPUT = LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT.with_name(
    "hcm-people-pep-v49.generated.json"
)
HCM_TIME_PEP_V49_OUTPUT = LEGACY_HCM_TIME_PEP_V35_OUTPUT.with_name(
    "hcm-time-pep-v49.generated.json"
)
HCM_PLATFORM_PEP_V49_OUTPUT = LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT.with_name(
    "hcm-platform-pep-v49.generated.json"
)
HCM_PAYROLL_PEP_V49_OUTPUT = LEGACY_HCM_PAYROLL_PEP_V38_OUTPUT.with_name(
    "hcm-payroll-pep-v49.generated.json"
)
HCM_PEOPLE_PEP_V50_OUTPUT = LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT.with_name(
    "hcm-people-pep-v50.generated.json"
)
HCM_TIME_PEP_V50_OUTPUT = LEGACY_HCM_TIME_PEP_V35_OUTPUT.with_name(
    "hcm-time-pep-v50.generated.json"
)
HCM_PLATFORM_PEP_V50_OUTPUT = LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT.with_name(
    "hcm-platform-pep-v50.generated.json"
)
HCM_PAYROLL_PEP_V50_OUTPUT = LEGACY_HCM_PAYROLL_PEP_V38_OUTPUT.with_name(
    "hcm-payroll-pep-v50.generated.json"
)
HCM_PEOPLE_PEP_V51_OUTPUT = LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT.with_name(
    "hcm-people-pep-v51.generated.json"
)
HCM_TIME_PEP_V51_OUTPUT = LEGACY_HCM_TIME_PEP_V35_OUTPUT.with_name(
    "hcm-time-pep-v51.generated.json"
)
HCM_PLATFORM_PEP_V51_OUTPUT = LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT.with_name(
    "hcm-platform-pep-v51.generated.json"
)
HCM_PAYROLL_PEP_V51_OUTPUT = LEGACY_HCM_PAYROLL_PEP_V38_OUTPUT.with_name(
    "hcm-payroll-pep-v51.generated.json"
)
HCM_PEOPLE_PEP_V52_OUTPUT = LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT.with_name(
    "hcm-people-pep-v52.generated.json"
)
HCM_TIME_PEP_V52_OUTPUT = LEGACY_HCM_TIME_PEP_V35_OUTPUT.with_name(
    "hcm-time-pep-v52.generated.json"
)
HCM_PLATFORM_PEP_V52_OUTPUT = LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT.with_name(
    "hcm-platform-pep-v52.generated.json"
)
HCM_PAYROLL_PEP_V52_OUTPUT = LEGACY_HCM_PAYROLL_PEP_V38_OUTPUT.with_name(
    "hcm-payroll-pep-v52.generated.json"
)
HCM_PEOPLE_PEP_V53_OUTPUT = LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT.with_name(
    "hcm-people-pep-v53.generated.json"
)
HCM_TIME_PEP_V53_OUTPUT = LEGACY_HCM_TIME_PEP_V35_OUTPUT.with_name(
    "hcm-time-pep-v53.generated.json"
)
HCM_PLATFORM_PEP_V53_OUTPUT = LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT.with_name(
    "hcm-platform-pep-v53.generated.json"
)
HCM_PAYROLL_PEP_V53_OUTPUT = LEGACY_HCM_PAYROLL_PEP_V38_OUTPUT.with_name(
    "hcm-payroll-pep-v53.generated.json"
)
HCM_PEOPLE_PEP_V54_OUTPUT = LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT.with_name(
    "hcm-people-pep-v54.generated.json"
)
HCM_TIME_PEP_V54_OUTPUT = LEGACY_HCM_TIME_PEP_V35_OUTPUT.with_name(
    "hcm-time-pep-v54.generated.json"
)
HCM_PLATFORM_PEP_V54_OUTPUT = LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT.with_name(
    "hcm-platform-pep-v54.generated.json"
)
HCM_PAYROLL_PEP_V54_OUTPUT = LEGACY_HCM_PAYROLL_PEP_V38_OUTPUT.with_name(
    "hcm-payroll-pep-v54.generated.json"
)
PLATFORM_TELEMETRY_DIMENSIONS_OUTPUT = (
    ROOT
    / "dwp-platform-server/src/main/resources/product-authorization/"
    / "platform-telemetry-dimensions-v3.generated.json"
)
HCM_PEP_V55_OUTPUTS = {
    service: ROOT / f"dwp-{service}-server/src/main/resources/product-authorization/"
    f"hcm-{service}-pep-v55.generated.json"
    for service in ("people", "time", "platform", "payroll")
}
HCM_PEP_V56_OUTPUTS = {
    service: ROOT / f"dwp-{service}-server/src/main/resources/product-authorization/"
    f"hcm-{service}-pep-v56.generated.json"
    for service in ("people", "time", "platform", "payroll")
}
HCM_PEP_V57_OUTPUTS = {
    service: ROOT / f"dwp-{service}-server/src/main/resources/product-authorization/"
    f"hcm-{service}-pep-v57.generated.json"
    for service in ("people", "time", "platform", "payroll")
}
HCM_PEP_V58_OUTPUTS = {
    service: ROOT / f"dwp-{service}-server/src/main/resources/product-authorization/"
    f"hcm-{service}-pep-v58.generated.json"
    for service in ("people", "time", "platform", "payroll")
}
BUNDLE_VERSIONS = tuple(range(1, 59))
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
    15: {"capabilities": 136, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 411, "PAGE": 87, "DATA": 117, "ACTION": 207},
    16: {"capabilities": 137, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 425, "PAGE": 87, "DATA": 122, "ACTION": 216},
    17: {"capabilities": 139, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 446, "PAGE": 90, "DATA": 128, "ACTION": 228},
    18: {"capabilities": 139, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 459, "PAGE": 90, "DATA": 137, "ACTION": 232},
    19: {"capabilities": 139, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 460, "PAGE": 90, "DATA": 137, "ACTION": 233},
    20: {"capabilities": 159, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 686, "PAGE": 111, "DATA": 207, "ACTION": 368},
    21: {"capabilities": 159, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 709, "PAGE": 112, "DATA": 217, "ACTION": 380},
    22: {"capabilities": 160, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 749, "PAGE": 118, "DATA": 228, "ACTION": 403},
    23: {"capabilities": 160, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 768, "PAGE": 118, "DATA": 239, "ACTION": 411},
    24: {"capabilities": 160, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 776, "PAGE": 118, "DATA": 244, "ACTION": 414},
    25: {"capabilities": 160, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 777, "PAGE": 118, "DATA": 244, "ACTION": 415},
    26: {"capabilities": 161, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 778, "PAGE": 118, "DATA": 245, "ACTION": 415},
    27: {"capabilities": 161, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 779, "PAGE": 118, "DATA": 245, "ACTION": 416},
    28: {"capabilities": 161, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 46, "routes": 780, "PAGE": 118, "DATA": 245, "ACTION": 417},
    29: {"capabilities": 183, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 47, "routes": 827, "PAGE": 118, "DATA": 256, "ACTION": 453},
    30: {"capabilities": 205, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 47, "routes": 888, "PAGE": 118, "DATA": 263, "ACTION": 507},
    31: {"capabilities": 205, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 47, "routes": 911, "PAGE": 118, "DATA": 274, "ACTION": 519},
    32: {"capabilities": 205, "accessPolicies": 22, "entitlementExpressions": 16,
         "predicatePolicies": 48, "routes": 912, "PAGE": 118, "DATA": 275, "ACTION": 519},
    33: {"capabilities": 219, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 54, "routes": 945, "PAGE": 119, "DATA": 290, "ACTION": 536},
    34: {"capabilities": 223, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 54, "routes": 952, "PAGE": 119, "DATA": 293, "ACTION": 540},
    35: {"capabilities": 236, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 57, "routes": 974, "PAGE": 124, "DATA": 295, "ACTION": 555},
    36: {"capabilities": 237, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 57, "routes": 974, "PAGE": 124, "DATA": 295, "ACTION": 555},
    37: {"capabilities": 237, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 57, "routes": 974, "PAGE": 124, "DATA": 295, "ACTION": 555},
    38: {"capabilities": 242, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 59, "routes": 991, "PAGE": 128, "DATA": 298, "ACTION": 565},
    39: {"capabilities": 245, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 60, "routes": 1003, "PAGE": 129, "DATA": 301, "ACTION": 573},
    40: {"capabilities": 248, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 61, "routes": 1012, "PAGE": 130, "DATA": 302, "ACTION": 580},
    41: {"capabilities": 252, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 64, "routes": 1017, "PAGE": 130, "DATA": 304, "ACTION": 583},
    42: {"capabilities": 255, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 69, "routes": 1028, "PAGE": 132, "DATA": 308, "ACTION": 588},
    43: {"capabilities": 256, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 71, "routes": 1032, "PAGE": 133, "DATA": 309, "ACTION": 590},
    44: {"capabilities": 258, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 73, "routes": 1034, "PAGE": 133, "DATA": 310, "ACTION": 591},
    45: {"capabilities": 258, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 73, "routes": 1046, "PAGE": 133, "DATA": 315, "ACTION": 598},
    46: {"capabilities": 258, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 73, "routes": 1048, "PAGE": 133, "DATA": 317, "ACTION": 598},
    47: {"capabilities": 258, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 73, "routes": 1053, "PAGE": 138, "DATA": 317, "ACTION": 598},
    48: {"capabilities": 258, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 73, "routes": 1063, "PAGE": 138, "DATA": 321, "ACTION": 604},
    49: {"capabilities": 262, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 81, "routes": 1079, "PAGE": 144, "DATA": 326, "ACTION": 609},
    50: {"capabilities": 262, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 81, "routes": 1079, "PAGE": 144, "DATA": 326, "ACTION": 609},
    51: {"capabilities": 265, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 81, "routes": 1087, "PAGE": 149, "DATA": 329, "ACTION": 609},
    52: {"capabilities": 268, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 81, "routes": 1092, "PAGE": 151, "DATA": 331, "ACTION": 610},
    53: {"capabilities": 269, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 81, "routes": 1095, "PAGE": 152, "DATA": 332, "ACTION": 611},
    54: {"capabilities": 270, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 82, "routes": 1100, "PAGE": 154, "DATA": 334, "ACTION": 612},
    55: {"capabilities": 270, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 82, "routes": 1104, "PAGE": 156, "DATA": 336, "ACTION": 612},
    56: {"capabilities": 270, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 82, "routes": 1120, "PAGE": 156, "DATA": 338, "ACTION": 626},
    57: {"capabilities": 271, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 83, "routes": 1123, "PAGE": 157, "DATA": 339, "ACTION": 627},
    58: {"capabilities": 271, "accessPolicies": 24, "entitlementExpressions": 17,
         "predicatePolicies": 83, "routes": 1125, "PAGE": 158, "DATA": 340, "ACTION": 627},
}

V47_CANONICAL_PAGE_FIRST_READ_SOURCES = {
    "route.hcm.operations.time-work-plans.page":
        "route.hcm.operations.work-plans-list.data",
    "route.hcm.operations.performance-goal-cycles.page":
        "route.hcm.operations.performance-cycles-list.data",
    "route.hcm.team.leave.page": "route.hcm.team.absence.page",
    "route.hcm.operations.time-leave-operations.page":
        "route.hcm.operations.absence.page",
    "route.hcm.operations.home.page": "route.hcm.operations.overview.page",
}

V49_PAYROLL_CAPABILITY_HARDENING = {
    "hcm.operations.payroll-run.execute": {
        "riskTier": "CRITICAL",
        "activationPolicy": "STEPUP-MGMT-HIGH-V1",
    },
    "hcm.operations.payroll-support.view": {
        "riskTier": "HIGH",
        "activationPolicy": "STEPUP-MGMT-HIGH-V1",
    },
}
V49_PAYROLL_CALCULATE_ROUTE = "route.hcm.operations.payroll-run-calculate.action"
V49_PAYROLL_CALCULATE_STEP_UP = [{
    "bindingKey": "route.hcm.operations.payroll-run-calculate.action.binding.01",
    "targetType": "PAYROLL_RUN",
    "targetIdPathParameter": "runId",
    "expectedObjectVersionSource": "COMMAND_HEADER",
    "expectedObjectVersionName": "X-DWP-Expected-Object-Version",
    "ownerServiceKey": "payroll",
    "audience": "dwp-payroll-server",
}]
V50_TIM007_ROUTE_RESPONSES = (
    {
        "routeContractKey": "route.hcm.operations.time-command-center.page",
        "profileKey": "time-operations-reader",
        "responseSchemaKey": "TimeCommandCenterProjectionV1",
        "apiBindingKey": "route.hcm.operations.time-command-center.page.binding.01",
        "projectionPolicyKey": (
            "route.hcm.operations.time-command-center.page."
            "time-operations-reader.projection.v1"
        ),
        "openApiSchemaSha256": (
            "b9f8e20f38dc265df0b65ae498c5728536e9634b719bae11dad4aeb53f3afd8e"
        ),
    },
    {
        "routeContractKey": "route.hcm.operations.time-daily-close.page",
        "profileKey": "time-operations-reader",
        "responseSchemaKey": "TimeDailyCloseProjectionV1",
        "apiBindingKey": "route.hcm.operations.time-daily-close.page.binding.01",
        "projectionPolicyKey": (
            "route.hcm.operations.time-daily-close.page."
            "time-operations-reader.projection.v1"
        ),
        "openApiSchemaSha256": (
            "7ad4402e9fd26c2d76fc38f9554a6f8370562e536879e516084c32d57c85e039"
        ),
    },
    {
        "routeContractKey": "route.hcm.operations.time-monthly-close.page",
        "profileKey": "time-operations-reader",
        "responseSchemaKey": "TimeMonthlyCloseProjectionV1",
        "apiBindingKey": "route.hcm.operations.time-monthly-close.page.binding.01",
        "projectionPolicyKey": (
            "route.hcm.operations.time-monthly-close.page."
            "time-operations-reader.projection.v1"
        ),
        "openApiSchemaSha256": (
            "985741661b4d879f242bfcbeb9310af40ae917411ae817bf1eb6239766e580c9"
        ),
    },
    {
        "routeContractKey": "route.hcm.operations.time-payroll-handoff.data",
        "profileKey": "time-close-operator",
        "responseSchemaKey": "PayrollTimeHandoffStatusV1",
    },
    {
        "routeContractKey": "route.hcm.time.command-receipt.data",
        "profileKey": "originating-command",
        "responseSchemaKey": "TimeCommandReceiptLookupV1",
    },
)
GRANDFATHERED_GENERIC_SESSION_STEP_UP_ROUTE_KEYS = frozenset({
    "route.hcm.configuration.configuration-version.data",
    "route.hcm.configuration.configurations.data",
    "route.hcm.operations.time-interpretation-run.action",
    "route.hcm.operations.time-interpretation-trace.data",
    "route.hcm.time.timecard-correction.action",
    "route.hcm.time.timecard.data",
})
V49_GENERIC_SESSION_STEP_UP_ROUTE_KEYS = frozenset({
    "route.hcm.operations.payroll-population.page",
    "route.hcm.operations.payroll-support.data",
    "route.hcm.operations.payroll-run.data",
    "route.hcm.operations.time-close-validate.action",
    "route.hcm.operations.time-payroll-handoff-create.action",
    "route.hcm.operations.time-payroll-handoff.data",
})
V54_GENERIC_SESSION_STEP_UP_ROUTE_KEYS = frozenset({
    "route.hcm.operations.pay-tax-social-insurance.page",
    "route.hcm.operations.pay-tax-social-insurance.data",
})
V56_GENERIC_SESSION_STEP_UP_ROUTE_KEYS = frozenset({
    "route.hcm.configuration.automation-catalog.data",
    "route.hcm.configuration.automation-run-detail.data",
})
V58_GENERIC_SESSION_STEP_UP_ROUTE_KEYS = frozenset({
    "route.hcm.operations.pay-retirement.page",
    "route.hcm.operations.pay-retirement.data",
})
GENERIC_SESSION_STEP_UP_ROUTE_KEYS = (
    GRANDFATHERED_GENERIC_SESSION_STEP_UP_ROUTE_KEYS
    | V49_GENERIC_SESSION_STEP_UP_ROUTE_KEYS
    | V54_GENERIC_SESSION_STEP_UP_ROUTE_KEYS
    | V56_GENERIC_SESSION_STEP_UP_ROUTE_KEYS
    | V58_GENERIC_SESSION_STEP_UP_ROUTE_KEYS
)

REQUIRED_RESPONSIBILITY_BY_CAPABILITY = {
    "hcm.app.shell.view": "HRIS_DUTY_APP_SHELL_READER",
    "hcm.audit.evidence.view": "HRIS_DUTY_AUDITOR",
    "hcm.integration.transfer.view": "HRIS_DUTY_CONTROLLED_TRANSFER_READER",
    "hcm.integration.transfer.create": "HRIS_DUTY_CONTROLLED_TRANSFER_CREATOR",
    "hcm.operations.organization.view": "HRIS_DUTY_ORGANIZATION_VIEWER",
    "hcm.operations.exception.view": "HRIS_DUTY_OPERATION_EXCEPTION_READER",
    "hcm.operations.time.close": "HRIS_DUTY_TIME_CLOSE_OPERATOR",
    "hcm.operations.time.reopen": "HRIS_DUTY_TIME_REOPEN_OPERATOR",
    "hcm.operations.contract.issue": "HRIS_DUTY_CONTRACT_ISSUER",
    "hcm.operations.statutory.execute": "HRIS_DUTY_STATUTORY_OPERATOR",
    "hcm.integration.workforce-ingestion.execute": "HRIS_DUTY_INTEGRATION_EXECUTOR",
}

V42_HCM_AUDIT_ELEVATED_READ_PROJECTIONS = {
    "route.hcm.audit.evidence.page": (
        "PAGE", "route.hcm.audit.evidence.page.hcm-evidence-minimized.projection.v1",
        "HcmAuditEvidencePageV1",
    ),
    "route.hcm.audit.evidence-detail.data": (
        "DATA", "route.hcm.audit.evidence-detail.data.hcm-evidence-minimized.projection.v1",
        "HcmAuditEvidenceV1",
    ),
}

V37_TIME_VOID_ROUTE = "route.hcm.operations.time-clock-event-void.action"
V37_TIME_VOID_REMOVED_PROFILE = "self-service"
V37_PROFILE_REMOVALS = frozenset({
    (V37_TIME_VOID_ROUTE, V37_TIME_VOID_REMOVED_PROFILE, 37),
})
V39_COMPENSATION_NAVIGATION_POLICY_KEYS = frozenset({
    "hcm.operations-access.v1",
    "hcm.operations-overview-read.v1",
})
V39_COMPENSATION_VIEW_CAPABILITY = "hcm.compensation.plan.view"

V30_RESEARCH_AUTHORITY_UPGRADE_ROUTES = frozenset({
    "route.dwaion.work.research-audit-download.data",
    "route.dwaion.work.research-deliveries.data",
    "route.dwaion.work.research-output.action",
    "route.dwaion.work.research-plan-create.action",
    "route.dwaion.work.research-plan-update.action",
    "route.dwaion.work.research-plans.data",
    "route.dwaion.work.research-raw-download.data",
    "route.dwaion.work.research-receipt-download.data",
    "route.dwaion.work.research-run-command.action",
    "route.dwaion.work.research-run-execute.action",
    "route.dwaion.work.research-run-start.action",
    "route.dwaion.work.research-runs.data",
})
V33_HRIS_QUERY_DISCRIMINATORS = {
    "route.hcm.operations.people.page": (
        "route.hcm.operations.people.page.binding.01",
        {"view": {"kind": "ABSENT"}, "projection": {"kind": "ABSENT"}},
    ),
    "route.hcm.operations.assignments.page": (
        "route.hcm.operations.assignments.page.binding.01",
        {
            "view": {"kind": "FIXED", "value": "assignments"},
            "projection": {"kind": "ABSENT"},
        },
    ),
    "route.hcm.operations.person-detail.data": (
        "route.hcm.operations.person-detail.data.binding.01",
        {"projection": {"kind": "ABSENT"}},
    ),
    "route.hcm.personal.home.page": (
        "route.hcm.personal.home.page.binding.01",
        {"projection": {"kind": "ABSENT"}},
    ),
    "route.hcm.team.home.page": (
        "route.hcm.team.home.page.binding.01",
        {"projection": {"kind": "ABSENT"}},
    ),
    "route.hcm.personal.product-access-snapshot.data": (
        "route.hcm.personal.product-access-snapshot.data.binding.01",
        {"view": {"kind": "ABSENT"}},
    ),
    "route.hcm.personal.configuration-projection.data": (
        "route.hcm.personal.configuration-projection.data.binding.01",
        {"view": {"kind": "ABSENT"}},
    ),
}
V30_RESEARCH_READ_AUTHORITY_UPGRADE_ROUTES = frozenset(
    key for key in V30_RESEARCH_AUTHORITY_UPGRADE_ROUTES if key.endswith(".data")
)
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
    15: "a9eab001b26d6488de0f7f176fa1d70fea2c9792a230769f645eb53ba90cf89f",
    16: "a677d2ad662d8791a09cdf4ad592f3483aa3dbf068ed2785c648351e8f2a20d0",
    17: "cdd1e671c8df1bbd63cab442acf175ee22c645cac071b79a3598f498c9947239",
    18: "ce5ce0e2058ab33998c0bf19ace2ff54ace06c8c0bda4dc4dca3554b06926fc5",
    19: "32bec6d3fb912af6072139aec72228281597c5b4fbb15f8b4af947c20721644c",
    20: "1acbce34c450c650aa3e8f1c11995b831f177ee4a6139dd8217ea5bdd22e7a60",
    21: "4cd1732df91d197cc47fca94699b0fb702ab1f6f2c557d3d17ce0e069d65af85",
    22: "1629b75f62c7bb524dc70faaecac73499b9f9b0fb126ab38221b6e4773f35ede",
    23: "4687f384ce79faacf4e2b1eb9c6b8eca3f2109dd62a88ac01c5deda22e1925c7",
    24: "be3db891d27cd0b94aa88ac706d9bc87d4b991c9f9d8e505e26b296647728b84",
    25: "805fa41852b1679d3c7c473d650020f481ace0c2a7910271872d935c223287c1",
    26: "b0e355e169233abef1cbcbc39672ec9a66feaa602b8cf8ab6fe4e8b3b97cd278",
    27: "f88e17c65ef95f8a9e1879b48eb093db40eda18b5fc3e077bce9b8abdfe44f6f",
    28: "7e807a353f281a9e0bc6d9d33074c5b1806946d21399bc541682bbf6dcab6f90",
    29: "aac6b6dfc94b4f4ea2956862ff1aa73ccd3459b29548fb20b3cbc26ae29cdbd8",
    30: "7c437bd768225db7dfe1c2491bcdb6ff256a2376946bab6a4ba4a62a7f31ce80",
    31: "be4e1b6db3d3f0b5100182a3c80066a39c64479f9ba88d908fee661efd3335b8",
    32: "b620ea86a8310cf23796e3e380b74c39764bdca28f41033496d21887a89da9cc",
    33: "9c9a18b44eb83de0e98f4ec16e44c1df0ce216e00bc7075462f4e35f7fb87639",
    34: "852d20e1e639e1a7170f02b5714d21d8c51a9eb8ff5ac32d8b7940b82d6be83b",
    35: "38ef33070a33b5da9a7825c764bd24daacd24c8ca4008f3a926727321d3d098d",
    36: "12e4b9329ab53ab1efd6c5f6e23ccc7869c4184a79e5a64f614fe9c40a9e704b",
    37: "1f23280116bc5b29f1a7ea6cc4ccbd2092a91673979d6bb46164a0dbb95ae10c",
    38: "a41145d6a2faefd2e6030a2ca9a070769019d4db5513ea214bf542dda31c374a",
    39: "86e796c95e45c02892d4c4ae76e7a5b36114ab09554ffc1be3a450066fe8e34c",
    40: "4dca145cbc9169c2d1fde71771b83a1a0635679a7190a39d0a21b4e76bbb9329",
    41: "026157acf598ebabf156b40e3c084c40d52346ebfd2019e2476f30ecdd2b8d93",
    42: "f73f9a64816ac8f5567a2a7c292821f4a40e6d675915ebc3c3ac8aad7d1fd983",
    43: "2222319502c362caf247a7a47fdacfc63c774dbf156f6272cafae797627e2400",
    44: "8b4e05e5974a8a25fb9edf318bb3cd609105f2e6353677b69c2eab1cd7038a1b",
    45: "6f41e17159104cd0fd721a13877611b6225bd6458cb2831f0f30e51573992e64",
    46: "3499e37653587feed433f84a79fbe7d0e80a1a7c0634d858a13f4e09d4bd015e",
    47: "925e8bfc406bc7e4ee62aee08582fdd7c67c629ffde470051704eaec5404800e",
    48: "9fd324cd67ede1f02cc8ac33c08ad880ec6e283f99f5d7aa50e0e514545aa043",
    49: "5c1dccdc27d8b260fbf001de5695e983a7107072824a8087038340d0d84cbc60",
    50: "c52222caad2749c4048c694f6582e157f6ab1184f73237f0944c3d88807a2760",
    51: "3beda5c13a3af0e265ace4533a3afcee3691c2b7486465dcf46156e58ae5ed2c",
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
PLATFORM_EXACT_CATALOG_ROUTE_KEYS = {
    "route.hcm.management.integration-code-sets.data",
    "route.hcm.management.org-code-sets.data",
}
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
    "agent": ("/v1/",),
    "auth": ("/auth/",),
    "meeting": ("/v1/",),
    "messaging": ("/v1/",),
    "notification": ("/v1/",),
    "payroll": ("/v1/",),
    "platform": ("/v1/", "/v2/", "/v3/"),
    "approval": ("/v1/",),
    "people": ("/v1/",),
    "space": ("/v1/",),
    "time": ("/v1/",),
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
        set(enrichment)
        == {
            "capabilities", "accessPolicies", "routes", "authorityEndpoints",
            "securityHardening", "tim007Successor",
        },
        "descriptorEnrichments envelope is invalid",
    )
    security_hardening = enrichment["securityHardening"]
    require(
        security_hardening == {
            "introducedInVersion": 49,
            "capabilities": [
                {"contractKey": key, **values}
                for key, values in V49_PAYROLL_CAPABILITY_HARDENING.items()
            ],
            "routes": [{
                "routeContractKey": V49_PAYROLL_CALCULATE_ROUTE,
                "profileKey": "payroll-run-operator",
                "activeAccessModes": ["ELEVATED"],
                "stepUpCommandBindings": V49_PAYROLL_CALCULATE_STEP_UP,
            }],
        },
        "securityHardening must contain only the exact v49 payroll exception",
    )
    require(
        enrichment["tim007Successor"] == {
            "introducedInVersion": 50,
            "routeResponses": list(V50_TIM007_ROUTE_RESPONSES),
        },
        "tim007Successor must contain only the exact v50 response bindings",
    )
    authority = enrichment["authorityEndpoints"]
    require(
        isinstance(authority, dict)
        and set(authority) == {"introducedInVersion", "values", "versionedValues"}
        and authority["introducedInVersion"] == 2
        and isinstance(authority["values"], list),
        "authorityEndpoints must be introduced exactly in registry v2",
    )
    require(
        isinstance(authority["versionedValues"], list)
        and all(
            isinstance(value, dict)
            and isinstance(value.get("introducedInVersion"), int)
            and 2 < value["introducedInVersion"] <= BUNDLE_VERSIONS[-1]
            and isinstance(value.get("endpoint"), dict)
            for value in authority["versionedValues"]
        ),
        "versioned authorityEndpoints are invalid",
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
        for descriptor_key, patch in patches.items():
            introduced = patch.get("introducedInVersion", first_versions[section][descriptor_key])
            require(
                isinstance(introduced, int)
                and first_versions[section][descriptor_key] <= introduced <= BUNDLE_VERSIONS[-1],
                f"{descriptor_key}: invalid enrichment introduction version",
            )

    declared_profile_removals = {
        (
            patch["routeContractKey"],
            removal.get("profileKey"),
            removal.get("introducedInVersion"),
        )
        for patch in enrichment["routes"]
        for removal in patch.get("versionedAccessProfileRemovals", [])
        if isinstance(patch, dict) and isinstance(removal, dict)
    }
    require(
        declared_profile_removals == V37_PROFILE_REMOVALS,
        "versioned access profile removals must contain only the exact v37 time void correction",
    )

    access_policy_patches = unique(
        enrichment["accessPolicies"],
        "accessPolicyKey",
        "access policy enrichments",
    )
    require(
        set(access_policy_patches) == V39_COMPENSATION_NAVIGATION_POLICY_KEYS,
        "access policy enrichments must contain only the v39 compensation navigation policies",
    )
    for policy_key, patch in access_policy_patches.items():
        require(
            patch
            == {
                "accessPolicyKey": policy_key,
                "introducedInVersion": 39,
                "normalCapabilityAdditions": [V39_COMPENSATION_VIEW_CAPABILITY],
            },
            f"{policy_key}: invalid v39 compensation navigation enrichment",
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
        candidate["authorityEndpoints"].extend(
            copy.deepcopy(value["endpoint"])
            for value in authority["versionedValues"]
            if version >= value["introducedInVersion"]
        )
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

    policies = unique(
        candidate["accessPolicies"], "accessPolicyKey", f"v{version} access policies"
    )
    policy_patches = unique(
        enrichment["accessPolicies"],
        "accessPolicyKey",
        "access policy enrichments",
    )
    for key, patch in policy_patches.items():
        if key not in policies or version < patch["introducedInVersion"]:
            continue
        branches = [
            branch
            for branch in policies[key]["modeBranches"]
            if branch.get("activeAccessMode") == "NORMAL"
            and branch.get("resultGrantKind") == "CAPABILITY"
            and branch.get("capabilityMode") == "ANY"
        ]
        require(len(branches) == 1, f"{key}: exact NORMAL ANY capability branch required")
        capability_keys = branches[0]["capabilityContractKeys"]
        for capability_key in patch["normalCapabilityAdditions"]:
            require(
                capability_key in capabilities,
                f"{key}: unknown enriched capability {capability_key}",
            )
            require(
                capability_key not in capability_keys,
                f"{key}: duplicate enriched capability {capability_key}",
            )
            capability_keys.append(capability_key)

    routes = unique(candidate["routes"], "routeContractKey", f"v{version} routes")
    route_patches = unique(
        enrichment["routes"], "routeContractKey", "route enrichments"
    )
    for key, patch in route_patches.items():
        if key not in routes or version < patch.get("introducedInVersion", 1):
            continue
        require(
            set(patch) <= {
                "routeContractKey", "authorizationEquivalenceKey",
                "queryParameterConstraintsByBinding", "projectionBindings",
                "versionedQueryParameterConstraints",
                "versionedAccessProfileRemovals",
                "stepUpCommandBindings", "requiredAccessByProfile",
                "introducedInVersion"
            },
            f"{key}: invalid route enrichment fields",
        )
        route = routes[key]
        if "authorizationEquivalenceKey" in patch:
            route["authorizationEquivalenceKey"] = patch["authorizationEquivalenceKey"]
        if "stepUpCommandBindings" in patch:
            route["stepUpCommandBindings"] = copy.deepcopy(patch["stepUpCommandBindings"])
        required_access = patch.get("requiredAccessByProfile", {})
        require(
            isinstance(required_access, dict)
            and all(
                isinstance(profile_key, str)
                and profile_key
                and isinstance(value, dict)
                for profile_key, value in required_access.items()
            ),
            f"{key}: invalid required access enrichment",
        )
        profiles = unique(route["accessProfiles"], "profileKey", f"{key} profiles")
        require(
            set(required_access) <= set(profiles),
            f"{key}: required access enrichment references an unknown profile",
        )
        for profile_key, value in required_access.items():
            profiles[profile_key]["requiredAccess"] = copy.deepcopy(value)
        profile_removals = patch.get("versionedAccessProfileRemovals", [])
        require(
            isinstance(profile_removals, list)
            and all(
                isinstance(value, dict)
                and set(value) == {"introducedInVersion", "profileKey"}
                and (
                    key,
                    value.get("profileKey"),
                    value.get("introducedInVersion"),
                ) in V37_PROFILE_REMOVALS
                for value in profile_removals
            ),
            f"{key}: invalid versioned access profile removal",
        )
        for value in profile_removals:
            if version < value["introducedInVersion"]:
                continue
            profile_key = value["profileKey"]
            require(
                profile_key in profiles,
                f"{key}: removed access profile does not exist: {profile_key}",
            )
            route["accessProfiles"] = [
                profile for profile in route["accessProfiles"]
                if profile["profileKey"] != profile_key
            ]
            profiles.pop(profile_key)
        for binding_key, constraints in patch.get(
            "queryParameterConstraintsByBinding", {}
        ).items():
            _apply_query_constraints(route, binding_key, constraints)
        versioned_query_constraints = patch.get(
            "versionedQueryParameterConstraints", []
        )
        require(
            isinstance(versioned_query_constraints, list)
            and all(
                isinstance(value, dict)
                and set(value) == {"introducedInVersion", "bindingKey", "constraints"}
                and isinstance(value["introducedInVersion"], int)
                and 1 <= value["introducedInVersion"] <= BUNDLE_VERSIONS[-1]
                and isinstance(value["bindingKey"], str)
                and value["bindingKey"]
                for value in versioned_query_constraints
            ),
            f"{key}: invalid versioned query constraints",
        )
        for value in versioned_query_constraints:
            if version >= value["introducedInVersion"]:
                _apply_query_constraints(
                    route, value["bindingKey"], value["constraints"]
                )
        for projection in patch.get("projectionBindings", []):
            _apply_projection_binding(route, projection)

    security_hardening = enrichment["securityHardening"]
    if version >= security_hardening["introducedInVersion"]:
        for patch in security_hardening["capabilities"]:
            capability = capabilities[patch["contractKey"]]
            capability["riskTier"] = patch["riskTier"]
            capability["activationPolicy"] = patch["activationPolicy"]
        for patch in security_hardening["routes"]:
            route = routes[patch["routeContractKey"]]
            profiles = unique(
                route["accessProfiles"], "profileKey",
                f"{patch['routeContractKey']} profiles",
            )
            profiles[patch["profileKey"]]["activeAccessModes"] = copy.deepcopy(
                patch["activeAccessModes"]
            )
            route["stepUpCommandBindings"] = copy.deepcopy(
                patch["stepUpCommandBindings"]
            )
    tim007_successor = enrichment["tim007Successor"]
    if version >= tim007_successor["introducedInVersion"]:
        for patch in tim007_successor["routeResponses"]:
            route_key = patch["routeContractKey"]
            route = routes[route_key]
            profiles = unique(
                route["accessProfiles"], "profileKey", f"{route_key} profiles"
            )
            profile = profiles[patch["profileKey"]]
            profile["responseSchemaKey"] = patch["responseSchemaKey"]
            if "openApiSchemaSha256" in patch:
                profile["responseProjectionBindings"] = [{
                    "apiBindingKey": patch["apiBindingKey"],
                    "projectionPolicyKey": patch["projectionPolicyKey"],
                    "responseSchemaKey": patch["responseSchemaKey"],
                    "schemaVersion": 1,
                    "openApiSchemaSha256": patch["openApiSchemaSha256"],
                    "additionalProperties": False,
                }]
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


def _is_v50_tim007_response_upgrade(
    route_key: str,
    previous: dict[str, Any],
    current: dict[str, Any],
) -> bool:
    patch = next(
        (
            value for value in V50_TIM007_ROUTE_RESPONSES
            if value["routeContractKey"] == route_key
        ),
        None,
    )
    if patch is None:
        return False
    prior = copy.deepcopy(previous)
    candidate = copy.deepcopy(current)
    prior_profiles = unique(
        prior["accessProfiles"], "profileKey", f"{route_key} previous profiles"
    )
    candidate_profiles = unique(
        candidate["accessProfiles"], "profileKey", f"{route_key} current profiles"
    )
    profile_key = patch["profileKey"]
    if prior_profiles.keys() != candidate_profiles.keys() or profile_key not in candidate_profiles:
        return False
    profile = candidate_profiles[profile_key]
    if "openApiSchemaSha256" in patch:
        expected_projection = [{
            "apiBindingKey": patch["apiBindingKey"],
            "projectionPolicyKey": patch["projectionPolicyKey"],
            "responseSchemaKey": patch["responseSchemaKey"],
            "schemaVersion": 1,
            "openApiSchemaSha256": patch["openApiSchemaSha256"],
            "additionalProperties": False,
        }]
    else:
        expected_projection = copy.deepcopy(
            prior_profiles[profile_key]["responseProjectionBindings"]
        )
        expected_projection[0]["responseSchemaKey"] = patch["responseSchemaKey"]
    if profile.get("responseProjectionBindings") != expected_projection:
        return False
    profile["responseProjectionBindings"] = copy.deepcopy(
        prior_profiles[profile_key]["responseProjectionBindings"]
    )
    return candidate == prior


def _validate_exact_superset(previous: dict[str, Any], current: dict[str, Any]) -> None:
    require(current["version"] == previous["version"] + 1, "snapshot versions must be contiguous")
    added_descriptor = False
    applied_v37_correction = False
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
            if (current["version"] == 15 and section == "routes"
                    and descriptor_key == "route.workplace.work.explore.page"):
                require(
                    candidate_descriptor.pop("authorizationEquivalenceKey", None)
                    == "wire-authority.workplace.work.explore.v1",
                    "v15 Workplace find alias equivalence is required",
                )
            if (
                current["version"] == 30
                and section == "routes"
                and _is_v30_research_authority_upgrade(
                    descriptor_key, prior_descriptor, candidate_descriptor
                )
            ):
                continue
            if (
                current["version"] == 33
                and section == "routes"
                and _is_v33_hris_query_discriminator_upgrade(
                    descriptor_key, prior_descriptor, candidate_descriptor
                )
            ):
                continue
            if (
                current["version"] == 35
                and section == "routes"
                and _is_v35_performance_authority_upgrade(
                    descriptor_key, prior_descriptor, candidate_descriptor
                )
            ):
                continue
            if (
                current["version"] == 36
                and section == "routes"
                and _is_v36_performance_freeze_authority_upgrade(
                    descriptor_key, prior_descriptor, candidate_descriptor
                )
            ):
                continue
            if (
                current["version"] == 37
                and section == "routes"
                and _is_v37_time_void_profile_removal(
                    descriptor_key, prior_descriptor, candidate_descriptor
                )
            ):
                applied_v37_correction = True
                continue
            if (
                current["version"] == 30
                and section == "accessPolicies"
                and descriptor_key == "dwaion.work-access.v1"
                and prior_descriptor == candidate_descriptor
                and candidate_routes
                == prior_routes - V30_RESEARCH_READ_AUTHORITY_UPGRADE_ROUTES
            ):
                continue
            if (
                current["version"] == 39
                and section == "accessPolicies"
                and _is_v39_compensation_navigation_policy_upgrade(
                    descriptor_key, prior_descriptor, candidate_descriptor
                )
            ):
                continue
            if (
                current["version"] == 35
                and section == "capabilities"
                and descriptor_key == "hcm.operations.talent.update"
                and prior_descriptor == candidate_descriptor
                and candidate_routes == prior_routes - {
                    "route.hcm.operations.performance-cycle-population-preview.action"
                }
            ):
                continue
            if (
                current["version"] == 36
                and section == "capabilities"
                and descriptor_key == "hcm.operations.performance.update"
                and prior_descriptor == candidate_descriptor
                and candidate_routes == prior_routes - {
                    "route.hcm.operations.performance-population-freeze.action"
                }
            ):
                continue
            if (
                current["version"] == 37
                and section == "capabilities"
                and descriptor_key == "hcm.personal.time.submit"
                and prior_descriptor == candidate_descriptor
                and candidate_routes == prior_routes - {V37_TIME_VOID_ROUTE}
            ):
                continue
            if (
                current["version"] == 49
                and section == "capabilities"
                and descriptor_key in V49_PAYROLL_CAPABILITY_HARDENING
            ):
                expected = copy.deepcopy(prior_descriptor)
                expected.update(V49_PAYROLL_CAPABILITY_HARDENING[descriptor_key])
                require(
                    candidate_descriptor == expected
                    and prior_routes <= candidate_routes,
                    f"v49/{descriptor_key}: payroll capability hardening drift",
                )
                continue
            if (
                current["version"] == 49
                and section == "routes"
                and descriptor_key == V49_PAYROLL_CALCULATE_ROUTE
            ):
                expected = copy.deepcopy(prior_descriptor)
                profiles = unique(
                    expected["accessProfiles"], "profileKey",
                    f"{descriptor_key} profiles",
                )
                profiles["payroll-run-operator"]["activeAccessModes"] = ["ELEVATED"]
                expected["stepUpCommandBindings"] = copy.deepcopy(
                    V49_PAYROLL_CALCULATE_STEP_UP
                )
                require(
                    candidate_descriptor == expected,
                    f"v49/{descriptor_key}: payroll calculate hardening drift",
                )
                continue
            if (
                current["version"] == 50
                and section == "routes"
                and _is_v50_tim007_response_upgrade(
                    descriptor_key, prior_descriptor, candidate_descriptor
                )
            ):
                continue
            if prior_descriptor != candidate_descriptor or not prior_routes <= candidate_routes:
                drift.append(descriptor_key)
        require(
            not drift,
            f"v{current['version']}/{section}: prior descriptor or reverse-reference drift {drift}",
        )
    if current["version"] == 37:
        require(
            not added_descriptor and applied_v37_correction,
            "v37 must contain only the exact time void access-profile correction",
        )
    elif current["version"] == 50:
        require(
            not added_descriptor,
            "v50 must contain only the exact TIM-007 response binding successor",
        )
    else:
        require(added_descriptor,
                f"v{current['version']}: append-only wave must add a descriptor")
    prior_endpoints = previous.get("authorityEndpoints", [])
    current_endpoints = current.get("authorityEndpoints", [])
    prior_endpoint_map = {
        value["endpointKey"]: value for value in prior_endpoints
    }
    current_endpoint_map = {
        value["endpointKey"]: value for value in current_endpoints
    }
    if current["version"] == 2:
        require(not prior_endpoints and current_endpoints,
                "v2 must introduce the step-up authority endpoint")
    elif current["version"] == 46:
        require(
            prior_endpoint_map.keys() < current_endpoint_map.keys()
            and all(current_endpoint_map[key] == value
                    for key, value in prior_endpoint_map.items()),
            "v46 must append the internal HRIS navigation POLICY authority endpoint",
        )
    else:
        require(prior_endpoints == current_endpoints,
                f"v{current['version']}: authority endpoint drift")


def _is_v30_research_authority_upgrade(
    route_key: str,
    previous: dict[str, Any],
    current: dict[str, Any],
) -> bool:
    if route_key not in V30_RESEARCH_AUTHORITY_UPGRADE_ROUTES:
        return False
    expected_capability = (
        "dwaion.work.research.read"
        if route_key in V30_RESEARCH_READ_AUTHORITY_UPGRADE_ROUTES
        else "dwaion.work.research.manage"
    )
    expected_access = {
        "type": "CAPABILITY_EXPRESSION",
        "mode": "ALL",
        "capabilityContractKeys": [
            "dwaion.work.ask.execute",
            expected_capability,
        ],
    }
    previous_copy = copy.deepcopy(previous)
    current_copy = copy.deepcopy(current)
    previous_profiles = unique(
        previous_copy["accessProfiles"], "profileKey", f"{route_key} previous profiles"
    )
    current_profiles = unique(
        current_copy["accessProfiles"], "profileKey", f"{route_key} current profiles"
    )
    if previous_profiles.keys() != current_profiles.keys():
        return False
    if any(
        profile["requiredAccess"] != expected_access
        for profile in current_profiles.values()
    ):
        return False
    for profile_key in current_profiles:
        current_profiles[profile_key]["requiredAccess"] = copy.deepcopy(
            previous_profiles[profile_key]["requiredAccess"]
        )
    return previous_copy == current_copy


def _is_v39_compensation_navigation_policy_upgrade(
    policy_key: str,
    previous: dict[str, Any],
    current: dict[str, Any],
) -> bool:
    if policy_key not in V39_COMPENSATION_NAVIGATION_POLICY_KEYS:
        return False
    previous_copy = copy.deepcopy(previous)
    current_copy = copy.deepcopy(current)
    branches = [
        branch
        for branch in current_copy.get("modeBranches") or []
        if branch.get("activeAccessMode") == "NORMAL"
        and branch.get("resultGrantKind") == "CAPABILITY"
        and branch.get("capabilityMode") == "ANY"
    ]
    if len(branches) != 1:
        return False
    capability_keys = branches[0].get("capabilityContractKeys")
    if (
        not isinstance(capability_keys, list)
        or capability_keys.count(V39_COMPENSATION_VIEW_CAPABILITY) != 1
        or capability_keys[-1] != V39_COMPENSATION_VIEW_CAPABILITY
    ):
        return False
    capability_keys.pop()
    return previous_copy == current_copy


def _is_v33_hris_query_discriminator_upgrade(
    route_key: str,
    previous: dict[str, Any],
    current: dict[str, Any],
) -> bool:
    expected = V33_HRIS_QUERY_DISCRIMINATORS.get(route_key)
    if expected is None:
        return False
    binding_key, constraints = expected
    candidate = copy.deepcopy(current)
    prior = copy.deepcopy(previous)
    for field in ("gatewayApiBindings", "servicePepBindings"):
        prior_bindings = unique(prior[field], "bindingKey", f"{route_key} prior bindings")
        current_bindings = unique(
            candidate[field], "bindingKey", f"{route_key} current bindings"
        )
        if binding_key not in prior_bindings or binding_key not in current_bindings:
            return False
        binding = current_bindings[binding_key]
        if binding.get("queryParameterConstraints") != constraints:
            return False
        if "queryParameterConstraints" in prior_bindings[binding_key]:
            binding["queryParameterConstraints"] = copy.deepcopy(
                prior_bindings[binding_key]["queryParameterConstraints"]
            )
        else:
            binding.pop("queryParameterConstraints", None)
    return candidate == prior


def _is_v35_performance_authority_upgrade(
    route_key: str,
    previous: dict[str, Any],
    current: dict[str, Any],
) -> bool:
    if route_key != "route.hcm.operations.performance-cycle-population-preview.action":
        return False
    prior = copy.deepcopy(previous)
    candidate = copy.deepcopy(current)
    prior_profiles = unique(
        prior["accessProfiles"], "profileKey", f"{route_key} previous profiles"
    )
    candidate_profiles = unique(
        candidate["accessProfiles"], "profileKey", f"{route_key} current profiles"
    )
    if prior_profiles.keys() != candidate_profiles.keys() \
            or "full-management" not in candidate_profiles:
        return False
    if candidate_profiles["full-management"].get("requiredAccess") != {
        "type": "CAPABILITY",
        "capabilityContractKey": "hcm.operations.performance.update",
    }:
        return False
    candidate_profiles["full-management"]["requiredAccess"] = copy.deepcopy(
        prior_profiles["full-management"]["requiredAccess"]
    )
    return candidate == prior


def _is_v36_performance_freeze_authority_upgrade(
    route_key: str,
    previous: dict[str, Any],
    current: dict[str, Any],
) -> bool:
    if route_key != "route.hcm.operations.performance-population-freeze.action":
        return False
    prior = copy.deepcopy(previous)
    candidate = copy.deepcopy(current)
    prior_profiles = unique(
        prior["accessProfiles"], "profileKey", f"{route_key} previous profiles"
    )
    candidate_profiles = unique(
        candidate["accessProfiles"], "profileKey", f"{route_key} current profiles"
    )
    if prior_profiles.keys() != candidate_profiles.keys() \
            or "full-management" not in candidate_profiles:
        return False
    if candidate_profiles["full-management"].get("requiredAccess") != {
        "type": "CAPABILITY",
        "capabilityContractKey": "hcm.operations.performance.freeze",
    }:
        return False
    expected_step_up = [{
        "audience": "dwp-people-server",
        "bindingKey": route_key + ".binding.01",
        "expectedObjectVersionName": "X-DWP-Expected-Object-Version",
        "expectedObjectVersionSource": "COMMAND_HEADER",
        "ownerServiceKey": "people",
        "targetIdPathParameter": "cycleId",
        "targetType": "PERFORMANCE_CYCLE",
    }]
    if candidate.get("stepUpCommandBindings") != expected_step_up:
        return False
    candidate_profiles["full-management"]["requiredAccess"] = copy.deepcopy(
        prior_profiles["full-management"]["requiredAccess"]
    )
    if "stepUpCommandBindings" in prior:
        candidate["stepUpCommandBindings"] = copy.deepcopy(
            prior["stepUpCommandBindings"]
        )
    else:
        candidate.pop("stepUpCommandBindings", None)
    return candidate == prior


def _is_v37_time_void_profile_removal(
    route_key: str,
    previous: dict[str, Any],
    current: dict[str, Any],
) -> bool:
    if route_key != V37_TIME_VOID_ROUTE:
        return False
    prior_profiles = unique(
        previous.get("accessProfiles", []),
        "profileKey",
        f"{route_key} previous profiles",
    )
    candidate_profiles = unique(
        current.get("accessProfiles", []),
        "profileKey",
        f"{route_key} current profiles",
    )
    if (
        set(prior_profiles) != {"full-management", V37_TIME_VOID_REMOVED_PROFILE}
        or set(candidate_profiles) != {"full-management"}
        or candidate_profiles["full-management"] != prior_profiles["full-management"]
    ):
        return False
    normalized = copy.deepcopy(current)
    normalized["accessProfiles"] = copy.deepcopy(previous["accessProfiles"])
    return normalized == previous


def _validate_release_snapshot(snapshot: dict[str, Any]) -> None:
    version = snapshot["version"]
    expected = EXPECTED_RELEASE_COUNTS[version]
    actual = {section: len(snapshot[section]) for section in SECTION_KEYS}
    for kind in ROUTE_KINDS:
        actual[kind] = sum(route["routeKind"] == kind for route in snapshot["routes"])
    require(actual == expected, f"v{version}: release count drift expected={expected} actual={actual}")
    if version == 30:
        _validate_v30_dwaion_authority_closure(snapshot)
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
    if version >= 46:
        expected_endpoints["hris-navigation-policy-decisions.evaluate"] = {
            "endpointKey": "hris-navigation-policy-decisions.evaluate",
            "method": "POST",
            "publicPath": None,
            "serviceKey": "auth",
            "servicePath": "/internal/auth/v1/hris-navigation-policy-decisions/evaluate",
            "requiresAuthentication": False,
            "requiresCsrf": False,
            "expectedDecisionRevisionHeader": None,
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


def _validate_v30_dwaion_authority_closure(snapshot: dict[str, Any]) -> None:
    capabilities = {
        item["contractKey"]: item for item in snapshot["capabilities"]
    }
    require(
        capabilities.get("dwaion.work.research.read", {}).get(
            "resolvedCapabilityCode"
        ) == "APP.DWAION_RESEARCH:VIEW"
        and capabilities.get("dwaion.work.research.manage", {}).get(
            "resolvedCapabilityCode"
        ) == "APP.DWAION_RESEARCH:MANAGE",
        "v30: Deep Research capabilities must resolve to grantable VIEW/MANAGE authorities",
    )
    require(
        capabilities.get("dwaion.work.routines.approve", {}).get(
            "resolvedCapabilityCode"
        ) == "APP.DWAION_ROUTINES:APPROVE"
        and capabilities["dwaion.work.routines.approve"].get("scopeResolver")
        == "APP_RESOURCE_SET:RS_DWAION",
        "v30: routine checker authority must use the dedicated tenant resource set",
    )
    routes = {item["routeContractKey"]: item for item in snapshot["routes"]}
    research = {
        key: route
        for key, route in routes.items()
        if key.startswith("route.dwaion.work.research-")
    }
    require(len(research) == 14, "v30: Deep Research route closure is incomplete")
    routine_capabilities = {
        "route.dwaion.work.routine-advanced.data": "dwaion.work.routines.read",
        "route.dwaion.work.routine-advanced.action": "dwaion.work.routines.manage",
        "route.dwaion.work.routine-advanced-approval.action": "dwaion.work.routines.approve",
        "route.dwaion.work.routine-advanced-pending-approvals.data": "dwaion.work.routines.approve",
    }
    expected_routes = {
        **{
            key: (
                "dwaion.work.research.read"
                if route["routeKind"] == "DATA"
                else "dwaion.work.research.manage"
            )
            for key, route in research.items()
        },
        **routine_capabilities,
    }
    for key, capability in expected_routes.items():
        expected_access = {
            "type": "CAPABILITY_EXPRESSION",
            "mode": "ALL",
            "capabilityContractKeys": [
                "dwaion.work.ask.execute",
                capability,
            ],
        }
        require(
            all(
                profile.get("requiredAccess") == expected_access
                for profile in routes[key]["accessProfiles"]
            ),
            f"v30: {key} must require APP.ASK plus its exact domain authority",
        )
        if key in {
            "route.dwaion.work.routine-advanced-approval.action",
            "route.dwaion.work.routine-advanced-pending-approvals.data",
        }:
            require(
                all(
                    profile.get("targetBindingKinds") == ["CONFIG_SCOPE"]
                    and profile.get("predicatePolicyKeys")
                    == ["predicate.dwaion-management-scope.v1"]
                    for profile in routes[key]["accessProfiles"]
                ),
                f"v30: {key} must be tenant resource-set scoped",
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
    seen_tim007_routes: set[str] = set()
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
                tim007 = next(
                    (
                        value for value in V50_TIM007_ROUTE_RESPONSES
                        if value["routeContractKey"] == route["routeContractKey"]
                        and "openApiSchemaSha256" in value
                    ),
                    None,
                )
                if snapshot["version"] >= 50 and tim007 is not None:
                    key = route["routeContractKey"]
                    require(profile_key == tim007["profileKey"] and projection == {
                        "apiBindingKey": tim007["apiBindingKey"],
                        "projectionPolicyKey": tim007["projectionPolicyKey"],
                        "responseSchemaKey": tim007["responseSchemaKey"],
                        "schemaVersion": 1,
                        "openApiSchemaSha256": tim007["openApiSchemaSha256"],
                        "additionalProperties": False,
                    }, f"{key}: invalid TIM-007 v50 projection metadata")
                    seen_tim007_routes.add(key)
                elif snapshot["version"] >= 11 and recovery11 is not None:
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
    expected_tim007 = {
        value["routeContractKey"] for value in V50_TIM007_ROUTE_RESPONSES
        if "openApiSchemaSha256" in value
    } if snapshot["version"] >= 50 else set()
    require(
        seen_tim007_routes == expected_tim007,
        "TIM-007 v50 projection schema coverage drift",
    )


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
        expected_responsibility = REQUIRED_RESPONSIBILITY_BY_CAPABILITY.get(
            key, "APP_CONFIG_ADMIN"
        )
        require(
            isinstance(required_responsibility, str)
            and required_responsibility == expected_responsibility,
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
    binding_mode = route.get("bindingMode", "NETWORK")
    require(
        binding_mode in {"NETWORK", "AUTHORIZATION_ONLY"},
        f"{key}: invalid bindingMode",
    )
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
    authorization_only = binding_mode == "AUTHORIZATION_ONLY"
    if authorization_only:
        purpose_code = route.get("purposeCode")
        require(
            kind == "PAGE"
            and subject.get("type") == "PRODUCT"
            and route.get("sideEffectFree") is None
            and not gateway
            and not service
            and route.get("authorizationEquivalenceKey") is None
            and route.get("stepUpCommandBindings") is None,
            f"{key}: invalid authorization-only PAGE envelope",
        )
        require(
            isinstance(purpose_code, str)
            and purpose_code in {"HRIS_CONFIGURATION", "TEAM_MANAGEMENT"},
            f"{key}: authorization-only PAGE purpose is required",
        )
    else:
        require(
            route.get("purposeCode") is None,
            f"{key}: network route purposeCode forbidden",
        )
        require(
            gateway and gateway.keys() == service.keys(),
            f"{key}: public/service binding mismatch",
        )
    for binding_key, public_binding in gateway.items():
        service_binding = service[binding_key]
        require(public_binding["method"] == service_binding["method"], f"{binding_key}: method mismatch")
        require(public_binding.get("pathParameterConstraints", {}) == service_binding.get("pathParameterConstraints", {}), f"{binding_key}: constraint mismatch")
        require(public_binding.get("queryParameterConstraints", {}) == service_binding.get("queryParameterConstraints", {}), f"{binding_key}: query constraint mismatch")
        _validate_binding_constraints(public_binding, binding_key)
        _validate_binding_constraints(service_binding, binding_key)
        service_key = service_binding.get("serviceKey")
        prefixes = SERVICE_PATH_PREFIXES.get(service_key, ())
        require(any(service_binding["path"].startswith(prefix) for prefix in prefixes),
                f"{binding_key}: service path grammar mismatch")
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
        if authorization_only:
            require(
                not projections
                and profile.get("projectionPolicyKey") is None
                and profile.get("responseSchemaKey") is None,
                f"{key}/{profile_key}: authorization-only response projection forbidden",
            )
            continue
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
        _required_access_has_activated_capability(
            profile["requiredAccess"], capabilities, f"{key}/{profile['profileKey']}"
        )
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
    elevated_read_exception = _is_v42_hcm_audit_elevated_read(
        route, gateway, service, capabilities
    )
    generic_session_step_up = _is_generic_session_step_up_exception(
        route, gateway, service, bundle_version
    )
    if authorization_only:
        require(
            not elevated_step_up,
            f"{key}: authorization-only PAGE cannot carry activated high-risk authority",
        )
    elif elevated_step_up and not original_authority_receipt \
            and not non_publishing_review_rejection \
            and not elevated_read_exception \
            and not generic_session_step_up:
        _validate_step_up_command_binding(route, service)
    else:
        require(route.get("stepUpCommandBindings") is None,
                f"{key}: stepUpCommandBindings forbidden")


def _is_generic_session_step_up_exception(
    route: dict[str, Any],
    gateway_bindings: dict[str, dict[str, Any]],
    service_bindings: dict[str, dict[str, Any]],
    bundle_version: int,
) -> bool:
    """Allow only the sealed, version-gated generic session step-up topologies."""
    key = route["routeContractKey"]
    if key not in GENERIC_SESSION_STEP_UP_ROUTE_KEYS:
        return False
    if key in V49_GENERIC_SESSION_STEP_UP_ROUTE_KEYS and bundle_version < 49:
        return False
    if key in V54_GENERIC_SESSION_STEP_UP_ROUTE_KEYS and bundle_version < 54:
        return False
    if key in V56_GENERIC_SESSION_STEP_UP_ROUTE_KEYS and bundle_version < 56:
        return False
    if key in V58_GENERIC_SESSION_STEP_UP_ROUTE_KEYS and bundle_version < 58:
        return False
    require(
        len(gateway_bindings) == len(service_bindings) == 1
        and route.get("stepUpCommandBindings") is None,
        f"{key}: invalid v49 no-fresh-step-up envelope",
    )
    if key in GRANDFATHERED_GENERIC_SESSION_STEP_UP_ROUTE_KEYS:
        return _is_exact_grandfathered_generic_session_route(
            route, gateway_bindings, service_bindings
        )
    profile = route["accessProfiles"][0] if len(route["accessProfiles"]) == 1 else {}
    if key in V58_GENERIC_SESSION_STEP_UP_ROUTE_KEYS:
        binding = next(iter(gateway_bindings.values()))
        owner_binding = next(iter(service_bindings.values()))
        page = key.endswith(".page")
        binding_key = f"{key}.binding.01"
        projections = profile.get("responseProjectionBindings", [])
        require(
            route["routeKind"] == ("PAGE" if page else "DATA")
            and route.get("sideEffectFree") is (None if page else True)
            and route.get("navigationContextId") == "hcm.operations"
            and route.get("subject") == {
                "type": "PRODUCT", "productKey": "hcm", "surfaceKey": "hcm.operations",
            }
            and route.get("authorizationEquivalenceKey")
                == "wire-authority.hcm.operations.pay-retirement.v1"
            and route.get("uiRouteId")
                == ("hcm.operations.pay-retirement" if page else None)
            and route.get("uiRoutePattern")
                == ("/hr/operations/payroll/retirement" if page else None)
            and binding.get("bindingKey") == binding_key
            and binding.get("method") == "GET"
            and binding.get("path") == "/api/payroll/v1/workbench/pay-retirement"
            and owner_binding.get("bindingKey") == binding_key
            and owner_binding.get("method") == "GET"
            and owner_binding.get("serviceKey") == "payroll"
            and owner_binding.get("path") == "/v1/workbench/pay-retirement"
            and profile.get("profileKey") == "payroll-support-viewer"
            and profile.get("precedence") == 300
            and profile.get("activeAccessModes") == ["NORMAL", "ELEVATED"]
            and profile.get("requiredAccess") == {
                "type": "CAPABILITY",
                "capabilityContractKey": "hcm.operations.payroll-support.view",
            }
            and profile.get("targetBindingKinds") == ["TARGET_POPULATION"]
            and profile.get("predicatePolicyKeys")
                == ["predicate.hcm-payroll-population-authority.v1"]
            and profile.get("readOnly") is True
            and projections == [{
                "apiBindingKey": binding_key,
                "projectionPolicyKey": "pay004.iaq-049.fields.v1",
                "responseSchemaKey": "Pay004RetirementProjectionV1",
            }],
            f"{key}: invalid v58 retirement generic-session envelope",
        )
    elif key in V56_GENERIC_SESSION_STEP_UP_ROUTE_KEYS:
        binding = next(iter(gateway_bindings.values()))
        owner_binding = next(iter(service_bindings.values()))
        profiles = route.get("accessProfiles", [])
        expected_path = (
            "/api/platform/v1/admin/hris/automations"
            if key.endswith("automation-catalog.data")
            else "/api/platform/v1/admin/hris/automation-runs/{runId}"
        )
        expected_owner_path = expected_path.removeprefix("/api/platform")
        require(
            route["routeKind"] == "DATA"
            and route.get("sideEffectFree") is True
            and route.get("navigationContextId") == "hcm.management"
            and route.get("subject") == {
                "type": "PRODUCT", "productKey": "hcm", "surfaceKey": "hcm.management",
            }
            and binding["method"] == "GET"
            and binding["path"] == expected_path
            and owner_binding["method"] == "GET"
            and owner_binding["serviceKey"] == "platform"
            and owner_binding["path"] == expected_owner_path,
            f"{key}: invalid v56 automation generic-session envelope",
        )
        expected_profiles = (
            (
                ("automation-author", 300, "hcm.configuration.policy.update"),
                ("automation-publisher", 200, "hcm.configuration.policy.publish"),
                ("automation-executor", 100, "hcm.integration.execute"),
            )
            if key.endswith("automation-catalog.data")
            else (("automation-executor", 300, "hcm.integration.execute"),)
        )
        expected_targets = (
            ["CONFIG_SCOPE"]
            if key.endswith("automation-catalog.data")
            else ["CONFIG_SCOPE", "OBJECT"]
        )
        expected_predicates = (
            ["predicate.hcm-settings-operations-authority.v1"]
            if key.endswith("automation-catalog.data")
            else [
                "predicate.hcm-settings-operations-authority.v1",
                "predicate.hcm-reference-configuration-authority.v1",
            ]
        )
        require(
            len(profiles) == len(expected_profiles)
            and all(
                profile.get("profileKey") == profile_key
                and profile.get("precedence") == precedence
                and profile.get("activeAccessModes") == ["NORMAL", "ELEVATED"]
                and profile.get("requiredAccess") == {
                    "type": "CAPABILITY", "capabilityContractKey": capability,
                }
                and profile.get("targetBindingKinds") == expected_targets
                and profile.get("predicatePolicyKeys") == expected_predicates
                and profile.get("readOnly") is True
                for profile, (profile_key, precedence, capability)
                in zip(profiles, expected_profiles)
            ),
            f"{key}: invalid v56 automation query profile separation",
        )
    elif key in V54_GENERIC_SESSION_STEP_UP_ROUTE_KEYS:
        binding = next(iter(gateway_bindings.values()))
        owner_binding = next(iter(service_bindings.values()))
        suffix = "page" if key.endswith(".page") else "data"
        require(
            route["routeKind"] == ("PAGE" if suffix == "page" else "DATA")
            and route.get("sideEffectFree") is (None if suffix == "page" else True)
            and binding["method"] == "GET"
            and binding["path"] == "/api/payroll/v1/workbench/pay-tax-social-insurance"
            and owner_binding["method"] == "GET"
            and owner_binding["serviceKey"] == "payroll"
            and owner_binding["path"] == "/v1/workbench/pay-tax-social-insurance"
            and profile.get("profileKey") == "payroll-support-viewer"
            and profile.get("activeAccessModes") == ["NORMAL", "ELEVATED"]
            and profile.get("requiredAccess") == {
                "type": "CAPABILITY",
                "capabilityContractKey": "hcm.operations.payroll-support.view",
            }
            and profile.get("targetBindingKinds") == ["TARGET_POPULATION"]
            and profile.get("predicatePolicyKeys")
                == ["predicate.hcm-payroll-population-authority.v1"]
            and profile.get("readOnly") is True,
            f"{key}: invalid v54 statutory-result generic-session exception",
        )
    elif key == "route.hcm.operations.payroll-run.data":
        binding = next(iter(gateway_bindings.values()))
        owner_binding = next(iter(service_bindings.values()))
        require(
            route["routeKind"] == "DATA"
            and route.get("sideEffectFree") is True
            and binding["method"] == "GET"
            and binding["path"] == "/api/payroll/v1/runs/{runId}"
            and owner_binding["method"] == "GET"
            and owner_binding["serviceKey"] == "payroll"
            and owner_binding["path"] == "/v1/runs/{runId}"
            and profile.get("profileKey") == "payroll-run-viewer"
            and profile.get("activeAccessModes") == ["NORMAL", "ELEVATED"]
            and profile.get("requiredAccess") == {
                "type": "CAPABILITY_EXPRESSION",
                "mode": "ANY",
                "capabilityContractKeys": [
                    "hcm.operations.payroll-run.execute",
                    "hcm.operations.payroll-support.view",
                ],
            }
            and profile.get("targetBindingKinds") == ["OBJECT", "TARGET_POPULATION"]
            and profile.get("predicatePolicyKeys")
                == ["predicate.hcm-payroll-run-authority.v1"]
            and profile.get("readOnly") is True,
            f"{key}: invalid v49 payroll run generic-session exception",
        )
    elif key.startswith("route.hcm.operations.payroll-"):
        require(
            route["routeKind"] in {"PAGE", "DATA"}
            and next(iter(gateway_bindings.values()))["method"] == "GET"
            and profile.get("profileKey") == "payroll-support-viewer"
            and profile.get("activeAccessModes") == ["ELEVATED"]
            and profile.get("readOnly") is True,
            f"{key}: invalid v49 elevated payroll read exception",
        )
    else:
        expected = {
            "route.hcm.operations.time-close-validate.action":
                ("ACTION", "POST", False),
            "route.hcm.operations.time-payroll-handoff-create.action":
                ("ACTION", "POST", False),
            "route.hcm.operations.time-payroll-handoff.data":
                ("DATA", "GET", True),
        }[key]
        require(
            (route["routeKind"], next(iter(gateway_bindings.values()))["method"],
             profile.get("readOnly")) == expected
            and profile.get("profileKey") == "time-close-operator"
            and profile.get("activeAccessModes") == ["NORMAL", "ELEVATED"]
            and profile.get("predicatePolicyKeys")
                == ["predicate.hcm-time-period-authority.v1"],
            f"{key}: invalid v49 Time continuation exception",
        )
    return True


def _is_exact_grandfathered_generic_session_route(
    route: dict[str, Any],
    gateway_bindings: dict[str, dict[str, Any]],
    service_bindings: dict[str, dict[str, Any]],
) -> bool:
    key = route["routeContractKey"]
    exact = {
        "route.hcm.configuration.configuration-version.data": (
            "DATA", "GET",
            "/api/platform/v1/admin/hris/configurations/{configId}/versions/{version}",
            "platform", "/v1/admin/hris/configurations/{configId}/versions/{version}",
            "hcm.management", 1,
        ),
        "route.hcm.configuration.configurations.data": (
            "DATA", "GET", "/api/platform/v1/admin/hris/configurations",
            "platform", "/v1/admin/hris/configurations", "hcm.management", 1,
        ),
        "route.hcm.operations.time-interpretation-run.action": (
            "ACTION", "POST", "/api/time/v1/interpretation-runs",
            "time", "/v1/interpretation-runs", "hcm.operations", 2,
        ),
        "route.hcm.operations.time-interpretation-trace.data": (
            "DATA", "GET", "/api/time/v1/interpretation-runs/{runId}/trace",
            "time", "/v1/interpretation-runs/{runId}/trace", "hcm.operations", 2,
        ),
        "route.hcm.time.timecard-correction.action": (
            "ACTION", "POST", "/api/time/v1/timecards/{timecardId}/corrections",
            "time", "/v1/timecards/{timecardId}/corrections", "hcm.operations", 2,
        ),
        "route.hcm.time.timecard.data": (
            "DATA", "GET", "/api/time/v1/timecards/{timecardId}",
            "time", "/v1/timecards/{timecardId}", "hcm.operations", 3,
        ),
    }[key]
    kind, method, gateway_path, service_key, service_path, navigation, profile_count = exact
    gateway = next(iter(gateway_bindings.values()))
    service = next(iter(service_bindings.values()))
    profiles = route.get("accessProfiles", [])
    binding_key = f"{key}.binding.01"
    require(
        route.get("routeKind") == kind
        and route.get("sideEffectFree") == (True if kind == "DATA" else None)
        and route.get("navigationContextId") == navigation
        and route.get("subject") == {
            "type": "PRODUCT", "productKey": "hcm", "surfaceKey": navigation,
        }
        and gateway.get("bindingKey") == binding_key
        and gateway.get("method") == method
        and gateway.get("path") == gateway_path
        and service.get("bindingKey") == binding_key
        and service.get("serviceKey") == service_key
        and service.get("method") == method
        and service.get("path") == service_path
        and len(profiles) == profile_count,
        f"{key}: invalid grandfathered generic-session envelope",
    )
    management = profiles[0]
    expected_management = (
        "configuration-manager", 300,
        ["predicate.hcm-reference-configuration-authority.v1"],
        ["CONFIG_SCOPE", "OBJECT"] if key.endswith("configuration-version.data")
        else ["CONFIG_SCOPE"],
        ["hcm.configuration.policy.update", "hcm.configuration.policy.publish"],
    ) if key.startswith("route.hcm.configuration.") else (
        "time-operator" if key.startswith("route.hcm.time.") else "full-management",
        400 if key == "route.hcm.time.timecard.data" else 300,
        ["predicate.hcm-timecard-authority.v1"]
        if key.startswith("route.hcm.time.")
        else ["predicate.hcm-time-capture-authority.v1"],
        ["OBJECT", "TARGET_POPULATION"],
        ["hcm.operations.time.update"],
    )
    profile_key, precedence, predicates, targets, capabilities = expected_management
    require(
        management.get("profileKey") == profile_key
        and management.get("precedence") == precedence
        and management.get("activeAccessModes") == ["NORMAL", "ELEVATED"]
        and management.get("predicatePolicyKeys") == predicates
        and management.get("targetBindingKinds") == targets
        and management.get("readOnly") == (kind == "DATA")
        and management.get("requiredAccess") == {
            "type": "CAPABILITY_EXPRESSION",
            "mode": "ANY",
            "capabilityContractKeys": capabilities,
        },
        f"{key}: invalid grandfathered activated profile",
    )
    if kind == "DATA":
        response_schema = "TimecardV1" if key == "route.hcm.time.timecard.data" \
            else f"{key}.response.v1"
        for profile in profiles:
            projection = profile.get("responseProjectionBindings", [])
            require(
                len(projection) == 1
                and projection[0].get("apiBindingKey") == binding_key
                and projection[0].get("projectionPolicyKey")
                    == f"{key}.{profile['profileKey']}.projection.v1"
                and projection[0].get("responseSchemaKey") == response_schema,
                f"{key}/{profile.get('profileKey')}: invalid grandfathered projection",
            )
    else:
        require(
            all(not profile.get("responseProjectionBindings") for profile in profiles),
            f"{key}: grandfathered command projections are forbidden",
        )
    alternative_profiles = {
        "route.hcm.operations.time-interpretation-run.action": [
            ("self-service", 200, False, ["SELF", "OBJECT"],
             "predicate.hcm-time-capture-authority.v1", "hcm.personal.time.submit"),
        ],
        "route.hcm.operations.time-interpretation-trace.data": [
            ("self-service", 200, True, ["SELF", "OBJECT"],
             "predicate.hcm-time-capture-authority.v1", "hcm.personal.time.view"),
        ],
        "route.hcm.time.timecard-correction.action": [
            ("self-submitter", 200, False, ["SELF", "OBJECT"],
             "predicate.hcm-timecard-authority.v1", "hcm.personal.time.submit"),
        ],
        "route.hcm.time.timecard.data": [
            ("manager-approver", 300, True, ["OBJECT", "TARGET_POPULATION"],
             "predicate.hcm-timecard-authority.v1", "hcm.team.time.approve"),
            ("self-reader", 200, True, ["SELF", "OBJECT"],
             "predicate.hcm-timecard-authority.v1", "hcm.personal.time.view"),
        ],
    }.get(key, [])
    require(
        len(profiles) - 1 == len(alternative_profiles),
        f"{key}: invalid grandfathered alternative profile count",
    )
    for profile, expected in zip(profiles[1:], alternative_profiles):
        alt_key, alt_precedence, alt_read_only, alt_targets, alt_predicate, alt_capability = expected
        require(
            profile.get("profileKey") == alt_key
            and profile.get("precedence") == alt_precedence
            and profile.get("activeAccessModes") == ["NORMAL", "ELEVATED"]
            and profile.get("readOnly") == alt_read_only
            and profile.get("targetBindingKinds") == alt_targets
            and profile.get("predicatePolicyKeys") == [alt_predicate]
            and profile.get("requiredAccess") == {
                "type": "CAPABILITY", "capabilityContractKey": alt_capability,
            },
            f"{key}/{alt_key}: invalid grandfathered alternative profile",
        )
    return True


def _required_access_has_activated_capability(
    access: dict[str, Any],
    capabilities: dict[str, dict[str, Any]],
    label: str,
) -> bool:
    access_type = access.get("type")
    if access_type == "POLICY":
        return False
    if access_type == "CAPABILITY":
        keys = [access.get("capabilityContractKey")]
    else:
        require(
            access_type == "CAPABILITY_EXPRESSION"
            and access.get("mode") in {"ANY", "ALL"}
            and isinstance(access.get("capabilityContractKeys"), list)
            and access["capabilityContractKeys"],
            f"{label}: invalid capability traversal",
        )
        keys = access["capabilityContractKeys"]
    resolved = []
    for capability_key in keys:
        require(
            isinstance(capability_key, str) and capability_key in capabilities,
            f"{label}: activated capability reference is missing",
        )
        resolved.append(capabilities[capability_key])
    return any(
        capability.get("lifecycleState") == "ACTIVE"
        and capability.get("riskTier") in {"HIGH", "CRITICAL"}
        and isinstance(capability.get("activationPolicy"), str)
        and capability["activationPolicy"].startswith("STEPUP-")
        for capability in resolved
    )


def _is_v42_hcm_audit_elevated_read(
    route: dict[str, Any],
    gateway_bindings: dict[str, dict[str, Any]],
    service_bindings: dict[str, dict[str, Any]],
    capabilities: dict[str, dict[str, Any]],
) -> bool:
    key = route["routeContractKey"]
    expected = V42_HCM_AUDIT_ELEVATED_READ_PROJECTIONS.get(key)
    if expected is None:
        return False
    route_kind, projection_key, response_schema = expected
    profiles = route.get("accessProfiles", [])
    bindings = [*gateway_bindings.values(), *service_bindings.values()]
    profile = profiles[0] if len(profiles) == 1 else {}
    capability = capabilities.get("hcm.audit.evidence.view", {})
    projections = profile.get("responseProjectionBindings", [])
    expected_query = {"product": {"kind": "FIXED", "value": "HCM"}}
    require(
        route.get("routeKind") == route_kind
        and route.get("sideEffectFree") is True
        and all(binding.get("method") == "GET" for binding in bindings)
        and all(binding.get("queryParameterConstraints") == expected_query
                for binding in bindings)
        and profile.get("profileKey") == "hcm-audit-evidence-reader"
        and profile.get("activeAccessModes") == ["ELEVATED"]
        and profile.get("requiredAccess") == {
            "type": "CAPABILITY",
            "capabilityContractKey": "hcm.audit.evidence.view",
        }
        and profile.get("targetBindingKinds") == ["CONFIG_SCOPE"]
        and profile.get("predicatePolicyKeys") == [
            "predicate.hcm-audit-evidence-authority.v1"
        ]
        and profile.get("readOnly") is True
        and len(projections) == 1
        and projections[0].get("projectionPolicyKey") == projection_key
        and projections[0].get("responseSchemaKey") == response_schema
        and capability.get("riskTier") == "HIGH"
        and capability.get("activationPolicy") == "STEPUP-MGMT-HIGH-V1"
        and capability.get("responsibilityRequirement") == "REQUIRED"
        and capability.get("requiredResponsibilityCode") == "HRIS_DUTY_AUDITOR"
        and capability.get("scopeResolver") == "APP_RESOURCE_SET:RS_HCM_CONFIG",
        f"{key}: invalid elevated read-only step-up exception",
    )
    return True


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
    elif kind == "REQUIRED":
        require(allow_absent and constraint == {"kind": "REQUIRED"},
                f"{label}: invalid REQUIRED constraint")
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
        member_keys = {route["routeContractKey"] for route, _ in members}
        canonical_page = next((
            key for key in member_keys
            if key in V47_CANONICAL_PAGE_FIRST_READ_SOURCES
        ), None)
        if canonical_page is not None:
            source_key = V47_CANONICAL_PAGE_FIRST_READ_SOURCES[canonical_page]
            require(
                member_keys == {canonical_page, source_key} and len(members) == 2,
                f"v47 canonical PAGE first-read wire is ambiguous: {wire_key}",
            )
            _validate_v47_canonical_page_first_read(
                routes[canonical_page], routes[source_key]
            )
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


def _validate_v47_canonical_page_first_read(
    page: dict[str, Any], source: dict[str, Any]
) -> None:
    """Seal a PAGE to one existing first-read contract without mutating its v46 source."""
    page_key = page["routeContractKey"]
    require(
        page.get("authorizationEquivalenceKey") is None
        and source.get("authorizationEquivalenceKey") is None
        and page["routeKind"] == "PAGE"
        and page["sideEffectFree"] is None
        and page["navigationContextId"] == source["navigationContextId"]
        and page["subject"] == source["subject"]
        and page["owner"] == source["owner"]
        and page["policyVersion"] == source["policyVersion"]
        and page["lifecycleState"] == source["lifecycleState"] == "ACTIVE",
        f"{page_key}: canonical PAGE envelope drifted from its sealed first read",
    )
    require(
        len(page["gatewayApiBindings"]) == len(source["gatewayApiBindings"]) == 1
        and len(page["servicePepBindings"]) == len(source["servicePepBindings"]) == 1,
        f"{page_key}: exactly one first-read binding is required",
    )
    for binding_kind in ("gatewayApiBindings", "servicePepBindings"):
        candidate = copy.deepcopy(page[binding_kind][0])
        prior = copy.deepcopy(source[binding_kind][0])
        candidate.pop("bindingKey")
        prior.pop("bindingKey")
        require(
            candidate == prior,
            f"{page_key}: {binding_kind} drifted from its sealed first read",
        )
    page_profiles = unique(page["accessProfiles"], "profileKey", f"{page_key} profiles")
    source_profiles = unique(
        source["accessProfiles"], "profileKey",
        f"{source['routeContractKey']} profiles",
    )
    require(
        page_profiles.keys() == source_profiles.keys(),
        f"{page_key}: first-read profile set drifted",
    )
    for profile_key, candidate_profile in page_profiles.items():
        prior_profile = source_profiles[profile_key]
        for field in (
            "precedence", "activeAccessModes", "requiredAccess",
            "targetBindingKinds", "predicatePolicyKeys", "readOnly",
        ):
            require(
                candidate_profile[field] == prior_profile[field],
                f"{page_key}/{profile_key}: {field} drifted from its sealed first read",
            )
        candidate_projection = candidate_profile["responseProjectionBindings"]
        prior_projection = prior_profile["responseProjectionBindings"]
        require(
            len(candidate_projection) == len(prior_projection) == 1
            and candidate_projection[0]["responseSchemaKey"]
            == prior_projection[0]["responseSchemaKey"],
            f"{page_key}/{profile_key}: response projection schema drifted",
        )


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
            # Equivalence groups may expose the same governed read as a PAGE
            # entry point and as a DATA endpoint.  Binding and projection
            # policy keys remain route identities; the response schema is the
            # shared wire-authority semantic that must stay equal.
            "responseSchemaKey": None if projection is None
            else projection["responseSchemaKey"],
        })
    return {
        "subject": route["subject"],
        "navigationContextId": route["navigationContextId"],
        "operationClass": (
            "READ" if public_binding["method"] == "GET"
            and route["routeKind"] in {"PAGE", "DATA"}
            else route["routeKind"]
        ),
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


def build_platform_canary_pep(
    snapshot: dict[str, Any], projection_version: int
) -> dict[str, Any]:
    """Project one immutable Platform Canary PEP from the registry graph."""
    expected = {
        1: {
            "registryVersion": 1,
            "routes": 33,
            "bindings": 36,
            "routeKinds": {"ACTION": 15, "DATA": 0, "PAGE": 18},
            "capabilities": 10,
            "accessPolicies": 3,
            "entitlementExpressions": 2,
            "predicatePolicies": 5,
            "ownedPathRoots": 4,
            "ownedExactPaths": 0,
        },
        2: {
            "registryVersion": 32,
            "routes": 39,
            "bindings": 42,
            "routeKinds": {"ACTION": 16, "DATA": 5, "PAGE": 18},
            "capabilities": 13,
            "accessPolicies": 3,
            "entitlementExpressions": 2,
            "predicatePolicies": 7,
            "ownedPathRoots": 4,
            "ownedExactPaths": 7,
        },
    }.get(projection_version)
    require(expected is not None, "Unsupported Platform Canary PEP projection version")
    require(
        snapshot["version"] == expected["registryVersion"],
        f"Platform Canary PEP v{projection_version} must project registry "
        f"v{expected['registryVersion']}",
    )
    routes = [
        copy.deepcopy(route)
        for route in snapshot["routes"]
        if route["subject"].get("productKey") in PLATFORM_CANARY_PRODUCTS
        or route["routeContractKey"] in PLATFORM_EXACT_CATALOG_ROUTE_KEYS
    ]
    require(
        len(routes) == expected["routes"],
        f"Platform Canary PEP v{projection_version} route count drift",
    )
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

    def finite_binding_paths(binding: dict[str, Any]) -> list[str]:
        path = binding["path"]
        parameters = re.findall(r"\{([A-Za-z][A-Za-z0-9]*)}", path)
        if not parameters:
            return []
        constraints = binding.get("pathParameterConstraints") or {}
        if any(parameter not in constraints for parameter in parameters):
            return []
        paths = [path]
        for parameter in parameters:
            constraint = constraints[parameter]
            kind = constraint.get("kind")
            if kind == "FIXED":
                values = [constraint.get("value")]
            elif kind == "ALLOWLIST":
                values = constraint.get("values") or []
            else:
                return []
            require(
                values and all(isinstance(value, str) and value for value in values),
                "Platform Canary exact path constraint is invalid",
            )
            paths = [
                candidate.replace("{" + parameter + "}", value)
                for candidate in paths
                for value in values
            ]
        return paths

    owned_path_roots: set[str] = set()
    owned_exact_paths: set[str] = set()
    for route in routes:
        for binding in route["servicePepBindings"]:
            exact_paths = (
                finite_binding_paths(binding) if projection_version >= 2 else []
            )
            if exact_paths:
                owned_exact_paths.update(exact_paths)
            else:
                owned_path_roots.add(owned_path_root(binding["path"]))
    owned_path_roots = sorted(owned_path_roots)
    owned_exact_paths = sorted(owned_exact_paths)
    require(
        len(owned_path_roots) == expected["ownedPathRoots"],
        "Platform Canary owned path root count drift",
    )
    require(
        len(owned_exact_paths) == expected["ownedExactPaths"],
        "Platform Canary exact owned path count drift",
    )

    projection = {
        "schemaVersion": 1,
        "projectionKey": f"platform-canary-pep-v{projection_version}",
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
    if projection_version >= 2:
        route_keys = {route["routeContractKey"] for route in routes}
        for section in ("capabilities", "accessPolicies", "predicatePolicies"):
            for descriptor in projection[section]:
                descriptor["routeContractKeys"] = sorted(
                    set(descriptor["routeContractKeys"]) & route_keys
                )
        projection["ownedExactPaths"] = owned_exact_paths
    require(
        projection["bindingPairCount"] == expected["bindings"],
        "Platform Canary PEP binding count drift",
    )
    require(
        projection["routeKindCounts"] == expected["routeKinds"],
        "Platform Canary PEP route kind count drift",
    )
    require(
        len(projection["capabilities"]) == expected["capabilities"],
        "Platform Canary capability closure drift",
    )
    require(
        len(projection["accessPolicies"]) == expected["accessPolicies"],
        "Platform Canary policy closure drift",
    )
    require(
        len(projection["entitlementExpressions"])
        == expected["entitlementExpressions"],
        "Platform Canary expression closure drift",
    )
    require(
        len(projection["predicatePolicies"]) == expected["predicatePolicies"],
        "Platform Canary predicate closure drift",
    )
    projection["projectionChecksum"] = hashlib.sha256(
        stable_json(projection).encode("utf-8")
    ).hexdigest()
    return projection


def verify_platform_canary_pep(
    output: pathlib.Path, projection: dict[str, Any]
) -> None:
    document = json.loads(output.read_text(encoding="utf-8"))
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
        PLATFORM_CANARY_PEP_V1_OUTPUT,
        PLATFORM_CANARY_PEP_OUTPUT,
        APPROVAL_PILOT_PEP_OUTPUT,
        APPROVAL_WORK_PEP_OUTPUT,
        APPROVAL_DOCUMENT_PEP_OUTPUT,
        APPROVAL_EXTENSION_PEP_OUTPUT,
        APPROVAL_RELEASE10_PEP_OUTPUT,
        APPROVAL_RECOVERY11_PEP_OUTPUT,
        APPROVAL_RELEASE12_PEP_OUTPUT,
        APPROVAL_RELEASE13_PEP_OUTPUT,
        APPROVAL_RELEASE14_PEP_OUTPUT,
        APPROVAL_RELEASE15_PEP_OUTPUT,
        APPROVAL_RELEASE19_PEP_OUTPUT,
        APPROVAL_RELEASE31_PEP_OUTPUT,
        APPROVAL_RELEASE32_PEP_OUTPUT,
        PLATFORM_APPROVALS_PEP_OUTPUT,
        LEGACY_PLATFORM_WORKPLACE_PEP_OUTPUT,
        LEGACY_PLATFORM_WORKPLACE_PEP_V16_OUTPUT,
        LEGACY_PLATFORM_WORKPLACE_PEP_V17_OUTPUT,
        LEGACY_PLATFORM_WORKPLACE_PEP_V18_OUTPUT,
        LEGACY_PLATFORM_WORKPLACE_PEP_V20_OUTPUT,
        LEGACY_PLATFORM_WORKPLACE_PEP_V21_OUTPUT,
        LEGACY_PLATFORM_WORKPLACE_PEP_V22_OUTPUT,
        LEGACY_PLATFORM_WORKPLACE_PEP_V23_OUTPUT,
        PLATFORM_WORKPLACE_PEP_OUTPUT,
        PLATFORM_TELEMETRY_DIMENSIONS_OUTPUT,
        LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT,
        LEGACY_HCM_PEOPLE_PEP_V33_OUTPUT,
        LEGACY_HCM_PEOPLE_PEP_V34_OUTPUT,
        LEGACY_HCM_PEOPLE_PEP_V35_OUTPUT,
        LEGACY_HCM_PEOPLE_PEP_V36_OUTPUT,
        LEGACY_HCM_PEOPLE_PEP_V37_OUTPUT,
        LEGACY_HCM_PEOPLE_PEP_V38_OUTPUT,
        LEGACY_HCM_PEOPLE_PEP_V39_OUTPUT,
        HCM_PEOPLE_PEP_OUTPUT,
        LEGACY_HCM_TIME_PEP_V35_OUTPUT,
        LEGACY_HCM_TIME_PEP_V36_OUTPUT,
        LEGACY_HCM_TIME_PEP_V37_OUTPUT,
        LEGACY_HCM_TIME_PEP_V38_OUTPUT,
        HCM_TIME_PEP_OUTPUT,
        LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT,
        LEGACY_HCM_PLATFORM_PEP_V36_OUTPUT,
        LEGACY_HCM_PLATFORM_PEP_V37_OUTPUT,
        LEGACY_HCM_PLATFORM_PEP_V38_OUTPUT,
        LEGACY_HCM_PLATFORM_PEP_V40_OUTPUT,
        LEGACY_HCM_PLATFORM_PEP_V42_OUTPUT,
        LEGACY_HCM_PLATFORM_PEP_V43_OUTPUT,
        LEGACY_HCM_PLATFORM_PEP_V44_OUTPUT,
        HCM_PLATFORM_PEP_OUTPUT,
        HCM_PLATFORM_PEP_V46_OUTPUT,
        HCM_PLATFORM_PEP_V47_OUTPUT,
        LEGACY_HCM_PAYROLL_PEP_V38_OUTPUT,
        HCM_PAYROLL_PEP_OUTPUT,
        HCM_PAYROLL_PEP_V48_OUTPUT,
        HCM_PEOPLE_PEP_V49_OUTPUT,
        HCM_TIME_PEP_V49_OUTPUT,
        HCM_PLATFORM_PEP_V49_OUTPUT,
        HCM_PAYROLL_PEP_V49_OUTPUT,
        HCM_PEOPLE_PEP_V50_OUTPUT,
        HCM_TIME_PEP_V50_OUTPUT,
        HCM_PLATFORM_PEP_V50_OUTPUT,
        HCM_PAYROLL_PEP_V50_OUTPUT,
        HCM_PEOPLE_PEP_V51_OUTPUT,
        HCM_TIME_PEP_V51_OUTPUT,
        HCM_PLATFORM_PEP_V51_OUTPUT,
        HCM_PAYROLL_PEP_V51_OUTPUT,
        HCM_PEOPLE_PEP_V52_OUTPUT,
        HCM_TIME_PEP_V52_OUTPUT,
        HCM_PLATFORM_PEP_V52_OUTPUT,
        HCM_PAYROLL_PEP_V52_OUTPUT,
        HCM_PEOPLE_PEP_V53_OUTPUT,
        HCM_TIME_PEP_V53_OUTPUT,
        HCM_PLATFORM_PEP_V53_OUTPUT,
        HCM_PAYROLL_PEP_V53_OUTPUT,
        HCM_PEOPLE_PEP_V54_OUTPUT,
        HCM_TIME_PEP_V54_OUTPUT,
        HCM_PLATFORM_PEP_V54_OUTPUT,
        HCM_PAYROLL_PEP_V54_OUTPUT,
        *HCM_PEP_V55_OUTPUTS.values(),
        *HCM_PEP_V56_OUTPUTS.values(),
        *HCM_PEP_V57_OUTPUTS.values(),
        *HCM_PEP_V58_OUTPUTS.values(),
    }
    candidates = {
        *CONTRACT_DIRECTORY.glob("product-surfaces-v1.bundle-v*.json"),
        *AUTH_SEED_DIRECTORY.glob("product-surfaces-v1.bundle-v*.generated.json"),
        *PLATFORM_CANARY_PEP_OUTPUT.parent.glob(
            "platform-canary-pep-v*.generated.json"
        ),
        *APPROVAL_PILOT_PEP_OUTPUT.parent.glob("approval-pilot-pep-v*.generated.json"),
        *PLATFORM_APPROVALS_PEP_OUTPUT.parent.glob(
            "platform-approvals-pep-v*.generated.json"
        ),
        *PLATFORM_WORKPLACE_PEP_OUTPUT.parent.glob(
            "platform-workplace-pep-v*.generated.json"
        ),
        *PLATFORM_TELEMETRY_DIMENSIONS_OUTPUT.parent.glob(
            "platform-telemetry-dimensions-v*.generated.json"
        ),
        *HCM_PEOPLE_PEP_OUTPUT.parent.glob("hcm-people-pep-v*.generated.json"),
        *HCM_TIME_PEP_OUTPUT.parent.glob("hcm-time-pep-v*.generated.json"),
        *HCM_PLATFORM_PEP_OUTPUT.parent.glob("hcm-platform-pep-v*.generated.json"),
        *HCM_PAYROLL_PEP_OUTPUT.parent.glob("hcm-payroll-pep-v*.generated.json"),
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
        platform_canary_pep_v1 = build_platform_canary_pep(snapshots[0], 1)
        platform_canary_pep = build_platform_canary_pep(snapshots[31], 2)
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
        approval_release15_pep = build_approvals_pep(
            snapshots[14], "approval", "approval-pilot-pep-v15",
            {
                "routes": 192, "bindings": 246,
                "routeKinds": {"ACTION": 99, "DATA": 73, "PAGE": 20},
                "capabilities": 39, "accessPolicies": 1,
                "entitlementExpressions": 1, "predicatePolicies": 18,
            }, version=15,
        )
        approval_release19_pep = build_approvals_pep(
            snapshots[18], "approval", "approval-pilot-pep-v19",
            {
                "routes": 193, "bindings": 247,
                "routeKinds": {"ACTION": 100, "DATA": 73, "PAGE": 20},
                "capabilities": 39, "accessPolicies": 1,
                "entitlementExpressions": 1, "predicatePolicies": 18,
            }, version=19,
        )
        approval_release31_pep = build_approvals_pep(
            snapshots[30], "approval", "approval-pilot-pep-v31",
            {
                "routes": 216, "bindings": 285,
                "routeKinds": {"ACTION": 112, "DATA": 84, "PAGE": 20},
                "capabilities": 39, "accessPolicies": 1,
                "entitlementExpressions": 1, "predicatePolicies": 18,
            }, version=31,
        )
        approval_release32_pep = build_approvals_pep(
            snapshots[31], "approval", "approval-pilot-pep-v32",
            {
                "routes": 216, "bindings": 285,
                "routeKinds": {"ACTION": 112, "DATA": 84, "PAGE": 20},
                "capabilities": 39, "accessPolicies": 1,
                "entitlementExpressions": 1, "predicatePolicies": 18,
            }, version=32,
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
        platform_workplace_pep_v21 = build_approvals_pep(
            snapshots[20],
            "platform",
            "platform-workplace-pep-v21",
            {
                "routes": 304,
                "bindings": 306,
                "routeKinds": {"ACTION": 172, "DATA": 103, "PAGE": 29},
                "capabilities": 26,
                "accessPolicies": 1,
                "entitlementExpressions": 1,
                "predicatePolicies": 1,
            },
            version=21,
            product_key="workplace",
        )
        platform_workplace_pep_v22 = build_approvals_pep(
            snapshots[21],
            "platform",
            "platform-workplace-pep-v22",
            {
                "routes": 307,
                "bindings": 309,
                "routeKinds": {"ACTION": 174, "DATA": 104, "PAGE": 29},
                "capabilities": 26,
                "accessPolicies": 1,
                "entitlementExpressions": 1,
                "predicatePolicies": 1,
            },
            version=22,
            product_key="workplace",
        )
        platform_workplace_pep_v23 = build_approvals_pep(
            snapshots[22],
            "platform",
            "platform-workplace-pep-v23",
            {
                "routes": 326,
                "bindings": 328,
                "routeKinds": {"ACTION": 182, "DATA": 115, "PAGE": 29},
                "capabilities": 26,
                "accessPolicies": 1,
                "entitlementExpressions": 1,
                "predicatePolicies": 1,
            },
            version=23,
            product_key="workplace",
        )
        platform_workplace_pep = build_approvals_pep(
            snapshots[23],
            "platform",
            "platform-workplace-pep-v24",
            {
                "routes": 326,
                "bindings": 328,
                "routeKinds": {"ACTION": 182, "DATA": 115, "PAGE": 29},
                "capabilities": 26,
                "accessPolicies": 1,
                "entitlementExpressions": 1,
                "predicatePolicies": 1,
            },
            version=24,
            product_key="workplace",
        )
        platform_telemetry_dimensions = build_platform_telemetry_dimensions(
            snapshots[2]
        )
        legacy_hcm_people_pep_v3 = build_approvals_pep(
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
        legacy_hcm_people_pep_v33 = build_approvals_pep(
            snapshots[32],
            "people",
            "hcm-people-pep-v33",
            {
                "routes": 60,
                "bindings": 87,
                "routeKinds": {"ACTION": 26, "DATA": 10, "PAGE": 24},
                "capabilities": 31,
                "accessPolicies": 5,
                "entitlementExpressions": 3,
                "predicatePolicies": 13,
            },
            version=33,
            product_key="hcm",
        )
        legacy_hcm_people_pep_v34 = build_approvals_pep(
            snapshots[33],
            "people",
            "hcm-people-pep-v34",
            {
                "routes": 67,
                "bindings": 94,
                "routeKinds": {"ACTION": 30, "DATA": 13, "PAGE": 24},
                "capabilities": 35,
                "accessPolicies": 5,
                "entitlementExpressions": 3,
                "predicatePolicies": 13,
            },
            version=34,
            product_key="hcm",
        )
        legacy_hcm_people_pep_v35 = build_approvals_pep(
            snapshots[34],
            "people",
            "hcm-people-pep-v35",
            {
                "routes": 73,
                "bindings": 100,
                "routeKinds": {"ACTION": 34, "DATA": 13, "PAGE": 26},
                "capabilities": 42,
                "accessPolicies": 5,
                "entitlementExpressions": 3,
                "predicatePolicies": 14,
            },
            version=35,
            product_key="hcm",
        )
        legacy_hcm_people_pep_v36 = build_approvals_pep(
            snapshots[35],
            "people",
            "hcm-people-pep-v36",
            {
                "routes": 73,
                "bindings": 100,
                "routeKinds": {"ACTION": 34, "DATA": 13, "PAGE": 26},
                "capabilities": 43,
                "accessPolicies": 5,
                "entitlementExpressions": 3,
                "predicatePolicies": 14,
            },
            version=36,
            product_key="hcm",
        )
        legacy_hcm_people_pep_v37 = build_approvals_pep(
            snapshots[36],
            "people",
            "hcm-people-pep-v37",
            {
                "routes": 73,
                "bindings": 100,
                "routeKinds": {"ACTION": 34, "DATA": 13, "PAGE": 26},
                "capabilities": 43,
                "accessPolicies": 5,
                "entitlementExpressions": 3,
                "predicatePolicies": 14,
            },
            version=37,
            product_key="hcm",
        )
        legacy_hcm_people_pep_v38 = build_approvals_pep(
            snapshots[37],
            "people",
            "hcm-people-pep-v38",
            {
                "routes": 81,
                "bindings": 108,
                "routeKinds": {"ACTION": 38, "DATA": 15, "PAGE": 28},
                "capabilities": 46,
                "accessPolicies": 5,
                "entitlementExpressions": 3,
                "predicatePolicies": 15,
            },
            version=38,
            product_key="hcm",
        )
        legacy_hcm_people_pep_v39 = build_approvals_pep(
            snapshots[38],
            "people",
            "hcm-people-pep-v39",
            {
                "routes": 93,
                "bindings": 120,
                "routeKinds": {"ACTION": 46, "DATA": 18, "PAGE": 29},
                "capabilities": 49,
                "accessPolicies": 5,
                "entitlementExpressions": 3,
                "predicatePolicies": 16,
            },
            version=39,
            product_key="hcm",
        )
        hcm_people_pep = build_approvals_pep(
            snapshots[41],
            "people",
            "hcm-people-pep-v42",
            {
                "routes": 97,
                "bindings": 124,
                "routeKinds": {"ACTION": 48, "DATA": 19, "PAGE": 30},
                "capabilities": 51,
                "accessPolicies": 5,
                "entitlementExpressions": 3,
                "predicatePolicies": 17,
            },
            version=42,
            product_key="hcm",
        )
        legacy_hcm_time_pep_v35 = build_approvals_pep(
            snapshots[34],
            "time",
            "hcm-time-pep-v35",
            {
                "routes": 12,
                "bindings": 12,
                "routeKinds": {"ACTION": 8, "DATA": 2, "PAGE": 2},
                "capabilities": 9,
                "accessPolicies": 0,
                "entitlementExpressions": 0,
                "predicatePolicies": 3,
            },
            version=35,
            product_key="hcm",
        )
        legacy_hcm_time_pep_v36 = build_approvals_pep(
            snapshots[35],
            "time",
            "hcm-time-pep-v36",
            {
                "routes": 12,
                "bindings": 12,
                "routeKinds": {"ACTION": 8, "DATA": 2, "PAGE": 2},
                "capabilities": 9,
                "accessPolicies": 0,
                "entitlementExpressions": 0,
                "predicatePolicies": 3,
            },
            version=36,
            product_key="hcm",
        )
        legacy_hcm_time_pep_v37 = build_approvals_pep(
            snapshots[36],
            "time",
            "hcm-time-pep-v37",
            {
                "routes": 12,
                "bindings": 12,
                "routeKinds": {"ACTION": 8, "DATA": 2, "PAGE": 2},
                "capabilities": 9,
                "accessPolicies": 0,
                "entitlementExpressions": 0,
                "predicatePolicies": 3,
            },
            version=37,
            product_key="hcm",
        )
        legacy_hcm_time_pep_v38 = build_approvals_pep(
            snapshots[37],
            "time",
            "hcm-time-pep-v38",
            {
                "routes": 15,
                "bindings": 15,
                "routeKinds": {"ACTION": 9, "DATA": 3, "PAGE": 3},
                "capabilities": 10,
                "accessPolicies": 0,
                "entitlementExpressions": 0,
                "predicatePolicies": 3,
            },
            version=38,
            product_key="hcm",
        )
        hcm_time_pep = build_approvals_pep(
            snapshots[41],
            "time",
            "hcm-time-pep-v42",
            {
                "routes": 20,
                "bindings": 20,
                "routeKinds": {"ACTION": 12, "DATA": 5, "PAGE": 3},
                "capabilities": 13,
                "accessPolicies": 0,
                "entitlementExpressions": 0,
                "predicatePolicies": 6,
            },
            version=42,
            product_key="hcm",
        )
        legacy_hcm_platform_pep_v35 = build_approvals_pep(
            snapshots[34],
            "platform",
            "hcm-platform-pep-v35",
            {
                "routes": 19,
                "bindings": 20,
                "routeKinds": {"ACTION": 10, "DATA": 6, "PAGE": 3},
                "capabilities": 5,
                "accessPolicies": 4,
                "entitlementExpressions": 3,
                "predicatePolicies": 3,
            },
            version=35,
            product_key="hcm",
        )
        legacy_hcm_platform_pep_v36 = build_approvals_pep(
            snapshots[35],
            "platform",
            "hcm-platform-pep-v36",
            {
                "routes": 19,
                "bindings": 20,
                "routeKinds": {"ACTION": 10, "DATA": 6, "PAGE": 3},
                "capabilities": 5,
                "accessPolicies": 4,
                "entitlementExpressions": 3,
                "predicatePolicies": 3,
            },
            version=36,
            product_key="hcm",
        )
        legacy_hcm_platform_pep_v37 = build_approvals_pep(
            snapshots[36],
            "platform",
            "hcm-platform-pep-v37",
            {
                "routes": 19,
                "bindings": 20,
                "routeKinds": {"ACTION": 10, "DATA": 6, "PAGE": 3},
                "capabilities": 5,
                "accessPolicies": 4,
                "entitlementExpressions": 3,
                "predicatePolicies": 3,
            },
            version=37,
            product_key="hcm",
        )
        legacy_hcm_platform_pep_v38 = build_approvals_pep(
            snapshots[37],
            "platform",
            "hcm-platform-pep-v38",
            {
                "routes": 19,
                "bindings": 20,
                "routeKinds": {"ACTION": 10, "DATA": 6, "PAGE": 3},
                "capabilities": 5,
                "accessPolicies": 4,
                "entitlementExpressions": 3,
                "predicatePolicies": 3,
            },
            version=38,
            product_key="hcm",
        )
        legacy_hcm_platform_pep_v40 = build_approvals_pep(
            snapshots[39],
            "platform",
            "hcm-platform-pep-v40",
            {
                "routes": 28,
                "bindings": 29,
                "routeKinds": {"ACTION": 17, "DATA": 7, "PAGE": 4},
                "capabilities": 15,
                "accessPolicies": 4,
                "entitlementExpressions": 3,
                "predicatePolicies": 4,
            },
            version=40,
            product_key="hcm",
        )
        legacy_hcm_platform_pep_v44 = build_approvals_pep(
            snapshots[43],
            "platform",
            "hcm-platform-pep-v44",
            {
                "routes": 36,
                "bindings": 37,
                "routeKinds": {"ACTION": 20, "DATA": 10, "PAGE": 6},
                "capabilities": 20,
                "accessPolicies": 4,
                "entitlementExpressions": 3,
                "predicatePolicies": 9,
            },
            version=44,
            product_key="hcm",
        )
        hcm_platform_pep = build_approvals_pep(
            snapshots[44],
            "platform",
            "hcm-platform-pep-v45",
            {
                "routes": 48,
                "bindings": 49,
                "routeKinds": {"ACTION": 27, "DATA": 15, "PAGE": 6},
                "capabilities": 20,
                "accessPolicies": 4,
                "entitlementExpressions": 3,
                "predicatePolicies": 9,
            },
            version=45,
            product_key="hcm",
        )
        hcm_platform_pep_v46 = build_approvals_pep(
            snapshots[45],
            "platform",
            "hcm-platform-pep-v46",
            {
                "routes": 50,
                "bindings": 51,
                "routeKinds": {"ACTION": 27, "DATA": 17, "PAGE": 6},
                "capabilities": 20,
                "accessPolicies": 4,
                "entitlementExpressions": 3,
                "predicatePolicies": 9,
            },
            version=46,
            product_key="hcm",
        )
        hcm_platform_pep_v47 = build_approvals_pep(
            snapshots[46],
            "platform",
            "hcm-platform-pep-v47",
            {
                "routes": 50,
                "bindings": 51,
                "routeKinds": {"ACTION": 27, "DATA": 17, "PAGE": 6},
                "capabilities": 20,
                "accessPolicies": 4,
                "entitlementExpressions": 3,
                "predicatePolicies": 9,
            },
            version=47,
            product_key="hcm",
        )
        legacy_hcm_payroll_pep_v38 = build_approvals_pep(
            snapshots[37],
            "payroll",
            "hcm-payroll-pep-v38",
            {
                "routes": 16,
                "bindings": 16,
                "routeKinds": {"ACTION": 11, "DATA": 4, "PAGE": 1},
                "capabilities": 10,
                "accessPolicies": 0,
                "entitlementExpressions": 0,
                "predicatePolicies": 3,
            },
            version=38,
            product_key="hcm",
        )
        hcm_payroll_pep = build_approvals_pep(
            snapshots[40],
            "payroll",
            "hcm-payroll-pep-v41",
            {
                "routes": 21,
                "bindings": 21,
                "routeKinds": {"ACTION": 14, "DATA": 6, "PAGE": 1},
                "capabilities": 15,
                "accessPolicies": 0,
                "entitlementExpressions": 0,
                "predicatePolicies": 6,
            },
            version=41,
            product_key="hcm",
        )
        hcm_payroll_pep_v48 = build_approvals_pep(
            snapshots[47],
            "payroll",
            "hcm-payroll-pep-v48",
            {
                "routes": 31,
                "bindings": 31,
                "routeKinds": {"ACTION": 20, "DATA": 10, "PAGE": 1},
                "capabilities": 16,
                "accessPolicies": 0,
                "entitlementExpressions": 0,
                "predicatePolicies": 6,
            },
            version=48,
            product_key="hcm",
        )
        hcm_people_pep_v49 = build_approvals_pep(
            snapshots[48],
            "people",
            "hcm-people-pep-v49",
            {
                "routes": 103,
                "bindings": 130,
                "routeKinds": {"ACTION": 48, "DATA": 20, "PAGE": 35},
                "capabilities": 52,
                "accessPolicies": 5,
                "entitlementExpressions": 3,
                "predicatePolicies": 18,
            },
            version=49,
            product_key="hcm",
        )
        hcm_time_pep_v49 = build_approvals_pep(
            snapshots[48],
            "time",
            "hcm-time-pep-v49",
            {
                "routes": 29,
                "bindings": 29,
                "routeKinds": {"ACTION": 16, "DATA": 6, "PAGE": 7},
                "capabilities": 15,
                "accessPolicies": 0,
                "entitlementExpressions": 0,
                "predicatePolicies": 9,
            },
            version=49,
            product_key="hcm",
        )
        hcm_platform_pep_v49 = build_approvals_pep(
            snapshots[48],
            "platform",
            "hcm-platform-pep-v49",
            {
                "routes": 53,
                "bindings": 54,
                "routeKinds": {"ACTION": 27, "DATA": 19, "PAGE": 7},
                "capabilities": 21,
                "accessPolicies": 4,
                "entitlementExpressions": 3,
                "predicatePolicies": 11,
            },
            version=49,
            product_key="hcm",
        )
        hcm_payroll_pep_v49 = build_approvals_pep(
            snapshots[48],
            "payroll",
            "hcm-payroll-pep-v49",
            {
                "routes": 34,
                "bindings": 34,
                "routeKinds": {"ACTION": 21, "DATA": 11, "PAGE": 2},
                "capabilities": 16,
                "accessPolicies": 0,
                "entitlementExpressions": 0,
                "predicatePolicies": 8,
            },
            version=49,
            product_key="hcm",
        )
        hcm_people_pep_v50 = build_approvals_pep(
            snapshots[49], "people", "hcm-people-pep-v50",
            {
                "routes": 103, "bindings": 130,
                "routeKinds": {"ACTION": 48, "DATA": 20, "PAGE": 35},
                "capabilities": 52, "accessPolicies": 5,
                "entitlementExpressions": 3, "predicatePolicies": 18,
            },
            version=50,
            product_key="hcm",
        )
        hcm_time_pep_v50 = build_approvals_pep(
            snapshots[49], "time", "hcm-time-pep-v50",
            {
                "routes": 29, "bindings": 29,
                "routeKinds": {"ACTION": 16, "DATA": 6, "PAGE": 7},
                "capabilities": 15, "accessPolicies": 0,
                "entitlementExpressions": 0, "predicatePolicies": 9,
            },
            version=50,
            product_key="hcm",
        )
        hcm_platform_pep_v50 = build_approvals_pep(
            snapshots[49], "platform", "hcm-platform-pep-v50",
            {
                "routes": 53, "bindings": 54,
                "routeKinds": {"ACTION": 27, "DATA": 19, "PAGE": 7},
                "capabilities": 21, "accessPolicies": 4,
                "entitlementExpressions": 3, "predicatePolicies": 11,
            },
            version=50,
            product_key="hcm",
        )
        hcm_payroll_pep_v50 = build_approvals_pep(
            snapshots[49], "payroll", "hcm-payroll-pep-v50",
            {
                "routes": 34, "bindings": 34,
                "routeKinds": {"ACTION": 21, "DATA": 11, "PAGE": 2},
                "capabilities": 16, "accessPolicies": 0,
                "entitlementExpressions": 0, "predicatePolicies": 8,
            },
            version=50,
            product_key="hcm",
        )
        hcm_people_pep_v51 = build_approvals_pep(
            snapshots[50], "people", "hcm-people-pep-v51",
            {
                "routes": 109, "bindings": 136,
                "routeKinds": {"ACTION": 48, "DATA": 23, "PAGE": 38},
                "capabilities": 54, "accessPolicies": 5,
                "entitlementExpressions": 3, "predicatePolicies": 18,
            },
            version=51,
            product_key="hcm",
        )
        hcm_time_pep_v51 = build_approvals_pep(
            snapshots[50], "time", "hcm-time-pep-v51",
            {
                "routes": 29, "bindings": 29,
                "routeKinds": {"ACTION": 16, "DATA": 6, "PAGE": 7},
                "capabilities": 15, "accessPolicies": 0,
                "entitlementExpressions": 0, "predicatePolicies": 9,
            },
            version=51,
            product_key="hcm",
        )
        hcm_platform_pep_v51 = build_approvals_pep(
            snapshots[50], "platform", "hcm-platform-pep-v51",
            {
                "routes": 53, "bindings": 54,
                "routeKinds": {"ACTION": 27, "DATA": 19, "PAGE": 7},
                "capabilities": 21, "accessPolicies": 4,
                "entitlementExpressions": 3, "predicatePolicies": 11,
            },
            version=51,
            product_key="hcm",
        )
        hcm_payroll_pep_v51 = build_approvals_pep(
            snapshots[50], "payroll", "hcm-payroll-pep-v51",
            {
                "routes": 34, "bindings": 34,
                "routeKinds": {"ACTION": 21, "DATA": 11, "PAGE": 2},
                "capabilities": 16, "accessPolicies": 0,
                "entitlementExpressions": 0, "predicatePolicies": 8,
            },
            version=51,
            product_key="hcm",
        )
        hcm_people_pep_v52 = build_approvals_pep(
            snapshots[51], "people", "hcm-people-pep-v52",
            {
                "routes": 114, "bindings": 141,
                "routeKinds": {"ACTION": 49, "DATA": 25, "PAGE": 40},
                "capabilities": 57, "accessPolicies": 5,
                "entitlementExpressions": 3, "predicatePolicies": 18,
            },
            version=52,
            product_key="hcm",
        )
        hcm_time_pep_v52 = build_approvals_pep(
            snapshots[51], "time", "hcm-time-pep-v52",
            {
                "routes": 29, "bindings": 29,
                "routeKinds": {"ACTION": 16, "DATA": 6, "PAGE": 7},
                "capabilities": 15, "accessPolicies": 0,
                "entitlementExpressions": 0, "predicatePolicies": 9,
            },
            version=52,
            product_key="hcm",
        )
        hcm_platform_pep_v52 = build_approvals_pep(
            snapshots[51], "platform", "hcm-platform-pep-v52",
            {
                "routes": 53, "bindings": 54,
                "routeKinds": {"ACTION": 27, "DATA": 19, "PAGE": 7},
                "capabilities": 21, "accessPolicies": 4,
                "entitlementExpressions": 3, "predicatePolicies": 11,
            },
            version=52,
            product_key="hcm",
        )
        hcm_payroll_pep_v52 = build_approvals_pep(
            snapshots[51], "payroll", "hcm-payroll-pep-v52",
            {
                "routes": 34, "bindings": 34,
                "routeKinds": {"ACTION": 21, "DATA": 11, "PAGE": 2},
                "capabilities": 16, "accessPolicies": 0,
                "entitlementExpressions": 0, "predicatePolicies": 8,
            },
            version=52,
            product_key="hcm",
        )
        hcm_people_pep_v53 = build_approvals_pep(
            snapshots[52], "people", "hcm-people-pep-v53",
            {
                "routes": 117, "bindings": 144,
                "routeKinds": {"ACTION": 50, "DATA": 26, "PAGE": 41},
                "capabilities": 58, "accessPolicies": 5,
                "entitlementExpressions": 3, "predicatePolicies": 18,
            },
            version=53,
            product_key="hcm",
        )
        hcm_time_pep_v53 = build_approvals_pep(
            snapshots[52], "time", "hcm-time-pep-v53",
            {
                "routes": 29, "bindings": 29,
                "routeKinds": {"ACTION": 16, "DATA": 6, "PAGE": 7},
                "capabilities": 15, "accessPolicies": 0,
                "entitlementExpressions": 0, "predicatePolicies": 9,
            },
            version=53,
            product_key="hcm",
        )
        hcm_platform_pep_v53 = build_approvals_pep(
            snapshots[52], "platform", "hcm-platform-pep-v53",
            {
                "routes": 53, "bindings": 54,
                "routeKinds": {"ACTION": 27, "DATA": 19, "PAGE": 7},
                "capabilities": 21, "accessPolicies": 4,
                "entitlementExpressions": 3, "predicatePolicies": 11,
            },
            version=53,
            product_key="hcm",
        )
        hcm_payroll_pep_v53 = build_approvals_pep(
            snapshots[52], "payroll", "hcm-payroll-pep-v53",
            {
                "routes": 34, "bindings": 34,
                "routeKinds": {"ACTION": 21, "DATA": 11, "PAGE": 2},
                "capabilities": 16, "accessPolicies": 0,
                "entitlementExpressions": 0, "predicatePolicies": 8,
            },
            version=53,
            product_key="hcm",
        )
        hcm_people_pep_v54 = build_approvals_pep(
            snapshots[53], "people", "hcm-people-pep-v54",
            {
                "routes": 117, "bindings": 144,
                "routeKinds": {"ACTION": 50, "DATA": 26, "PAGE": 41},
                "capabilities": 58, "accessPolicies": 5,
                "entitlementExpressions": 3, "predicatePolicies": 18,
            },
            version=54,
            product_key="hcm",
        )
        hcm_time_pep_v54 = build_approvals_pep(
            snapshots[53], "time", "hcm-time-pep-v54",
            {
                "routes": 29, "bindings": 29,
                "routeKinds": {"ACTION": 16, "DATA": 6, "PAGE": 7},
                "capabilities": 15, "accessPolicies": 0,
                "entitlementExpressions": 0, "predicatePolicies": 9,
            },
            version=54,
            product_key="hcm",
        )
        hcm_platform_pep_v54 = build_approvals_pep(
            snapshots[53], "platform", "hcm-platform-pep-v54",
            {
                "routes": 53, "bindings": 54,
                "routeKinds": {"ACTION": 27, "DATA": 19, "PAGE": 7},
                "capabilities": 21, "accessPolicies": 4,
                "entitlementExpressions": 3, "predicatePolicies": 11,
            },
            version=54,
            product_key="hcm",
        )
        hcm_payroll_pep_v54 = build_approvals_pep(
            snapshots[53], "payroll", "hcm-payroll-pep-v54",
            {
                "routes": 39, "bindings": 39,
                "routeKinds": {"ACTION": 22, "DATA": 13, "PAGE": 4},
                "capabilities": 17, "accessPolicies": 0,
                "entitlementExpressions": 0, "predicatePolicies": 9,
            },
            version=54,
            product_key="hcm",
        )
        hcm_peps_v55 = {
            service: build_approvals_pep(
                snapshots[54], service, f"hcm-{service}-pep-v55", counts,
                version=55, product_key="hcm",
            )
            for service, counts in {
                "people": {"routes": 121, "bindings": 148,
                           "routeKinds": {"ACTION": 50, "DATA": 28, "PAGE": 43},
                           "capabilities": 58, "accessPolicies": 5,
                           "entitlementExpressions": 3, "predicatePolicies": 18},
                "time": {"routes": 29, "bindings": 29,
                         "routeKinds": {"ACTION": 16, "DATA": 6, "PAGE": 7},
                         "capabilities": 15, "accessPolicies": 0,
                         "entitlementExpressions": 0, "predicatePolicies": 9},
                "platform": {"routes": 53, "bindings": 54,
                             "routeKinds": {"ACTION": 27, "DATA": 19, "PAGE": 7},
                             "capabilities": 21, "accessPolicies": 4,
                             "entitlementExpressions": 3, "predicatePolicies": 11},
                "payroll": {"routes": 39, "bindings": 39,
                            "routeKinds": {"ACTION": 22, "DATA": 13, "PAGE": 4},
                            "capabilities": 17, "accessPolicies": 0,
                            "entitlementExpressions": 0, "predicatePolicies": 9},
            }.items()
        }
        hcm_peps_v56 = {
            service: build_approvals_pep(
                snapshots[55], service, f"hcm-{service}-pep-v56", counts,
                version=56, product_key="hcm",
            )
            for service, counts in {
                "people": {"routes": 121, "bindings": 148,
                           "routeKinds": {"ACTION": 50, "DATA": 28, "PAGE": 43},
                           "capabilities": 58, "accessPolicies": 5,
                           "entitlementExpressions": 3, "predicatePolicies": 18},
                "time": {"routes": 29, "bindings": 29,
                         "routeKinds": {"ACTION": 16, "DATA": 6, "PAGE": 7},
                         "capabilities": 15, "accessPolicies": 0,
                         "entitlementExpressions": 0, "predicatePolicies": 9},
                "platform": {"routes": 69, "bindings": 70,
                             "routeKinds": {"ACTION": 41, "DATA": 21, "PAGE": 7},
                             "capabilities": 22, "accessPolicies": 4,
                             "entitlementExpressions": 3, "predicatePolicies": 11},
                "payroll": {"routes": 39, "bindings": 39,
                            "routeKinds": {"ACTION": 22, "DATA": 13, "PAGE": 4},
                            "capabilities": 17, "accessPolicies": 0,
                            "entitlementExpressions": 0, "predicatePolicies": 9},
            }.items()
        }
        hcm_peps_v57 = {
            service: build_approvals_pep(
                snapshots[56], service, f"hcm-{service}-pep-v57", counts,
                version=57, product_key="hcm",
            )
            for service, counts in {
                "people": {"routes": 124, "bindings": 151,
                           "routeKinds": {"ACTION": 51, "DATA": 29, "PAGE": 44},
                           "capabilities": 59, "accessPolicies": 5,
                           "entitlementExpressions": 3, "predicatePolicies": 19},
                "time": {"routes": 29, "bindings": 29,
                         "routeKinds": {"ACTION": 16, "DATA": 6, "PAGE": 7},
                         "capabilities": 15, "accessPolicies": 0,
                         "entitlementExpressions": 0, "predicatePolicies": 9},
                "platform": {"routes": 69, "bindings": 70,
                             "routeKinds": {"ACTION": 41, "DATA": 21, "PAGE": 7},
                             "capabilities": 22, "accessPolicies": 4,
                             "entitlementExpressions": 3, "predicatePolicies": 11},
                "payroll": {"routes": 39, "bindings": 39,
                            "routeKinds": {"ACTION": 22, "DATA": 13, "PAGE": 4},
                            "capabilities": 17, "accessPolicies": 0,
                            "entitlementExpressions": 0, "predicatePolicies": 9},
            }.items()
        }
        hcm_peps_v58 = {
            service: build_approvals_pep(
                snapshots[57], service, f"hcm-{service}-pep-v58", counts,
                version=58, product_key="hcm",
            )
            for service, counts in {
                "people": {"routes": 124, "bindings": 151,
                           "routeKinds": {"ACTION": 51, "DATA": 29, "PAGE": 44},
                           "capabilities": 59, "accessPolicies": 5,
                           "entitlementExpressions": 3, "predicatePolicies": 19},
                "time": {"routes": 29, "bindings": 29,
                         "routeKinds": {"ACTION": 16, "DATA": 6, "PAGE": 7},
                         "capabilities": 15, "accessPolicies": 0,
                         "entitlementExpressions": 0, "predicatePolicies": 9},
                "platform": {"routes": 69, "bindings": 70,
                             "routeKinds": {"ACTION": 41, "DATA": 21, "PAGE": 7},
                             "capabilities": 22, "accessPolicies": 4,
                             "entitlementExpressions": 3, "predicatePolicies": 11},
                "payroll": {"routes": 41, "bindings": 41,
                            "routeKinds": {"ACTION": 22, "DATA": 14, "PAGE": 5},
                            "capabilities": 17, "accessPolicies": 0,
                            "entitlementExpressions": 0, "predicatePolicies": 9},
            }.items()
        }
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
                PLATFORM_CANARY_PEP_V1_OUTPUT,
                render(platform_canary_pep_v1),
                args.check,
            ),
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
            write_or_check(APPROVAL_RELEASE15_PEP_OUTPUT, render(approval_release15_pep), args.check),
            write_or_check(APPROVAL_RELEASE19_PEP_OUTPUT, render(approval_release19_pep), args.check),
            write_or_check(APPROVAL_RELEASE31_PEP_OUTPUT, render(approval_release31_pep), args.check),
            write_or_check(APPROVAL_RELEASE32_PEP_OUTPUT, render(approval_release32_pep), args.check),
            write_or_check(
                PLATFORM_APPROVALS_PEP_OUTPUT,
                render(platform_approvals_pep),
                args.check,
            ),
            write_or_check(
                LEGACY_PLATFORM_WORKPLACE_PEP_V21_OUTPUT,
                render(platform_workplace_pep_v21),
                args.check,
            ),
            write_or_check(
                LEGACY_PLATFORM_WORKPLACE_PEP_V22_OUTPUT,
                render(platform_workplace_pep_v22),
                args.check,
            ),
            write_or_check(
                LEGACY_PLATFORM_WORKPLACE_PEP_V23_OUTPUT,
                render(platform_workplace_pep_v23),
                args.check,
            ),
            write_or_check(
                PLATFORM_WORKPLACE_PEP_OUTPUT,
                render(platform_workplace_pep),
                args.check,
            ),
            write_or_check(
                PLATFORM_TELEMETRY_DIMENSIONS_OUTPUT,
                render(platform_telemetry_dimensions),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT,
                render(legacy_hcm_people_pep_v3),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_PEOPLE_PEP_V33_OUTPUT,
                render(legacy_hcm_people_pep_v33),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_PEOPLE_PEP_V34_OUTPUT,
                render(legacy_hcm_people_pep_v34),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_PEOPLE_PEP_V35_OUTPUT,
                render(legacy_hcm_people_pep_v35),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_PEOPLE_PEP_V36_OUTPUT,
                render(legacy_hcm_people_pep_v36),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_PEOPLE_PEP_V37_OUTPUT,
                render(legacy_hcm_people_pep_v37),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_PEOPLE_PEP_V38_OUTPUT,
                render(legacy_hcm_people_pep_v38),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_PEOPLE_PEP_V39_OUTPUT,
                render(legacy_hcm_people_pep_v39),
                args.check,
            ),
            write_or_check(
                HCM_PEOPLE_PEP_OUTPUT,
                render(hcm_people_pep),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_TIME_PEP_V35_OUTPUT,
                render(legacy_hcm_time_pep_v35),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_TIME_PEP_V36_OUTPUT,
                render(legacy_hcm_time_pep_v36),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_TIME_PEP_V37_OUTPUT,
                render(legacy_hcm_time_pep_v37),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_TIME_PEP_V38_OUTPUT,
                render(legacy_hcm_time_pep_v38),
                args.check,
            ),
            write_or_check(
                HCM_TIME_PEP_OUTPUT,
                render(hcm_time_pep),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT,
                render(legacy_hcm_platform_pep_v35),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_PLATFORM_PEP_V36_OUTPUT,
                render(legacy_hcm_platform_pep_v36),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_PLATFORM_PEP_V37_OUTPUT,
                render(legacy_hcm_platform_pep_v37),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_PLATFORM_PEP_V38_OUTPUT,
                render(legacy_hcm_platform_pep_v38),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_PLATFORM_PEP_V40_OUTPUT,
                render(legacy_hcm_platform_pep_v40),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_PLATFORM_PEP_V44_OUTPUT,
                render(legacy_hcm_platform_pep_v44),
                args.check,
            ),
            write_or_check(
                HCM_PLATFORM_PEP_OUTPUT,
                render(hcm_platform_pep),
                args.check,
            ),
            write_or_check(
                HCM_PLATFORM_PEP_V46_OUTPUT,
                render(hcm_platform_pep_v46),
                args.check,
            ),
            write_or_check(
                HCM_PLATFORM_PEP_V47_OUTPUT,
                render(hcm_platform_pep_v47),
                args.check,
            ),
            write_or_check(
                LEGACY_HCM_PAYROLL_PEP_V38_OUTPUT,
                render(legacy_hcm_payroll_pep_v38),
                args.check,
            ),
            write_or_check(
                HCM_PAYROLL_PEP_OUTPUT,
                render(hcm_payroll_pep),
                args.check,
            ),
            write_or_check(
                HCM_PAYROLL_PEP_V48_OUTPUT,
                render(hcm_payroll_pep_v48),
                args.check,
            ),
            write_or_check(
                HCM_PEOPLE_PEP_V49_OUTPUT,
                render(hcm_people_pep_v49),
                args.check,
            ),
            write_or_check(
                HCM_TIME_PEP_V49_OUTPUT,
                render(hcm_time_pep_v49),
                args.check,
            ),
            write_or_check(
                HCM_PLATFORM_PEP_V49_OUTPUT,
                render(hcm_platform_pep_v49),
                args.check,
            ),
            write_or_check(
                HCM_PAYROLL_PEP_V49_OUTPUT,
                render(hcm_payroll_pep_v49),
                args.check,
            ),
            write_or_check(
                HCM_PEOPLE_PEP_V50_OUTPUT,
                render(hcm_people_pep_v50),
                args.check,
            ),
            write_or_check(
                HCM_TIME_PEP_V50_OUTPUT,
                render(hcm_time_pep_v50),
                args.check,
            ),
            write_or_check(
                HCM_PLATFORM_PEP_V50_OUTPUT,
                render(hcm_platform_pep_v50),
                args.check,
            ),
            write_or_check(
                HCM_PAYROLL_PEP_V50_OUTPUT,
                render(hcm_payroll_pep_v50),
                args.check,
            ),
            write_or_check(
                HCM_PEOPLE_PEP_V51_OUTPUT,
                render(hcm_people_pep_v51),
                args.check,
            ),
            write_or_check(
                HCM_TIME_PEP_V51_OUTPUT,
                render(hcm_time_pep_v51),
                args.check,
            ),
            write_or_check(
                HCM_PLATFORM_PEP_V51_OUTPUT,
                render(hcm_platform_pep_v51),
                args.check,
            ),
            write_or_check(
                HCM_PAYROLL_PEP_V51_OUTPUT,
                render(hcm_payroll_pep_v51),
                args.check,
            ),
            write_or_check(
                HCM_PEOPLE_PEP_V52_OUTPUT,
                render(hcm_people_pep_v52),
                args.check,
            ),
            write_or_check(
                HCM_TIME_PEP_V52_OUTPUT,
                render(hcm_time_pep_v52),
                args.check,
            ),
            write_or_check(
                HCM_PLATFORM_PEP_V52_OUTPUT,
                render(hcm_platform_pep_v52),
                args.check,
            ),
            write_or_check(
                HCM_PAYROLL_PEP_V52_OUTPUT,
                render(hcm_payroll_pep_v52),
                args.check,
            ),
            write_or_check(
                HCM_PEOPLE_PEP_V53_OUTPUT,
                render(hcm_people_pep_v53),
                args.check,
            ),
            write_or_check(
                HCM_TIME_PEP_V53_OUTPUT,
                render(hcm_time_pep_v53),
                args.check,
            ),
            write_or_check(
                HCM_PLATFORM_PEP_V53_OUTPUT,
                render(hcm_platform_pep_v53),
                args.check,
            ),
            write_or_check(
                HCM_PAYROLL_PEP_V53_OUTPUT,
                render(hcm_payroll_pep_v53),
                args.check,
            ),
            write_or_check(
                HCM_PEOPLE_PEP_V54_OUTPUT,
                render(hcm_people_pep_v54),
                args.check,
            ),
            write_or_check(
                HCM_TIME_PEP_V54_OUTPUT,
                render(hcm_time_pep_v54),
                args.check,
            ),
            write_or_check(
                HCM_PLATFORM_PEP_V54_OUTPUT,
                render(hcm_platform_pep_v54),
                args.check,
            ),
            write_or_check(
                HCM_PAYROLL_PEP_V54_OUTPUT,
                render(hcm_payroll_pep_v54),
                args.check,
            ),
        ]
    )
    outcomes.extend(
        write_or_check(HCM_PEP_V55_OUTPUTS[service], render(pep), args.check)
        for service, pep in hcm_peps_v55.items()
    )
    outcomes.extend(
        write_or_check(HCM_PEP_V56_OUTPUTS[service], render(pep), args.check)
        for service, pep in hcm_peps_v56.items()
    )
    outcomes.extend(
        write_or_check(HCM_PEP_V57_OUTPUTS[service], render(pep), args.check)
        for service, pep in hcm_peps_v57.items()
    )
    outcomes.extend(
        write_or_check(HCM_PEP_V58_OUTPUTS[service], render(pep), args.check)
        for service, pep in hcm_peps_v58.items()
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
        verify_platform_canary_pep(
            PLATFORM_CANARY_PEP_V1_OUTPUT, platform_canary_pep_v1
        )
        verify_platform_canary_pep(PLATFORM_CANARY_PEP_OUTPUT, platform_canary_pep)
        verify_approvals_pep(APPROVAL_PILOT_PEP_OUTPUT, approval_pilot_pep)
        verify_approvals_pep(APPROVAL_WORK_PEP_OUTPUT, approval_work_pep)
        verify_approvals_pep(APPROVAL_DOCUMENT_PEP_OUTPUT, approval_document_pep)
        verify_approvals_pep(APPROVAL_EXTENSION_PEP_OUTPUT, approval_extension_pep)
        verify_approvals_pep(APPROVAL_RELEASE10_PEP_OUTPUT, approval_release10_pep)
        verify_approvals_pep(APPROVAL_RECOVERY11_PEP_OUTPUT, approval_recovery11_pep)
        verify_approvals_pep(APPROVAL_RELEASE12_PEP_OUTPUT, approval_release12_pep)
        verify_approvals_pep(APPROVAL_RELEASE13_PEP_OUTPUT, approval_release13_pep)
        verify_approvals_pep(APPROVAL_RELEASE14_PEP_OUTPUT, approval_release14_pep)
        verify_approvals_pep(APPROVAL_RELEASE15_PEP_OUTPUT, approval_release15_pep)
        verify_approvals_pep(APPROVAL_RELEASE19_PEP_OUTPUT, approval_release19_pep)
        verify_approvals_pep(APPROVAL_RELEASE31_PEP_OUTPUT, approval_release31_pep)
        verify_approvals_pep(APPROVAL_RELEASE32_PEP_OUTPUT, approval_release32_pep)
        verify_approvals_pep(PLATFORM_APPROVALS_PEP_OUTPUT, platform_approvals_pep)
        verify_approvals_pep(
            LEGACY_PLATFORM_WORKPLACE_PEP_V21_OUTPUT,
            platform_workplace_pep_v21,
        )
        verify_approvals_pep(
            LEGACY_PLATFORM_WORKPLACE_PEP_V22_OUTPUT,
            platform_workplace_pep_v22,
        )
        verify_approvals_pep(
            LEGACY_PLATFORM_WORKPLACE_PEP_V23_OUTPUT,
            platform_workplace_pep_v23,
        )
        verify_approvals_pep(PLATFORM_WORKPLACE_PEP_OUTPUT, platform_workplace_pep)
        verify_platform_telemetry_dimensions(platform_telemetry_dimensions)
        verify_approvals_pep(
            LEGACY_HCM_PEOPLE_PEP_V3_OUTPUT, legacy_hcm_people_pep_v3
        )
        verify_approvals_pep(
            LEGACY_HCM_PEOPLE_PEP_V33_OUTPUT, legacy_hcm_people_pep_v33
        )
        verify_approvals_pep(
            LEGACY_HCM_PEOPLE_PEP_V34_OUTPUT, legacy_hcm_people_pep_v34
        )
        verify_approvals_pep(
            LEGACY_HCM_PEOPLE_PEP_V35_OUTPUT, legacy_hcm_people_pep_v35
        )
        verify_approvals_pep(
            LEGACY_HCM_PEOPLE_PEP_V36_OUTPUT, legacy_hcm_people_pep_v36
        )
        verify_approvals_pep(
            LEGACY_HCM_PEOPLE_PEP_V37_OUTPUT, legacy_hcm_people_pep_v37
        )
        verify_approvals_pep(
            LEGACY_HCM_PEOPLE_PEP_V38_OUTPUT, legacy_hcm_people_pep_v38
        )
        verify_approvals_pep(
            LEGACY_HCM_PEOPLE_PEP_V39_OUTPUT, legacy_hcm_people_pep_v39
        )
        verify_approvals_pep(HCM_PEOPLE_PEP_OUTPUT, hcm_people_pep)
        verify_approvals_pep(
            LEGACY_HCM_TIME_PEP_V35_OUTPUT, legacy_hcm_time_pep_v35
        )
        verify_approvals_pep(
            LEGACY_HCM_TIME_PEP_V36_OUTPUT, legacy_hcm_time_pep_v36
        )
        verify_approvals_pep(
            LEGACY_HCM_TIME_PEP_V37_OUTPUT, legacy_hcm_time_pep_v37
        )
        verify_approvals_pep(
            LEGACY_HCM_TIME_PEP_V38_OUTPUT, legacy_hcm_time_pep_v38
        )
        verify_approvals_pep(HCM_TIME_PEP_OUTPUT, hcm_time_pep)
        verify_approvals_pep(
            LEGACY_HCM_PLATFORM_PEP_V35_OUTPUT, legacy_hcm_platform_pep_v35
        )
        verify_approvals_pep(
            LEGACY_HCM_PLATFORM_PEP_V36_OUTPUT, legacy_hcm_platform_pep_v36
        )
        verify_approvals_pep(
            LEGACY_HCM_PLATFORM_PEP_V37_OUTPUT, legacy_hcm_platform_pep_v37
        )
        verify_approvals_pep(
            LEGACY_HCM_PLATFORM_PEP_V38_OUTPUT, legacy_hcm_platform_pep_v38
        )
        verify_approvals_pep(
            LEGACY_HCM_PLATFORM_PEP_V40_OUTPUT, legacy_hcm_platform_pep_v40
        )
        verify_approvals_pep(
            LEGACY_HCM_PLATFORM_PEP_V44_OUTPUT, legacy_hcm_platform_pep_v44
        )
        verify_approvals_pep(HCM_PLATFORM_PEP_OUTPUT, hcm_platform_pep)
        verify_approvals_pep(HCM_PLATFORM_PEP_V46_OUTPUT, hcm_platform_pep_v46)
        verify_approvals_pep(HCM_PLATFORM_PEP_V47_OUTPUT, hcm_platform_pep_v47)
        verify_approvals_pep(
            LEGACY_HCM_PAYROLL_PEP_V38_OUTPUT, legacy_hcm_payroll_pep_v38
        )
        verify_approvals_pep(HCM_PAYROLL_PEP_OUTPUT, hcm_payroll_pep)
        verify_approvals_pep(HCM_PAYROLL_PEP_V48_OUTPUT, hcm_payroll_pep_v48)
        verify_approvals_pep(HCM_PEOPLE_PEP_V49_OUTPUT, hcm_people_pep_v49)
        verify_approvals_pep(HCM_TIME_PEP_V49_OUTPUT, hcm_time_pep_v49)
        verify_approvals_pep(HCM_PLATFORM_PEP_V49_OUTPUT, hcm_platform_pep_v49)
        verify_approvals_pep(HCM_PAYROLL_PEP_V49_OUTPUT, hcm_payroll_pep_v49)
        verify_approvals_pep(HCM_PEOPLE_PEP_V50_OUTPUT, hcm_people_pep_v50)
        verify_approvals_pep(HCM_TIME_PEP_V50_OUTPUT, hcm_time_pep_v50)
        verify_approvals_pep(HCM_PLATFORM_PEP_V50_OUTPUT, hcm_platform_pep_v50)
        verify_approvals_pep(HCM_PAYROLL_PEP_V50_OUTPUT, hcm_payroll_pep_v50)
        verify_approvals_pep(HCM_PEOPLE_PEP_V51_OUTPUT, hcm_people_pep_v51)
        verify_approvals_pep(HCM_TIME_PEP_V51_OUTPUT, hcm_time_pep_v51)
        verify_approvals_pep(HCM_PLATFORM_PEP_V51_OUTPUT, hcm_platform_pep_v51)
        verify_approvals_pep(HCM_PAYROLL_PEP_V51_OUTPUT, hcm_payroll_pep_v51)
        verify_approvals_pep(HCM_PEOPLE_PEP_V52_OUTPUT, hcm_people_pep_v52)
        verify_approvals_pep(HCM_TIME_PEP_V52_OUTPUT, hcm_time_pep_v52)
        verify_approvals_pep(HCM_PLATFORM_PEP_V52_OUTPUT, hcm_platform_pep_v52)
        verify_approvals_pep(HCM_PAYROLL_PEP_V52_OUTPUT, hcm_payroll_pep_v52)
        verify_approvals_pep(HCM_PEOPLE_PEP_V53_OUTPUT, hcm_people_pep_v53)
        verify_approvals_pep(HCM_TIME_PEP_V53_OUTPUT, hcm_time_pep_v53)
        verify_approvals_pep(HCM_PLATFORM_PEP_V53_OUTPUT, hcm_platform_pep_v53)
        verify_approvals_pep(HCM_PAYROLL_PEP_V53_OUTPUT, hcm_payroll_pep_v53)
        verify_approvals_pep(HCM_PEOPLE_PEP_V54_OUTPUT, hcm_people_pep_v54)
        verify_approvals_pep(HCM_TIME_PEP_V54_OUTPUT, hcm_time_pep_v54)
        verify_approvals_pep(HCM_PLATFORM_PEP_V54_OUTPUT, hcm_platform_pep_v54)
        verify_approvals_pep(HCM_PAYROLL_PEP_V54_OUTPUT, hcm_payroll_pep_v54)
        for service, pep in hcm_peps_v55.items():
            verify_approvals_pep(HCM_PEP_V55_OUTPUTS[service], pep)
        for service, pep in hcm_peps_v56.items():
            verify_approvals_pep(HCM_PEP_V56_OUTPUTS[service], pep)
        for service, pep in hcm_peps_v57.items():
            verify_approvals_pep(HCM_PEP_V57_OUTPUTS[service], pep)
        for service, pep in hcm_peps_v58.items():
            verify_approvals_pep(HCM_PEP_V58_OUTPUTS[service], pep)
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
