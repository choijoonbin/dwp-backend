package com.dwp.services.platform.workplace.workplaceassistant;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.AuditRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.CommandRow;
import com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantRepository.FeedbackRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.*;

@Repository
public class WorkplaceAssistantAuditRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WorkplaceAssistantAuditRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void lockCommand(
            long tenantId, long actorId, String commandType, String idempotencyKey) {
        String lockName = tenantId + ":" + actorId + ":" + commandType + ":" + idempotencyKey;
        jdbc.query("""
                SELECT pg_advisory_xact_lock(hashtextextended(:lockName, 0))
                """, new MapSqlParameterSource("lockName", lockName), resultSet -> {
            if (resultSet.next()) {
                resultSet.getObject(1);
            }
            return null;
        });
    }

    public void lockConfirmationRequest(long tenantId, UUID requestId) {
        String lockName = tenantId + ":assistant-confirm:" + requestId;
        jdbc.query("""
                SELECT pg_advisory_xact_lock(hashtextextended(:lockName, 0))
                """, new MapSqlParameterSource("lockName", lockName), resultSet -> {
            if (resultSet.next()) {
                resultSet.getObject(1);
            }
            return null;
        });
    }

    public boolean lockLiveAssistantRequest(
            long tenantId, long actorId, UUID requestId, OffsetDateTime now) {
        return jdbc.query("""
                SELECT retention_deleted_at IS NULL AND retention_expires_at > :now
                  FROM wp_assistant_requests
                 WHERE tenant_id = :tenantId AND actor_user_id = :actorId
                   AND request_id = :requestId
                 FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("requestId", requestId)
                .addValue("now", now), (resultSet, ignored) -> resultSet.getBoolean(1))
                .stream().findFirst().orElse(false);
    }

    public Optional<ActiveConfirmation> activeConfirmation(long tenantId, UUID requestId) {
        return jdbc.query("""
                SELECT command_id, tenant_id, actor_user_id, request_id, command_type,
                       idempotency_key, request_fingerprint, command_state, status_href,
                       reason, correlation_id, result_code, accepted_at, completed_at,
                       execution_lease_until
                  FROM wp_assistant_commands
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                   AND command_type = 'CONFIRM_REQUEST' AND command_state = 'ACCEPTED'
                 ORDER BY accepted_at, command_id
                 LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("requestId", requestId), (resultSet, ignored) ->
                        new ActiveConfirmation(commandRow(resultSet, ignored),
                                resultSet.getObject(
                                        "execution_lease_until", OffsetDateTime.class)))
                .stream().findFirst();
    }

    public boolean expireConfirmationClaim(
            long tenantId, UUID commandId, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_assistant_commands
                   SET command_state = 'RESULT_UNKNOWN',
                       result_code = 'EXECUTION_LEASE_EXPIRED',
                       completed_at = :now,
                       execution_claim_token = NULL,
                       execution_lease_until = NULL
                 WHERE tenant_id = :tenantId AND command_id = :commandId
                   AND command_type = 'CONFIRM_REQUEST'
                   AND command_state = 'ACCEPTED'
                   AND execution_lease_until <= :now
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("commandId", commandId)
                .addValue("now", now)) == 1;
    }

    public Optional<CommandRow> command(
            long tenantId, long actorId, String commandType, String idempotencyKey) {
        return jdbc.query("""
                SELECT command_id, tenant_id, actor_user_id, request_id, command_type,
                       idempotency_key, request_fingerprint, command_state, status_href,
                       reason, correlation_id, result_code, accepted_at, completed_at
                  FROM wp_assistant_commands
                 WHERE tenant_id = :tenantId AND actor_user_id = :actorId
                   AND command_type = :commandType AND idempotency_key = :idempotencyKey
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("commandType", commandType)
                .addValue("idempotencyKey", idempotencyKey), this::commandRow)
                .stream().findFirst();
    }

    public void createCommand(CommandRow row) {
        jdbc.update("""
                INSERT INTO wp_assistant_commands(
                    command_id, tenant_id, actor_user_id, request_id, command_type,
                    idempotency_key, request_fingerprint, command_state, status_href,
                    reason, correlation_id, result_code, accepted_at, completed_at)
                VALUES (:commandId, :tenantId, :actorId, :requestId, :commandType,
                        :idempotencyKey, :requestFingerprint, :commandState, :statusHref,
                        :reason, :correlationId, :resultCode, :acceptedAt, :completedAt)
                """, new MapSqlParameterSource()
                .addValue("commandId", row.commandId())
                .addValue("tenantId", row.tenantId())
                .addValue("actorId", row.actorUserId())
                .addValue("requestId", row.requestId())
                .addValue("commandType", row.commandType())
                .addValue("idempotencyKey", row.idempotencyKey())
                .addValue("requestFingerprint", row.requestFingerprint())
                .addValue("commandState", row.state().name())
                .addValue("statusHref", row.statusHref())
                .addValue("reason", row.reason())
                .addValue("correlationId", row.correlationId())
                .addValue("resultCode", row.resultCode())
                .addValue("acceptedAt", row.acceptedAt())
                .addValue("completedAt", row.completedAt()));
    }

    public void attachCommandRequest(long tenantId, UUID commandId, UUID requestId) {
        jdbc.update("""
                UPDATE wp_assistant_commands
                   SET request_id = :requestId
                 WHERE tenant_id = :tenantId AND command_id = :commandId
                   AND request_id IS NULL
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("commandId", commandId)
                .addValue("requestId", requestId));
    }

    public boolean claimExecution(
            long tenantId, UUID commandId, UUID claimToken,
            OffsetDateTime now, OffsetDateTime leaseUntil) {
        return jdbc.update("""
                UPDATE wp_assistant_commands
                   SET execution_claim_token = :claimToken,
                       execution_lease_until = :leaseUntil
                 WHERE tenant_id = :tenantId AND command_id = :commandId
                   AND command_type = 'CONFIRM_REQUEST'
                   AND command_state = 'ACCEPTED'
                   AND (execution_lease_until IS NULL OR execution_lease_until <= :now)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("commandId", commandId)
                .addValue("claimToken", claimToken)
                .addValue("now", now)
                .addValue("leaseUntil", leaseUntil)) == 1;
    }

    public void finishCommand(long tenantId, UUID commandId, CommandState state,
                              String resultCode, OffsetDateTime now) {
        jdbc.update("""
                UPDATE wp_assistant_commands
                   SET command_state = :state, result_code = :resultCode,
                       completed_at = :now,
                       execution_claim_token = NULL,
                       execution_lease_until = NULL
                 WHERE tenant_id = :tenantId AND command_id = :commandId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("commandId", commandId)
                .addValue("state", state.name())
                .addValue("resultCode", resultCode)
                .addValue("now", now));
    }

    public boolean finishClaimedCommand(
            long tenantId, UUID commandId, UUID claimToken, CommandState state,
            String resultCode, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_assistant_commands
                   SET command_state = :state, result_code = :resultCode,
                       completed_at = :now,
                       execution_claim_token = NULL,
                       execution_lease_until = NULL
                 WHERE tenant_id = :tenantId AND command_id = :commandId
                   AND command_state = 'ACCEPTED'
                   AND execution_claim_token = :claimToken
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("commandId", commandId)
                .addValue("claimToken", claimToken)
                .addValue("state", state.name())
                .addValue("resultCode", resultCode)
                .addValue("now", now)) == 1;
    }

    public Optional<FeedbackRow> feedback(long tenantId, UUID requestId, long actorId) {
        return jdbc.query("""
                SELECT feedback_id, tenant_id, request_id, actor_user_id, rating,
                       redacted_comment, eligible_for_model_improvement,
                       audit_event_id, created_at
                  FROM wp_assistant_feedback
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                   AND actor_user_id = :actorId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("requestId", requestId)
                .addValue("actorId", actorId), this::feedbackRow).stream().findFirst();
    }

    public void createFeedback(FeedbackRow row) {
        jdbc.update("""
                INSERT INTO wp_assistant_feedback(
                    feedback_id, tenant_id, request_id, actor_user_id, rating,
                    redacted_comment, eligible_for_model_improvement,
                    audit_event_id, created_at)
                VALUES (:feedbackId, :tenantId, :requestId, :actorId, :rating,
                        :redactedComment, :eligible, :auditEventId, :createdAt)
                """, new MapSqlParameterSource()
                .addValue("feedbackId", row.feedbackId())
                .addValue("tenantId", row.tenantId())
                .addValue("requestId", row.requestId())
                .addValue("actorId", row.actorUserId())
                .addValue("rating", row.rating().name())
                .addValue("redactedComment", row.redactedComment())
                .addValue("eligible", row.eligibleForModelImprovement())
                .addValue("auditEventId", row.auditEventId())
                .addValue("createdAt", row.createdAt()));
    }

    @Transactional
    public void auditAndOutbox(long tenantId, long actorId, UUID requestId,
                               String eventType, JsonNode metadata, String correlationId,
                               long aggregateVersion, OffsetDateTime now, UUID auditEventId) {
        jdbc.update("""
                INSERT INTO wp_assistant_audit_events(
                    audit_event_id, tenant_id, request_id, actor_user_id, event_type,
                    metadata, correlation_id, created_at)
                VALUES (:auditEventId, :tenantId, :requestId, :actorId, :eventType,
                        CAST(:metadata AS jsonb), :correlationId, :now)
                """, new MapSqlParameterSource()
                .addValue("auditEventId", auditEventId)
                .addValue("tenantId", tenantId)
                .addValue("requestId", requestId)
                .addValue("actorId", actorId)
                .addValue("eventType", eventType)
                .addValue("metadata", json(metadata))
                .addValue("correlationId", correlationId)
                .addValue("now", now));
        jdbc.update("""
                INSERT INTO wp_assistant_outbox(
                    outbox_id, tenant_id, aggregate_type, aggregate_id,
                    aggregate_version, event_type, payload, correlation_id, created_at)
                VALUES (:outboxId, :tenantId, :aggregateType, :aggregateId,
                        :aggregateVersion, :eventType, CAST(:payload AS jsonb),
                        :correlationId, :now)
                """, new MapSqlParameterSource()
                .addValue("outboxId", UUID.randomUUID())
                .addValue("tenantId", tenantId)
                .addValue("aggregateType", requestId == null
                        ? "ASSISTANT_GOVERNANCE" : "ASSISTANT_REQUEST")
                .addValue("aggregateId", requestId == null
                        ? deterministicGovernanceId(tenantId) : requestId)
                .addValue("aggregateVersion", Math.max(1, aggregateVersion))
                .addValue("eventType", eventType)
                .addValue("payload", json(metadata))
                .addValue("correlationId", correlationId)
                .addValue("now", now));
    }

    public List<AuditRow> auditEvents(long tenantId, UUID requestId, int limit) {
        String requestFilter = requestId == null ? "" : " AND request_id = :requestId";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", limit);
        if (requestId != null) parameters.addValue("requestId", requestId);
        return jdbc.query("""
                SELECT audit_event_id, tenant_id, request_id, actor_user_id,
                       event_type, metadata, correlation_id, created_at
                  FROM wp_assistant_audit_events
                 WHERE tenant_id = :tenantId
                """ + requestFilter + """
                 ORDER BY created_at DESC, audit_event_id DESC
                 LIMIT :limit
                """, parameters, this::auditRow);
    }

    public int redactExpired(OffsetDateTime now) {
        jdbc.query("""
                SELECT request.request_id
                  FROM wp_assistant_requests request
                 WHERE request.retention_expires_at <= :now
                   AND (request.retention_deleted_at IS NULL
                        OR EXISTS (
                            SELECT 1 FROM wp_assistant_feedback feedback
                             WHERE feedback.tenant_id = request.tenant_id
                               AND feedback.request_id = request.request_id
                               AND (feedback.redacted_comment IS NOT NULL
                                    OR feedback.eligible_for_model_improvement))
                        OR EXISTS (
                            SELECT 1 FROM wp_assistant_commands command
                             WHERE command.tenant_id = request.tenant_id
                               AND command.request_id = request.request_id
                               AND command.reason <> 'RETAINED_CONTENT_DELETED')
                        OR EXISTS (
                            SELECT 1 FROM wp_assistant_proposal_items proposal
                             WHERE proposal.tenant_id = request.tenant_id
                               AND proposal.request_id = request.request_id))
                 FOR UPDATE OF request
                """, new MapSqlParameterSource("now", now), resultSet -> {
            while (resultSet.next()) {
                resultSet.getObject(1);
            }
            return null;
        });
        jdbc.update("""
                DELETE FROM wp_assistant_proposal_items proposal
                 USING wp_assistant_requests request
                 WHERE proposal.tenant_id = request.tenant_id
                   AND proposal.request_id = request.request_id
                   AND request.retention_expires_at <= :now
                """, new MapSqlParameterSource("now", now));
        jdbc.update("""
                UPDATE wp_assistant_feedback feedback
                   SET redacted_comment = NULL,
                       eligible_for_model_improvement = FALSE
                  FROM wp_assistant_requests request
                 WHERE feedback.tenant_id = request.tenant_id
                   AND feedback.request_id = request.request_id
                   AND request.retention_expires_at <= :now
                """, new MapSqlParameterSource("now", now));
        jdbc.update("""
                UPDATE wp_assistant_commands command
                   SET reason = 'RETAINED_CONTENT_DELETED'
                  FROM wp_assistant_requests request
                 WHERE command.tenant_id = request.tenant_id
                   AND command.request_id = request.request_id
                   AND request.retention_expires_at <= :now
                """, new MapSqlParameterSource("now", now));
        return jdbc.update("""
                UPDATE wp_assistant_requests
                   SET redacted_request_text = NULL,
                       redaction_state = 'RETAINED_CONTENT_DELETED',
                       structured_proposal = '[]'::jsonb,
                       validation_snapshot = NULL,
                       limitations = '[]'::jsonb,
                       retention_deleted_at = :now,
                       version = version + 1,
                       updated_at = :now
                 WHERE retention_deleted_at IS NULL
                   AND retention_expires_at <= :now
                """, new MapSqlParameterSource("now", now));
    }

    private CommandRow commandRow(ResultSet rs, int ignored) throws SQLException {
        return new CommandRow(
                rs.getObject("command_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("actor_user_id"), rs.getObject("request_id", UUID.class),
                rs.getString("command_type"), rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"),
                CommandState.valueOf(rs.getString("command_state")),
                rs.getString("status_href"), rs.getString("reason"),
                rs.getString("correlation_id"), rs.getString("result_code"),
                rs.getObject("accepted_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class));
    }

    private FeedbackRow feedbackRow(ResultSet rs, int ignored) throws SQLException {
        return new FeedbackRow(
                rs.getObject("feedback_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("request_id", UUID.class), rs.getLong("actor_user_id"),
                FeedbackRating.valueOf(rs.getString("rating")),
                rs.getString("redacted_comment"),
                rs.getBoolean("eligible_for_model_improvement"),
                rs.getObject("audit_event_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private AuditRow auditRow(ResultSet rs, int ignored) throws SQLException {
        return new AuditRow(
                rs.getObject("audit_event_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("request_id", UUID.class), rs.getLong("actor_user_id"),
                rs.getString("event_type"), tree(rs.getString("metadata")),
                rs.getString("correlation_id"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private JsonNode tree(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "Stored Workplace Assistant JSON is unreadable.");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "Workplace Assistant data could not be serialized.");
        }
    }

    private static UUID deterministicGovernanceId(long tenantId) {
        return UUID.nameUUIDFromBytes(
                ("workplace-assistant-governance:" + tenantId)
                        .getBytes(StandardCharsets.UTF_8));
    }

    public record ActiveConfirmation(
            CommandRow command, OffsetDateTime leaseUntil) { }
}
