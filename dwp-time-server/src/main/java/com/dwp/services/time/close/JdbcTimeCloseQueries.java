package com.dwp.services.time.close;

import com.dwp.contracts.hris.xcon.v1.Xcon008ClosedTimeResultV1;
import com.dwp.services.time.close.TimeCloseModels.ClosePeriod;
import com.dwp.services.time.close.TimeCloseModels.CloseState;
import com.dwp.services.time.close.TimeCloseModels.HandoffRecord;
import com.dwp.services.time.close.TimeCloseModels.HandoffState;
import com.dwp.services.time.close.TimeCloseModels.PayrollTimeHandoffStatusV1;
import com.dwp.services.time.close.TimeCloseModels.PersistedClosedResult;
import com.dwp.services.time.close.TimeCloseModels.ResultLine;
import com.dwp.services.time.close.TimeCloseModels.SourceEvidence;
import com.dwp.services.time.close.TimeCloseModels.StoredReceipt;
import com.dwp.services.time.close.TimeCloseModels.TimeCloseCommandReceiptV1;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads and integrity verification performed in the owner's tenant transaction. */
final class JdbcTimeCloseQueries {

    private final JdbcTemplate jdbc;

    JdbcTimeCloseQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<TimeCloseIaQueryModels.CloseProjectionRow> queryProjectionRows(
            long tenantId,
            UUID legalEntityPublicId,
            UUID timeGroupPublicId,
            String closeScope,
            Instant asOf) {
        return jdbc.query("""
                SELECT p.public_id, p.close_scope, p.period_start, p.period_end,
                       p.status, p.close_revision, p.row_version,
                       p.created_at AS period_created_at,
                       p.updated_at AS period_updated_at,
                       COALESCE(a.blocker_count, 0) AS blocker_count,
                       COALESCE(cr.line_count, 0) AS impact_count,
                       rc.public_id AS receipt_public_id,
                       rc.status AS receipt_status,
                       rc.created_at AS receipt_created_at,
                       rc.completed_at AS receipt_completed_at,
                       rc.aggregate_version AS receipt_revision,
                       h.public_id AS handoff_public_id,
                       h.status AS handoff_status,
                       h.created_at AS handoff_created_at,
                       h.updated_at AS handoff_updated_at,
                       h.row_version AS handoff_revision
                  FROM tme_close_periods p
                  LEFT JOIN tme_closed_time_results cr
                    ON cr.tenant_id=p.tenant_id
                   AND cr.close_period_id=p.close_period_id
                   AND cr.public_id=p.latest_result_public_id
                  LEFT JOIN LATERAL (
                       SELECT ca.blocker_count
                         FROM tme_close_attempts ca
                        WHERE ca.tenant_id=p.tenant_id
                          AND ca.close_period_id=p.close_period_id
                          AND ca.completed_at <= ?
                        ORDER BY ca.attempt_no DESC
                        LIMIT 1
                  ) a ON TRUE
                  LEFT JOIN LATERAL (
                       SELECT r.public_id, r.status, r.created_at, r.completed_at,
                              r.aggregate_version
                         FROM tme_close_command_receipts r
                        WHERE r.tenant_id=p.tenant_id
                          AND r.aggregate_public_id=p.public_id
                          AND r.created_at <= ?
                        ORDER BY r.created_at DESC, r.public_id DESC
                        LIMIT 1
                  ) rc ON TRUE
                  LEFT JOIN LATERAL (
                       SELECT ph.public_id, ph.status, ph.created_at, ph.updated_at,
                              ph.row_version
                         FROM tme_payroll_handoffs ph
                        WHERE ph.tenant_id=p.tenant_id
                          AND ph.close_period_id=p.close_period_id
                          AND ph.created_at <= ?
                        ORDER BY ph.created_at DESC, ph.public_id DESC
                        LIMIT 1
                  ) h ON TRUE
                 WHERE p.tenant_id=?
                   AND p.legal_entity_public_id=?
                   AND p.time_group_public_id=?
                   AND (? IS NULL OR p.close_scope=?)
                   AND p.created_at <= ?
                 ORDER BY p.public_id
                """, (row, ignored) -> new TimeCloseIaQueryModels.CloseProjectionRow(
                        row.getObject("public_id", UUID.class),
                        row.getString("close_scope"),
                        row.getDate("period_start").toLocalDate(),
                        row.getDate("period_end").toLocalDate(),
                        row.getString("status"), row.getLong("close_revision"),
                        row.getLong("row_version"),
                        row.getTimestamp("period_created_at").toInstant(),
                        row.getTimestamp("period_updated_at").toInstant(),
                        row.getInt("blocker_count"),
                        row.getInt("impact_count"),
                        row.getObject("receipt_public_id", UUID.class),
                        row.getString("receipt_status"),
                        instant(row.getTimestamp("receipt_created_at")),
                        instant(row.getTimestamp("receipt_completed_at")),
                        nullableLong(row, "receipt_revision"),
                        row.getObject("handoff_public_id", UUID.class),
                        row.getString("handoff_status"),
                        instant(row.getTimestamp("handoff_created_at")),
                        instant(row.getTimestamp("handoff_updated_at")),
                        nullableLong(row, "handoff_revision")),
                Timestamp.from(asOf), Timestamp.from(asOf), Timestamp.from(asOf),
                tenantId, legalEntityPublicId, timeGroupPublicId, closeScope, closeScope,
                Timestamp.from(asOf));
    }

