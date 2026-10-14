package com.dwp.services.people.hris.performance.review;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.AssignedReview;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ReviewAnswer;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ReviewForm;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ReviewState;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ReviewSubmission;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ReviewerType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class PerformanceReviewRepository {

    private static final TypeReference<List<ReviewAnswer>> ANSWERS = new TypeReference<>() { };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    PerformanceReviewRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.mapper = objectMapper.copy().findAndRegisterModules();
    }

    void bindTenant(long tenantId) {
        jdbc.queryForObject(
                "SELECT set_config('dwp.tenant_id', CAST(:tenantId AS VARCHAR), TRUE)",
                new MapSqlParameterSource("tenantId", tenantId), String.class);
    }

    Optional<ReviewRow> assigned(
            PerformanceReviewAuthorityPort.AuthorityEvidence authority,
            boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        List<ReviewRow> rows = jdbc.query("""
                SELECT review_id, participant_id, reviewer_assignment_id,
                       reviewer_principal_public_id, organization_public_id,
                       reviewer_type, stage_key, review_state, template_version_id,
                       template_content_hash, aggregate_version, writable,
                       draft_content_hash, form_definition::text AS form_definition,
                       draft_answers::text AS draft_answers, due_at
                  FROM hris_performance.prf_review_assignments
                 WHERE tenant_id = :tenantId
                   AND review_id = :reviewId
                   AND review_state NOT IN ('COMPLETED', 'CANCELLED')
                   AND (
                       (:profile = 'self-reviewer'
                        AND reviewer_type = 'SELF'
                        AND participant_id = :subjectId)
                       OR
                       (:profile = 'assigned-reviewer'
                        AND reviewer_type <> 'SELF'
                        AND reviewer_principal_public_id = :subjectId)
                   )
                """ + suffix, authorityParams(authority), this::reviewRow);
        return rows.stream().findFirst();
    }

    boolean reserveReceipt(
            PerformanceReviewAuthorityPort.AuthorityEvidence authority,
            UUID receiptId,
            String idempotencyKey,
            String commandType,
            String action,
            long expectedVersion,
            String requestHash) {
        return jdbc.update("""
                INSERT INTO hris_performance.prf_command_receipts (
                    command_receipt_id, tenant_id, idempotency_key, command_type,
                    originating_action, actor_id, subject_principal_public_id,
                    aggregate_id, expected_aggregate_version, request_hash,
                    population_scope_digest, field_policy_revision, purpose_code,
                    authorization_revision, receipt_state)
                VALUES (
                    :receiptId, :tenantId, :idempotencyKey, :commandType,
                    :action, :actorId, :subjectId,
                    :reviewId, :expectedVersion, :requestHash,
                    :populationDigest, :fieldRevision, :purpose,
                    :authorizationRevision, 'ACCEPTED')
                ON CONFLICT (
                    tenant_id, subject_principal_public_id,
                    originating_action, idempotency_key)
                DO NOTHING
                """, authorityParams(authority)
                .addValue("receiptId", receiptId)
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("commandType", commandType)
                .addValue("action", action)
                .addValue("expectedVersion", expectedVersion)
                .addValue("requestHash", requestHash)) == 1;
    }

    Optional<ReceiptRow> receiptByIdempotency(
            PerformanceReviewAuthorityPort.AuthorityEvidence authority,
            String action,
            String idempotencyKey) {
        List<ReceiptRow> rows = jdbc.query("""
                SELECT command_receipt_id, aggregate_id, command_type,
                       originating_action, expected_aggregate_version,
                       applied_aggregate_version, request_hash, receipt_state,
                       result_ref, actor_id, population_scope_digest,
                       field_policy_revision, purpose_code,
                       authorization_revision
                  FROM hris_performance.prf_command_receipts
                 WHERE tenant_id = :tenantId
                   AND subject_principal_public_id = :subjectId
                   AND originating_action = :action
                   AND idempotency_key = :idempotencyKey
                """, authorityParams(authority)
                .addValue("action", action)
                .addValue("idempotencyKey", idempotencyKey), (rs, rowNumber) ->
                new ReceiptRow(
                        uuid(rs, "command_receipt_id"), uuid(rs, "aggregate_id"),
                        rs.getString("command_type"), rs.getString("originating_action"),
                        rs.getLong("expected_aggregate_version"),
                        nullableLong(rs, "applied_aggregate_version"),
                        rs.getString("request_hash"), rs.getString("receipt_state"),
                        nullableUuid(rs, "result_ref"), rs.getLong("actor_id"),
                        rs.getString("population_scope_digest"),
                        rs.getLong("field_policy_revision"),
                        rs.getString("purpose_code"),
                        rs.getLong("authorization_revision")));
        return rows.stream().findFirst();
    }

    AssignedReview updateDraft(
            PerformanceReviewAuthorityPort.AuthorityEvidence authority,
            long expectedVersion,
            List<ReviewAnswer> answers,
            String contentHash) {
        int updated = jdbc.update("""
                UPDATE hris_performance.prf_review_assignments
                   SET aggregate_version = aggregate_version + 1,
                       review_state = CASE
                           WHEN review_state IN ('NOT_STARTED', 'RETURNED')
                           THEN 'IN_PROGRESS'
                           ELSE review_state
                       END,
                       draft_answers = CAST(:answers AS jsonb),
                       draft_content_hash = :contentHash,
                       updated_at = CURRENT_TIMESTAMP,
                       updated_by = :actorId
                 WHERE tenant_id = :tenantId
                   AND review_id = :reviewId
                   AND aggregate_version = :expectedVersion
                   AND writable
                   AND review_state IN ('NOT_STARTED', 'IN_PROGRESS', 'RETURNED')
                   AND (
                       (:profile = 'self-reviewer'
                        AND reviewer_type = 'SELF'
                        AND participant_id = :subjectId)
                       OR
                       (:profile = 'assigned-reviewer'
                        AND reviewer_type <> 'SELF'
                        AND reviewer_principal_public_id = :subjectId)
                   )
                """, authorityParams(authority)
                .addValue("expectedVersion", expectedVersion)
                .addValue("answers", json(answers))
                .addValue("contentHash", contentHash));
        requireCas(updated);
        return assigned(authority, false).orElseThrow(this::opaqueNotFound).view();
    }

    ReviewSubmission submit(
            PerformanceReviewAuthorityPort.AuthorityEvidence authority,
            ReviewRow current,
            UUID receiptId,
            UUID submissionId,
            String contentHash,
            Instant submittedAt) {
        long submissionNo = nextSubmissionNo(authority.tenantId(), authority.reviewId());
        int updated = jdbc.update("""
                UPDATE hris_performance.prf_review_assignments
                   SET aggregate_version = aggregate_version + 1,
                       review_state = 'SUBMITTED', writable = FALSE,
                       updated_at = :submittedAt, updated_by = :actorId
                 WHERE tenant_id = :tenantId
                   AND review_id = :reviewId
                   AND aggregate_version = :expectedVersion
                   AND draft_content_hash = :contentHash
                   AND writable
                   AND review_state IN ('NOT_STARTED', 'IN_PROGRESS', 'RETURNED')
                   AND (
                       (:profile = 'self-reviewer'
                        AND reviewer_type = 'SELF'
                        AND participant_id = :subjectId)
                       OR
                       (:profile = 'assigned-reviewer'
                        AND reviewer_type <> 'SELF'
                        AND reviewer_principal_public_id = :subjectId)
                   )
                """, authorityParams(authority)
                .addValue("expectedVersion", current.view().aggregateVersion())
                .addValue("contentHash", contentHash)
                .addValue("submittedAt", Timestamp.from(submittedAt)));
        requireCas(updated);
        jdbc.update("""
                INSERT INTO hris_performance.prf_review_submissions (
                    tenant_id, submission_id, review_id, reviewer_assignment_id,
                    template_version_id, template_content_hash, submission_no,
                    content_hash, submitted_answers, submitted_at, submitted_by,
                    command_receipt_id)
                VALUES (
                    :tenantId, :submissionId, :reviewId, :reviewerAssignmentId,
                    :templateVersionId, :templateContentHash, :submissionNo,
                    :contentHash, CAST(:answers AS jsonb), :submittedAt, :actorId,
                    :receiptId)
                """, authorityParams(authority)
                .addValue("submissionId", submissionId)
                .addValue("reviewerAssignmentId", current.view().reviewerAssignmentId())
                .addValue("templateVersionId", current.view().templateVersionId())
                .addValue("templateContentHash", current.view().templateContentHash())
                .addValue("submissionNo", submissionNo)
                .addValue("contentHash", contentHash)
                .addValue("answers", json(current.view().answers()))
                .addValue("submittedAt", Timestamp.from(submittedAt))
                .addValue("receiptId", receiptId));
        SubmittedEventData event = new SubmittedEventData(
                current.view().reviewId(), current.view().participantId(),
                current.view().reviewerAssignmentId(), submissionId,
                current.view().templateVersionId(), contentHash);
        jdbc.update("""
                INSERT INTO hris_performance.prf_outbox_events (
                    event_id, tenant_id, command_receipt_id, aggregate_type,
                    aggregate_id, aggregate_version, event_type, payload)
                VALUES (
                    :eventId, :tenantId, :receiptId, 'PERFORMANCE_REVIEW',
                    :reviewId, :aggregateVersion, 'PerformanceReviewSubmitted.v1',
                    CAST(:payload AS jsonb))
                """, authorityParams(authority)
                .addValue("eventId", UUID.randomUUID())
                .addValue("receiptId", receiptId)
                .addValue("aggregateVersion", current.view().aggregateVersion() + 1)
                .addValue("payload", json(event)));
        return new ReviewSubmission(
                submissionId, submissionNo, ReviewState.SUBMITTED,
                contentHash, submittedAt);
    }

    void storeResult(
            PerformanceReviewAuthorityPort.AuthorityEvidence authority,
            UUID receiptId,
            String operation,
            String requestHash,
            Object result,
            String resultEtag) {
        jdbc.update("""
                INSERT INTO hris_performance.prf_review_command_results (
                    tenant_id, command_receipt_id, review_id, operation_id,
                    request_hash, result_payload, result_etag)
                VALUES (
                    :tenantId, :receiptId, :reviewId, :operation,
                    :requestHash, CAST(:result AS jsonb), :resultEtag)
                """, authorityParams(authority)
                .addValue("receiptId", receiptId)
                .addValue("operation", operation)
                .addValue("requestHash", requestHash)
                .addValue("result", json(result))
                .addValue("resultEtag", resultEtag));
    }

    void completeReceipt(
            PerformanceReviewAuthorityPort.AuthorityEvidence authority,
            UUID receiptId,
            UUID resultRef,
            long appliedVersion) {
        int updated = jdbc.update("""
                UPDATE hris_performance.prf_command_receipts
                   SET receipt_state = 'SUCCEEDED', result_ref = :resultRef,
                       applied_aggregate_version = :appliedVersion,
                       completed_at = CURRENT_TIMESTAMP, error_code = NULL
                 WHERE tenant_id = :tenantId
                   AND command_receipt_id = :receiptId
                   AND receipt_state = 'ACCEPTED'
                """, authorityParams(authority)
                .addValue("receiptId", receiptId)
                .addValue("resultRef", resultRef)
                .addValue("appliedVersion", appliedVersion));
        requireCas(updated);
    }

    Optional<StoredResult> storedResult(
            PerformanceReviewAuthorityPort.AuthorityEvidence authority,
            UUID receiptId,
            String operation,
            String requestHash) {
        List<StoredResult> results = jdbc.query("""
                SELECT result_payload::text AS result_payload, result_etag
                  FROM hris_performance.prf_review_command_results
                 WHERE tenant_id = :tenantId
                   AND command_receipt_id = :receiptId
                   AND review_id = :reviewId
                   AND operation_id = :operation
                   AND request_hash = :requestHash
                """, authorityParams(authority)
                .addValue("receiptId", receiptId)
                .addValue("operation", operation)
                .addValue("requestHash", requestHash), (rs, rowNumber) ->
                new StoredResult(
                        rs.getString("result_payload"), rs.getString("result_etag")));
        return results.stream().findFirst();
    }

    AssignedReview assignedResult(StoredResult result) {
        return read(result.payload(), AssignedReview.class);
    }

    ReviewSubmission submissionResult(StoredResult result) {
        return read(result.payload(), ReviewSubmission.class);
    }

    private long nextSubmissionNo(long tenantId, UUID reviewId) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(submission_no), 0) + 1
                  FROM hris_performance.prf_review_submissions
                 WHERE tenant_id = :tenantId AND review_id = :reviewId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("reviewId", reviewId), Long.class);
        return value == null ? 1 : value;
    }

    private ReviewRow reviewRow(ResultSet rs, int rowNumber) throws SQLException {
        AssignedReview review = new AssignedReview(
                uuid(rs, "review_id"), uuid(rs, "participant_id"),
                uuid(rs, "reviewer_assignment_id"),
                ReviewerType.valueOf(rs.getString("reviewer_type")),
                rs.getString("stage_key"), ReviewState.valueOf(rs.getString("review_state")),
                uuid(rs, "template_version_id"), rs.getString("template_content_hash"),
                rs.getLong("aggregate_version"), rs.getBoolean("writable"),
                rs.getString("draft_content_hash"),
                read(rs.getString("form_definition"), ReviewForm.class),
                readAnswers(rs.getString("draft_answers")),
                nullableInstant(rs, "due_at"));
        return new ReviewRow(
                review, uuid(rs, "reviewer_principal_public_id"),
                uuid(rs, "organization_public_id"));
    }

    private MapSqlParameterSource authorityParams(
            PerformanceReviewAuthorityPort.AuthorityEvidence authority) {
        return new MapSqlParameterSource()
                .addValue("tenantId", authority.tenantId())
                .addValue("actorId", authority.actorId())
                .addValue("subjectId", authority.subjectPrincipalPublicId())
                .addValue("reviewId", authority.reviewId())
                .addValue("profile", authority.profileKey())
                .addValue("populationDigest", authority.populationScopeDigest())
                .addValue("fieldRevision", authority.fieldPolicyRevision())
                .addValue("purpose", authority.purposeCode())
                .addValue("authorizationRevision", authority.authorizationRevision());
    }

    private List<ReviewAnswer> readAnswers(String value) {
        try {
            return mapper.readValue(value, ANSWERS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted review answers are invalid.", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted review payload is invalid.", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Review persistence serialization failed.", exception);
        }
    }

    private void requireCas(int updated) {
        if (updated != 1) {
            throw new BaseException(
                    ErrorCode.OBJECT_VERSION_CONFLICT,
                    "The assigned review changed or is no longer writable.");
        }
    }

    private BaseException opaqueNotFound() {
        return new BaseException(
                ErrorCode.NOT_FOUND,
                "The assigned review is not available.");
    }

    private UUID uuid(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, UUID.class);
    }

    private UUID nullableUuid(ResultSet rs, String name) throws SQLException {
        return rs.getObject(name, UUID.class);
    }

    private Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private Instant nullableInstant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    record ReviewRow(
            AssignedReview view,
            UUID reviewerPrincipalPublicId,
            UUID organizationPublicId) {
    }

    record ReceiptRow(
            UUID receiptId,
            UUID aggregateId,
            String commandType,
            String action,
            long expectedVersion,
            Long appliedVersion,
            String requestHash,
            String state,
            UUID resultRef,
            long actorId,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision) {
    }

    record StoredResult(String payload, String etag) {
    }

    private record SubmittedEventData(
            UUID reviewId,
            UUID participantId,
            UUID reviewerAssignmentId,
            UUID submissionId,
            UUID templateVersionId,
            String contentHash) {
    }
}
