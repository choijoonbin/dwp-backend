package com.dwp.services.time.absence.employment;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.UUID;

/** Exact TIM-003 deferred-row activator used by terminal gap backfill. */
public final class JdbcEmploymentAbsenceDeferredInboxActivator
        implements EmploymentAbsenceDeferredInboxActivator {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcEmploymentAbsenceDeferredInboxActivator(
            NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void activateExact(
            long tenantId,
            UUID relationshipPublicId,
            long eventSequence) {
        EmploymentAbsenceContract.positive(tenantId, "tenantId");
        if (relationshipPublicId == null || eventSequence <= 0) {
            throw new IllegalArgumentException("deferred activation tuple is invalid");
        }
        int changed = jdbc.update("""
                UPDATE sys_domain_event_inbox
                   SET available_at = CURRENT_TIMESTAMP,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE consumer_name = :consumerName
                   AND tenant_id = :tenantId
                   AND event_source = :eventSource
                   AND event_type = :eventType
                   AND aggregate_type = :aggregateType
                   AND aggregate_id = :aggregateId
                   AND aggregate_sequence = :eventSequence
                   AND status = 'DEFERRED'
                """, new MapSqlParameterSource()
                .addValue("consumerName", EmploymentAbsenceContract.CONSUMER)
                .addValue("tenantId", tenantId)
                .addValue("eventSource", EmploymentAbsenceContract.EVENT_SOURCE)
                .addValue("eventType", EmploymentAbsenceContract.EVENT_TYPE)
                .addValue("aggregateType", EmploymentAbsenceContract.AGGREGATE_TYPE)
                .addValue("aggregateId", relationshipPublicId.toString())
                .addValue("eventSequence", eventSequence));
        if (changed != 1) {
            throw new IllegalStateException(
                    "terminal gap backfill must activate exactly one deferred row");
        }
    }
}