    Optional<ClosePeriod> period(long tenantId, UUID periodId, boolean lock) {
        List<ClosePeriod> rows = jdbc.query("""
                SELECT close_period_id, tenant_id, public_id, close_scope,
                       legal_entity_public_id, time_group_public_id,
                       payroll_entity_public_id, pay_period_public_id,
                       period_start, period_end, status, close_revision,
                       ledger_revision, latest_result_public_id,
                       closed_by_subject_public_id,
                       close_approval_actor_public_id,
                       latest_correction_maker_subject_public_id, row_version
                  FROM tme_close_periods
                 WHERE tenant_id=? AND public_id=?
                """ + (lock ? " FOR UPDATE" : ""), (row, ignored) -> new ClosePeriod(
                        row.getLong("close_period_id"), row.getLong("tenant_id"),
                        row.getObject("public_id", UUID.class), row.getString("close_scope"),
                        row.getObject("legal_entity_public_id", UUID.class),
                        row.getObject("time_group_public_id", UUID.class),
                        row.getObject("payroll_entity_public_id", UUID.class),
                        row.getObject("pay_period_public_id", UUID.class),
                        row.getDate("period_start").toLocalDate(),
                        row.getDate("period_end").toLocalDate(),
                        CloseState.valueOf(row.getString("status")),
                        row.getLong("close_revision"), nullableLong(row, "ledger_revision"),
                        row.getObject("latest_result_public_id", UUID.class),
                        row.getObject("closed_by_subject_public_id", UUID.class),
                        row.getObject("close_approval_actor_public_id", UUID.class),
                        row.getObject("latest_correction_maker_subject_public_id", UUID.class),
                        row.getLong("row_version")), tenantId, periodId);
        return rows.stream().findFirst();
    }

