package com.dwp.services.provider.commercial;

import com.dwp.services.provider.ProviderDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ProviderCommercialRenewalRepository {

    private static final String SELECT = """
            SELECT revision.renewal_revision_id,
                   revision.organization_subscription_id,
                   subscription.organization_id,
                   organization.organization_key,
                   organization.display_name AS organization_name,
                   revision.revision_number,
                   revision.lifecycle_state,
                   revision.baseline_subscription_version,
                   current_plan.plan_key AS current_plan_key,
                   current_plan.display_name AS current_plan_name,
                   target_plan.plan_key AS target_plan_key,
                   target_plan.display_name AS target_plan_name,
                   target_plan.service_tier AS target_service_tier,
                   revision.baseline_ends_at AS current_ends_at,
                   revision.proposed_ends_at,
                   revision.baseline_contract_reference AS current_contract_reference,
                   revision.proposed_contract_reference,
                   revision.reason,
                   revision.added_entitlements,
                   revision.removed_entitlements,
                   revision.impacted_tenants,
                   revision.current_entitlement_count,
                   revision.projected_entitlement_count,
                   revision.content_sha256,
                   revision.request_fingerprint,
                   revision.request_key,
                   revision.requested_by,
                   requester.display_name AS requested_by_name,
                   revision.requested_at,
                   revision.decision_due_at,
                   revision.decided_by,
                   decider.display_name AS decided_by_name,
                   revision.decided_at,
                   revision.decision_reason,
                   revision.published_by,
                   publisher.display_name AS published_by_name,
                   revision.published_at,
                   revision.execution_state,
                   revision.notification_state,
                   revision.version
              FROM prv_subscription_renewal_revisions revision
              JOIN prv_organization_subscriptions subscription
                ON subscription.organization_subscription_id = revision.organization_subscription_id
              JOIN prv_organizations organization
                ON organization.organization_id = subscription.organization_id
              JOIN prv_service_plans current_plan
                ON current_plan.service_plan_id = revision.baseline_service_plan_id
              JOIN prv_service_plans target_plan
                ON target_plan.service_plan_id = revision.target_service_plan_id
              JOIN prv_operators requester ON requester.provider_operator_id = revision.requested_by
              LEFT JOIN prv_operators decider ON decider.provider_operator_id = revision.decided_by
              LEFT JOIN prv_operators publisher ON publisher.provider_operator_id = revision.published_by
            """;

    private final JdbcTemplate jdbc;

    public ProviderCommercialRenewalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SubscriptionRecord> subscription(UUID subscriptionId) {
        return jdbc.query("""
                SELECT subscription.organization_subscription_id,
                       subscription.organization_id,
                       subscription.service_plan_id,
                       plan.plan_key,
                       plan.display_name AS plan_name,
                       subscription.starts_at,
                       subscription.ends_at,
                       subscription.contract_reference,
                       subscription.version,
                       (SELECT COUNT(*) FROM prv_tenants tenant
                         WHERE tenant.organization_id = subscription.organization_id
                           AND tenant.lifecycle_state <> 'RETIRED') AS tenant_count
                  FROM prv_organization_subscriptions subscription
                  JOIN prv_service_plans plan ON plan.service_plan_id = subscription.service_plan_id
                 WHERE subscription.organization_subscription_id = ?
                   AND subscription.lifecycle_state IN ('TRIAL', 'ACTIVE', 'SUSPENDED')
                 FOR UPDATE OF subscription
                """, (result, ignored) -> new SubscriptionRecord(
                        result.getObject("organization_subscription_id", UUID.class),
                        result.getObject("organization_id", UUID.class),
                        result.getObject("service_plan_id", UUID.class),
                        result.getString("plan_key"),
                        result.getString("plan_name"),
                        instant(result, "starts_at"),
                        instant(result, "ends_at"),
                        result.getString("contract_reference"),
                        result.getLong("version"),
                        result.getLong("tenant_count")), subscriptionId).stream().findFirst();
    }

    public Optional<PlanRecord> activePlan(String planKey) {
        return jdbc.query("""
                SELECT service_plan_id, plan_key, display_name, service_tier, plan_version
                  FROM prv_service_plans
                 WHERE LOWER(plan_key) = LOWER(?) AND lifecycle_state = 'ACTIVE'
                """, (result, ignored) -> new PlanRecord(
                        result.getObject("service_plan_id", UUID.class),
                        result.getString("plan_key"),
                        result.getString("display_name"),
                        result.getString("service_tier"),
                        result.getInt("plan_version")), planKey).stream().findFirst();
    }

    public List<String> entitlements(UUID servicePlanId) {
        return jdbc.queryForList("""
                SELECT entitlement.entitlement_key
                  FROM prv_service_plan_entitlements assignment
                  JOIN prv_entitlement_catalog entitlement
                    ON entitlement.entitlement_id = assignment.entitlement_id
                 WHERE assignment.service_plan_id = ?
                 ORDER BY entitlement.entitlement_key
                """, String.class, servicePlanId);
    }

    public Optional<RenewalRecord> byKey(Long requesterId, String requestKey) {
        expire();
        return records("revision.requested_by = ? AND revision.request_key = ?",
                requesterId, requestKey).stream().findFirst();
    }

    public Optional<RenewalRecord> byId(UUID revisionId) {
        expire();
        return records("revision.renewal_revision_id = ?", revisionId).stream().findFirst();
    }

    public UUID create(
            SubscriptionRecord subscription,
            PlanRecord targetPlan,
            Instant proposedEndsAt,
            String proposedContractReference,
            String reason,
            List<String> added,
            List<String> removed,
            String contentSha256,
            String requestFingerprint,
            String requestKey,
            Long requesterId) {
        UUID revisionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO prv_subscription_renewal_revisions (
                    renewal_revision_id, organization_subscription_id, revision_number,
                    baseline_subscription_version, baseline_service_plan_id, baseline_ends_at,
                    baseline_contract_reference, target_service_plan_id, proposed_ends_at,
                    proposed_contract_reference, reason, added_entitlements, removed_entitlements,
                    impacted_tenants, current_entitlement_count, projected_entitlement_count,
                    content_sha256, request_fingerprint, request_key, decision_due_at, requested_by)
                VALUES (?, ?,
                    COALESCE((SELECT MAX(current.revision_number) + 1
                                FROM prv_subscription_renewal_revisions current
                               WHERE current.organization_subscription_id = ?), 1),
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    CURRENT_TIMESTAMP + INTERVAL '48 hours', ?)
                """, revisionId, subscription.subscriptionId(), subscription.subscriptionId(),
                subscription.version(), subscription.servicePlanId(), timestamp(subscription.endsAt()),
                subscription.contractReference(), targetPlan.servicePlanId(), Timestamp.from(proposedEndsAt),
                proposedContractReference, reason,
                added.toArray(String[]::new), removed.toArray(String[]::new),
                subscription.tenantCount(),
                entitlements(subscription.servicePlanId()).size(),
                entitlements(targetPlan.servicePlanId()).size(),
                contentSha256, requestFingerprint, requestKey, requesterId);
        return revisionId;
    }

    public List<ProviderDtos.SubscriptionRenewalRevision> list() {
        expire();
        return jdbc.query(SELECT + " ORDER BY revision.requested_at DESC LIMIT 200", this::mapSummary);
    }

    public ProviderDtos.SubscriptionRenewalRevision summary(UUID revisionId) {
        expire();
        return jdbc.query(
                        SELECT + " WHERE revision.renewal_revision_id = ?",
                        this::mapSummary,
                        revisionId)
                .stream().findFirst().orElseThrow();
    }

    public boolean decide(
            UUID revisionId,
            long version,
            Long reviewerId,
            String decision,
            String reason) {
        return jdbc.update("""
                UPDATE prv_subscription_renewal_revisions
                   SET lifecycle_state = ?, decided_by = ?, decided_at = CURRENT_TIMESTAMP,
                       decision_reason = ?, updated_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE renewal_revision_id = ?
                   AND lifecycle_state = 'PENDING_APPROVAL'
                   AND decision_due_at > CURRENT_TIMESTAMP
                   AND requested_by <> ?
                   AND version = ?
                """, decision, reviewerId, reason, revisionId, reviewerId, version) == 1;
    }

    public boolean publish(UUID revisionId, long version, Long publisherId) {
        int subscriptionChanged = jdbc.update("""
                UPDATE prv_organization_subscriptions subscription
                   SET service_plan_id = revision.target_service_plan_id,
                       ends_at = revision.proposed_ends_at,
                       contract_reference = revision.proposed_contract_reference,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = ?,
                       version = version + 1
                  FROM prv_subscription_renewal_revisions revision
                 WHERE revision.renewal_revision_id = ?
                   AND revision.lifecycle_state = 'APPROVED'
                   AND revision.version = ?
                   AND subscription.organization_subscription_id = revision.organization_subscription_id
                   AND subscription.version = revision.baseline_subscription_version
                """, publisherId, revisionId, version);
        if (subscriptionChanged != 1) return false;
        return jdbc.update("""
                UPDATE prv_subscription_renewal_revisions
                   SET lifecycle_state = 'PUBLISHED',
                       published_by = ?, published_at = CURRENT_TIMESTAMP,
                       execution_state = CASE
                           WHEN cardinality(added_entitlements) + cardinality(removed_entitlements) = 0
                           THEN 'NOT_REQUIRED' ELSE 'MANUAL_ACTION_REQUIRED' END,
                       updated_at = CURRENT_TIMESTAMP,
                       version = version + 1
                 WHERE renewal_revision_id = ? AND lifecycle_state = 'APPROVED' AND version = ?
                """, publisherId, revisionId, version) == 1;
    }

    private List<RenewalRecord> records(String predicate, Object... arguments) {
        String sql = """
                SELECT revision.renewal_revision_id, revision.organization_subscription_id,
                       subscription.organization_id, revision.lifecycle_state,
                       revision.baseline_subscription_version, revision.target_service_plan_id,
                       revision.proposed_ends_at, revision.proposed_contract_reference,
                       revision.reason, revision.added_entitlements, revision.removed_entitlements,
                       revision.content_sha256, revision.request_fingerprint,
                       revision.request_key, revision.requested_by,
                       revision.decision_due_at, revision.version
                  FROM prv_subscription_renewal_revisions revision
                  JOIN prv_organization_subscriptions subscription
                    ON subscription.organization_subscription_id = revision.organization_subscription_id
                 WHERE %s
                """.formatted(predicate);
        return jdbc.query(sql, this::record, arguments);
    }

    private void expire() {
        jdbc.update("""
                UPDATE prv_subscription_renewal_revisions
                   SET lifecycle_state = 'EXPIRED', updated_at = CURRENT_TIMESTAMP, version = version + 1
                 WHERE lifecycle_state = 'PENDING_APPROVAL' AND decision_due_at <= CURRENT_TIMESTAMP
                """);
    }

    private ProviderDtos.SubscriptionRenewalRevision mapSummary(ResultSet result, int ignored)
            throws SQLException {
        return new ProviderDtos.SubscriptionRenewalRevision(
                result.getObject("renewal_revision_id", UUID.class),
                result.getObject("organization_subscription_id", UUID.class),
                result.getObject("organization_id", UUID.class),
                result.getString("organization_key"), result.getString("organization_name"),
                result.getInt("revision_number"), result.getString("lifecycle_state"),
                result.getLong("baseline_subscription_version"),
                result.getString("current_plan_key"), result.getString("current_plan_name"),
                result.getString("target_plan_key"), result.getString("target_plan_name"),
                result.getString("target_service_tier"),
                instant(result, "current_ends_at"), instant(result, "proposed_ends_at"),
                result.getString("current_contract_reference"),
                result.getString("proposed_contract_reference"), result.getString("reason"),
                strings(result, "added_entitlements"), strings(result, "removed_entitlements"),
                result.getLong("impacted_tenants"), result.getLong("current_entitlement_count"),
                result.getLong("projected_entitlement_count"), result.getString("content_sha256"),
                result.getString("request_key"), result.getLong("requested_by"),
                result.getString("requested_by_name"), instant(result, "requested_at"),
                instant(result, "decision_due_at"), nullableLong(result, "decided_by"),
                result.getString("decided_by_name"), instant(result, "decided_at"),
                result.getString("decision_reason"), nullableLong(result, "published_by"),
                result.getString("published_by_name"), instant(result, "published_at"),
                result.getString("execution_state"),
                result.getString("notification_state"), result.getLong("version"));
    }

    private RenewalRecord record(ResultSet result, int ignored) throws SQLException {
        return new RenewalRecord(
                result.getObject("renewal_revision_id", UUID.class),
                result.getObject("organization_subscription_id", UUID.class),
                result.getObject("organization_id", UUID.class), result.getString("lifecycle_state"),
                result.getLong("baseline_subscription_version"),
                result.getObject("target_service_plan_id", UUID.class),
                instant(result, "proposed_ends_at"), result.getString("proposed_contract_reference"),
                result.getString("reason"), strings(result, "added_entitlements"),
                strings(result, "removed_entitlements"), result.getString("content_sha256"),
                result.getString("request_fingerprint"), result.getString("request_key"),
                result.getLong("requested_by"),
                instant(result, "decision_due_at"), result.getLong("version"));
    }

    private List<String> strings(ResultSet result, String column) throws SQLException {
        return List.of((String[]) result.getArray(column).getArray());
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Long nullableLong(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    public record SubscriptionRecord(
            UUID subscriptionId, UUID organizationId, UUID servicePlanId, String planKey,
            String planName, Instant startsAt, Instant endsAt, String contractReference,
            long version, long tenantCount) {
    }

    public record PlanRecord(
            UUID servicePlanId, String planKey, String planName, String serviceTier, int planVersion) {
    }

    public record RenewalRecord(
            UUID revisionId, UUID subscriptionId, UUID organizationId, String lifecycleState,
            long baselineSubscriptionVersion, UUID targetServicePlanId, Instant proposedEndsAt,
            String proposedContractReference, String reason, List<String> addedEntitlements,
            List<String> removedEntitlements, String contentSha256, String requestFingerprint,
            String requestKey,
            Long requestedBy, Instant decisionDueAt, long version) {
    }
}
