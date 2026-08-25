package com.dwp.services.auth.service;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Records the Identity-owned assigned-review lifecycle in the shared transactional
 * outbox. Events contain only opaque queue projection data; review evidence and
 * decisions remain in Auth.
 */
@Component
public class AccessReviewWorkItemOutboxPublisher {

    public static final String ASSIGNED = "identity.access-review.item.assigned.v1";
    public static final String DECIDED = "identity.access-review.item.decided.v1";
    public static final String REVOKED = "identity.access-review.item.revoked.v1";

    private static final String SOURCE = "urn:dwp:auth:access-review";
    private static final String AGGREGATE = "ACCESS_REVIEW_WORK_ITEM";

    private final JdbcTemplate jdbc;
    private final DomainEventRecorder recorder;
    private final ObjectMapper objectMapper;

    public AccessReviewWorkItemOutboxPublisher(
            JdbcTemplate jdbc,
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.recorder = recorder;
        this.objectMapper = objectMapper;
        List.of(ASSIGNED, DECIDED, REVOKED)
                .forEach(type -> contracts.register(type, 1, 1));
    }

    /** Records one assignment event per item, in the campaign activation transaction. */
    @Transactional
    public void assignedForCampaign(Long tenantId, UUID campaignId, String correlationId) {
        assignments(tenantId, campaignId).forEach(item -> {
            requireSequence(item, 0L, ASSIGNED);
            long sequence = advance(tenantId, item);
            record(
                    ASSIGNED,
                    tenantId,
                    item,
                    sequence,
                    correlationId,
                    "PENDING",
                    "ACTIVE");
        });
    }

    /** Records an owner decision after the optimistic item update succeeds. */
    @Transactional
    public void decided(
            Long tenantId,
            UUID workItemRef,
            String correlationId,
            String decision,
            long resultingVersion) {
        Projection item = projection(tenantId, workItemRef);
        emitDecided(tenantId, item, correlationId, decision, resultingVersion);
    }

    @Transactional
    public void decidedByInternalId(
            Long tenantId,
            UUID campaignId,
            UUID itemId,
            String correlationId,
            String decision,
            long resultingVersion) {
        Projection item = projection(tenantId, campaignId, itemId);
        emitDecided(tenantId, item, correlationId, decision, resultingVersion);
    }

    private void emitDecided(
            Long tenantId,
            Projection item,
            String correlationId,
            String decision,
            long resultingVersion) {
        requireVersion(item, resultingVersion, DECIDED);
        if (!decision.equals(item.decision()) || !List.of("APPROVE", "REVOKE").contains(decision)) {
            throw invalid(DECIDED, "persisted decision does not match the event");
        }
        requireSequence(
                item,
                "REVOKED".equals(item.assignmentState()) ? 2L : 1L,
                DECIDED);
        long sequence = advance(tenantId, item);
        record(
                DECIDED,
                tenantId,
                item,
                sequence,
                correlationId,
                decision,
                item.assignmentState());
    }

    /** Records explicit removal of a named-reviewer relationship. */
    @Transactional
    public void revoked(
            Long tenantId,
            UUID workItemRef,
            String correlationId,
            long resultingVersion) {
        Projection item = projection(tenantId, workItemRef);
        requireVersion(item, resultingVersion, REVOKED);
        if (!"REVOKED".equals(item.assignmentState())) {
            throw invalid(REVOKED, "persisted assignment is not revoked");
        }
        requireSequence(item, "PENDING".equals(item.decision()) ? 1L : 2L, REVOKED);
        long sequence = advance(tenantId, item);
        record(
                REVOKED,
                tenantId,
                item,
                sequence,
                correlationId,
                item.decision(),
                "REVOKED");
    }

    private List<Projection> assignments(Long tenantId, UUID campaignId) {
        return jdbc.query(SELECT_PROJECTION + """
                 WHERE item.tenant_id = ?
                   AND item.access_review_campaign_id = ?
                   AND campaign.reviewer_strategy = 'NAMED_REVIEWER'
                   AND campaign.lifecycle_state = 'ACTIVE'
                   AND item.reviewer_user_id IS NOT NULL
                   AND item.reviewer_assignment_state = 'ACTIVE'
                   AND item.decision = 'PENDING'
                   AND item.work_event_sequence = 0
                   FOR UPDATE OF item
                """, this::projection, tenantId, campaignId);
    }

