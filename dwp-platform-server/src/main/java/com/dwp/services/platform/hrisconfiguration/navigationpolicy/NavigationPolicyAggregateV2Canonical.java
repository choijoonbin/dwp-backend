package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.AggregatePolicyDocument;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyAggregateV2Dtos.WorkbenchPolicy;
import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.ScopeKind;

/** The sealed 7 workbench / 46 group / 97 descendant IA topology. */
final class NavigationPolicyAggregateV2Canonical {

    static final String IA_REGISTER_SHA256 =
            "ab506a2bb2f80c8ab81a4b9d575374d6bda83b312d11dce07495d4812bdf4686";
    static final String SHELL_REGISTER_SHA256 =
            "f85ee40a66ce4100c5578fd3ca96bc89b652b0762e19d74cc7aeb76338009000";
    static final String TASK_GROUP_REGISTER_SHA256 =
            "f0b1ebb316e4d5944aac90002e144f25d8c1318216b6733e674cdc8c5042872b";
    static final String GENERATED_IA_SHA256 =
            "584cc32f845720ad06fb1bdcea7c967026c0e3a648f70e0215fd8a95a6b055bd";
    static final String TOPOLOGY_REVISION = "HRIS-IA-98-WB7-G46-N97-R5";
    static final String TOPOLOGY_SHA256 =
            "4d27b3b8958d591968ed42e7ccd6387664fff66a364adfc83a8a5b7f223a6d8a";

    record Group(String id, String defaultNodeId, List<String> nodeIds) {
        Group {
            nodeIds = List.copyOf(nodeIds);
        }
    }

    record Workbench(String id, int displayOrder, String defaultNodeId, List<Group> groups) {
        Workbench {
            groups = List.copyOf(groups);
        }
    }

