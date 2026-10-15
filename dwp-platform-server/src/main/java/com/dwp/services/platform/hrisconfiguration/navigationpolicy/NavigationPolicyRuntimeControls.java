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
            String installedNodeIds) {
        return new ServerNavigationPolicyEligibilityProvider(installedNodeIds);
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

    private static final Map<String, String> NODE_PERMISSION = permissionMap();
    private final Set<String> installed;

    ServerNavigationPolicyEligibilityProvider(
            @Value("${dwp.hris.navigation-policy.installed-nodes:}") String installedNodeIds) {
        this.installed = tokens(installedNodeIds).stream()
                .filter(NavigationPolicyCanonical.NODE_IDS::contains)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<String> installedNodeIds(long tenantId) {
        return tenantId > 0 ? installed : Set.of();
    }

    @Override
    public Set<String> pageAuthorizedNodeIds(Set<String> trustedPermissions) {
        Set<String> normalized = trustedPermissions.stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return NODE_PERMISSION.entrySet().stream()
                .filter(entry -> normalized.contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Map<String, String> permissionMap() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        put(result, "DATA.HRIS_WORKFORCE:VIEW",
                "BASE:hr-operations-home", "BASE:hr-people",
                "BASE:hr-employment-assignments", "BASE:hr-appointments",
                "BASE:hr-contracts-compensation-basis",
                "BASE:hr-employee-change-requests", "BASE:hr-certificates-documents",
                "BASE:hr-exit-return", "BASE:hr-data-quality");
        put(result, "DATA.HRIS_ORGANIZATION:VIEW", "BASE:hr-organization-positions");
        put(result, "DATA.HRIS_WORKFORCE_SCENARIO:VIEW",
                "MODERN:hcm.operations.workforce-planning");
        put(result, "DATA.HRIS_HR_CASE:VIEW", "MODERN:hcm.operations.hr-services");
        put(result, "DATA.HRIS_BENEFIT_ENROLLMENT:VIEW", "MODERN:hcm.operations.benefits");
        put(result, "DATA.HRIS_ONBOARDING_JOURNEY:VIEW", "MODERN:hcm.operations.onboarding");
        put(result, "DATA.HRIS_RECRUITING_CASE:VIEW", "MODERN:hcm.operations.recruiting");
        put(result, "DATA.HRIS_CONTINGENT_ENGAGEMENT:VIEW",
                "MODERN:hcm.operations.contingent-workforce");
        put(result, "DATA.HRIS_PEOPLE_METRIC:VIEW",
                "MODERN:hcm.operations.people-analytics");
        if (!result.keySet().equals(NavigationPolicyCanonical.NODE_IDS)) {
            throw new IllegalStateException("HRIS_NAV_POLICY_PERMISSION_MAP_INCOMPLETE");
        }
        return Map.copyOf(result);
    }

    private static void put(Map<String, String> target, String permission, String... nodeIds) {
        for (String nodeId : nodeIds) target.put(nodeId, permission);
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
