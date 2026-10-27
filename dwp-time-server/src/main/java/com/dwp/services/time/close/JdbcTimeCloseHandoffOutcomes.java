package com.dwp.services.time.close;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.close.TimeCloseModels.HandoffRecord;
import com.dwp.services.time.close.TimeCloseModels.HandoffState;
import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffOutcomeV1;
import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffStatusV1;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Applies receiver outcomes under the handoff lock and sealed lineage fence. */
final class JdbcTimeCloseHandoffOutcomes {

    private final JdbcTemplate jdbc;
    private final JdbcTimeCloseQueries queries;
    private final Clock clock;

    JdbcTimeCloseHandoffOutcomes(
            JdbcTemplate jdbc, JdbcTimeCloseQueries queries, Clock clock) {
        this.jdbc = jdbc;
        this.queries = queries;
        this.clock = clock;
    }

    OutcomeApplication applyOutcomeInTenant(
            long tenantId,
            UUID handoffId,
            String digest,
            PayrollTimeHandoffOutcomeV1 outcome) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", ignored -> {},
                "tim-close-outcome:" + tenantId + ":" + handoffId);
        HandoffRecord locked = queries.handoff(tenantId, handoffId, true)
                .orElseThrow(JdbcTimeCloseHandoffOutcomes::opaqueNotFound);
        Optional<StoredOutcome> existing = outcome(
                tenantId, handoffId, outcome.receiverReceiptId(),
                outcome.receiverReceiptRevision());
        if (existing.isPresent()) {
            StoredOutcome prior = existing.get();
            if (prior.commandDigest().equals(digest)) {
                return new OutcomeApplication(snapshot(locked, prior), false);
            }
            quarantine(tenantId, handoffId, "OUTCOME_DIGEST_CONFLICT", digest,
                    outcome.correlationId());
            return new OutcomeApplication(locked.projection(), true);
        }
        PayrollTimeHandoffStatusV1 current = locked.projection();
        if (!TimeCloseHandoffRecovery.accepts(locked, outcome)) {
            quarantine(tenantId, handoffId, "OUTCOME_LINEAGE_CONFLICT", digest,
                    outcome.correlationId());
            return new OutcomeApplication(current, true);
        }
        Instant appliedAt = clock.instant();
        long responseVersion = current.version() + 1;
        int updated = jdbc.update("""
                UPDATE tme_payroll_handoffs
                   SET status=?, receiver_receipt_public_id=?,
                       receiver_receipt_revision=?, receiver_receipt_digest=?,
                       receiver_reason_code=?, outcome_at=?, row_version=row_version+1,
                       updated_at=?
                 WHERE tenant_id=? AND public_id=? AND row_version=?
                """, outcome.outcome().name(), outcome.receiverReceiptId(),
                outcome.receiverReceiptRevision(), outcome.receiverReceiptDigest(),
                outcome.reasonCode(), Timestamp.from(outcome.outcomeAt()),
                Timestamp.from(appliedAt), tenantId, handoffId, current.version());
        if (updated != 1) throw versionConflict(current.version());
        jdbc.update("""
                INSERT INTO tme_payroll_handoff_outcomes (
                    tenant_id, handoff_public_id, receiver_receipt_public_id,
                    receiver_receipt_revision, outcome_command_digest, outcome,
                    receiver_receipt_digest, reason_code, outcome_at,
                    response_updated_at, response_version, correlation_id, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, handoffId, outcome.receiverReceiptId(),
                outcome.receiverReceiptRevision(), digest, outcome.outcome().name(),
                outcome.receiverReceiptDigest(), outcome.reasonCode(),
                Timestamp.from(outcome.outcomeAt()), Timestamp.from(appliedAt),
                responseVersion, outcome.correlationId(), Timestamp.from(appliedAt));
        StoredOutcome stored = outcome(
                tenantId, handoffId, outcome.receiverReceiptId(),
                outcome.receiverReceiptRevision()).orElseThrow();
        return new OutcomeApplication(snapshot(locked, stored), false);
    }

    private Optional<StoredOutcome> outcome(
            long tenantId,
            UUID handoffId,
            UUID receiverReceiptId,
            long receiverReceiptRevision) {
        List<StoredOutcome> rows = jdbc.query("""
                SELECT outcome_command_digest, outcome,
                       receiver_receipt_public_id, receiver_receipt_revision,
                       receiver_receipt_digest, reason_code, outcome_at,
                       response_updated_at, response_version
                  FROM tme_payroll_handoff_outcomes
                 WHERE tenant_id=? AND handoff_public_id=?
                   AND receiver_receipt_public_id=? AND receiver_receipt_revision=?
                """, (row, ignored) -> new StoredOutcome(
                        row.getString("outcome_command_digest").trim(),
                        HandoffState.valueOf(row.getString("outcome")),
                        row.getObject("receiver_receipt_public_id", UUID.class),
                        row.getLong("receiver_receipt_revision"),
                        row.getString("receiver_receipt_digest").trim(),
                        row.getString("reason_code"),
                        row.getTimestamp("outcome_at").toInstant(),
                        row.getTimestamp("response_updated_at").toInstant(),
                        row.getLong("response_version")),
                tenantId, handoffId, receiverReceiptId, receiverReceiptRevision);
        return rows.stream().findFirst();
    }

    private static PayrollTimeHandoffStatusV1 snapshot(
            HandoffRecord handoff, StoredOutcome outcome) {
        PayrollTimeHandoffStatusV1 identity = handoff.projection();
        return new PayrollTimeHandoffStatusV1(
                identity.handoffId(), identity.closePeriodId(),
                identity.closedTimeResultId(), identity.closeRevision(),
                identity.payrollEntityId(), identity.payPeriodId(),
                identity.workerCount(), identity.lineCount(), identity.sourceDigest(),
                identity.ruleDigest(), identity.payloadDigest(), outcome.status(),
                outcome.receiverReceiptId(), outcome.receiverReceiptRevision(),
                outcome.receiverReceiptDigest(), outcome.reasonCode(), outcome.outcomeAt(),
                identity.createdAt(), outcome.responseUpdatedAt(), outcome.responseVersion());
    }

    private void quarantine(
            long tenantId,
            UUID handoffId,
            String reason,
            String evidenceDigest,
            UUID correlationId) {
        jdbc.update("""
                INSERT INTO tme_close_quarantine (
                    public_id, tenant_id, handoff_public_id, reason_code,
                    evidence_digest, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, handoffId, reason,
                evidenceDigest, correlationId, Timestamp.from(clock.instant()));
    }

    private static BaseException opaqueNotFound() {
        return new BaseException(ErrorCode.NOT_FOUND, "The close resource was not found.");
    }

    private static TimeCloseBoundaryFailure versionConflict(long currentVersion) {
        return new TimeCloseBoundaryFailure(
                412, "PRECONDITION_FAILED", false, currentVersion,
                "The close period aggregate version changed.");
    }

    private record StoredOutcome(
            String commandDigest,
            HandoffState status,
            UUID receiverReceiptId,
            long receiverReceiptRevision,
            String receiverReceiptDigest,
            String reasonCode,
            Instant outcomeAt,
            Instant responseUpdatedAt,
            long responseVersion) {}

    record OutcomeApplication(
            PayrollTimeHandoffStatusV1 projection, boolean conflict) {}
}