    static final List<Workbench> WORKBENCHES = List.of(
            wb("HRIS-WB-MY-HR", 1, "BASE:my-hr-profile",
                    g("MY-01", "BASE:my-hr-profile", "BASE:my-hr-profile"),
                    g("MY-02", "BASE:my-employment-documents", "BASE:my-employment-documents"),
                    g("MY-03", "BASE:my-time", "BASE:my-time", "BASE:my-leave"),
                    g("MY-04", "BASE:my-pay-tax", "BASE:my-pay-tax", "MODERN:hcm.personal.benefits"),
                    g("MY-05", "BASE:my-goals-performance", "BASE:my-goals-performance",
                            "MODERN:hcm.personal.growth", "MODERN:hcm.personal.learning",
                            "MODERN:hcm.personal.opportunities"),
                    g("MY-06", "BASE:my-hr-requests", "BASE:my-hr-requests",
                            "MODERN:hcm.personal.services", "MODERN:hcm.personal.listening",
                            "MODERN:hcm.personal.assistant")),
            wb("HRIS-WB-TEAM", 2, "BASE:team-home",
                    g("TEAM-01", "BASE:team-home", "BASE:team-home", "BASE:team-members"),
                    g("TEAM-02", "BASE:team-time", "BASE:team-time", "BASE:team-leave"),
                    g("TEAM-03", "BASE:team-people-changes", "BASE:team-people-changes"),
                    g("TEAM-04", "BASE:team-performance", "BASE:team-performance"),
                    g("TEAM-05", "BASE:team-delegation", "BASE:team-delegation")),
            wb("HRIS-WB-HR-OPERATIONS", 3, "BASE:hr-operations-home",
                    g("HROPS-01", "BASE:hr-operations-home", "BASE:hr-operations-home", "BASE:hr-people"),
                    g("HROPS-02", "BASE:hr-employment-assignments", "BASE:hr-employment-assignments",
                            "BASE:hr-appointments", "BASE:hr-contracts-compensation-basis"),
                    g("HROPS-03", "BASE:hr-organization-positions", "BASE:hr-organization-positions",
                            "MODERN:hcm.operations.workforce-planning"),
                    g("HROPS-04", "BASE:hr-employee-change-requests", "BASE:hr-employee-change-requests",
                            "BASE:hr-certificates-documents", "MODERN:hcm.operations.hr-services",
                            "MODERN:hcm.operations.benefits"),
                    g("HROPS-05", "BASE:hr-exit-return", "BASE:hr-exit-return",
                            "MODERN:hcm.operations.onboarding"),
                    g("HROPS-06", "MODERN:hcm.operations.recruiting",
                            "MODERN:hcm.operations.recruiting", "MODERN:hcm.operations.contingent-workforce"),
                    g("HROPS-07", "BASE:hr-data-quality", "BASE:hr-data-quality",
                            "MODERN:hcm.operations.people-analytics")),
            wb("HRIS-WB-TIME", 4, "BASE:time-command-center",
                    g("TIME-01", "BASE:time-command-center", "BASE:time-command-center"),
                    g("TIME-02", "BASE:time-work-plans", "BASE:time-work-plans",
                            "MODERN:hcm.operations.workforce-management"),
                    g("TIME-03", "BASE:time-clock-ingestion", "BASE:time-clock-ingestion",
                            "BASE:time-interpretation"),
                    g("TIME-04", "BASE:time-exceptions", "BASE:time-exceptions"),
                    g("TIME-05", "BASE:time-leave-operations", "BASE:time-leave-operations",
                            "BASE:time-accrual-expiration"),
                    g("TIME-06", "BASE:time-daily-close", "BASE:time-daily-close",
                            "BASE:time-monthly-close", "BASE:time-reports")),
            wb("HRIS-WB-PAYROLL", 5, "BASE:pay-command-center",
                    g("PAY-01", "BASE:pay-command-center", "BASE:pay-command-center", "BASE:pay-population"),
                    g("PAY-02", "BASE:pay-inputs", "BASE:pay-inputs", "BASE:pay-calculation"),
                    g("PAY-03", "BASE:pay-validation-reconciliation", "BASE:pay-validation-reconciliation",
                            "BASE:pay-results"),
                    g("PAY-04", "BASE:pay-retro-corrections", "BASE:pay-retro-corrections"),
                    g("PAY-05", "BASE:pay-payments", "BASE:pay-payments", "BASE:pay-accounting"),
                    g("PAY-06", "BASE:pay-tax-social-insurance", "BASE:pay-tax-social-insurance",
                            "BASE:pay-retirement", "BASE:pay-year-end-tax"),
                    g("PAY-07", "BASE:pay-statements", "BASE:pay-statements", "BASE:pay-reports")),
            wb("HRIS-WB-PERFORMANCE", 6, "BASE:performance-command-center",
                    g("PERF-01", "BASE:performance-command-center", "BASE:performance-command-center",
                            "BASE:performance-goal-cycles", "BASE:performance-forms-scales"),
                    g("PERF-02", "BASE:performance-review-operations", "BASE:performance-review-operations"),
                    g("PERF-03", "BASE:performance-multirater-surveys", "BASE:performance-multirater-surveys",
                            "BASE:performance-conversations-feedback"),
                    g("PERF-04", "BASE:performance-calibration", "BASE:performance-calibration",
                            "BASE:performance-results"),
                    g("PERF-05", "MODERN:hcm.operations.learning", "MODERN:hcm.operations.learning"),
                    g("PERF-06", "MODERN:hcm.operations.opportunities", "MODERN:hcm.operations.opportunities",
                            "MODERN:hcm.operations.succession"),
                    g("PERF-07", "MODERN:hcm.operations.compensation-planning",
                            "MODERN:hcm.operations.compensation-planning")),
            wb("HRIS-WB-SETTINGS", 7, "BASE:settings-enterprise",
                    g("SET-01", "BASE:settings-enterprise", "BASE:settings-enterprise",
                            "BASE:settings-reference", "BASE:settings-people-employment"),
                    g("SET-02", "BASE:settings-time", "BASE:settings-time", "BASE:settings-leave"),
                    g("SET-03", "BASE:settings-payroll", "BASE:settings-payroll", "BASE:settings-statutory"),
                    g("SET-04", "BASE:settings-performance", "BASE:settings-performance",
                            "MODERN:hcm.settings.skills", "MODERN:hcm.settings.listening"),
                    g("SET-05", "BASE:settings-workflow", "BASE:settings-workflow",
                            "BASE:settings-documents-communications"),
                    g("SET-06", "BASE:settings-integrations", "BASE:settings-integrations",
                            "BASE:dwp-integrations"),
                    g("SET-07", "BASE:settings-extensions", "BASE:settings-extensions",
                            "MODERN:hcm.settings.ai-governance", "BASE:dwp-extensibility"),
                    g("SET-08", "BASE:settings-operations", "BASE:settings-operations",
                            "BASE:dwp-experience", "BASE:dwp-identity", "BASE:dwp-platform",
                            "BASE:dwp-governance")));

