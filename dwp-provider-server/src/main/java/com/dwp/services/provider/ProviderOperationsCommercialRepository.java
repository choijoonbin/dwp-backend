package com.dwp.services.provider;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class ProviderOperationsCommercialRepository {

    private final JdbcTemplate jdbc;

    ProviderOperationsCommercialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    ProviderDtos.CommercialOverview commercialOverview() {
        long active = count("""
                SELECT COUNT(*) FROM prv_organization_subscriptions WHERE lifecycle_state = 'ACTIVE'
                """);
        long trials = count("""
                SELECT COUNT(*) FROM prv_organization_subscriptions WHERE lifecycle_state = 'TRIAL'
                """);
        long expiring = count("""
                SELECT COUNT(*) FROM prv_organization_subscriptions
                 WHERE lifecycle_state IN ('TRIAL', 'ACTIVE')
                   AND ends_at BETWEEN CURRENT_TIMESTAMP AND CURRENT_TIMESTAMP + INTERVAL '90 days'
                """);
        long uncontracted = count("""
                SELECT COUNT(*)
                  FROM prv_organizations organization
                 WHERE organization.lifecycle_state <> 'CLOSED'
                   AND NOT EXISTS (
                       SELECT 1 FROM prv_organization_subscriptions subscription
                        WHERE subscription.organization_id = organization.organization_id
                          AND subscription.lifecycle_state IN ('TRIAL', 'ACTIVE', 'SUSPENDED')
                   )
                """);
        return new ProviderDtos.CommercialOverview(
                Instant.now(), active, trials, expiring, uncontracted,
                servicePlanPortfolio(), subscriptionPortfolio(), entitlementAdoption());
    }

    ProviderDtos.AuditInsights auditInsights() {
        return new ProviderDtos.AuditInsights(
                Instant.now(),
                count("SELECT COUNT(*) FROM prv_audit_events WHERE occurred_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'"),
                count("SELECT COUNT(*) FROM prv_audit_events WHERE outcome = 'FAILED' AND occurred_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'"),
                count("SELECT COUNT(*) FROM prv_audit_events WHERE outcome = 'DENIED' AND occurred_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'"),
                count("SELECT COUNT(*) FROM prv_audit_events WHERE event_category = 'PRIVILEGED_ACCESS' AND occurred_at >= CURRENT_TIMESTAMP - INTERVAL '24 hours'"),
                metrics("""
                        SELECT outcome AS key, COUNT(*) AS total
                          FROM prv_audit_events
                         WHERE occurred_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'
                         GROUP BY outcome ORDER BY total DESC, key
                        """),
                metrics("""
                        SELECT event_category AS key, COUNT(*) AS total
                          FROM prv_audit_events
                         WHERE occurred_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'
                         GROUP BY event_category ORDER BY total DESC, key
                        """));
    }

    private List<ProviderDtos.ServicePlanPortfolio> servicePlanPortfolio() {
        return jdbc.query("""
                SELECT plan.plan_key,
                       plan.plan_version,
                       plan.display_name,
                       plan.service_tier,
                       plan.lifecycle_state,
                       COUNT(DISTINCT subscription.organization_id) FILTER (
                           WHERE subscription.lifecycle_state IN ('TRIAL', 'ACTIVE', 'SUSPENDED')) AS organizations,
                       COUNT(DISTINCT tenant.provider_tenant_id) FILTER (
                           WHERE subscription.lifecycle_state IN ('TRIAL', 'ACTIVE', 'SUSPENDED')) AS tenants
                  FROM prv_service_plans plan
                  LEFT JOIN prv_organization_subscriptions subscription
                    ON subscription.service_plan_id = plan.service_plan_id
                  LEFT JOIN prv_tenants tenant ON tenant.organization_id = subscription.organization_id
                 GROUP BY plan.service_plan_id
                 ORDER BY plan.service_tier, plan.plan_version DESC
                """, (result, ignored) -> new ProviderDtos.ServicePlanPortfolio(
                        result.getString("plan_key"), result.getInt("plan_version"),
                        result.getString("display_name"), result.getString("service_tier"),
                        result.getString("lifecycle_state"), result.getLong("organizations"),
                        result.getLong("tenants")));
    }

    private List<ProviderDtos.SubscriptionPortfolio> subscriptionPortfolio() {
        return jdbc.query("""
                SELECT subscription.organization_subscription_id,
                       organization.organization_id,
                       organization.organization_key,
                       organization.display_name AS organization_name,
                       plan.plan_key,
                       plan.display_name AS plan_name,
                       plan.service_tier,
                       subscription.lifecycle_state,
                       subscription.starts_at,
                       subscription.ends_at,
                       subscription.contract_reference,
                       subscription.version,
                       COUNT(DISTINCT tenant.provider_tenant_id) AS tenants,
                       COUNT(DISTINCT entitlement.tenant_entitlement_id) FILTER (
                           WHERE entitlement.lifecycle_state = 'ACTIVE') AS active_entitlements
                  FROM prv_organization_subscriptions subscription
                  JOIN prv_organizations organization ON organization.organization_id = subscription.organization_id
                  JOIN prv_service_plans plan ON plan.service_plan_id = subscription.service_plan_id
                  LEFT JOIN prv_tenants tenant ON tenant.organization_id = organization.organization_id
                  LEFT JOIN prv_tenant_entitlements entitlement
                    ON entitlement.provider_tenant_id = tenant.provider_tenant_id
                 GROUP BY subscription.organization_subscription_id,
                          organization.organization_id, plan.service_plan_id
                 ORDER BY CASE subscription.lifecycle_state
                              WHEN 'TRIAL' THEN 0 WHEN 'ACTIVE' THEN 1
                              WHEN 'SUSPENDED' THEN 2 ELSE 3
                          END,
                          organization.display_name
                """, (result, ignored) -> new ProviderDtos.SubscriptionPortfolio(
                        result.getObject("organization_subscription_id", UUID.class),
                        result.getObject("organization_id", UUID.class),
                        result.getString("organization_key"),
                        result.getString("organization_name"),
                        result.getString("plan_key"),
                        result.getString("plan_name"),
                        result.getString("service_tier"),
                        result.getString("lifecycle_state"),
                        instant(result, "starts_at"),
                        instant(result, "ends_at"),
                        result.getString("contract_reference"),
                        result.getLong("tenants"),
                        result.getLong("active_entitlements"),
                        result.getLong("version")));
    }

    private List<ProviderDtos.EntitlementAdoption> entitlementAdoption() {
        return jdbc.query("""
                SELECT catalog.entitlement_id,
                       catalog.entitlement_key,
                       catalog.name,
                       catalog.entitlement_type,
                       COUNT(DISTINCT assignment.provider_tenant_id) FILTER (
                           WHERE assignment.lifecycle_state = 'ACTIVE') AS assigned_tenants,
                       (SELECT COUNT(*) FROM prv_tenants WHERE lifecycle_state <> 'RETIRED') AS eligible_tenants
                  FROM prv_entitlement_catalog catalog
                  LEFT JOIN prv_tenant_entitlements assignment
                    ON assignment.entitlement_id = catalog.entitlement_id
                 WHERE catalog.lifecycle_state = 'ACTIVE'
                 GROUP BY catalog.entitlement_id
                 ORDER BY assigned_tenants DESC, catalog.entitlement_key
                """, (result, ignored) -> new ProviderDtos.EntitlementAdoption(
                        result.getLong("entitlement_id"), result.getString("entitlement_key"),
                        result.getString("name"), result.getString("entitlement_type"),
                        result.getLong("assigned_tenants"), result.getLong("eligible_tenants")));
    }

    private List<ProviderDtos.Metric> metrics(String sql) {
        return jdbc.query(sql, (result, ignored) ->
                new ProviderDtos.Metric(result.getString("key"), result.getLong("total")));
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
