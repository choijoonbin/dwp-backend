package com.dwp.services.provider;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProviderEstateRepository {

    private final JdbcTemplate jdbc;

    public ProviderEstateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ProviderDtos.EstateOverview overview() {
        long organizations = count("SELECT COUNT(*) FROM prv_organizations WHERE lifecycle_state <> 'CLOSED'");
        long tenants = count("SELECT COUNT(*) FROM prv_tenants WHERE lifecycle_state <> 'RETIRED'");
        long active = count("SELECT COUNT(*) FROM prv_tenants WHERE lifecycle_state = 'ACTIVE'");
        long provisioning = count("SELECT COUNT(*) FROM prv_tenants WHERE lifecycle_state = 'PROVISIONING'");
        long suspended = count("SELECT COUNT(*) FROM prv_tenants WHERE lifecycle_state = 'SUSPENDED'");
        long failed = count("SELECT COUNT(*) FROM prv_tenants WHERE onboarding_state = 'FAILED'");
        long operations = count("""
                SELECT COUNT(*) FROM prv_operations
                 WHERE lifecycle_state IN ('PREVIEWED', 'EXECUTING', 'PARTIAL', 'FAILED')
                """);
        long support = count("""
                SELECT COUNT(*) FROM prv_support_sessions
                 WHERE lifecycle_state = 'ACTIVE'
                   AND expires_at > CURRENT_TIMESTAMP
                   AND last_used_at > CURRENT_TIMESTAMP - INTERVAL '15 minutes'
                """);
        return new ProviderDtos.EstateOverview(
                organizations,
                tenants,
                active,
                provisioning,
                suspended,
                failed,
                operations,
                support,
                metrics("SELECT data_region AS key, COUNT(*) AS total FROM prv_tenants GROUP BY data_region ORDER BY total DESC, key"),
                metrics("SELECT service_tier AS key, COUNT(*) AS total FROM prv_tenants GROUP BY service_tier ORDER BY total DESC, key"));
    }

    public Optional<ProviderDtos.OrganizationSummary> organization(UUID organizationId) {
        return jdbc.query("""
                SELECT organization_id, organization_key, display_name, legal_name,
                       customer_reference, lifecycle_state, schema_version,
                       attributes::text, version
                  FROM prv_organizations
                 WHERE organization_id = ?
                """, (RowMapper<ProviderDtos.OrganizationSummary>) this::organization, organizationId)
                .stream().findFirst();
    }

    public Optional<UUID> organizationIdByKey(String organizationKey) {
        return jdbc.query("SELECT organization_id FROM prv_organizations WHERE organization_key = ?",
                (result, ignored) -> result.getObject(1, UUID.class), organizationKey).stream().findFirst();
    }

    public List<UUID> organizationIdsMatching(String pattern) {
        return jdbc.query("""
                SELECT organization_id
                  FROM prv_organizations
                 WHERE LOWER(organization_key) LIKE ?
                    OR LOWER(display_name) LIKE ?
                    OR LOWER(COALESCE(legal_name, '')) LIKE ?
                """, (result, ignored) -> result.getObject(1, UUID.class), pattern, pattern, pattern);
    }

    public Optional<ProviderDtos.SubscriptionSummary> currentSubscription(UUID organizationId) {
        return jdbc.query("""
                SELECT subscription.organization_subscription_id,
                       plan.plan_key,
                       plan.plan_version,
                       plan.display_name AS plan_name,
                       subscription.lifecycle_state,
                       subscription.starts_at,
                       subscription.ends_at,
                       subscription.contract_reference,
                       subscription.version
                  FROM prv_organization_subscriptions subscription
                  JOIN prv_service_plans plan
                    ON plan.service_plan_id = subscription.service_plan_id
                 WHERE subscription.organization_id = ?
                   AND subscription.lifecycle_state IN ('TRIAL', 'ACTIVE', 'SUSPENDED')
                 ORDER BY subscription.starts_at DESC
                 LIMIT 1
                """, this::subscription, organizationId).stream().findFirst();
    }

    public void ensureOrganizationSubscription(
            UUID organizationId,
            String serviceTier,
            String contractReference,
            Long operatorId) {
        jdbc.update("""
                INSERT INTO prv_organization_subscriptions (
                    organization_id, service_plan_id, lifecycle_state,
                    contract_reference, created_by, updated_by)
                SELECT ?, plan.service_plan_id, 'ACTIVE', ?, ?, ?
                  FROM prv_service_plans plan
                 WHERE plan.service_tier = ?
                   AND plan.lifecycle_state = 'ACTIVE'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM prv_organization_subscriptions current
                        WHERE current.organization_id = ?
                          AND current.lifecycle_state IN ('TRIAL', 'ACTIVE', 'SUSPENDED')
                   )
                """, organizationId, nullable(contractReference), operatorId, operatorId,
                serviceTier, organizationId);
    }

    public boolean environmentExists(UUID organizationId, String environmentKey) {
        return count("""
                SELECT COUNT(*) FROM prv_tenants
                 WHERE organization_id = ? AND environment_key = ?
                """, organizationId, environmentKey) > 0;
    }

    public UUID createOrganization(
            String organizationKey,
            String displayName,
            String legalName,
            String customerReference,
            Long operatorId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_organizations (
                    organization_id, organization_key, display_name, legal_name,
                    customer_reference, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, organizationKey, displayName, nullable(legalName),
                nullable(customerReference), operatorId, operatorId);
        return id;
    }

    public void initializeServiceInstances(
            UUID tenantId,
            String region,
            Long operatorId) {
        jdbc.update("""
                INSERT INTO prv_tenant_service_instances (
                    provider_tenant_id, service_key, deployment_cell_id,
                    lifecycle_state, created_by, updated_by)
                SELECT ?, service.service_key, cell.deployment_cell_id,
                       'PROVISIONING', ?, ?
                  FROM prv_service_catalog service
                  JOIN prv_deployment_cells cell
                    ON cell.region_key = ? AND cell.lifecycle_state = 'ACTIVE'
                 WHERE service.lifecycle_state = 'ACTIVE'
                ON CONFLICT (provider_tenant_id, service_key) DO NOTHING
                """, tenantId, operatorId, operatorId, region);
    }

    public void initializeTenantExtension(UUID tenantId, String configuration, Long operatorId) {
        jdbc.update("""
                INSERT INTO prv_configuration_values (
                    namespace, schema_version, provider_tenant_id, value,
                    created_by, updated_by)
                VALUES ('provider.tenant.extensions', 1, ?, CAST(? AS jsonb), ?, ?)
                ON CONFLICT DO NOTHING
                """, tenantId, configuration, operatorId, operatorId);
    }

    public UUID createInternalDomain(UUID tenantId, String tenantKey, Long operatorId) {
        UUID domainId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_tenant_domains (
                    tenant_domain_id, provider_tenant_id, domain_name, domain_type,
                    verification_method, verification_state, primary_domain,
                    verified_at, created_by, updated_by)
                VALUES (?, ?, ?, 'LOGIN', 'INTERNAL', 'VERIFIED', TRUE,
                        CURRENT_TIMESTAMP, ?, ?)
                ON CONFLICT (domain_name) DO NOTHING
                """, domainId, tenantId, tenantKey + ".local", operatorId, operatorId);
        return domainId;
    }

    public UUID createDomain(
            UUID tenantId,
            String domainName,
            String domainType,
            boolean primary,
            String recordValue,
            String tokenHash,
            Long operatorId) {
        UUID domainId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_tenant_domains (
                    tenant_domain_id, provider_tenant_id, domain_name, domain_type,
                    verification_method, verification_state, verification_token_hash,
                    verification_record_value, primary_domain, requested_primary,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, 'DNS_TXT', 'PENDING', ?, ?, FALSE, ?, ?, ?)
                """, domainId, tenantId, domainName, domainType, tokenHash, recordValue,
                primary, operatorId, operatorId);
        return domainId;
    }

    public List<ProviderDtos.TenantDomainSummary> domains(UUID tenantId) {
        return jdbc.query("""
                SELECT tenant_domain_id, domain_name, domain_type, verification_method,
                       verification_state, primary_domain, verified_at, last_checked_at, version
                  FROM prv_tenant_domains
                 WHERE provider_tenant_id = ? AND verification_state <> 'REVOKED'
                 ORDER BY primary_domain DESC, domain_name
                """, this::domain, tenantId);
    }

    public Optional<DomainRecord> domainRecord(UUID tenantId, UUID domainId) {
        return jdbc.query("""
                SELECT tenant_domain_id, domain_name, verification_method,
                       verification_state, verification_record_value,
                       verification_token_hash, version
                  FROM prv_tenant_domains
                 WHERE provider_tenant_id = ? AND tenant_domain_id = ?
                """, (result, ignored) -> new DomainRecord(
                        result.getObject("tenant_domain_id", UUID.class),
                        result.getString("domain_name"),
                        result.getString("verification_method"),
                        result.getString("verification_state"),
                        result.getString("verification_record_value"),
                        result.getString("verification_token_hash"),
                        result.getLong("version")), tenantId, domainId).stream().findFirst();
    }

    public void markDomainChecked(
            UUID tenantId,
            UUID domainId,
            boolean verified,
            Long operatorId) {
        if (verified) {
            jdbc.update("""
                    UPDATE prv_tenant_domains
                       SET primary_domain = FALSE,
                           updated_at = CURRENT_TIMESTAMP,
                           updated_by = ?,
                           version = version + 1
                     WHERE provider_tenant_id = ?
                       AND primary_domain = TRUE
                       AND EXISTS (
                           SELECT 1
                             FROM prv_tenant_domains candidate
                            WHERE candidate.tenant_domain_id = ?
                              AND candidate.requested_primary = TRUE
                       )
                    """, operatorId, tenantId, domainId);
        }
        jdbc.update("""
                UPDATE prv_tenant_domains
                   SET verification_state = ?,
                       verified_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE verified_at END,
                       primary_domain = CASE
                           WHEN ? AND requested_primary THEN TRUE
                           ELSE primary_domain
                       END,
                       last_checked_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?,
                       version = version + 1
                 WHERE tenant_domain_id = ?
                   AND provider_tenant_id = ?
                """, verified ? "VERIFIED" : "FAILED", verified, verified,
                operatorId, domainId, tenantId);
    }

    public UUID createTenantAdministrator(
            UUID tenantId,
            String email,
            String displayName,
            Long operatorId) {
        UUID administratorId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_tenant_administrators (
                    tenant_administrator_id, provider_tenant_id, email,
                    display_name, primary_administrator, created_by, updated_by)
                VALUES (?, ?, ?, ?, TRUE, ?, ?)
                """, administratorId, tenantId, email, displayName, operatorId, operatorId);
        return administratorId;
    }

    public void linkTenantAdministrator(
            UUID tenantId,
            String email,
            Long authUserId,
            Long operatorId) {
        jdbc.update("""
                UPDATE prv_tenant_administrators
                   SET auth_user_id = ?,
                       lifecycle_state = CASE
                           WHEN lifecycle_state = 'ACTIVE' THEN lifecycle_state
                           ELSE 'PENDING'
                       END,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?,
                       version = version + 1
                 WHERE provider_tenant_id = ? AND LOWER(BTRIM(email)) = LOWER(BTRIM(?))
                """, authUserId, operatorId, tenantId, email);
    }

    public void markAdministratorInvited(UUID administratorId, Long operatorId) {
        jdbc.update("""
                UPDATE prv_tenant_administrators
                   SET lifecycle_state = 'INVITED',
                       last_invited_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?,
                       version = version + 1
                 WHERE tenant_administrator_id = ?
                """, operatorId, administratorId);
    }

    public Optional<AdministratorRecord> administrator(UUID tenantId, UUID administratorId) {
        return jdbc.query("""
                SELECT tenant_administrator_id, auth_user_id, email,
                       display_name, lifecycle_state
                  FROM prv_tenant_administrators
                 WHERE provider_tenant_id = ? AND tenant_administrator_id = ?
                """, (result, ignored) -> new AdministratorRecord(
                        result.getObject("tenant_administrator_id", UUID.class),
                        nullableLong(result, "auth_user_id"),
                        result.getString("email"),
                        result.getString("display_name"),
                        result.getString("lifecycle_state")), tenantId, administratorId)
                .stream().findFirst();
    }

    public ProviderDtos.TenantAdministratorPosture administratorPosture(UUID tenantId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)::INTEGER AS configured_count,
                       COUNT(*) FILTER (WHERE lifecycle_state = 'ACTIVE')::INTEGER AS active_count,
                       COUNT(*) FILTER (
                           WHERE lifecycle_state IN ('PENDING', 'INVITED'))::INTEGER
                           AS pending_delivery_count,
                       COALESCE(BOOL_OR(primary_administrator), FALSE) AS primary_configured,
                       MAX(last_invited_at) AS last_invited_at
                  FROM prv_tenant_administrators
                 WHERE provider_tenant_id = ? AND lifecycle_state <> 'REVOKED'
                """, (result, ignored) -> new ProviderDtos.TenantAdministratorPosture(
                result.getInt("configured_count"),
                result.getInt("active_count"),
                result.getInt("pending_delivery_count"),
                result.getBoolean("primary_configured"),
                instant(result, "last_invited_at")), tenantId);
    }

    public List<ProviderDtos.ServiceInstanceSummary> serviceInstances(UUID tenantId) {
        return jdbc.query("""
                SELECT instance.tenant_service_instance_id,
                       instance.service_key,
                       service.display_name AS service_name,
                       cell.cell_key,
                       cell.region_key,
                       instance.lifecycle_state,
                       instance.external_resource_id,
                       instance.applied_schema_version,
                       instance.health_snapshot::text,
                       instance.last_reconciled_at,
                       instance.version
                  FROM prv_tenant_service_instances instance
                  JOIN prv_service_catalog service ON service.service_key = instance.service_key
                  LEFT JOIN prv_deployment_cells cell
                    ON cell.deployment_cell_id = instance.deployment_cell_id
                 WHERE instance.provider_tenant_id = ?
                 ORDER BY service.provisioning_order
                """, this::serviceInstance, tenantId);
    }

    public void updateServiceInstance(
            UUID tenantId,
            String serviceKey,
            String state,
            String externalReference,
            String healthSnapshot,
            Long operatorId) {
        jdbc.update("""
                UPDATE prv_tenant_service_instances
                   SET lifecycle_state = ?,
                       external_resource_id = COALESCE(?, external_resource_id),
                       applied_schema_version = CASE WHEN ? = 'READY' THEN 1 ELSE applied_schema_version END,
                       health_snapshot = CAST(? AS jsonb),
                       last_reconciled_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?,
                       version = version + 1
                 WHERE provider_tenant_id = ? AND service_key = ?
                """, state, externalReference, state, healthSnapshot, operatorId, tenantId, serviceKey);
    }

    public List<ProviderDtos.RegionSummary> regions() {
        return jdbc.query("""
                SELECT region_key, display_name, jurisdiction_code, residency_class, lifecycle_state
                  FROM prv_regions
                 WHERE lifecycle_state <> 'RETIRED'
                 ORDER BY display_name
                """, (result, ignored) -> new ProviderDtos.RegionSummary(
                        result.getString("region_key"),
                        result.getString("display_name"),
                        result.getString("jurisdiction_code"),
                        result.getString("residency_class"),
                        result.getString("lifecycle_state")));
    }

    public List<ProviderDtos.AuditEventSummary> auditEvents(UUID tenantId, int limit) {
        String tenantClause = tenantId == null ? "" : " WHERE audit.provider_tenant_id = ?";
        Object[] arguments = tenantId == null ? new Object[0] : new Object[]{tenantId};
        String sql = """
                SELECT audit.audit_event_id,
                       audit.provider_operator_id,
                       operator.display_name AS operator_name,
                       audit.provider_tenant_id,
                       tenant.tenant_key,
                       audit.action,
                       audit.target_type,
                       audit.target_id,
                       audit.event_category,
                       audit.outcome,
                       audit.correlation_id,
                       audit.redacted_snapshot::text,
                       audit.occurred_at
                  FROM prv_audit_events audit
                  LEFT JOIN prv_operators operator
                    ON operator.provider_operator_id = audit.provider_operator_id
                  LEFT JOIN prv_tenants tenant
                    ON tenant.provider_tenant_id = audit.provider_tenant_id
                """ + tenantClause
                + " ORDER BY audit.occurred_at DESC LIMIT "
                + Math.min(500, Math.max(1, limit));
        return jdbc.query(sql, this::auditEvent, arguments);
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private List<ProviderDtos.Metric> metrics(String sql) {
        return jdbc.query(sql, (result, ignored) ->
                new ProviderDtos.Metric(result.getString("key"), result.getLong("total")));
    }

    private ProviderDtos.OrganizationSummary organization(ResultSet result, int ignored) throws SQLException {
        return new ProviderDtos.OrganizationSummary(
                result.getObject("organization_id", UUID.class),
                result.getString("organization_key"),
                result.getString("display_name"),
                result.getString("legal_name"),
                result.getString("customer_reference"),
                result.getString("lifecycle_state"),
                result.getInt("schema_version"),
                result.getString("attributes"),
                result.getLong("version"));
    }

    private ProviderDtos.SubscriptionSummary subscription(ResultSet result, int ignored)
            throws SQLException {
        return new ProviderDtos.SubscriptionSummary(
                result.getObject("organization_subscription_id", UUID.class),
                result.getString("plan_key"),
                result.getInt("plan_version"),
                result.getString("plan_name"),
                result.getString("lifecycle_state"),
                instant(result, "starts_at"),
                instant(result, "ends_at"),
                result.getString("contract_reference"),
                result.getLong("version"));
    }

    private ProviderDtos.ServiceInstanceSummary serviceInstance(ResultSet result, int ignored)
            throws SQLException {
        return new ProviderDtos.ServiceInstanceSummary(
                result.getObject("tenant_service_instance_id", UUID.class),
                result.getString("service_key"),
                result.getString("service_name"),
                result.getString("cell_key"),
                result.getString("region_key"),
                result.getString("lifecycle_state"),
                result.getString("external_resource_id"),
                nullableInteger(result, "applied_schema_version"),
                result.getString("health_snapshot"),
                instant(result, "last_reconciled_at"),
                result.getLong("version"));
    }

    private ProviderDtos.TenantDomainSummary domain(ResultSet result, int ignored) throws SQLException {
        return new ProviderDtos.TenantDomainSummary(
                result.getObject("tenant_domain_id", UUID.class),
                result.getString("domain_name"),
                result.getString("domain_type"),
                result.getString("verification_method"),
                result.getString("verification_state"),
                result.getBoolean("primary_domain"),
                instant(result, "verified_at"),
                instant(result, "last_checked_at"),
                result.getLong("version"));
    }

    private ProviderDtos.AuditEventSummary auditEvent(ResultSet result, int ignored) throws SQLException {
        return new ProviderDtos.AuditEventSummary(
                result.getObject("audit_event_id", UUID.class),
                nullableLong(result, "provider_operator_id"),
                result.getString("operator_name"),
                result.getObject("provider_tenant_id", UUID.class),
                result.getString("tenant_key"),
                result.getString("action"),
                result.getString("target_type"),
                result.getString("target_id"),
                result.getString("event_category"),
                result.getString("outcome"),
                result.getString("correlation_id"),
                result.getString("redacted_snapshot"),
                instant(result, "occurred_at"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private Integer nullableInteger(ResultSet result, String column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record DomainRecord(
            UUID domainId,
            String domainName,
            String verificationMethod,
            String verificationState,
            String recordValue,
            String tokenHash,
            long version) {
    }

    public record AdministratorRecord(
            UUID administratorId,
            Long authUserId,
            String email,
            String displayName,
            String lifecycleState) {
    }

}
