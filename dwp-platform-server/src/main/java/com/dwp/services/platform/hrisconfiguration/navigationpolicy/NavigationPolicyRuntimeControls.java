package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.dwp.services.platform.hrisconfiguration.navigationpolicy.NavigationPolicyDtos.RolloutMode;

@Configuration(proxyBeanMethods = false)
class NavigationPolicyRuntimeControls {

    @Bean
    @ConditionalOnMissingBean(NavigationPolicyEligibilityProvider.class)
    NavigationPolicyEligibilityProvider navigationPolicyEligibilityProvider(
            @Value("${dwp.hris.navigation-policy.installed-nodes:}")
            String installedNodeIds,
            @Value("${dwp.hris.navigation-policy.deployed-nodes:}")
            String deployedNodeIds) {
        return new ServerNavigationPolicyEligibilityProvider(
                installedNodeIds, deployedNodeIds);
    }

    @Bean
    @ConditionalOnMissingBean(NavigationPolicyRolloutProvider.class)
    NavigationPolicyRolloutProvider navigationPolicyRolloutProvider(
            @Value("${dwp.hris.navigation-policy.mode:OFF}") RolloutMode mode,
            @Value("${dwp.hris.navigation-policy.enrolled-tenants:}")
            String enrolledTenants) {
        return new ServerNavigationPolicyRolloutProvider(mode, enrolledTenants);
    }
}