    Optional<PersistedClosedResult> result(long tenantId, UUID resultId) {
        List<ResultHeader> headers = jdbc.query("""
                SELECT closed_time_result_id, public_id, close_period_id,
                       close_revision, payroll_entity_public_id, pay_period_public_id,
                       ledger_revision_from, ledger_revision_to, worker_count, line_count,
                       source_digest, rule_digest, payload_digest,
                       supersedes_result_public_id, created_at,
                       (SELECT public_id FROM tme_close_periods p
                         WHERE p.tenant_id=r.tenant_id
                           AND p.close_period_id=r.close_period_id) AS close_period_public_id
                  FROM tme_closed_time_results r
                 WHERE tenant_id=? AND public_id=?
                """, (row, ignored) -> new ResultHeader(
                        row.getLong("closed_time_result_id"),
                        row.getObject("public_id", UUID.class),
                        row.getObject("close_period_public_id", UUID.class),
                        row.getLong("close_revision"),
                        row.getObject("payroll_entity_public_id", UUID.class),
                        row.getObject("pay_period_public_id", UUID.class),
                        row.getLong("ledger_revision_from"),
                        row.getLong("ledger_revision_to"), row.getInt("worker_count"),
                        row.getInt("line_count"), row.getString("source_digest").trim(),
                        row.getString("rule_digest").trim(),
                        row.getString("payload_digest").trim(),
                        row.getObject("supersedes_result_public_id", UUID.class),
                        row.getTimestamp("created_at").toInstant()), tenantId, resultId);
        if (headers.isEmpty()) return Optional.empty();
        ResultHeader header = headers.getFirst();
        List<ResultSourceRow> rows = jdbc.query("""
                SELECT l.closed_time_result_line_id, l.line_no,
                       l.worker_public_id, l.assignment_public_id, l.pay_code,
                       l.quantity, l.currency_code, l.source_entry_count,
                       l.source_entry_digest, l.unit, s.source_sequence,
                       s.ledger_entry_public_id, s.ledger_entry_posted_at,
                       s.ledger_revision, s.contribution_quantity,
                       s.contribution_unit, s.entry_digest
                  FROM tme_closed_time_result_lines l
                  JOIN tme_closed_time_result_sources s
                    ON s.tenant_id=l.tenant_id
                   AND s.closed_time_result_line_id=l.closed_time_result_line_id
                 WHERE l.tenant_id=? AND l.closed_time_result_id=?
                 ORDER BY l.line_no, s.source_sequence
                """, (row, ignored) -> new ResultSourceRow(
                        row.getLong("closed_time_result_line_id"), row.getInt("line_no"),
                        row.getObject("worker_public_id", UUID.class),
                        row.getObject("assignment_public_id", UUID.class),
                        row.getString("pay_code"), row.getBigDecimal("quantity"),
                        row.getString("currency_code").trim(),
                        row.getInt("source_entry_count"),
                        row.getString("source_entry_digest").trim(), row.getString("unit"),
                        row.getInt("source_sequence"),
                        row.getObject("ledger_entry_public_id", UUID.class),
                        row.getTimestamp("ledger_entry_posted_at").toInstant(),
                        row.getLong("ledger_revision"),
                        row.getBigDecimal("contribution_quantity"),
                        row.getString("contribution_unit"),
                        row.getString("entry_digest").trim()), tenantId, header.internalId());
        List<ResultLine> verifiedLines = new ArrayList<>(header.lineCount());
        int rowIndex = 0;
        for (int expectedLine = 1; expectedLine <= header.lineCount(); expectedLine++) {
            if (rowIndex >= rows.size() || rows.get(rowIndex).lineNo() != expectedLine) {
                throw TimeCloseResultIntegrity.corrupt(
                        "Closed-time result line sequence is not contiguous.");
            }
            ResultSourceRow first = rows.get(rowIndex);
            List<SourceEvidence> sources = new ArrayList<>(first.sourceCount());
            for (int expectedSource = 1; expectedSource <= first.sourceCount(); expectedSource++) {
                if (rowIndex >= rows.size()) {
                    throw TimeCloseResultIntegrity.corrupt(
                            "Closed-time result source count is mismatched.");
                }
                ResultSourceRow source = rows.get(rowIndex++);
                if (source.lineId() != first.lineId()
                        || source.lineNo() != expectedLine
                        || source.sourceSequence() != expectedSource) {
                    throw TimeCloseResultIntegrity.corrupt(
                            "Closed-time result source sequence is not contiguous.");
                }
                sources.add(new SourceEvidence(
                        source.ledgerEntryId(), source.postedAt(), source.ledgerRevision(),
                        source.contributionQuantity(), source.contributionUnit(),
                        source.entryDigest()));
            }
            String currency = "XXX".equals(first.currency()) ? null : first.currency();
            verifiedLines.add(new ResultLine(
                    first.workerId(), first.assignmentId(), first.payCode(), first.quantity(),
                    first.unit(), currency, first.sourceEntryDigest(), sources));
        }
        if (rowIndex != rows.size() || verifiedLines.size() != header.lineCount()) {
            throw TimeCloseResultIntegrity.corrupt(
                    "Closed-time result contains trailing or missing evidence.");
        }
        TimeCloseResultIntegrity.verifyEvidence(verifiedLines, header.sourceDigest());
        long workerCount = verifiedLines.stream().map(ResultLine::workerId).distinct().count();
        if (workerCount != header.workerCount()) {
            throw TimeCloseResultIntegrity.corrupt(
                    "Closed-time result worker count is mismatched.");
        }
        List<Xcon008ClosedTimeResultV1.ClosedTimeLine> lines = verifiedLines.stream()
                .map(ResultLine::toXcon)
                .toList();
        String payloadDigest = TimeCloseResultIntegrity.payloadDigest(
                header.closePeriodId(), header.closeRevision(), header.publicId(),
                header.ledgerFrom(), header.ledgerTo(), header.payPeriodId(),
                header.payrollEntityId(), header.ruleDigest(), header.sourceDigest(),
                header.supersedesId(), header.workerCount(), lines);
        if (!payloadDigest.equals(header.payloadDigest())) {
            throw TimeCloseResultIntegrity.corrupt(
                    "Closed-time result payload digest is mismatched.");
        }
        Xcon008ClosedTimeResultV1 snapshot = new Xcon008ClosedTimeResultV1(
                header.closePeriodId(), header.closeRevision(), header.publicId(),
                header.ledgerFrom(), header.ledgerTo(), header.lineCount(), lines,
                header.payPeriodId(), header.payloadDigest(), header.payrollEntityId(),
                header.ruleDigest(), header.sourceDigest(), header.supersedesId(),
                header.workerCount());
        return Optional.of(new PersistedClosedResult(
                header.internalId(), snapshot, header.createdAt()));
    }

