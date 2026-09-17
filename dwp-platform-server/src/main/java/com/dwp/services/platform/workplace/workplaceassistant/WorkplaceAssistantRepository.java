package com.dwp.services.platform.workplace.workplaceassistant;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.workplaceassistant.WorkplaceAssistantDtos.*;

@Repository
public class WorkplaceAssistantRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public WorkplaceAssistantRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Optional<GovernanceRow> governance(long tenantId) {
        return jdbc.query("""
                SELECT tenant_id, tenant_opt_in, kill_switch, model_provider_reference,
                       model_version, prompt_version, tool_version, retention_days,
                       feedback_use_enabled, redaction_state, version, updated_at, updated_by
                  FROM wp_assistant_governance
                 WHERE tenant_id = :tenantId
                """, new MapSqlParameterSource("tenantId", tenantId), this::governanceRow)
                .stream().findFirst();
    }

    public boolean createGovernance(long tenantId, long actorId,
                                    GovernanceUpdateRequest request, OffsetDateTime now) {
        return jdbc.update("""
                INSERT INTO wp_assistant_governance(
                    tenant_id, tenant_opt_in, kill_switch, model_provider_reference,
                    model_version, prompt_version, tool_version, retention_days,
                    feedback_use_enabled, redaction_state, version, updated_at, updated_by)
                VALUES (:tenantId, :tenantOptIn, :killSwitch, :providerReference,
                        :modelVersion, :promptVersion, :toolVersion, :retentionDays,
                        :feedbackUseEnabled, :redactionState, 1, :now, :actorId)
                ON CONFLICT (tenant_id) DO NOTHING
                """, governanceParameters(tenantId, actorId, request, now)) == 1;
    }

    public boolean updateGovernance(long tenantId, long actorId,
                                    GovernanceUpdateRequest request, OffsetDateTime now) {
        MapSqlParameterSource parameters = governanceParameters(tenantId, actorId, request, now)
                .addValue("expectedVersion", request.expectedVersion());
        return jdbc.update("""
                UPDATE wp_assistant_governance
                   SET tenant_opt_in = :tenantOptIn,
                       kill_switch = :killSwitch,
                       model_provider_reference = :providerReference,
                       model_version = :modelVersion,
                       prompt_version = :promptVersion,
                       tool_version = :toolVersion,
                       retention_days = :retentionDays,
                       feedback_use_enabled = :feedbackUseEnabled,
                       redaction_state = :redactionState,
                       version = version + 1,
                       updated_at = :now,
                       updated_by = :actorId
                 WHERE tenant_id = :tenantId
                   AND version = :expectedVersion
                """, parameters) == 1;
    }

    public Optional<RequestRow> request(long tenantId, long actorId, UUID requestId) {
        return jdbc.query(REQUEST_SELECT + """
                 WHERE tenant_id = :tenantId
                   AND actor_user_id = :actorId
                   AND request_id = :requestId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("requestId", requestId), this::requestRow).stream().findFirst();
    }

    public Optional<RequestRow> requestForUpdate(
            long tenantId, long actorId, UUID requestId) {
        return jdbc.query(REQUEST_SELECT + """
                 WHERE tenant_id = :tenantId
                   AND actor_user_id = :actorId
                   AND request_id = :requestId
                 FOR UPDATE
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("requestId", requestId), this::requestRow).stream().findFirst();
    }

    public Optional<RequestRow> requestByIdempotency(
            long tenantId, long actorId, String idempotencyKey) {
        return jdbc.query(REQUEST_SELECT + """
                 WHERE tenant_id = :tenantId
                   AND actor_user_id = :actorId
                   AND idempotency_key = :idempotencyKey
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("idempotencyKey", idempotencyKey), this::requestRow)
                .stream().findFirst();
    }

    public void createRequest(RequestRow row) {
        jdbc.update("""
                INSERT INTO wp_assistant_requests(
                    request_id, tenant_id, actor_user_id, request_state,
                    redacted_request_text, redaction_state, request_processing_consent,
                    feedback_use_consent, model_provider_reference, model_version,
                    prompt_version, tool_version, structured_proposal, validation_snapshot,
                    booking_intent_id, booking_batch_id, requery_required, last_result_code,
                    limitations, idempotency_key, request_fingerprint, correlation_id,
                    version, retention_expires_at, created_at, updated_at)
                VALUES (:requestId, :tenantId, :actorId, :state,
                        :redactedRequestText, :redactionState, :requestProcessingConsent,
                        :feedbackUseConsent, :providerReference, :modelVersion,
                        :promptVersion, :toolVersion, CAST(:structuredProposal AS jsonb), NULL,
                        NULL, NULL, FALSE, NULL, CAST(:limitations AS jsonb),
                        :idempotencyKey, :requestFingerprint, :correlationId,
                        :version, :retentionExpiresAt, :createdAt, :updatedAt)
                """, new MapSqlParameterSource()
                .addValue("requestId", row.requestId())
                .addValue("tenantId", row.tenantId())
                .addValue("actorId", row.actorUserId())
                .addValue("state", row.state().name())
                .addValue("redactedRequestText", row.redactedRequestText())
                .addValue("redactionState", row.redactionState().name())
                .addValue("requestProcessingConsent", row.requestProcessingConsent())
                .addValue("feedbackUseConsent", row.feedbackUseConsent())
                .addValue("providerReference", row.providerReference())
                .addValue("modelVersion", row.modelVersion())
                .addValue("promptVersion", row.promptVersion())
                .addValue("toolVersion", row.toolVersion())
                .addValue("structuredProposal", json(row.structuredProposal()))
                .addValue("limitations", json(row.limitations()))
                .addValue("idempotencyKey", row.idempotencyKey())
                .addValue("requestFingerprint", row.requestFingerprint())
                .addValue("correlationId", row.correlationId())
                .addValue("version", row.version())
                .addValue("retentionExpiresAt", row.retentionExpiresAt())
                .addValue("createdAt", row.createdAt())
                .addValue("updatedAt", row.updatedAt()));
    }

    public void createProposal(ProposalRow row) {
        jdbc.update("""
                INSERT INTO wp_assistant_proposal_items(
                    proposal_item_id, tenant_id, request_id, sort_order, client_item_key,
                    requested_item, rationale, constraints_used, exclusions, policy_result,
                    conflicts, alternatives, version, created_at, updated_at)
                VALUES (:proposalItemId, :tenantId, :requestId, :sortOrder, :clientItemKey,
                        CAST(:requestedItem AS jsonb), :rationale,
                        CAST(:constraintsUsed AS jsonb), CAST(:exclusions AS jsonb),
                        :policyResult, CAST(:conflicts AS jsonb), CAST(:alternatives AS jsonb),
                        :version, :createdAt, :updatedAt)
                """, new MapSqlParameterSource()
                .addValue("proposalItemId", row.proposalItemId())
                .addValue("tenantId", row.tenantId())
                .addValue("requestId", row.requestId())
                .addValue("sortOrder", row.sortOrder())
                .addValue("clientItemKey", row.clientItemKey())
                .addValue("requestedItem", json(row.requestedItem()))
                .addValue("rationale", row.rationale())
                .addValue("constraintsUsed", json(row.constraintsUsed()))
                .addValue("exclusions", json(row.exclusions()))
                .addValue("policyResult", row.policyResult().name())
                .addValue("conflicts", json(row.conflicts()))
                .addValue("alternatives", json(row.alternatives()))
                .addValue("version", row.version())
                .addValue("createdAt", row.createdAt())
                .addValue("updatedAt", row.updatedAt()));
    }

    public List<ProposalRow> proposals(long tenantId, UUID requestId) {
        return jdbc.query("""
                SELECT proposal_item_id, tenant_id, request_id, sort_order, client_item_key,
                       requested_item, rationale, constraints_used, exclusions, policy_result,
                       conflicts, alternatives, authoritative_intent_item_id,
                       authoritative_intent_item_version, selected_resource_id,
                       selected_resource_version, selected_resource_name, version,
                       created_at, updated_at
                  FROM wp_assistant_proposal_items
                 WHERE tenant_id = :tenantId AND request_id = :requestId
                 ORDER BY sort_order, proposal_item_id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("requestId", requestId), this::proposalRow);
    }

    public void applyProposalValidation(
            long tenantId,
            UUID requestId,
            UUID proposalItemId,
            RequestedBookingItem authoritativeRequestedItem,
            PolicyResult policyResult,
            List<String> conflicts,
            List<AlternativeOption> alternatives,
            UUID authoritativeIntentItemId,
            long authoritativeIntentItemVersion,
            UUID selectedResourceId,
            Long selectedResourceVersion,
            String selectedResourceName,
            OffsetDateTime now) {
        jdbc.update("""
                UPDATE wp_assistant_proposal_items
                   SET requested_item = CAST(:requestedItem AS jsonb),
                       policy_result = :policyResult,
                       conflicts = CAST(:conflicts AS jsonb),
                       alternatives = CAST(:alternatives AS jsonb),
                       authoritative_intent_item_id = :authoritativeIntentItemId,
                       authoritative_intent_item_version = :authoritativeIntentItemVersion,
                       selected_resource_id = :selectedResourceId,
                       selected_resource_version = :selectedResourceVersion,
                       selected_resource_name = :selectedResourceName,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId
                   AND request_id = :requestId
                   AND proposal_item_id = :proposalItemId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("requestId", requestId)
                .addValue("proposalItemId", proposalItemId)
                .addValue("requestedItem", json(authoritativeRequestedItem))
                .addValue("policyResult", policyResult.name())
                .addValue("conflicts", json(conflicts))
                .addValue("alternatives", json(alternatives))
                .addValue("authoritativeIntentItemId", authoritativeIntentItemId)
                .addValue("authoritativeIntentItemVersion", authoritativeIntentItemVersion)
                .addValue("selectedResourceId", selectedResourceId)
                .addValue("selectedResourceVersion", selectedResourceVersion)
                .addValue("selectedResourceName", selectedResourceName)
                .addValue("now", now));
    }

    public boolean applyValidation(long tenantId, long actorId, UUID requestId,
                                   long expectedVersion, JsonNode snapshot,
                                   UUID bookingIntentId, List<String> limitations,
                                   OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_assistant_requests
                   SET request_state = 'VALIDATED',
                       validation_snapshot = CAST(:snapshot AS jsonb),
                       booking_intent_id = :bookingIntentId,
                       limitations = CAST(:limitations AS jsonb),
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND actor_user_id = :actorId
                   AND request_id = :requestId AND request_state = 'SUGGESTED'
                   AND version = :expectedVersion
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("requestId", requestId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("snapshot", json(snapshot))
                .addValue("bookingIntentId", bookingIntentId)
                .addValue("limitations", json(limitations))
                .addValue("now", now)) == 1;
    }

    public boolean markAwaitingConfirmation(
            long tenantId, long actorId, UUID requestId, long expectedVersion,
            OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_assistant_requests
                   SET request_state = 'AWAITING_CONFIRMATION',
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND actor_user_id = :actorId
                   AND request_id = :requestId AND request_state = 'VALIDATED'
                   AND version = :expectedVersion
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("requestId", requestId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("now", now)) == 1;
    }

    public boolean attachBatch(long tenantId, long actorId, UUID requestId,
                               long expectedVersion, UUID bookingBatchId,
                               OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_assistant_requests
                   SET request_state = 'PROCESSING', booking_batch_id = :bookingBatchId,
                       requery_required = FALSE, last_result_code = NULL,
                       version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND actor_user_id = :actorId
                   AND request_id = :requestId
                   AND request_state = 'AWAITING_CONFIRMATION'
                   AND version = :expectedVersion
                   AND booking_batch_id IS NULL
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("requestId", requestId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("bookingBatchId", bookingBatchId)
                .addValue("now", now)) == 1;
    }

    public boolean reconcile(long tenantId, long actorId, UUID requestId,
                             RequestState state, boolean requeryRequired,
                             String resultCode, OffsetDateTime now) {
        return jdbc.update("""
                UPDATE wp_assistant_requests
                   SET request_state = :state,
                       requery_required = :requeryRequired,
                       last_result_code = :resultCode,
                       version = version + 1,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND actor_user_id = :actorId
                   AND request_id = :requestId
                   AND booking_batch_id IS NOT NULL
                   AND NOT (request_state IN (
                        'SUCCEEDED','PARTIAL','FAILED','RESULT_UNKNOWN')
                       AND :state = 'PROCESSING')
                   AND (request_state <> :state
                        OR requery_required <> :requeryRequired
                        OR last_result_code IS DISTINCT FROM :resultCode)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("requestId", requestId)
                .addValue("state", state.name())
                .addValue("requeryRequired", requeryRequired)
                .addValue("resultCode", resultCode)
                .addValue("now", now)) == 1;
    }

    private MapSqlParameterSource governanceParameters(
            long tenantId, long actorId, GovernanceUpdateRequest request, OffsetDateTime now) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("actorId", actorId)
                .addValue("tenantOptIn", request.tenantOptIn())
                .addValue("killSwitch", request.killSwitch())
                .addValue("providerReference", blankToNull(request.modelProviderReference()))
                .addValue("modelVersion", blankToNull(request.modelVersion()))
                .addValue("promptVersion", blankToNull(request.promptVersion()))
                .addValue("toolVersion", blankToNull(request.toolVersion()))
                .addValue("retentionDays", request.retentionDays())
                .addValue("feedbackUseEnabled", request.feedbackUseEnabled())
                .addValue("redactionState", request.redactionState().name())
                .addValue("now", now);
    }

    private GovernanceRow governanceRow(ResultSet rs, int ignored) throws SQLException {
        return new GovernanceRow(
                rs.getLong("tenant_id"), rs.getBoolean("tenant_opt_in"),
                rs.getBoolean("kill_switch"), rs.getString("model_provider_reference"),
                rs.getString("model_version"), rs.getString("prompt_version"),
                rs.getString("tool_version"), rs.getInt("retention_days"),
                rs.getBoolean("feedback_use_enabled"),
                GovernanceRedactionState.valueOf(rs.getString("redaction_state")),
                rs.getLong("version"), rs.getObject("updated_at", OffsetDateTime.class),
                nullableLong(rs, "updated_by"));
    }

    private RequestRow requestRow(ResultSet rs, int ignored) throws SQLException {
        return new RequestRow(
                rs.getObject("request_id", UUID.class), rs.getLong("tenant_id"),
                rs.getLong("actor_user_id"), RequestState.valueOf(rs.getString("request_state")),
                rs.getString("redacted_request_text"),
                RedactionState.valueOf(rs.getString("redaction_state")),
                rs.getBoolean("request_processing_consent"),
                rs.getBoolean("feedback_use_consent"),
                rs.getString("model_provider_reference"), rs.getString("model_version"),
                rs.getString("prompt_version"), rs.getString("tool_version"),
                tree(rs.getString("structured_proposal")),
                treeNullable(rs.getString("validation_snapshot")),
                rs.getObject("booking_intent_id", UUID.class),
                rs.getObject("booking_batch_id", UUID.class),
                rs.getBoolean("requery_required"), rs.getString("last_result_code"),
                tree(rs.getString("limitations")), rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"), rs.getString("correlation_id"),
                rs.getLong("version"), rs.getObject("retention_expires_at", OffsetDateTime.class),
                rs.getObject("retention_deleted_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private ProposalRow proposalRow(ResultSet rs, int ignored) throws SQLException {
        return new ProposalRow(
                rs.getObject("proposal_item_id", UUID.class), rs.getLong("tenant_id"),
                rs.getObject("request_id", UUID.class), rs.getInt("sort_order"),
                rs.getString("client_item_key"),
                value(rs.getString("requested_item"), RequestedBookingItem.class),
                rs.getString("rationale"), strings(rs.getString("constraints_used")),
                strings(rs.getString("exclusions")),
                PolicyResult.valueOf(rs.getString("policy_result")),
                strings(rs.getString("conflicts")),
                values(rs.getString("alternatives"), AlternativeOption.class),
                rs.getObject("authoritative_intent_item_id", UUID.class),
                nullableLong(rs, "authoritative_intent_item_version"),
                rs.getObject("selected_resource_id", UUID.class),
                nullableLong(rs, "selected_resource_version"),
                rs.getString("selected_resource_name"), rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private JsonNode tree(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "Stored Workplace Assistant JSON is unreadable.");
        }
    }

    private JsonNode treeNullable(String value) {
        return value == null ? null : tree(value);
    }

    private <T> T value(String raw, Class<T> type) {
        try {
            return mapper.readValue(raw, type);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "Stored Workplace Assistant data is unreadable.");
        }
    }

    private List<String> strings(String raw) {
        return mapper.convertValue(tree(raw), mapper.getTypeFactory()
                .constructCollectionType(List.class, String.class));
    }

    private <T> List<T> values(String raw, Class<T> type) {
        return mapper.convertValue(tree(raw), mapper.getTypeFactory()
                .constructCollectionType(List.class, type));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "Workplace Assistant data could not be serialized.");
        }
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }


    private static final String REQUEST_SELECT = """
            SELECT request_id, tenant_id, actor_user_id, request_state,
                   redacted_request_text, redaction_state, request_processing_consent,
                   feedback_use_consent, model_provider_reference, model_version,
                   prompt_version, tool_version, structured_proposal, validation_snapshot,
                   booking_intent_id, booking_batch_id, requery_required, last_result_code,
                   limitations, idempotency_key, request_fingerprint, correlation_id,
                   version, retention_expires_at, retention_deleted_at, created_at, updated_at
              FROM wp_assistant_requests
            """;

    public record GovernanceRow(
            long tenantId,
            boolean tenantOptIn,
            boolean killSwitch,
            String providerReference,
            String modelVersion,
            String promptVersion,
            String toolVersion,
            int retentionDays,
            boolean feedbackUseEnabled,
            GovernanceRedactionState redactionState,
            long version,
            OffsetDateTime updatedAt,
            Long updatedBy) { }

    public record RequestRow(
            UUID requestId,
            long tenantId,
            long actorUserId,
            RequestState state,
            String redactedRequestText,
            RedactionState redactionState,
            boolean requestProcessingConsent,
            boolean feedbackUseConsent,
            String providerReference,
            String modelVersion,
            String promptVersion,
            String toolVersion,
            JsonNode structuredProposal,
            JsonNode validationSnapshot,
            UUID bookingIntentId,
            UUID bookingBatchId,
            boolean requeryRequired,
            String lastResultCode,
            JsonNode limitations,
            String idempotencyKey,
            String requestFingerprint,
            String correlationId,
            long version,
            OffsetDateTime retentionExpiresAt,
            OffsetDateTime retentionDeletedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) { }

    public record ProposalRow(
            UUID proposalItemId,
            long tenantId,
            UUID requestId,
            int sortOrder,
            String clientItemKey,
            RequestedBookingItem requestedItem,
            String rationale,
            List<String> constraintsUsed,
            List<String> exclusions,
            PolicyResult policyResult,
            List<String> conflicts,
            List<AlternativeOption> alternatives,
            UUID authoritativeIntentItemId,
            Long authoritativeIntentItemVersion,
            UUID selectedResourceId,
            Long selectedResourceVersion,
            String selectedResourceName,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) { }

    public record CommandRow(
            UUID commandId,
            long tenantId,
            long actorUserId,
            UUID requestId,
            String commandType,
            String idempotencyKey,
            String requestFingerprint,
            CommandState state,
            String statusHref,
            String reason,
            String correlationId,
            String resultCode,
            OffsetDateTime acceptedAt,
            OffsetDateTime completedAt) { }

    public record FeedbackRow(
            UUID feedbackId,
            long tenantId,
            UUID requestId,
            long actorUserId,
            FeedbackRating rating,
            String redactedComment,
            boolean eligibleForModelImprovement,
            UUID auditEventId,
            OffsetDateTime createdAt) { }

    public record AuditRow(
            UUID auditEventId,
            long tenantId,
            UUID requestId,
            long actorUserId,
            String eventType,
            JsonNode metadata,
            String correlationId,
            OffsetDateTime createdAt) { }
}
