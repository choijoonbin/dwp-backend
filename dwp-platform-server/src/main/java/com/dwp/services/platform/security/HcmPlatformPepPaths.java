package com.dwp.services.platform.security;


import java.util.Set;
import java.util.regex.Pattern;

/** Canonical route families and fail-closed path claiming for the Platform PEP. */
final class HcmPlatformPepPaths {
    private HcmPlatformPepPaths() {
    }

    static final String CONFIGURATION_ROOT = "/v1/admin/hris/configurations";
    static final String WORKBENCH_PATH = "/v1/hris/workbench/settings-reference";
    static final String SETTINGS_OPERATIONS_PATH =
            "/v1/hris/workbench/settings-operations";
    static final String OPERATION_EXCEPTION_ROOT =
            "/v1/admin/hris/operations/exceptions";
    static final String NAVIGATION_POLICY_ROOT =
            "/v1/admin/hris/navigation-policies";
    static final String NAVIGATION_PROJECTION_PATH =
            "/v1/hris/navigation/projection";
    static final String NAVIGATION_V2_POLICY_ROOT =
            "/v2/admin/hris/navigation-policies";
    static final String NAVIGATION_V2_PROJECTION_PATH =
            "/v2/hris/navigation/projection";
    static final String NAVIGATION_V2_DEEP_LINK_PATH =
            "/v2/hris/navigation/deep-link-decision";
    static final String NAVIGATION_V3_PROJECTION_PATH =
            "/v3/hris/navigation/projection";
    static final String NAVIGATION_V3_DEEP_LINK_PATH =
            "/v3/hris/navigation/deep-link-decision";
    static final String HCM_AUDIT_EVENTS_ROOT =
            "/v1/admin/audit-control/events";
    static final String EXPLORER_PATH = "/v1/hris/explorer";
    static final String EXPLORER_PREFERENCES_PATH =
            "/v1/hris/explorer/preferences";
    static final String EXPLORER_RECENTS_PATH =
            "/v1/hris/explorer/recents";
    static final String HRIS_COMMAND_RECEIPT_ROOT =
            "/v1/admin/hris/command-receipts";
    static final String CONTROLLED_TRANSFER_PATH =
            "/v1/admin/hris/file-transfers";
    static final String CONTROLLED_TRANSFER_ROUTE_PREFIX =
            "route.hcm.management.controlled-transfer";
    static final String NAVIGATION_SHELL_ROUTE_PREFIX =
            "route.hcm.shell.navigation-";
    static final Set<String> NAVIGATION_LEGAL_ENTITY_ROUTE_KEYS = Set.of(
            "route.hcm.shell.navigation-aggregate-legal-entity.data",
            "route.hcm.shell.navigation-deep-link-decision-legal-entity.data");
    static final Set<String> NAVIGATION_OPERATIONS_CAPABILITIES = Set.of(
            "hcm.operations.workforce.read",
            "hcm.operations.time.read",
            "hcm.operations.absence.read",
            "hcm.operations.benefits.read",
            "hcm.operations.pay.read",
            "hcm.operations.talent.read",
            "hcm.compensation.plan.view");
    static final String CONTROLLED_TRANSFER_PURPOSE =
            "HRIS_INTEGRATION_ADMIN";
    static final String CONTROLLED_TRANSFER_READER_DUTY =
            "HRIS_DUTY_CONTROLLED_TRANSFER_READER";
    static final String CONTROLLED_TRANSFER_CREATOR_DUTY =
            "HRIS_DUTY_CONTROLLED_TRANSFER_CREATOR";
    static final Set<String> CONTROLLED_TRANSFER_READER_PACKAGES = Set.of(
            "HRIS_INTEGRATION_AUTHOR", "HRIS_INTEGRATION_EXECUTOR");
    static final String EXPLORER_ROUTE_PREFIX = "route.hcm.utility.explorer";
    static final Set<String> EXPLORER_PACKAGES = Set.of(
            "HRIS_EMPLOYEE",
            "HRIS_LINE_MANAGER",
            "HRIS_HR_BUSINESS_PARTNER",
            "HRIS_CONFIG_AUTHOR",
            "HRIS_AUDITOR");
    static final Set<String> PLATFORM_RECEIPT_PACKAGES = Set.of(
            "HRIS_CONFIG_AUTHOR",
            "HRIS_CONFIG_PUBLISHER",
            "HRIS_INTEGRATION_AUTHOR",
            "HRIS_INTEGRATION_EXECUTOR");
    static final String EXPLORER_DUTY =
            "HRIS_DUTY_APP_SHELL_READER@SELF";
    static final Pattern EXPLORER_AUTHORITY_CONTEXT = Pattern.compile(
            "^psc-[a-f0-9]{64}$");
    static final String EXPLORER_PURPOSE = "HRIS_APP_DISCOVERY";
    static final String SETTINGS_OPERATIONS_PURPOSE = "HRIS_CONFIGURATION";
    static final String OPERATION_EXCEPTION_PURPOSE = "HRIS_OPERATIONS";
    static final String SETTINGS_OPERATIONS_ROUTE =
            "route.hcm.configuration.settings-operations.page";
    static final String OPERATION_EXCEPTION_ROUTE =
            "route.hcm.operations.exception.data";
    static final String OPERATION_EXCEPTION_DETAIL_ROUTE =
            "route.hcm.operations.exception-detail.data";
    static final String OPERATION_EXCEPTION_DUTY_PREFIX =
            "HRIS_DUTY_OPERATION_EXCEPTION_READER@";
    static final Pattern OPERATIONS_SCOPE_KEY = Pattern.compile(
            "^scope-[a-f0-9]{32}$");
    static final Pattern OPERATION_EXCEPTION_RESOURCE_ROLE = Pattern.compile(
            "^" + OPERATION_EXCEPTION_DUTY_PREFIX
                    + "[A-Z][A-Z0-9_]{2,79}$");
    static final Set<String> OPERATION_EXCEPTION_PACKAGES = Set.of(
            "HRIS_HR_BUSINESS_PARTNER",
            "HRIS_TIMEKEEPER",
            "HRIS_TIME_CONTROLLER",
            "HRIS_LEAVE_ADMINISTRATOR",
            "HRIS_PAYROLL_RUN_OPERATOR",
            "HRIS_PAYROLL_CONTROLLER",
            "HRIS_PERFORMANCE_OPERATOR",
            "HRIS_INTEGRATION_EXECUTOR",
            "HRIS_AUDITOR");
    static final Set<String> EXPLORER_RECEIPT_ACTIONS = Set.of(
            "EXPLORER_PREFERENCES_REPLACE",
            "EXPLORER_RECENT_RECORD");
    /**
     * Stable handoff used only by an earlier in-process Explorer owner PEP. Network
     * headers are deliberately not accepted as owner evidence.
     */
    static String normalizeForClaimOnly(String path) {
        String normalized = path.replace('\\', '/');
        normalized = normalized.replaceAll(";[^/]*", "");
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        return normalized;
    }

