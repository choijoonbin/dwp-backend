package com.dwp.services.provider.settings;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TenantOwnerProjectionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TenantOwnerProjectionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DomainRow> domains(UUID providerTenantId) {
        return jdbc.query("""
                SELECT tenant_domain_id, domain_name, domain_type, verification_method,
                       verification_state, primary_domain, verified_at, last_checked_at,
                       updated_at, version
                  FROM prv_tenant_domains
                 WHERE provider_tenant_id = :tenantId
                   AND verification_state <> 'REVOKED'
                 ORDER BY primary_domain DESC, domain_name
                """, tenant(providerTenantId), this::domainRow);
    }

    public List<PolicyRow> globalRetentionAndLegalHoldPolicies() {
        return jdbc.query("""
                SELECT policy.policy_type, policy.owner_service, revision.revision_number,
                       CASE WHEN policy.policy_type = 'RETENTION'
                            THEN (revision.policy_rule ->> 'retentionDays')::integer END
                           AS retention_days,
                       CASE WHEN policy.policy_type = 'LEGAL_HOLD'
                            THEN (revision.policy_rule ->> 'active')::boolean END
                           AS legal_hold_active,
                       revision.effective_from, revision.effective_to,
                       revision.published_at, revision.updated_at,
                       revision.impact_hash, revision.version
                  FROM prv_data_policies policy
                  JOIN prv_data_policy_revisions revision
                    ON revision.data_policy_id = policy.data_policy_id
                   AND revision.lifecycle_state = 'ACTIVE'
                 WHERE policy.lifecycle_state = 'ACTIVE'
                   AND policy.policy_type IN ('RETENTION', 'LEGAL_HOLD')
                   AND policy.scope_type = 'GLOBAL'
                 ORDER BY policy.policy_type, revision.published_at DESC,
                          revision.data_policy_revision_id
                """, new MapSqlParameterSource(), this::policyRow);
    }

    public List<TenantLifecycleHoldRow> latestTenantLifecycleHoldObservations(
            UUID providerTenantId) {
        return jdbc.query("""
                SELECT DISTINCT ON (requested_action)
                       lifecycle_request_id, requested_action, lifecycle_state,
                       hold_evaluation_state, execution_state,
                       jsonb_array_length(hold_evidence) AS evidence_reference_count,
                       updated_at, version
                  FROM prv_tenant_lifecycle_requests
                 WHERE provider_tenant_id = :tenantId
                 ORDER BY requested_action, updated_at DESC, lifecycle_request_id DESC
                """, tenant(providerTenantId), this::holdRow);
    }

    public Optional<PlanRow> currentPlan(UUID providerTenantId) {
        return jdbc.query("""
                SELECT subscription.lifecycle_state AS subscription_state,
                       subscription.starts_at, subscription.ends_at,
                       subscription.version AS subscription_version,
                       plan.plan_key, plan.plan_version, plan.display_name,
                       GREATEST(subscription.updated_at, plan.updated_at) AS updated_at
                  FROM prv_tenants tenant
                  JOIN prv_organization_subscriptions subscription
                    ON subscription.organization_id = tenant.organization_id
                   AND subscription.lifecycle_state IN ('TRIAL', 'ACTIVE', 'SUSPENDED')
                   AND subscription.starts_at <= CURRENT_TIMESTAMP
                   AND (subscription.ends_at IS NULL
                        OR subscription.ends_at > CURRENT_TIMESTAMP)
                  JOIN prv_service_plans plan
                    ON plan.service_plan_id = subscription.service_plan_id
                   AND plan.lifecycle_state = 'ACTIVE'
                 WHERE tenant.provider_tenant_id = :tenantId
                 ORDER BY subscription.starts_at DESC,
                          subscription.organization_subscription_id DESC
                 LIMIT 1
                """, tenant(providerTenantId), this::planRow).stream().findFirst();
    }

    public List<ProductEligibilityRow> productEligibility(UUID providerTenantId) {
        return jdbc.query("""
                SELECT binding.product_key, binding.app_resource_key,
                       entitlement.entitlement_key, entitlement.entitlement_type,
                       CASE
                           WHEN subscription.organization_subscription_id IS NULL
                               THEN 'NO_ACTIVE_SUBSCRIPTION'
                           WHEN subscription.lifecycle_state = 'SUSPENDED'
                               THEN 'SUBSCRIPTION_SUSPENDED'
                           WHEN plan_entitlement.entitlement_id IS NULL
                               THEN 'NOT_INCLUDED_IN_PLAN'
                           WHEN tenant_entitlement.tenant_entitlement_id IS NULL
                                OR tenant_entitlement.lifecycle_state = 'RETIRED'
                               THEN 'NOT_ASSIGNED_TO_TENANT'
                           WHEN tenant_entitlement.lifecycle_state = 'SUSPENDED'
                               THEN 'TENANT_ENTITLEMENT_SUSPENDED'
                           WHEN entitlement.lifecycle_state <> 'ACTIVE'
                               THEN 'CATALOG_RETIRED'
                           ELSE 'ELIGIBLE'
                       END AS eligibility_state,
                       GREATEST(
                           binding.updated_at, entitlement.updated_at, tenant.updated_at,
                           subscription.updated_at, plan.updated_at,
                           tenant_entitlement.updated_at) AS updated_at
                  FROM prv_tenants tenant
                  CROSS JOIN prv_product_entitlement_bindings binding
                  JOIN prv_entitlement_catalog entitlement
                    ON entitlement.entitlement_id = binding.entitlement_id
                  LEFT JOIN LATERAL (
                      SELECT candidate.*
                        FROM prv_organization_subscriptions candidate
                       WHERE candidate.organization_id = tenant.organization_id
                         AND candidate.lifecycle_state IN ('TRIAL', 'ACTIVE', 'SUSPENDED')
                         AND candidate.starts_at <= CURRENT_TIMESTAMP
                         AND (candidate.ends_at IS NULL
                              OR candidate.ends_at > CURRENT_TIMESTAMP)
                       ORDER BY candidate.starts_at DESC,
                                candidate.organization_subscription_id DESC
                       LIMIT 1
                  ) subscription ON TRUE
                  LEFT JOIN prv_service_plans plan
                    ON plan.service_plan_id = subscription.service_plan_id
                   AND plan.lifecycle_state = 'ACTIVE'
                  LEFT JOIN prv_service_plan_entitlements plan_entitlement
                    ON plan_entitlement.service_plan_id = plan.service_plan_id
                   AND plan_entitlement.entitlement_id = entitlement.entitlement_id
                  LEFT JOIN prv_tenant_entitlements tenant_entitlement
                    ON tenant_entitlement.provider_tenant_id = tenant.provider_tenant_id
                   AND tenant_entitlement.entitlement_id = entitlement.entitlement_id
                 WHERE tenant.provider_tenant_id = :tenantId
                   AND binding.lifecycle_state = 'ACTIVE'
                 ORDER BY binding.product_key
                """, tenant(providerTenantId), this::productEligibilityRow);
    }

    private MapSqlParameterSource tenant(UUID providerTenantId) {
        return new MapSqlParameterSource("tenantId", providerTenantId);
    }

    private DomainRow domainRow(ResultSet result, int ignored) throws SQLException {
        return new DomainRow(
                result.getObject("tenant_domain_id", UUID.class),
                result.getString("domain_name"),
                result.getString("domain_type"),
                result.getString("verification_method"),
                result.getString("verification_state"),
                result.getBoolean("primary_domain"),
                instant(result, "verified_at"),
                instant(result, "last_checked_at"),
                instant(result, "updated_at"),
                result.getLong("version"));
    }

    private PolicyRow policyRow(ResultSet result, int ignored) throws SQLException {
        return new PolicyRow(
                result.getString("policy_type"),
                result.getString("owner_service"),
                result.getInt("revision_number"),
                result.getObject("retention_days", Integer.class),
                result.getObject("legal_hold_active", Boolean.class),
                instant(result, "effective_from"),
                instant(result, "effective_to"),
                instant(result, "published_at"),
                instant(result, "updated_at"),
                result.getString("impact_hash"),
                result.getLong("version"));
    }

    private TenantLifecycleHoldRow holdRow(ResultSet result, int ignored) throws SQLException {
        return new TenantLifecycleHoldRow(
                result.getObject("lifecycle_request_id", UUID.class),
                result.getString("requested_action"),
                result.getString("lifecycle_state"),
                result.getString("hold_evaluation_state"),
                result.getString("execution_state"),
                result.getInt("evidence_reference_count"),
                instant(result, "updated_at"),
                result.getLong("version"));
    }

    private PlanRow planRow(ResultSet result, int ignored) throws SQLException {
        return new PlanRow(
                result.getString("subscription_state"), result.getString("plan_key"),
                result.getInt("plan_version"), result.getString("display_name"),
                instant(result, "starts_at"), instant(result, "ends_at"),
                result.getLong("subscription_version"), instant(result, "updated_at"));
    }

    private ProductEligibilityRow productEligibilityRow(ResultSet result, int ignored)
            throws SQLException {
        return new ProductEligibilityRow(
                result.getString("product_key"), result.getString("app_resource_key"),
                result.getString("entitlement_key"), result.getString("entitlement_type"),
                result.getString("eligibility_state"), instant(result, "updated_at"));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record DomainRow(
            UUID domainId,
            String domainName,
            String domainType,
            String verificationMethod,
            String verificationState,
            boolean primaryDomain,
            Instant verifiedAt,
            Instant lastCheckedAt,
            Instant updatedAt,
            long version) {
    }

    public record PolicyRow(
            String policyType,
            String ownerService,
            int revisionNumber,
            Integer retentionDays,
            Boolean legalHoldActive,
            Instant effectiveFrom,
            Instant effectiveTo,
            Instant publishedAt,
            Instant updatedAt,
            String impactHash,
            long version) {
    }

    public record TenantLifecycleHoldRow(
            UUID lifecycleRequestId,
            String requestedAction,
            String lifecycleState,
            String holdEvaluationState,
            String executionState,
            int evidenceReferenceCount,
            Instant updatedAt,
            long version) {
    }

    public record PlanRow(
            String subscriptionState,
            String planKey,
            int planVersion,
            String displayName,
            Instant startsAt,
            Instant endsAt,
            long version,
            Instant updatedAt) {
    }

    public record ProductEligibilityRow(
            String productKey,
            String appResourceKey,
            String entitlementKey,
            String entitlementType,
            String eligibilityState,
            Instant updatedAt) {
    }
}
