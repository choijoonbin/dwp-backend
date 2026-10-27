package com.dwp.services.people.hris.performance.per008;

import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static com.dwp.services.people.hris.performance.per008.Per008V20MigrationFixture.*;

/** PER-008 forged-evidence, privacy, and lifecycle rollback assertions. */
final class Per008V20MigrationGuards {

    static void verifyInitialRequestInsertGuard(
            DataSource source, JdbcTemplate jdbc) throws Exception {
        RequestInsertFixture directOpen = requestInsertFixture(8_092L, "direct-open");
        RequestInsertFixture nonInitialVersion = requestInsertFixture(8_093L, "version-two");
        RequestInsertFixture priorReceipt = requestInsertFixture(8_094L, "prior-receipt");
        RequestInsertFixture mismatchedDigest = requestInsertFixture(
                8_095L, "mismatched-receipt-digest");
        withReplica(source, scoped -> {
            seedRequestAuthority(scoped, directOpen);
            seedRequestAuthority(scoped, nonInitialVersion);
            seedRequestAuthority(scoped, priorReceipt);
            seedRequestAuthority(scoped, mismatchedDigest, HEX_C);
        });

        UUID directOpenReceipt = id("direct-open-lifecycle-receipt");
        UUID priorLifecycleReceipt = id("prior-lifecycle-receipt");
        insertSucceededReceipt(jdbc, directOpen.tenantId(), directOpenReceipt,
                "owner.people.performance-feedback.lifecycle.v1:open",
                id(directOpen.requestId() + "-principal"), directOpen.requestId(),
                1, 2, directOpen.requestId(), OPENED);
        insertSucceededReceipt(jdbc, priorReceipt.tenantId(), priorLifecycleReceipt,
                "owner.people.performance-feedback.lifecycle.v1:open",
                id(priorReceipt.requestId() + "-principal"), priorReceipt.requestId(),
                1, 2, priorReceipt.requestId(), OPENED);

        assertThatThrownBy(() -> insertRequest(
                jdbc, directOpen.tenantId(), directOpen.requestId(), 1, "OPEN",
                directOpen.createReceiptId(), directOpenReceipt, null, null,
                1, OPENED, CLOSED))
                .hasMessageContaining("PER008_FROZEN_AUTHORITY_INVALID");
        assertThat(requestCount(jdbc, directOpen)).isZero();

        assertThatThrownBy(() -> insertRequest(
                jdbc, nonInitialVersion.tenantId(), nonInitialVersion.requestId(),
                2, "DRAFT", nonInitialVersion.createReceiptId(), null, null, null,
                1, OPENED, CLOSED))
                .hasMessageContaining("PER008_FROZEN_AUTHORITY_INVALID");
        assertThat(requestCount(jdbc, nonInitialVersion)).isZero();

        assertThatThrownBy(() -> insertRequest(
                jdbc, priorReceipt.tenantId(), priorReceipt.requestId(), 1, "DRAFT",
                priorReceipt.createReceiptId(), priorLifecycleReceipt, null, null,
                1, OPENED, CLOSED))
                .hasMessageContaining("PER008_FROZEN_AUTHORITY_INVALID");
        assertThat(requestCount(jdbc, priorReceipt)).isZero();

        assertThatThrownBy(() -> insertRequest(
                jdbc, mismatchedDigest.tenantId(), mismatchedDigest.requestId(),
                1, "DRAFT", mismatchedDigest.createReceiptId(), null, null, null,
                1, OPENED, CLOSED))
                .hasMessageContaining("PER008_FROZEN_AUTHORITY_INVALID");
        assertThat(requestCount(jdbc, mismatchedDigest)).isZero();
    }