    static boolean claimed(String path) {
        return path.equals(WORKBENCH_PATH)
                || path.startsWith(WORKBENCH_PATH + ";")
                || path.startsWith(SETTINGS_OPERATIONS_PATH)
                || path.startsWith(OPERATION_EXCEPTION_ROOT)
                || path.equals(NAVIGATION_PROJECTION_PATH)
                || path.startsWith(NAVIGATION_PROJECTION_PATH + ";")
                || path.equals(NAVIGATION_V2_PROJECTION_PATH)
                || path.startsWith(NAVIGATION_V2_PROJECTION_PATH + ";")
                || path.startsWith(NAVIGATION_V2_PROJECTION_PATH + "%")
                || path.equals(NAVIGATION_V2_DEEP_LINK_PATH)
                || path.startsWith(NAVIGATION_V2_DEEP_LINK_PATH + ";")
                || path.startsWith(NAVIGATION_V2_DEEP_LINK_PATH + "%")
                || path.equals(NAVIGATION_V3_PROJECTION_PATH)
                || path.startsWith(NAVIGATION_V3_PROJECTION_PATH + ";")
                || path.startsWith(NAVIGATION_V3_PROJECTION_PATH + "%")
                || path.equals(NAVIGATION_V3_DEEP_LINK_PATH)
                || path.startsWith(NAVIGATION_V3_DEEP_LINK_PATH + ";")
                || path.startsWith(NAVIGATION_V3_DEEP_LINK_PATH + "%")
                || path.equals(HCM_AUDIT_EVENTS_ROOT)
                || path.startsWith(HCM_AUDIT_EVENTS_ROOT + "/")
                || path.startsWith(HCM_AUDIT_EVENTS_ROOT + ";")
                || path.startsWith(HCM_AUDIT_EVENTS_ROOT + "%")
                || path.equals(EXPLORER_PATH)
                || path.equals(EXPLORER_PREFERENCES_PATH)
                || path.equals(EXPLORER_RECENTS_PATH)
                || path.startsWith(HRIS_COMMAND_RECEIPT_ROOT + "/")
                || path.startsWith(HRIS_COMMAND_RECEIPT_ROOT + ";")
                || path.startsWith(HRIS_COMMAND_RECEIPT_ROOT + "%")
                || path.equals(CONTROLLED_TRANSFER_PATH)
                || path.startsWith(CONTROLLED_TRANSFER_PATH + "/")
                || path.startsWith(CONTROLLED_TRANSFER_PATH + ";")
                || path.startsWith(CONTROLLED_TRANSFER_PATH + "%")
                || path.equals(CONFIGURATION_ROOT)
                || path.startsWith(CONFIGURATION_ROOT + "/")
                || path.startsWith(CONFIGURATION_ROOT + ";")
                || path.startsWith(CONFIGURATION_ROOT + "%")
                || path.equals(NAVIGATION_POLICY_ROOT)
                || path.startsWith(NAVIGATION_POLICY_ROOT + "/")
                || path.startsWith(NAVIGATION_POLICY_ROOT + ";")
                || path.startsWith(NAVIGATION_POLICY_ROOT + "%")
                || path.equals(NAVIGATION_V2_POLICY_ROOT)
                || path.startsWith(NAVIGATION_V2_POLICY_ROOT + "/")
                || path.startsWith(NAVIGATION_V2_POLICY_ROOT + ";")
                || path.startsWith(NAVIGATION_V2_POLICY_ROOT + "%");
    }

    static String decodeForClaim(String path) {
        String current = path;
        for (int pass = 0; pass < 2; pass++) {
            StringBuilder decoded = new StringBuilder(current.length());
            boolean changed = false;
            for (int index = 0; index < current.length(); index++) {
                if (current.charAt(index) == '%' && index + 2 < current.length()) {
                    int high = Character.digit(current.charAt(index + 1), 16);
                    int low = Character.digit(current.charAt(index + 2), 16);
                    int value = high < 0 || low < 0 ? -1 : (high << 4) + low;
                    if (value >= 0x20 && value <= 0x7e) {
                        decoded.append((char) value);
                        index += 2;
                        changed = true;
                        continue;
                    }
                }
                decoded.append(current.charAt(index));
            }
            if (!changed) return current;
            current = decoded.toString();
        }
        return current;
    }

    static boolean isOperationsRoute(String routeContractKey) {
        return SETTINGS_OPERATIONS_ROUTE.equals(routeContractKey)
                || OPERATION_EXCEPTION_ROUTE.equals(routeContractKey)
                || OPERATION_EXCEPTION_DETAIL_ROUTE.equals(routeContractKey);
    }

}