/** Server-owned installation catalog and exact node-to-PAGE data grant mapping. */
final class ServerNavigationPolicyEligibilityProvider
        implements NavigationPolicyEligibilityProvider {

    private static final Map<String, Set<String>> EXACT_PAGE_ROUTE_PERMISSION =
            exactPageRoutePermissionMap();
    private static final Map<String, Set<String>> NODE_PERMISSION =
            nodePermissionMap(EXACT_PAGE_ROUTE_PERMISSION);
    private final Set<String> installed;
    private final Set<String> deployed;

    ServerNavigationPolicyEligibilityProvider(
            @Value("${dwp.hris.navigation-policy.installed-nodes:}") String installedNodeIds) {
        this(installedNodeIds, installedNodeIds);
    }

    ServerNavigationPolicyEligibilityProvider(
            String installedNodeIds,
            String deployedNodeIds) {
        this.installed = tokens(installedNodeIds).stream()
                .filter(ServerNavigationPolicyEligibilityProvider::canonicalNode)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.deployed = tokens(deployedNodeIds).stream()
                .filter(ServerNavigationPolicyEligibilityProvider::canonicalNode)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<String> installedNodeIds(long tenantId) {
        return tenantId > 0 ? installed : Set.of();
    }

    @Override
    public Set<String> deployedNodeIds(long tenantId) {
        return tenantId > 0 ? deployed : Set.of();
    }

    @Override
    public Set<String> pageAuthorizedNodeIds(Set<String> trustedPermissions) {
        Set<String> normalized = normalizedPermissions(trustedPermissions);
        return NODE_PERMISSION.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(normalized::contains))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean pageAuthorizedPath(
            String nodeId, String path, Set<String> trustedPermissions) {
        if (nodeId == null || path == null) return false;
        Set<String> required = EXACT_PAGE_ROUTE_PERMISSION.get(routeKey(nodeId, path));
        if (required == null || required.isEmpty()) return false;
        Set<String> normalized = normalizedPermissions(trustedPermissions);
        return required.stream().anyMatch(normalized::contains);
    }

    private static Map<String, Set<String>> exactPageRoutePermissionMap() {
        LinkedHashMap<String, Set<String>> result = new LinkedHashMap<>();
        // Every entry below is a v45 routeKind=PAGE binding with an exact resolved capability.
        // POLICY routes and planned placeholders are deliberately absent and therefore fail closed.
        exact(result, "BASE:my-employment-documents", "/hr/me/employment-documents",
                "DATA.HRIS_SELF_PROFILE:VIEW");
        exact(result, "BASE:team-time", "/hr/team/time", "DATA.HR_TIME:VIEW");
        exact(result, "BASE:team-leave", "/hr/team/absence", "DATA.HR_ABSENCE:VIEW");
        exact(result, "BASE:hr-operations-home", "/hr/operations",
                "DATA.WORKFORCE:VIEW", "DATA.HR_TIME:VIEW", "DATA.HR_ABSENCE:VIEW",
                "DATA.HR_BENEFITS:VIEW", "DATA.HR_PAY:VIEW", "DATA.HR_TALENT:VIEW");
        exact(result, "BASE:hr-people", "/hr/operations/people", "DATA.WORKFORCE:VIEW");
        exact(result, "BASE:hr-employment-assignments",
                "/hr/operations/people/employment-assignments",
                "DATA.HRIS_WORKFORCE:VIEW");
        exact(result, "BASE:hr-employment-assignments",
                "/hr/operations/assignments", "DATA.WORKFORCE:VIEW");
        exact(result, "BASE:hr-organization-positions", "/hr/design/organization",
                "ACTION.WORKFORCE_ORG_DESIGN:VIEW");
        exact(result, "BASE:hr-employee-change-requests",
                "/hr/operations/people/employee-change-requests",
                "DATA.HRIS_WORKFORCE:VIEW");
        exact(result, "BASE:time-command-center", "/hr/operations/time",
                "DATA.HR_TIME:VIEW");
        exact(result, "BASE:time-clock-ingestion", "/hr/operations/time/clock-ingestion",
                "DATA.HR_TIME:VIEW");
        exact(result, "BASE:time-interpretation", "/hr/operations/time/interpretation",
                "DATA.HR_TIME:VIEW");
        exact(result, "BASE:time-exceptions", "/hr/operations/time/exceptions",
                "DATA.HR_TIME:VIEW");
        exact(result, "BASE:time-leave-operations", "/hr/operations/absence",
                "DATA.HR_ABSENCE:VIEW");
        exact(result, "BASE:pay-command-center", "/hr/operations/pay",
                "DATA.HR_PAY:VIEW");
        exact(result, "BASE:performance-command-center", "/hr/operations/talent",
                "DATA.HR_TALENT:VIEW");
        exact(result, "BASE:performance-review-operations",
                "/hr/operations/performance/review-operations", "DATA.HR_TALENT:VIEW");
        exact(result, "BASE:performance-multirater-surveys",
                "/hr/operations/performance/multirater-surveys", "DATA.HR_TALENT:VIEW");
        exact(result, "MODERN:hcm.operations.compensation-planning",
                "/hr/operations/performance/compensation-planning",
                "DATA.HRIS_COMPENSATION_PLAN:VIEW");
        exact(result, "BASE:settings-reference", "/hr/settings/reference-data",
                "CONFIG.HRIS_WORKBENCH:VIEW");
        exact(result, "BASE:settings-payroll", "/hr/settings/payroll",
                "CONFIG.HRIS_WORKBENCH:VIEW");
        exact(result, "BASE:dwp-governance",
                "/admin/governance/audit-overview?product=HCM",
                "ADMIN.AUDIT_VIEW:VIEW");
        exact(result, "BASE:dwp-governance",
                "/admin/governance/audit-overview?product=HCM&tab=overview",
                "ADMIN.AUDIT_VIEW:VIEW");
        exact(result, "BASE:dwp-governance",
                "/hr/data/exports", "ACTION.WORKFORCE_CONTROLLED_EXPORT:VIEW");
        result.keySet().forEach(key -> {
            int separator = key.indexOf('\n');
            String nodeId = key.substring(0, separator);
            String path = key.substring(separator + 1);
            boolean canonical = path.equals(
                    NavigationPolicyAggregateV2Canonical.CANONICAL_ROUTES.get(nodeId));
            boolean compatible = nodeId.equals(
                    NavigationPolicyAggregateV2Canonical.COMPATIBILITY_ROUTES.get(path));
            if (!canonical && !compatible) {
                throw new IllegalStateException("HRIS_NAV_POLICY_PAGE_ROUTE_NOT_SEALED");
            }
        });
        return Map.copyOf(result);
    }

    private static Map<String, Set<String>> nodePermissionMap(
            Map<String, Set<String>> exactRoutes) {
        LinkedHashMap<String, Set<String>> result = new LinkedHashMap<>();
        exactRoutes.forEach((key, permissions) -> {
            String nodeId = key.substring(0, key.indexOf('\n'));
            LinkedHashSet<String> merged = new LinkedHashSet<>(
                    result.getOrDefault(nodeId, Set.of()));
            merged.addAll(permissions);
            result.put(nodeId, Set.copyOf(merged));
        });
        if (!NavigationPolicyAggregateV2Canonical.NODE_IDS.containsAll(result.keySet())) {
            throw new IllegalStateException("HRIS_NAV_POLICY_PAGE_NODE_NOT_CANONICAL");
        }
        return Map.copyOf(result);
    }

    private static void exact(
            Map<String, Set<String>> target,
            String nodeId,
            String path,
            String... permissions) {
        target.put(routeKey(nodeId, path), Set.of(permissions));
    }

    private static String routeKey(String nodeId, String path) {
        return nodeId + '\n' + path;
    }

    private static Set<String> normalizedPermissions(Set<String> trustedPermissions) {
        if (trustedPermissions == null || trustedPermissions.isEmpty()) return Set.of();
        return trustedPermissions.stream()
                .filter(java.util.Objects::nonNull)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static boolean canonicalNode(String nodeId) {
        return NavigationPolicyCanonical.NODE_IDS.contains(nodeId)
                || NavigationPolicyAggregateV2Canonical.NODE_IDS.contains(nodeId);
    }

    private static Set<String> tokens(String value) {
        if (value == null || value.isBlank()) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Arrays.stream(value.split(",", -1)).map(String::trim)
                .filter(token -> !token.isEmpty()).forEach(result::add);
        return Set.copyOf(result);
    }
}

/** Default OFF and empty enrollment make accidental customer activation impossible. */
final class ServerNavigationPolicyRolloutProvider implements NavigationPolicyRolloutProvider {

    private final RolloutMode mode;
    private final Set<Long> enrolledTenants;

    ServerNavigationPolicyRolloutProvider(
            @Value("${dwp.hris.navigation-policy.mode:OFF}") RolloutMode mode,
            @Value("${dwp.hris.navigation-policy.enrolled-tenants:}") String enrolledTenants) {
        this.mode = mode;
        this.enrolledTenants = parseTenantIds(enrolledTenants);
    }

    @Override
    public RuntimeEnrollment enrollment(long tenantId) {
        boolean enrolled = mode != RolloutMode.OFF && enrolledTenants.contains(tenantId);
        return new RuntimeEnrollment(enrolled, enrolled ? mode : RolloutMode.OFF);
    }

    private static Set<Long> parseTenantIds(String value) {
        if (value == null || value.isBlank()) return Set.of();
        LinkedHashSet<Long> result = new LinkedHashSet<>();
        for (String token : value.split(",", -1)) {
            long tenantId = Long.parseLong(token.trim());
            if (tenantId < 1) throw new IllegalArgumentException(
                    "navigation-policy enrolled tenant must be positive");
            result.add(tenantId);
        }
        return Set.copyOf(result);
    }
}