    static final Map<String, Workbench> BY_WORKBENCH = indexWorkbenches();
    static final Set<String> WORKBENCH_IDS = Set.copyOf(BY_WORKBENCH.keySet());
    static final Set<String> GROUP_IDS = allGroups();
    static final Set<String> NODE_IDS = allNodes();
    static final Map<String, String> CANONICAL_ROUTES = canonicalRoutes();
    /**
     * Finite, unique compatibility aliases from the pinned generated IA/catalog. Ambiguous
     * /hr/data/integrations is deliberately absent until its owner is sealed in R6.
     */
    static final Map<String, String> COMPATIBILITY_ROUTES = Map.ofEntries(
            Map.entry("/hr/me", "BASE:my-hr-profile"),
            Map.entry("/hr/absence", "BASE:my-leave"),
            Map.entry("/hr/services", "BASE:my-hr-requests"),
            Map.entry("/hr/team", "BASE:team-home"),
            Map.entry("/hr/team/absence", "BASE:team-leave"),
            Map.entry("/hr/operations", "BASE:hr-operations-home"),
            Map.entry("/hr/directory", "BASE:hr-people"),
            Map.entry("/hr/operations/assignments", "BASE:hr-employment-assignments"),
            Map.entry("/hr/organization", "BASE:hr-organization-positions"),
            Map.entry("/hr/design/organization", "BASE:hr-organization-positions"),
            Map.entry("/hr/operations/time", "BASE:time-command-center"),
            Map.entry("/hr/operations/absence", "BASE:time-leave-operations"),
            Map.entry("/hr/operations/pay", "BASE:pay-command-center"),
            Map.entry("/hr/operations/talent", "BASE:performance-command-center"),
            Map.entry("/hr/manage/system", "BASE:settings-operations"),
            Map.entry("/admin/governance/audit-overview?product=HCM&tab=overview",
                    "BASE:dwp-governance"),
            Map.entry("/hr/data/exports", "BASE:dwp-governance"));
    static final Set<String> EXCLUDED_PATHS = Set.of(
            "/hr/explore", "/hr/benefits", "/hr/operations/benefits");

    static {
        if (WORKBENCHES.size() != 7 || GROUP_IDS.size() != 46 || NODE_IDS.size() != 97
                || !CANONICAL_ROUTES.keySet().equals(NODE_IDS)) {
            throw new IllegalStateException("HRIS_NAV_V2_CANONICAL_TOPOLOGY_INVALID");
        }
        if (!TOPOLOGY_SHA256.equals(sha256(topologyHashMaterial()))) {
            throw new IllegalStateException("HRIS_NAV_V2_TOPOLOGY_DIGEST_INVALID");
        }
    }

    private NavigationPolicyAggregateV2Canonical() {
    }

