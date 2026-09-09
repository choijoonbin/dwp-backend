package com.dwp.services.notification.domain;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class NotificationPolicyDraftRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public NotificationPolicyDraftRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean withdraw(
            long tenantId,
            long authorId,
            UUID policyId,
            long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE ntf_routing_policies
                   SET state = 'RETIRED'
                 WHERE tenant_id = :tenantId
                   AND policy_id = :policyId
                   AND version = :expectedVersion
                   AND state = 'DRAFT'
                   AND created_by = :actorId
                """, params(tenantId, authorId, policyId, expectedVersion));
        if (updated == 1) {
            appendOutbox(
                    tenantId,
                    policyId,
                    "notification.policy.draft-withdrawn",
                    "notification-policy-withdrawn:" + policyId);
        }
        return updated == 1;
    }

    public boolean reject(
            long tenantId,
            long reviewerId,
            UUID policyId,
            long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE ntf_routing_policies
                   SET state = 'RETIRED'
                 WHERE tenant_id = :tenantId
                   AND policy_id = :policyId
                   AND version = :expectedVersion
                   AND state = 'DRAFT'
                   AND created_by IS NOT NULL
                   AND created_by <> :actorId
                """, params(tenantId, reviewerId, policyId, expectedVersion));
        if (updated == 1) {
            appendOutbox(
                    tenantId,
                    policyId,
                    "notification.policy.draft-rejected",
                    "notification-policy-rejected:" + policyId);
        }
        return updated == 1;
    }

    private MapSqlParameterSource params(
            long tenantId,
            long actorId,
            UUID policyId,
            long expectedVersion) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("policyId", policyId)
                .addValue("expectedVersion", expectedVersion);
    }

    private void appendOutbox(
            long tenantId,
            UUID policyId,
            String eventType,
            String eventKey) {
        jdbc.update("""
                INSERT INTO ntf_outbox_events (
                    outbox_id, tenant_id, aggregate_type, aggregate_id,
                    event_type, event_key, payload, occurred_at)
                VALUES (
                    :outboxId, :tenantId, 'NOTIFICATION_POLICY', :aggregateId,
                    :eventType, :eventKey,
                    jsonb_build_object('policyId', CAST(:aggregateId AS text)),
                    CURRENT_TIMESTAMP)
                ON CONFLICT (tenant_id, event_key) DO NOTHING
                """, new MapSqlParameterSource()
                .addValue("outboxId", UUID.randomUUID())
                .addValue("tenantId", tenantId)
                .addValue("aggregateId", policyId.toString())
                .addValue("eventType", eventType)
                .addValue("eventKey", eventKey));
    }
}