    Optional<HandoffRecord> handoff(long tenantId, UUID handoffId, boolean lock) {
        List<HandoffRecord> rows = jdbc.query("""
                SELECT h.payroll_handoff_id, h.tenant_id, h.public_id,
                       h.close_period_public_id, r.public_id AS result_public_id,
                       h.close_revision, h.payroll_entity_public_id,
                       h.pay_period_public_id, h.worker_count, h.line_count,
                       h.source_digest, h.rule_digest, h.payload_digest, h.status,
                       h.correlation_id,
                       h.receiver_receipt_public_id, h.receiver_receipt_revision,
                       h.receiver_receipt_digest, h.receiver_reason_code,
                       h.outcome_at, h.created_at, h.updated_at, h.row_version
                  FROM tme_payroll_handoffs h
                  JOIN tme_closed_time_results r
                    ON r.tenant_id=h.tenant_id
                   AND r.closed_time_result_id=h.closed_time_result_id
                 WHERE h.tenant_id=? AND h.public_id=?
                """ + (lock ? " FOR UPDATE OF h" : ""), (row, ignored) ->
                        new HandoffRecord(
                                row.getLong("payroll_handoff_id"),
                                row.getLong("tenant_id"),
                                row.getObject("correlation_id", UUID.class),
                                new PayrollTimeHandoffStatusV1(
                                        row.getObject("public_id", UUID.class),
                                        row.getObject("close_period_public_id", UUID.class),
                                        row.getObject("result_public_id", UUID.class),
                                        row.getLong("close_revision"),
                                        row.getObject("payroll_entity_public_id", UUID.class),
                                        row.getObject("pay_period_public_id", UUID.class),
                                        row.getInt("worker_count"), row.getInt("line_count"),
                                        row.getString("source_digest").trim(),
                                        row.getString("rule_digest").trim(),
                                        row.getString("payload_digest").trim(),
                                        HandoffState.valueOf(row.getString("status")),
                                        row.getObject("receiver_receipt_public_id", UUID.class),
                                        nullableLong(row, "receiver_receipt_revision"),
                                        trim(row.getString("receiver_receipt_digest")),
                                        row.getString("receiver_reason_code"),
                                        instant(row.getTimestamp("outcome_at")),
                                        row.getTimestamp("created_at").toInstant(),
                                        row.getTimestamp("updated_at").toInstant(),
                                        row.getLong("row_version"))), tenantId, handoffId);
        return rows.stream().findFirst();
    }