    static String topologyHashMaterial() {
        return String.join("|", TOPOLOGY_REVISION, IA_REGISTER_SHA256,
                SHELL_REGISTER_SHA256, TASK_GROUP_REGISTER_SHA256,
                GENERATED_IA_SHA256, Integer.toString(WORKBENCHES.size()),
                Integer.toString(GROUP_IDS.size()), Integer.toString(NODE_IDS.size()));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    static void validate(AggregatePolicyDocument policy) {
        if (policy == null || !NavigationPolicyAggregateV2Dtos.SCHEMA_VERSION.equals(
                policy.schemaVersion())) {
            throw new IllegalArgumentException("HRIS_NAV_V2_SCHEMA_INVALID");
        }
        if (policy.scopeKind() == ScopeKind.TENANT && policy.legalEntityPublicId() != null
                || policy.scopeKind() == ScopeKind.LEGAL_ENTITY
                && policy.legalEntityPublicId() == null) {
            throw new IllegalArgumentException("HRIS_NAV_V2_SCOPE_INVALID");
        }
        if (policy.workbenches().size() != 7) {
            throw new IllegalArgumentException("HRIS_NAV_V2_SEVEN_WORKBENCHES_REQUIRED");
        }
        Map<String, WorkbenchPolicy> configured = uniquePolicies(policy);
        if (!configured.keySet().equals(WORKBENCH_IDS)) {
            throw new IllegalArgumentException("HRIS_NAV_V2_WORKBENCH_SET_INVALID");
        }
        for (Workbench canonical : WORKBENCHES) {
            WorkbenchPolicy selected = configured.get(canonical.id());
            Set<String> canonicalGroups = canonical.groups().stream().map(Group::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<String> enabledGroups = distinct(selected.enabledGroupIds(),
                    "HRIS_NAV_V2_GROUP_DUPLICATE");
            if (!canonicalGroups.containsAll(enabledGroups)) {
                throw new IllegalArgumentException("HRIS_NAV_V2_GROUP_NOT_CANONICAL");
            }
            Set<String> canonicalNodes = canonical.groups().stream().flatMap(
                            group -> group.nodeIds().stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<String> enabledNodes = distinct(selected.enabledNodeIds(),
                    "HRIS_NAV_V2_NODE_DUPLICATE");
            if (!canonicalNodes.containsAll(enabledNodes)) {
                throw new IllegalArgumentException("HRIS_NAV_V2_NODE_NOT_CANONICAL");
            }
            for (Group group : canonical.groups()) {
                if (!enabledGroups.contains(group.id()) && group.nodeIds().stream()
                        .anyMatch(enabledNodes::contains)) {
                    throw new IllegalArgumentException("HRIS_NAV_V2_DISABLED_GROUP_HAS_NODE");
                }
                if (enabledGroups.contains(group.id()) && group.nodeIds().stream()
                        .noneMatch(enabledNodes::contains)) {
                    throw new IllegalArgumentException("HRIS_NAV_V2_ENABLED_GROUP_EMPTY");
                }
            }
            if (!selected.enabled()
                    && (!enabledGroups.isEmpty() || !enabledNodes.isEmpty())) {
                throw new IllegalArgumentException("HRIS_NAV_V2_DISABLED_WORKBENCH_HAS_MEMBERS");
            }
            if (selected.enabled() && enabledGroups.isEmpty()) {
                throw new IllegalArgumentException("HRIS_NAV_V2_ENABLED_WORKBENCH_EMPTY");
            }
        }
    }

    static void validateRestrictiveOverlay(
            AggregatePolicyDocument tenant, AggregatePolicyDocument overlay) {
        validate(tenant);
        validate(overlay);
        if (tenant.scopeKind() != ScopeKind.TENANT
                || overlay.scopeKind() != ScopeKind.LEGAL_ENTITY) {
            throw new IllegalArgumentException("HRIS_NAV_V2_OVERLAY_SCOPE_INVALID");
        }
        if (tenant.rolloutMode() != overlay.rolloutMode()) {
            throw new IllegalArgumentException("HRIS_NAV_V2_OVERLAY_ROLLOUT_MISMATCH");
        }
        Map<String, WorkbenchPolicy> baseline = uniquePolicies(tenant);
        for (WorkbenchPolicy restricted : overlay.workbenches()) {
            WorkbenchPolicy allowed = baseline.get(restricted.workbenchId());
            if (restricted.enabled() && !allowed.enabled()
                    || !Set.copyOf(allowed.enabledGroupIds()).containsAll(
                    restricted.enabledGroupIds())
                    || !Set.copyOf(allowed.enabledNodeIds()).containsAll(
                    restricted.enabledNodeIds())) {
                throw new IllegalArgumentException("HRIS_NAV_V2_OVERLAY_REENABLES_MEMBER");
            }
        }
    }

    static AggregatePolicyDocument canonicalPolicy(
            ScopeKind scopeKind,
            java.util.UUID legalEntityPublicId,
            NavigationPolicyDtos.RolloutMode rolloutMode) {
        return new AggregatePolicyDocument(
                NavigationPolicyAggregateV2Dtos.SCHEMA_VERSION,
                scopeKind,
                legalEntityPublicId,
                rolloutMode,
                WORKBENCHES.stream().map(workbench -> new WorkbenchPolicy(
                        workbench.id(), true,
                        workbench.groups().stream().map(Group::id).toList(),
                        workbench.groups().stream().flatMap(group -> group.nodeIds().stream())
                                .toList())).toList());
    }

    private static Map<String, WorkbenchPolicy> uniquePolicies(AggregatePolicyDocument policy) {
        LinkedHashMap<String, WorkbenchPolicy> result = new LinkedHashMap<>();
        for (WorkbenchPolicy workbench : policy.workbenches()) {
            if (workbench == null || workbench.workbenchId() == null
                    || result.putIfAbsent(workbench.workbenchId(), workbench) != null) {
                throw new IllegalArgumentException("HRIS_NAV_V2_WORKBENCH_DUPLICATE");
            }
        }
        return Map.copyOf(result);
    }

    private static Set<String> distinct(List<String> values, String error) {
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(error);
        }
        LinkedHashSet<String> result = new LinkedHashSet<>(values);
        if (result.size() != values.size()) throw new IllegalArgumentException(error);
        return Set.copyOf(result);
    }

    private static Workbench wb(
            String id, int displayOrder, String defaultNodeId, Group... groups) {
        return new Workbench(id, displayOrder, defaultNodeId, List.of(groups));
    }

    private static Group g(String id, String defaultNodeId, String... nodeIds) {
        return new Group(id, defaultNodeId, List.of(nodeIds));
    }

    private static Map<String, Workbench> indexWorkbenches() {
        LinkedHashMap<String, Workbench> result = new LinkedHashMap<>();
        WORKBENCHES.forEach(workbench -> result.put(workbench.id(), workbench));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Set<String> allGroups() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        WORKBENCHES.forEach(workbench -> workbench.groups().forEach(group -> result.add(group.id())));
        return Set.copyOf(result);
    }

    private static Set<String> allNodes() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        WORKBENCHES.forEach(workbench -> workbench.groups().forEach(
                group -> result.addAll(group.nodeIds())));
        return Set.copyOf(result);
    }

    private static Map<String, String> canonicalRoutes() {
        String rows = """
                BASE:my-hr-profile=/hr/me/profile
                BASE:my-employment-documents=/hr/me/employment-documents
                BASE:my-time=/hr/time
                BASE:my-leave=/hr/me/leave
                BASE:my-pay-tax=/hr/pay
                BASE:my-goals-performance=/hr/talent
                BASE:my-hr-requests=/hr/me/requests
                BASE:team-home=/hr/team/home
                BASE:team-members=/hr/team/members
                BASE:team-time=/hr/team/time
                BASE:team-leave=/hr/team/leave
                BASE:team-people-changes=/hr/team/people-changes
                BASE:team-performance=/hr/team/performance
                BASE:team-delegation=/hr/team/delegation
                BASE:hr-operations-home=/hr/operations/home
                BASE:hr-people=/hr/operations/people
                BASE:hr-employment-assignments=/hr/operations/people/employment-assignments
                BASE:hr-appointments=/hr/operations/people/appointments
                BASE:hr-organization-positions=/hr/operations/people/organization-positions
                BASE:hr-contracts-compensation-basis=/hr/operations/people/contracts-compensation-basis
                BASE:hr-employee-change-requests=/hr/operations/people/employee-change-requests
                BASE:hr-certificates-documents=/hr/operations/people/certificates-documents
                BASE:hr-exit-return=/hr/operations/people/exit-return
                BASE:hr-data-quality=/hr/operations/people/data-quality
                BASE:time-command-center=/hr/operations/time/command-center
                BASE:time-work-plans=/hr/operations/time/work-plans
                BASE:time-clock-ingestion=/hr/operations/time/clock-ingestion
                BASE:time-interpretation=/hr/operations/time/interpretation
                BASE:time-exceptions=/hr/operations/time/exceptions
                BASE:time-daily-close=/hr/operations/time/daily-close
                BASE:time-monthly-close=/hr/operations/time/monthly-close
                BASE:time-leave-operations=/hr/operations/time/leave-operations
                BASE:time-accrual-expiration=/hr/operations/time/accrual-expiration
                BASE:time-reports=/hr/operations/time/reports
                BASE:pay-command-center=/hr/operations/payroll/command-center
                BASE:pay-population=/hr/operations/payroll/population
                BASE:pay-inputs=/hr/operations/payroll/inputs
                BASE:pay-calculation=/hr/operations/payroll/calculation
                BASE:pay-validation-reconciliation=/hr/operations/payroll/validation-reconciliation
                BASE:pay-retro-corrections=/hr/operations/payroll/retro-corrections
                BASE:pay-results=/hr/operations/payroll/results
                BASE:pay-payments=/hr/operations/payroll/payments
                BASE:pay-accounting=/hr/operations/payroll/accounting
                BASE:pay-statements=/hr/operations/payroll/statements
                BASE:pay-tax-social-insurance=/hr/operations/payroll/tax-social-insurance
                BASE:pay-retirement=/hr/operations/payroll/retirement
                BASE:pay-year-end-tax=/hr/operations/payroll/year-end-tax
                BASE:pay-reports=/hr/operations/payroll/reports
                BASE:performance-command-center=/hr/operations/performance/command-center
                BASE:performance-goal-cycles=/hr/operations/performance/goal-cycles
                BASE:performance-review-operations=/hr/operations/performance/review-operations
                BASE:performance-forms-scales=/hr/operations/performance/forms-scales
                BASE:performance-multirater-surveys=/hr/operations/performance/multirater-surveys
                BASE:performance-conversations-feedback=/hr/operations/performance/conversations-feedback
                BASE:performance-calibration=/hr/operations/performance/calibration
                BASE:performance-results=/hr/operations/performance/results
                BASE:settings-enterprise=/hr/settings/enterprise
                BASE:settings-reference=/hr/settings/reference-data
                BASE:settings-people-employment=/hr/settings/people-employment
                BASE:settings-time=/hr/settings/time
                BASE:settings-leave=/hr/settings/leave
                BASE:settings-payroll=/hr/settings/payroll
                BASE:settings-statutory=/hr/settings/statutory
                BASE:settings-performance=/hr/settings/performance
                BASE:settings-workflow=/hr/settings/workflow
                BASE:settings-documents-communications=/hr/settings/documents-communications
                BASE:settings-integrations=/hr/settings/integrations
                BASE:settings-extensions=/hr/settings/extensions
                BASE:settings-operations=/hr/settings/operations
                BASE:dwp-experience=/admin/experience/branding?product=HCM
                BASE:dwp-identity=/admin/identity/app-governance?product=HCM
                BASE:dwp-platform=/admin/platform/catalog?product=HCM
                BASE:dwp-integrations=/admin/integrations/productivity?product=HCM
                BASE:dwp-governance=/admin/governance/audit-overview?product=HCM
                BASE:dwp-extensibility=/admin/platform/registry?namespace=hris-extension
                MODERN:hcm.personal.growth=/hr/me/growth
                MODERN:hcm.settings.skills=/hr/settings/talent/skills
                MODERN:hcm.operations.recruiting=/hr/operations/recruiting
                MODERN:hcm.personal.services=/hr/me/services
                MODERN:hcm.operations.onboarding=/hr/operations/onboarding
                MODERN:hcm.personal.learning=/hr/me/learning
                MODERN:hcm.operations.learning=/hr/operations/learning
                MODERN:hcm.personal.opportunities=/hr/me/opportunities
                MODERN:hcm.operations.opportunities=/hr/operations/opportunities
                MODERN:hcm.operations.succession=/hr/operations/talent/succession
                MODERN:hcm.operations.workforce-planning=/hr/operations/workforce-planning
                MODERN:hcm.operations.compensation-planning=/hr/operations/performance/compensation-planning
                MODERN:hcm.personal.benefits=/hr/me/benefits
                MODERN:hcm.operations.benefits=/hr/operations/employee-services/benefits
                MODERN:hcm.operations.hr-services=/hr/operations/hr-services
                MODERN:hcm.personal.listening=/hr/me/listening
                MODERN:hcm.settings.listening=/hr/settings/experience/listening
                MODERN:hcm.operations.workforce-management=/hr/operations/workforce-management
                MODERN:hcm.operations.contingent-workforce=/hr/operations/contingent-workforce
                MODERN:hcm.operations.people-analytics=/hr/operations/people-analytics
                MODERN:hcm.personal.assistant=/hr/me/assistant
                MODERN:hcm.settings.ai-governance=/hr/settings/ai-governance
                """;
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String row : rows.lines().filter(value -> !value.isBlank()).toList()) {
            int separator = row.indexOf('=');
            String nodeId = row.substring(0, separator);
            String route = row.substring(separator + 1);
            if (result.putIfAbsent(nodeId, route) != null || !route.startsWith("/")) {
                throw new IllegalStateException("HRIS_NAV_V2_ROUTE_CATALOG_INVALID");
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }
}