    private Projection projection(Long tenantId, UUID workItemRef) {
        return jdbc.query(SELECT_PROJECTION + """
                 WHERE item.tenant_id = ? AND item.work_item_ref = ?
                   AND campaign.reviewer_strategy = 'NAMED_REVIEWER'
                   AND item.reviewer_user_id IS NOT NULL
                   FOR UPDATE OF item
                """, this::projection, tenantId, workItemRef).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Access-review work event lost its owner projection."));
    }

    private Projection projection(Long tenantId, UUID campaignId, UUID itemId) {
        return jdbc.query(SELECT_PROJECTION + """
                 WHERE item.tenant_id = ?
                   AND item.access_review_campaign_id = ?
                   AND item.access_review_item_id = ?
                   AND campaign.reviewer_strategy = 'NAMED_REVIEWER'
                   AND item.reviewer_user_id IS NOT NULL
                   FOR UPDATE OF item
                """, this::projection, tenantId, campaignId, itemId).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Access-review work event lost its owner projection."));
    }

    private long advance(Long tenantId, Projection item) {
        long sequence;
        try {
            sequence = Math.addExact(item.workEventSequence(), 1L);
        } catch (ArithmeticException exception) {
            throw invalid("work-item", "event sequence overflow");
        }
        int updated = jdbc.update("""
                UPDATE com_access_review_items
                   SET work_event_sequence = ?
                 WHERE tenant_id = ? AND work_item_ref = ?
                   AND version = ? AND work_event_sequence = ?
                """, sequence, tenantId, item.workItemRef(),
                item.version(), item.workEventSequence());
        if (updated != 1) {
            throw invalid("work-item", "event sequence changed concurrently");
        }
        return sequence;
    }

    private void requireVersion(Projection item, long expected, String type) {
        if (expected < 0 || item.version() != expected) {
            throw invalid(type, "persisted object version does not match resultingVersion");
        }
    }

    private void requireSequence(Projection item, long expected, String type) {
        if (item.workEventSequence() != expected) {
            throw invalid(type, "event lifecycle is not contiguous");
        }
    }

    private IllegalStateException invalid(String type, String reason) {
        return new IllegalStateException("Invalid " + type + " access-review work event: " + reason + '.');
    }

    private void record(
            String type,
            Long tenantId,
            Projection item,
            long sequence,
            String correlationId,
            String decision,
            String assignmentState) {
        ObjectNode data = objectMapper.createObjectNode()
                .put("workItemRef", item.workItemRef().toString())
                .put("reviewerUserId", item.reviewerUserId())
                .put("dueAt", item.dueAt().toString())
                .put("decision", decision)
                .put("assignmentState", assignmentState)
                .put("objectVersion", item.version());
        String resolvedCorrelation = correlationId == null || correlationId.isBlank()
                ? "access-review:" + item.workItemRef() + ':' + sequence
                : correlationId.strip();
        recorder.record(DomainEventEnvelope.create(
                SOURCE,
                type,
                1,
                tenantId,
                AGGREGATE,
                item.workItemRef().toString(),
                sequence,
                resolvedCorrelation,
                null,
                null,
                data));
    }

    private Projection projection(java.sql.ResultSet result, int ignored)
            throws java.sql.SQLException {
        return new Projection(
                result.getObject("work_item_ref", UUID.class),
                result.getLong("reviewer_user_id"),
                instant(result, "due_at"),
                result.getString("decision"),
                result.getString("reviewer_assignment_state"),
                result.getLong("version"),
                result.getLong("work_event_sequence"));
    }

    private Instant instant(java.sql.ResultSet result, String column)
            throws java.sql.SQLException {
        Timestamp value = result.getTimestamp(column);
        if (value == null) {
            throw new IllegalStateException("Access-review work event is missing " + column + '.');
        }
        return value.toInstant();
    }

    private static final String SELECT_PROJECTION = """
            SELECT item.work_item_ref, item.reviewer_user_id, campaign.due_at,
                   item.decision, item.reviewer_assignment_state, item.version,
                   item.work_event_sequence
              FROM com_access_review_items item
              JOIN com_access_review_campaigns campaign
                ON campaign.tenant_id = item.tenant_id
               AND campaign.access_review_campaign_id = item.access_review_campaign_id
            """;

    private record Projection(
            UUID workItemRef,
            Long reviewerUserId,
            Instant dueAt,
            String decision,
            String assignmentState,
            long version,
            long workEventSequence) {
    }
}