    static void verifyCreateCommitAtomicity(
            DataSource source, JdbcTemplate jdbc) throws Exception {
        RequestInsertFixture empty = requestInsertFixture(8_101L, "empty-audience");
        RequestInsertFixture belowThreshold = requestInsertFixture(8_102L, "below-threshold");
        RequestInsertFixture timestampMismatch = requestInsertFixture(8_103L, "timestamp-mismatch");
        RequestInsertFixture valid = requestInsertFixture(8_104L, "valid-create-chain");
        UUID lateWorker = id(valid.requestId() + "-late-worker");
        UUID lateAssignment = id(valid.requestId() + "-late-provider-assignment");
        withReplica(source, scoped -> {
            seedRequestAuthority(scoped, empty);
            seedRequestAuthority(scoped, belowThreshold);
            seedRequestAuthority(scoped, timestampMismatch);
            seedRequestAuthority(scoped, valid);
            seedProjectionMember(scoped, valid, lateWorker, lateAssignment);
        });

        assertThatThrownBy(() -> inTransaction(source, transaction ->
                insertRequest(transaction, empty.tenantId(), empty.requestId(), 1, "DRAFT",
                        empty.createReceiptId(), null, null, null, 1, OPENED, CLOSED)))
                .hasMessageContaining("PER008_CREATE_COMMIT_INCOMPLETE");
        assertThat(requestCount(jdbc, empty)).isZero();

        assertThatThrownBy(() -> inTransaction(source, transaction -> {
            insertRequest(transaction, belowThreshold.tenantId(), belowThreshold.requestId(),
                    1, "DRAFT", belowThreshold.createReceiptId(), null, null, null,
                    2, OPENED, CLOSED);
            insertInitialAssignment(transaction, belowThreshold,
                    id(belowThreshold.requestId() + "-subject-worker"),
                    id(belowThreshold.requestId() + "-subject-assignment"),
                    id("below-threshold-assignment"), null);
            completeCreateReceipt(transaction, belowThreshold);
        })).hasMessageContaining("PER008_RECEIPT_MUTATION_FORBIDDEN");
        assertThat(requestCount(jdbc, belowThreshold)).isZero();

        assertThatThrownBy(() -> inTransaction(source, transaction -> {
            insertRequest(transaction, timestampMismatch.tenantId(),
                    timestampMismatch.requestId(), 1, "DRAFT",
                    timestampMismatch.createReceiptId(), null, null, null,
                    1, OPENED, CLOSED);
            insertInitialAssignment(transaction, timestampMismatch,
                    id(timestampMismatch.requestId() + "-subject-worker"),
                    id(timestampMismatch.requestId() + "-subject-assignment"),
                    id("timestamp-mismatch-assignment"), OPENED);
            completeCreateReceipt(transaction, timestampMismatch);
        })).hasMessageContaining("PER008_CREATE_COMMIT_INCOMPLETE");
        assertThat(requestCount(jdbc, timestampMismatch)).isZero();

        UUID validAssignment = id("valid-create-assignment");
        inTransaction(source, transaction -> {
            insertRequest(transaction, valid.tenantId(), valid.requestId(), 1, "DRAFT",
                    valid.createReceiptId(), null, null, null, 1, OPENED, CLOSED);
            insertInitialAssignment(transaction, valid,
                    id(valid.requestId() + "-subject-worker"),
                    id(valid.requestId() + "-subject-assignment"), validAssignment, null);
            completeCreateReceipt(transaction, valid);
        });
        assertThat(requestCount(jdbc, valid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_feedback_assignments
                 WHERE tenant_id = ? AND feedback_request_public_id = ?
                """, Integer.class, valid.tenantId(), valid.requestId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT receipt_state FROM hris_performance.prf_command_receipts
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, String.class, valid.tenantId(), valid.createReceiptId()))
                .isEqualTo("SUCCEEDED");

        assertThatThrownBy(() -> insertInitialAssignment(jdbc, valid, lateWorker,
                lateAssignment, id("late-create-assignment"), null))
                .hasMessageContaining("PER008_ASSIGNMENT_CREATE_BINDING_INVALID");
    }

    static void verifyResponseReceiptAndWindowGuards(
            DataSource source, JdbcTemplate jdbc) throws Exception {
        UUID requestId = id("response-request");
        UUID assignmentId = id("response-assignment");
        UUID createReceipt = id("response-create-receipt");
        UUID openReceipt = id("response-open-receipt");
        UUID forgedReceipt = id("response-forged-receipt");
        UUID forgedPrincipalReceipt = id("response-forged-principal-receipt");
        UUID lateReceipt = id("response-late-receipt");
        UUID validReceipt = id("response-valid-receipt");
        UUID providerWorker = id(assignmentId + "-provider-worker");
        Instant submittedAt = Instant.parse("2026-10-15T12:00:00Z");
        Instant lateAt = Instant.parse("2026-11-01T00:00:00Z");

        withReplica(source, scoped -> {
            insertRequest(scoped, TENANT_A, requestId, 2, "OPEN",
                    createReceipt, openReceipt, null, null, 1, OPENED, CLOSED);
            insertAssignment(scoped, TENANT_A, requestId, assignmentId,
                    "ASSIGNED", null);
        });
        withReplica(source, scoped -> {
            insertSucceededReceipt(scoped, TENANT_A, forgedReceipt,
                    "PEP-PER-015", providerWorker,
                    requestId, 2, 2, assignmentId, submittedAt);
            insertSucceededReceipt(scoped, TENANT_A, forgedPrincipalReceipt,
                    "PEP-PER-022", id("not-the-assignment-provider"),
                    requestId, 2, 2, assignmentId, submittedAt);
            insertSucceededReceipt(scoped, TENANT_A, lateReceipt,
                    "PEP-PER-022", providerWorker,
                    requestId, 2, 2, assignmentId, lateAt);
        });

        assertThatThrownBy(() -> insertResponse(jdbc, TENANT_A, requestId,
                assignmentId, forgedReceipt, submittedAt, id("forged-response")))
                .hasMessageContaining("PER008_RESPONSE_BINDING_INVALID");
        assertThatThrownBy(() -> insertResponse(jdbc, TENANT_A, requestId,
                assignmentId, forgedPrincipalReceipt, submittedAt,
                id("forged-principal-response")))
                .hasMessageContaining("PER008_RESPONSE_BINDING_INVALID");
        assertThatThrownBy(() -> insertResponse(jdbc, TENANT_A, requestId,
                assignmentId, lateReceipt, lateAt, id("late-response")))
                .hasMessageContaining("PER008_RESPONSE_BINDING_INVALID");

        withReplica(source, scoped -> scoped.update("""
                DELETE FROM hris_performance.prf_command_receipts
                 WHERE tenant_id = ? AND command_receipt_id IN (?, ?, ?)
                """, TENANT_A, forgedReceipt, forgedPrincipalReceipt, lateReceipt));

        UUID partialReceipt = id("partial-submit-receipt");
        assertThatThrownBy(() -> inTransaction(source, transaction -> {
            insertSucceededReceipt(transaction, TENANT_A, partialReceipt,
                    "PEP-PER-022", providerWorker,
                    requestId, 2, 2, assignmentId, submittedAt);
            insertResponse(transaction, TENANT_A, requestId, assignmentId,
                    partialReceipt, submittedAt, id("partial-response"));
            submitAssignment(transaction, TENANT_A, assignmentId, submittedAt);
        })).hasMessageContaining("PER008_SUBMISSION_COMMIT_INCOMPLETE");

        UUID extraPayloadReceipt = id("extra-payload-submit-receipt");
        assertThatThrownBy(() -> inTransaction(source, transaction -> {
            insertSucceededReceipt(transaction, TENANT_A, extraPayloadReceipt,
                    "PEP-PER-022", providerWorker,
                    requestId, 2, 2, assignmentId, submittedAt);
            insertResponse(transaction, TENANT_A, requestId, assignmentId,
                    extraPayloadReceipt, submittedAt, id("extra-payload-response"));
            submitAssignment(transaction, TENANT_A, assignmentId, submittedAt);
            insertSubmissionEvent(transaction, TENANT_A,
                    id("extra-payload-submission-event"), extraPayloadReceipt,
                    requestId, assignmentId, 2, submittedAt, true);
        })).hasMessageContaining("PER008_SUBMISSION_COMMIT_INCOMPLETE");

        UUID validResponse = id("valid-response");
        UUID validEvent = id("valid-submission-event");
        inTransaction(source, transaction -> {
            insertSucceededReceipt(transaction, TENANT_A, validReceipt,
                    "PEP-PER-022", providerWorker,
                    requestId, 2, 2, assignmentId, submittedAt);
            insertResponse(transaction, TENANT_A, requestId, assignmentId,
                    validReceipt, submittedAt, validResponse);
            submitAssignment(transaction, TENANT_A, assignmentId, submittedAt);
            insertSubmissionEvent(transaction, TENANT_A, validEvent, validReceipt,
                    requestId, assignmentId, 2, submittedAt, false);
        });
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_feedback_responses
                 WHERE tenant_id = ? AND feedback_assignment_public_id = ?
                """, Integer.class, TENANT_A, assignmentId)).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE hris_performance.prf_command_receipts
                   SET request_hash = ?
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, HEX_C, TENANT_A, validReceipt))
                .hasMessageContaining("PER008_RECEIPT_IDENTITY_OR_INTENT_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET payload = payload || '{"extra":true}'::jsonb
                 WHERE tenant_id = ? AND event_id = ?
                """, TENANT_A, validEvent))
                .hasMessageContaining("PER008_OUTBOX_IDENTITY_OR_PAYLOAD_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM hris_performance.prf_command_receipts
                 WHERE tenant_id = ? AND command_receipt_id = ?
                """, TENANT_A, validReceipt))
                .hasMessageContaining("PER008_RECEIPT_EVIDENCE_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM hris_performance.prf_outbox_events
                 WHERE tenant_id = ? AND event_id = ?
                """, TENANT_A, validEvent))
                .hasMessageContaining("PER008_OUTBOX_EVIDENCE_IMMUTABLE");
        assertThat(jdbc.update("""
                UPDATE hris_performance.prf_outbox_events
                   SET delivery_state = 'DELIVERED', delivery_attempts = 1
                 WHERE tenant_id = ? AND event_id = ?
                """, TENANT_A, validEvent)).isEqualTo(1);
    }

    static void verifyThresholdAndLifecycleRollback(
            DataSource source, JdbcTemplate jdbc) throws Exception {
        UUID requestId = id("aggregate-request");
        UUID firstAssignment = id("aggregate-assignment-1");
        UUID secondAssignment = id("aggregate-assignment-2");
        UUID createReceipt = id("aggregate-create-receipt");
        UUID openReceipt = id("aggregate-open-receipt");
        UUID closeReceipt = id("aggregate-close-receipt");
        UUID aggregateReceipt = id("aggregate-receipt");
        Instant aggregatedAt = Instant.parse("2026-11-02T00:00:00Z");

        withReplica(source, scoped -> {
            insertRequest(scoped, TENANT_A, requestId, 3, "CLOSED",
                    createReceipt, openReceipt, closeReceipt, null, 2, OPENED, CLOSED);
            insertAssignment(scoped, TENANT_A, requestId, firstAssignment,
                    "SUBMITTED", Instant.parse("2026-10-10T00:00:00Z"));
            insertAssignment(scoped, TENANT_A, requestId, secondAssignment,
                    "SUBMITTED", Instant.parse("2026-10-11T00:00:00Z"));
            insertResponse(scoped, TENANT_A, requestId, firstAssignment,
                    id("aggregate-submit-receipt-1"),
                    Instant.parse("2026-10-10T00:00:00Z"), id("aggregate-response-1"));
        });
        insertSucceededReceipt(jdbc, TENANT_A, aggregateReceipt,
                "owner.people.performance-feedback.lifecycle.v1:aggregate",
                requestId, 3, 4, requestId, aggregatedAt);

        String oneResponseDigest = sourceSetDigest(jdbc, TENANT_A, requestId);
        assertThatThrownBy(() -> insertSnapshot(jdbc, TENANT_A, requestId,
                aggregateReceipt, 1, oneResponseDigest,
                "PerformanceFeedbackAggregate.v1/mean-text-v1",
                aggregatedAt, id("below-threshold-snapshot")))
                .hasMessageContaining("PER008_SNAPSHOT_BINDING_INVALID");

        withReplica(source, scoped -> insertResponse(scoped, TENANT_A, requestId,
                secondAssignment, id("aggregate-submit-receipt-2"),
                Instant.parse("2026-10-11T00:00:00Z"), id("aggregate-response-2")));
        String exactSourceDigest = sourceSetDigest(jdbc, TENANT_A, requestId);
        assertThatThrownBy(() -> insertSnapshot(jdbc, TENANT_A, requestId,
                aggregateReceipt, 1, exactSourceDigest,
                "PerformanceFeedbackAggregate.v1/mean-text-v1",
                aggregatedAt, id("forged-count-snapshot")))
                .hasMessageContaining("PER008_SNAPSHOT_BINDING_INVALID");

        assertThatThrownBy(() -> insertSnapshot(jdbc, TENANT_A, requestId,
                aggregateReceipt, 2, oneResponseDigest,
                "PerformanceFeedbackAggregate.v1/mean-text-v1",
                aggregatedAt, id("row-substitution-snapshot")))
                .hasMessageContaining("PER008_SNAPSHOT_BINDING_INVALID");
        assertThatThrownBy(() -> insertSnapshot(jdbc, TENANT_A, requestId,
                aggregateReceipt, 2, exactSourceDigest,
                "PerformanceFeedbackAggregate.v1/forged",
                aggregatedAt, id("forged-algorithm-snapshot")))
                .hasMessageContaining("PER008_SNAPSHOT_BINDING_INVALID");

        UUID wrongPurposeReceipt = id("aggregate-wrong-purpose-receipt");
        withReplica(source, scoped -> {
            insertSucceededReceipt(scoped, TENANT_A, wrongPurposeReceipt,
                    "owner.people.performance-feedback.lifecycle.v1:aggregate",
                    requestId, 3, 4, requestId, aggregatedAt);
            scoped.update("""
                    UPDATE hris_performance.prf_command_receipts
                       SET command_type = 'PERFORMANCE_FEEDBACK',
                           purpose_code = 'PERFORMANCE_FEEDBACK'
                     WHERE tenant_id = ? AND command_receipt_id = ?
                    """, TENANT_A, wrongPurposeReceipt);
        });
        assertThatThrownBy(() -> insertSnapshot(jdbc, TENANT_A, requestId,
                wrongPurposeReceipt, 2, exactSourceDigest,
                "PerformanceFeedbackAggregate.v1/mean-text-v1",
                aggregatedAt, id("wrong-purpose-snapshot")))
                .hasMessageContaining("PER008_SNAPSHOT_BINDING_INVALID");

        assertThatThrownBy(() -> insertSnapshot(jdbc, TENANT_A, requestId,
                aggregateReceipt, 2, exactSourceDigest,
                "PerformanceFeedbackAggregate.v1/mean-text-v1",
                aggregatedAt, id("orphan-closed-snapshot")))
                .hasMessageContaining("PER008_ORPHAN_SNAPSHOT_COMMIT_FORBIDDEN");

        UUID validSnapshot = id("valid-aggregate-snapshot");
        inTransaction(source, transaction -> {
            insertSnapshot(transaction, TENANT_A, requestId, aggregateReceipt,
                    2, exactSourceDigest,
                    "PerformanceFeedbackAggregate.v1/mean-text-v1",
                    aggregatedAt, validSnapshot);
            assertThat(transaction.update("""
                    UPDATE hris_performance.prf_feedback_requests
                       SET request_state = 'AGGREGATED', aggregate_version = 4,
                           aggregation_command_receipt_id = ?, aggregated_at = ?, updated_at = ?
                     WHERE tenant_id = ? AND public_id = ?
                    """, aggregateReceipt, Timestamp.from(aggregatedAt),
                    Timestamp.from(aggregatedAt), TENANT_A, requestId)).isEqualTo(1);
        });

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM hris_performance.prf_feedback_release_snapshots
                 WHERE tenant_id = ? AND feedback_request_public_id = ?
                """, Integer.class, TENANT_A, requestId)).isEqualTo(1);
        Map<String, Object> state = jdbc.queryForMap("""
                SELECT request_state, aggregate_version
                  FROM hris_performance.prf_feedback_requests
                 WHERE tenant_id = ? AND public_id = ?
                """, TENANT_A, requestId);
        assertThat(state.get("request_state")).isEqualTo("AGGREGATED");
        assertThat(((Number) state.get("aggregate_version")).longValue()).isEqualTo(4L);
    }

    static void verifyAssignmentTerminalAndCancellationBinding(
            DataSource source, JdbcTemplate jdbc) throws Exception {
        long tenantId = 8_111L;
        UUID requestId = id("cancel-request");
        UUID assignmentId = id("cancel-assignment");
        Instant cancelledAt = Instant.parse("2026-10-20T00:00:00Z");
        withReplica(source, scoped -> {
            insertRequest(scoped, tenantId, requestId, 1, "DRAFT",
                    id("cancel-create-receipt"), null, null, null,
                    1, OPENED, CLOSED);
            insertAssignment(scoped, tenantId, requestId, assignmentId,
                    "ASSIGNED", null);
        });

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE hris_performance.prf_feedback_assignments
                   SET assignment_state = 'DECLINED', declined_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND public_id = ?
                """, Timestamp.from(cancelledAt), Timestamp.from(cancelledAt),
                tenantId, assignmentId))
                .hasMessageContaining("PER008_ASSIGNMENT_IMMUTABLE_OR_ILLEGAL_TRANSITION");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE hris_performance.prf_feedback_assignments
                   SET assignment_state = 'CANCELLED', cancelled_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND public_id = ?
                """, Timestamp.from(cancelledAt), Timestamp.from(cancelledAt),
                tenantId, assignmentId))
                .hasMessageContaining("PER008_ASSIGNMENT_IMMUTABLE_OR_ILLEGAL_TRANSITION");

        UUID wrongPurposeReceipt = id("cancel-wrong-purpose-receipt");
        withReplica(source, scoped -> {
            insertSucceededReceipt(scoped, tenantId, wrongPurposeReceipt,
                    "owner.people.performance-feedback.lifecycle.v1:cancel",
                    requestId, 1, 2, requestId, cancelledAt);
            scoped.update("""
                    UPDATE hris_performance.prf_command_receipts
                       SET command_type = 'PERFORMANCE_FEEDBACK',
                           purpose_code = 'PERFORMANCE_FEEDBACK'
                     WHERE tenant_id = ? AND command_receipt_id = ?
                    """, tenantId, wrongPurposeReceipt);
        });
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE hris_performance.prf_feedback_assignments
                   SET assignment_state = 'CANCELLED', cancelled_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND public_id = ?
                """, Timestamp.from(cancelledAt), Timestamp.from(cancelledAt),
                tenantId, assignmentId))
                .hasMessageContaining("PER008_ASSIGNMENT_IMMUTABLE_OR_ILLEGAL_TRANSITION");

        UUID cancelReceipt = id("cancel-valid-receipt");
        insertSucceededReceipt(jdbc, tenantId, cancelReceipt,
                "owner.people.performance-feedback.lifecycle.v1:cancel",
                requestId, 1, 2, requestId, cancelledAt);
        inTransaction(source, transaction -> {
            assertThat(transaction.update("""
                    UPDATE hris_performance.prf_feedback_assignments
                       SET assignment_state = 'CANCELLED', cancelled_at = ?, updated_at = ?
                     WHERE tenant_id = ? AND public_id = ?
                    """, Timestamp.from(cancelledAt), Timestamp.from(cancelledAt),
                    tenantId, assignmentId)).isEqualTo(1);
            assertThat(transaction.update("""
                    UPDATE hris_performance.prf_feedback_requests
                       SET request_state = 'CANCELLED', aggregate_version = 2,
                           cancel_command_receipt_id = ?, cancelled_at = ?, updated_at = ?
                     WHERE tenant_id = ? AND public_id = ?
                    """, cancelReceipt, Timestamp.from(cancelledAt),
                    Timestamp.from(cancelledAt), tenantId, requestId)).isEqualTo(1);
        });
        assertThat(jdbc.queryForObject("""
                SELECT assignment_state
                  FROM hris_performance.prf_feedback_assignments
                 WHERE tenant_id = ? AND public_id = ?
                """, String.class, tenantId, assignmentId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("""
                SELECT request_state FROM hris_performance.prf_feedback_requests
                 WHERE tenant_id = ? AND public_id = ?
                """, String.class, tenantId, requestId)).isEqualTo("CANCELLED");
    }
}