    Optional<StoredReceipt> receiptByIdempotency(
            long tenantId, UUID subject, String route, String key) {
        return receipts("""
                WHERE tenant_id=? AND subject_principal_public_id=?
                  AND route_contract_key=? AND idempotency_key=?
                """, tenantId, subject, route, key).stream().findFirst();
    }

    Optional<StoredReceipt> receiptById(long tenantId, UUID receiptId) {
        return receipts("WHERE tenant_id=? AND public_id=?", tenantId, receiptId)
                .stream().findFirst();
    }

    List<StoredReceipt> receipts(String predicate, Object... arguments) {
        return jdbc.query("""
                SELECT public_id, idempotency_key, route_contract_key,
                       command_type, originating_action,
                       subject_principal_public_id, population_public_id,
                       population_revision, grant_revision, population_scope_digest,
                       field_policy_revision, purpose_code, authorization_revision,
                       required_permission, request_digest, aggregate_public_id,
                       aggregate_version, status, result_ref, correlation_id, created_at
                  FROM tme_close_command_receipts
                """ + predicate, (row, ignored) -> {
                    TimeCloseCommandReceiptV1 receipt = new TimeCloseCommandReceiptV1(
                            row.getObject("public_id", UUID.class),
                            row.getString("command_type"), row.getString("originating_action"),
                            row.getObject("subject_principal_public_id", UUID.class),
                            row.getString("population_scope_digest").trim(),
                            row.getLong("field_policy_revision"),
                            row.getString("purpose_code"),
                            row.getLong("authorization_revision"), row.getString("status"),
                            row.getObject("correlation_id", UUID.class),
                            row.getString("request_digest").trim(),
                            row.getTimestamp("created_at").toInstant(),
                            nullableLong(row, "aggregate_version"), row.getString("result_ref"));
                    return new StoredReceipt(
                            receipt, row.getString("idempotency_key"),
                            row.getString("route_contract_key"),
                            row.getObject("population_public_id", UUID.class),
                            row.getLong("population_revision"), row.getLong("grant_revision"),
                            row.getString("required_permission"),
                            row.getObject("aggregate_public_id", UUID.class));
                }, arguments);
    }

    private static Long nullableLong(java.sql.ResultSet row, String column)
            throws java.sql.SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record ResultHeader(
            long internalId,
            UUID publicId,
            UUID closePeriodId,
            long closeRevision,
            UUID payrollEntityId,
            UUID payPeriodId,
            long ledgerFrom,
            long ledgerTo,
            int workerCount,
            int lineCount,
            String sourceDigest,
            String ruleDigest,
            String payloadDigest,
            UUID supersedesId,
            Instant createdAt) {}

    private record ResultSourceRow(
            long lineId,
            int lineNo,
            UUID workerId,
            UUID assignmentId,
            String payCode,
            java.math.BigDecimal quantity,
            String currency,
            int sourceCount,
            String sourceEntryDigest,
            String unit,
            int sourceSequence,
            UUID ledgerEntryId,
            Instant postedAt,
            long ledgerRevision,
            java.math.BigDecimal contributionQuantity,
            String contributionUnit,
            String entryDigest) {}

}
