package com.dwp.services.time.interpretation;

import com.dwp.services.time.interpretation.InterpretationModels.InterpretationEntry;
import com.dwp.services.time.interpretation.InterpretationModels.InterpretationRun;
import com.dwp.services.time.interpretation.InterpretationModels.RunStatus;
import com.dwp.services.time.interpretation.InterpretationModels.TraceStep;
import com.dwp.services.time.interpretation.InterpretationModels.WorkerMinuteResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Compact JDBC row representation used while rebuilding immutable interpretation evidence. */
record JdbcInterpretationRunRow(
        UUID runId,
        long tenantId,
        UUID subjectId,
        LocalDate periodStart,
        LocalDate periodEnd,
        Instant asOf,
        UUID ruleVersionId,
        long ruleVersionRevision,
        String inputDigest,
        String ruleDigest,
        String outputDigest,
        int attemptNo,
        RunStatus status,
        boolean ledgerDeltaApplied,
        UUID supersedesRunId,
        UUID correlationId,
        Instant createdAt,
        long attemptInternalId) {

    static JdbcInterpretationRunRow map(ResultSet row, int ignored) throws SQLException {
        return new JdbcInterpretationRunRow(
                row.getObject("public_id", UUID.class),
                row.getLong("tenant_id"),
                row.getObject("subject_principal_public_id", UUID.class),
                row.getObject("period_start", LocalDate.class),
                row.getObject("period_end", LocalDate.class),
                row.getTimestamp("as_of").toInstant(),
                row.getObject("rule_version_public_id", UUID.class),
                row.getLong("rule_version_revision"),
                row.getString("input_snapshot_digest").trim(),
                row.getString("rule_snapshot_digest").trim(),
                row.getString("output_digest").trim(),
                row.getInt("attempt_no"),
                RunStatus.valueOf(row.getString("status")),
                row.getBoolean("ledger_delta_applied"),
                row.getObject("supersedes_run_public_id", UUID.class),
                row.getObject("correlation_id", UUID.class),
                row.getTimestamp("created_at").toInstant(),
                row.getLong("interpretation_attempt_id"));
    }

    InterpretationRun toRun(
            List<WorkerMinuteResult> results,
            List<InterpretationEntry> entries,
            List<TraceStep> traceSteps) {
        return new InterpretationRun(
                runId, tenantId, subjectId, periodStart, periodEnd, asOf,
                ruleVersionId, ruleVersionRevision, inputDigest, ruleDigest, outputDigest,
                attemptNo, status, ledgerDeltaApplied, supersedesRunId,
                correlationId, createdAt, results, entries, traceSteps);
    }
}
