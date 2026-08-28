package com.dwp.services.provider.provisioning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
public class ProviderOnboardingFoundationRepository {

    private final JdbcTemplate jdbc;

    public ProviderOnboardingFoundationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean matches(ControlFoundation expected) {
        Boolean coreMatches = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                      FROM prv_tenants tenant
                      JOIN prv_organizations organization
                        ON organization.organization_id = tenant.organization_id
                     WHERE tenant.provider_tenant_id = ?
                       AND organization.organization_id = ?
                       AND organization.organization_key = ?
                       AND organization.display_name = ?
                       AND organization.legal_name IS NOT DISTINCT FROM ?
                       AND organization.customer_reference IS NOT DISTINCT FROM ?
                       AND organization.lifecycle_state = 'ACTIVE'
                       AND organization.schema_version = 1
                       AND tenant.tenant_key = ?
                       AND tenant.display_name = ?
                       AND tenant.environment_key = ?
                       AND tenant.service_tier = ?
                       AND tenant.data_region = ?
                       AND tenant.isolation_model = ?
                       AND tenant.default_locale = ?
                       AND tenant.time_zone = ?
                       AND tenant.lifecycle_state = 'PROVISIONING'
                       AND tenant.onboarding_state = 'CONTROL_PLANE_READY'
                       AND tenant.auth_tenant_id IS NULL
                       AND tenant.schema_version = 1
                       AND tenant.configuration = CAST(? AS jsonb)
                       AND (
                           SELECT COUNT(*)
                             FROM prv_organization_subscriptions subscription
                             JOIN prv_service_plans plan
                               ON plan.service_plan_id = subscription.service_plan_id
                            WHERE subscription.organization_id = organization.organization_id
                              AND subscription.lifecycle_state = 'ACTIVE'
                              AND plan.service_tier = ?
                              AND plan.lifecycle_state = 'ACTIVE'
                              AND subscription.contract_reference IS NOT DISTINCT FROM ?
                       ) = 1
                       AND (
                           SELECT COUNT(*)
                             FROM prv_configuration_values configuration
                            WHERE configuration.provider_tenant_id = tenant.provider_tenant_id
                              AND configuration.namespace = 'provider.tenant.extensions'
                              AND configuration.schema_version = 1
                              AND configuration.lifecycle_state = 'ACTIVE'
                              AND configuration.value = tenant.configuration
                       ) = 1
                )
                """, Boolean.class,
                expected.tenantId(), expected.organizationId(), expected.organizationKey(),
                expected.organizationName(), nullable(expected.legalName()),
                nullable(expected.customerReference()), expected.tenantKey(),
                expected.tenantDisplayName(), expected.environmentKey(), expected.serviceTier(),
                expected.dataRegion(), expected.isolationModel(), expected.defaultLocale(),
                expected.timeZone(), expected.configuration(), expected.serviceTier(),
                nullable(expected.customerReference()));
        return Boolean.TRUE.equals(coreMatches)
                && entitlementsMatch(expected)
                && administratorMatches(expected)
                && domainsMatch(expected);
    }

    private boolean entitlementsMatch(ControlFoundation expected) {
        List<EntitlementFoundation> assignments = jdbc.query("""
                SELECT entitlement.entitlement_key, assignment.lifecycle_state,
                       assignment.configuration = '{}'::jsonb AS empty_configuration,
                       assignment.version
                  FROM prv_tenant_entitlements assignment
                  JOIN prv_entitlement_catalog entitlement
                    ON entitlement.entitlement_id = assignment.entitlement_id
                 WHERE assignment.provider_tenant_id = ?
                 ORDER BY entitlement.entitlement_key
                """, (result, ignored) -> new EntitlementFoundation(
                result.getString("entitlement_key"), result.getString("lifecycle_state"),
                result.getBoolean("empty_configuration"), result.getLong("version")),
                expected.tenantId());
        List<String> expectedKeys = expected.entitlementKeys().stream().sorted().toList();
        if (assignments.size() != expectedKeys.size()) return false;
        for (int index = 0; index < expectedKeys.size(); index++) {
            EntitlementFoundation assignment = assignments.get(index);
            if (!expectedKeys.get(index).equals(assignment.entitlementKey())
                    || !"ACTIVE".equals(assignment.lifecycleState())
                    || !assignment.emptyConfiguration()
                    || assignment.version() != 0) return false;
        }
        return true;
    }

    private boolean administratorMatches(ControlFoundation expected) {
        List<AdministratorFoundation> administrators = jdbc.query("""
                SELECT email, display_name, primary_administrator,
                       lifecycle_state, auth_user_id, role_code
                  FROM prv_tenant_administrators
                 WHERE provider_tenant_id = ?
                   AND lifecycle_state <> 'REVOKED'
                """, (result, ignored) -> new AdministratorFoundation(
                result.getString("email"), result.getString("display_name"),
                result.getBoolean("primary_administrator"),
                result.getString("lifecycle_state"), result.getObject("auth_user_id", Long.class),
                result.getString("role_code")), expected.tenantId());
        if (administrators.size() != 1) return false;
        AdministratorFoundation administrator = administrators.get(0);
        return administrator.primary()
                && "PENDING".equals(administrator.lifecycleState())
                && administrator.authUserId() == null
                && "TENANT_ADMIN".equals(administrator.roleKey())
                && Objects.equals(administrator.email(), expected.administratorEmail())
                && Objects.equals(administrator.displayName(), expected.administratorDisplayName());
    }

    private boolean domainsMatch(ControlFoundation expected) {
        List<DomainFoundation> domains = jdbc.query("""
                SELECT domain_name, domain_type, verification_method, verification_state,
                       primary_domain, requested_primary, verification_record_value,
                       verification_token_hash
                  FROM prv_tenant_domains
                 WHERE provider_tenant_id = ?
                   AND verification_state <> 'REVOKED'
                 ORDER BY domain_name
                """, (result, ignored) -> new DomainFoundation(
                result.getString("domain_name"), result.getString("domain_type"),
                result.getString("verification_method"), result.getString("verification_state"),
                result.getBoolean("primary_domain"),
                result.getBoolean("requested_primary"),
                result.getString("verification_record_value"),
                result.getString("verification_token_hash")), expected.tenantId());
        boolean internalMatches = domains.stream().anyMatch(domain ->
                domain.domainName().equals(expected.tenantKey() + ".local")
                        && "LOGIN".equals(domain.domainType())
                        && "INTERNAL".equals(domain.verificationMethod())
                        && "VERIFIED".equals(domain.verificationState())
                        && domain.primary()
                        && !domain.requestedPrimary()
                        && domain.recordValue() == null
                        && domain.tokenHash() == null);
        if (!internalMatches) return false;
        if (expected.primaryDomain() == null) return domains.size() == 1;
        boolean requestedDomainMatches = domains.stream().anyMatch(domain ->
                domain.domainName().equals(expected.primaryDomain())
                        && "LOGIN".equals(domain.domainType())
                        && "DNS_TXT".equals(domain.verificationMethod())
                        && "PENDING".equals(domain.verificationState())
                        && !domain.primary()
                        && domain.requestedPrimary()
                        && Objects.equals(domain.recordValue(), expected.verificationRecordValue())
                        && Objects.equals(domain.tokenHash(), expected.verificationTokenHash()));
        return domains.size() == 2 && requestedDomainMatches;
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record ControlFoundation(
            UUID tenantId,
            UUID organizationId,
            String organizationKey,
            String organizationName,
            String legalName,
            String customerReference,
            String tenantKey,
            String tenantDisplayName,
            String environmentKey,
            String serviceTier,
            String dataRegion,
            String isolationModel,
            String defaultLocale,
            String timeZone,
            String configuration,
            String administratorEmail,
            String administratorDisplayName,
            List<String> entitlementKeys,
            String primaryDomain,
            String verificationRecordValue,
            String verificationTokenHash) {

        public ControlFoundation {
            entitlementKeys = List.copyOf(entitlementKeys);
        }
    }

    private record AdministratorFoundation(
            String email,
            String displayName,
            boolean primary,
            String lifecycleState,
            Long authUserId,
            String roleKey) {
    }

    private record EntitlementFoundation(
            String entitlementKey,
            String lifecycleState,
            boolean emptyConfiguration,
            long version) {
    }

    private record DomainFoundation(
            String domainName,
            String domainType,
            String verificationMethod,
            String verificationState,
            boolean primary,
            boolean requestedPrimary,
            String recordValue,
            String tokenHash) {
    }
}
