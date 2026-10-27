package com.dwp.services.platform.hrisconfiguration.automation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.AutomationRepository.*;
import static com.dwp.services.platform.hrisconfiguration.automation.JdbcAutomationRepositoryData.*;

/** Internal jdbc automation evidence operations shared by the owner facade. */
final class JdbcAutomationEvidence {

    private final JdbcAutomationRepositoryOperations owner;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AutomationCanonicalJson canonical;

    JdbcAutomationEvidence(
            JdbcAutomationRepositoryOperations owner,
            JdbcTemplate jdbc,
            ObjectMapper json,
            AutomationCanonicalJson canonical) {
        this.owner = owner;
        this.jdbc = jdbc;
        this.json = json;
        this.canonical = canonical;
    }


    public void appendRunEventAndAudit(
            CommandSeal command,
            RunRecord run,
            String transition,
            OffsetDateTime now) {
        validateRunEvent(run);
        JsonNode payload = json.valueToTree(new RunEventPayload(
                run.publicId(), run.state(), run.itemTotal(),
                run.itemSucceeded(), run.itemFailed(), run.outputDigest()));
        String payloadText = canonical.text(payload);
        String payloadDigest = AutomationDomainPolicy.sha256(
                payloadText.getBytes(StandardCharsets.UTF_8));
        UUID eventId = UUID.nameUUIDFromBytes((command.tenantId() + ":"
                + run.publicId() + ":" + run.objectRevision())
                .getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                INSERT INTO sys_domain_event_outbox (
                    outbox_id, event_id, spec_version, event_source, event_type,
                    schema_version, subject, tenant_id, aggregate_type,
                    aggregate_id, aggregate_sequence, correlation_id, payload,
                    payload_sha256, status, available_at, created_at, updated_at)
                VALUES (?, ?, '1.0', ?, ?, 1, NULL, ?, 'HRIS_AUTOMATION_RUN',
                        ?, ?, ?, ?::jsonb, ?, 'PENDING', ?, ?, ?)
                """, UUID.randomUUID(), eventId, EVENT_SOURCE, EVENT_TYPE,
                command.tenantId(), run.publicId().toString(),
                run.objectRevision(), command.correlationId().toString(),
                payloadText, payloadDigest, now, now, now);
        JsonNode audit = json.valueToTree(new AuditPayload(
                "SYS-006", transition, run.publicId(), run.objectRevision(),
                run.state(), command.correlationId(), payloadDigest));
        jdbc.update("""
                INSERT INTO sys_audit_outbox (
                    outbox_id, event_id, tenant_id, payload, status,
                    available_at, created_at, updated_at)
                VALUES (?, ?, ?, ?::jsonb, 'PENDING', ?, ?, ?)
                """, UUID.randomUUID(), UUID.randomUUID(), command.tenantId(),
                owner.stringify(audit), now, now, now);
    }

    void validateRunEvent(RunRecord run) {
        Map<String, Object> counts = jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE lifecycle_state = 'SUCCEEDED') AS succeeded,
                       COUNT(*) FILTER (WHERE lifecycle_state IN
                           ('FAILED_RETRYABLE', 'FAILED_FINAL', 'DEAD_LETTER')) AS failed,
                       COUNT(*) FILTER (WHERE lifecycle_state = 'FAILED_RETRYABLE')
                           AS retryable,
                       COUNT(*) FILTER (WHERE effect_state IN
                           ('PREPARED', 'RESULT_UNKNOWN')) AS uncertain,
                       COUNT(*) FILTER (WHERE compensation_state IN
                           ('PENDING', 'RUNNING', 'RESULT_UNKNOWN')) AS compensation_pending
                  FROM sys_automation_run_items
                 WHERE tenant_id = ? AND run_id = ?
                """, run.tenantId(), run.runId());
        long total = ((Number) counts.get("total")).longValue();
        long succeeded = ((Number) counts.get("succeeded")).longValue();
        long failed = ((Number) counts.get("failed")).longValue();
        long retryable = ((Number) counts.get("retryable")).longValue();
        long uncertain = ((Number) counts.get("uncertain")).longValue();
        long compensationPending = ((Number) counts.get("compensation_pending"))
                .longValue();
        if (run.itemTotal() != total || run.itemSucceeded() != succeeded
                || run.itemFailed() != failed || succeeded + failed > total) {
            throw conflict("AUTOMATION_RUN_EVENT_COUNT_MISMATCH");
        }
        boolean validState = switch (run.state()) {
            case QUEUED, LEASED, RUNNING -> succeeded + failed <= total;
            case SUCCEEDED -> succeeded == total && failed == 0;
            case PARTIAL -> succeeded > 0 && failed > 0
                    && succeeded + failed == total && retryable > 0
                    && run.currentAttempt() < run.maxAttempts();
            case FAILED -> succeeded == 0 && failed == total && total > 0
                    && retryable > 0 && run.currentAttempt() < run.maxAttempts();
            case CANCELLED -> uncertain == 0 && compensationPending == 0;
            case DEAD_LETTER -> failed > 0 && succeeded + failed == total
                    && (retryable == 0 || run.currentAttempt() >= run.maxAttempts());
        };
        if (!validState) {
            throw conflict("AUTOMATION_RUN_EVENT_STATE_INVALID");
        }
        boolean terminal = List.of(
                RunState.SUCCEEDED, RunState.CANCELLED, RunState.DEAD_LETTER)
                .contains(run.state());
        if (run.outputDigest() != null || terminal) {
            ReceiptSet receiptSet = currentReceiptSet(
                    run.tenantId(), run.runId(), run.publicId(), run.objectRevision());
            if (!receiptSet.digest().equals(run.outputDigest())
                    || receiptSet.itemCount() != total
                    || (terminal && receiptSet.terminalCount() != total)) {
                throw conflict("AUTOMATION_RUN_EVENT_OUTPUT_DIGEST_INVALID");
            }
        }
    }

    ReceiptSet currentReceiptSet(
            long tenantId,
            UUID runId,
            UUID runPublicId,
            long runRevision) {
        List<ReceiptDigestItem> receipts = jdbc.query("""
                SELECT i.public_id, i.item_sequence, a.receipt_state,
                       a.attempt_ordinal, a.result_digest, a.error_code,
                       i.compensation_receipt_digest
                  FROM sys_automation_run_items i
                  JOIN LATERAL (
                       SELECT ia.receipt_state, ia.attempt_ordinal,
                              ia.result_digest, ia.error_code
                         FROM sys_automation_item_attempts ia
                        WHERE ia.tenant_id = i.tenant_id
                          AND ia.run_id = i.run_id
                          AND ia.run_item_id = i.run_item_id
                          AND ia.run_revision_snapshot <= ?
                        ORDER BY ia.run_revision_snapshot DESC,
                                 ia.attempt_ordinal DESC
                        LIMIT 1
                  ) a ON TRUE
                 WHERE i.tenant_id = ? AND i.run_id = ?
                 ORDER BY i.item_sequence, i.public_id
                """, (rs, row) -> new ReceiptDigestItem(
                uuid(rs, "public_id"), rs.getLong("item_sequence"),
                rs.getString("receipt_state"), rs.getInt("attempt_ordinal"),
                rs.getString("result_digest"), rs.getString("error_code"),
                rs.getString("compensation_receipt_digest")),
                runRevision, tenantId, runId);
        List<Map<String, Object>> items = receipts.stream().map(receipt -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemPublicId", receipt.itemPublicId());
            item.put("itemSequence", Long.toString(receipt.itemSequence()));
            item.put("receiptState", receipt.receiptState());
            item.put("attemptNumber", receipt.attemptNumber());
            item.put("resultDigest", receipt.resultDigest());
            item.put("errorCode", receipt.errorCode());
            item.put("compensationReceiptDigest",
                    receipt.compensationReceiptDigest());
            return item;
        }).toList();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("runPublicId", runPublicId);
        document.put("runRevision", Long.toString(runRevision));
        document.put("items", items);
        long terminal = receipts.stream().filter(receipt -> List.of(
                "SUCCEEDED", "FAILED_RETRYABLE", "FAILED_FINAL", "CANCELLED",
                "DEAD_LETTER").contains(receipt.receiptState())).count();
        return new ReceiptSet(canonical.digest(document), receipts.size(), terminal);
    }

    ReceiptSet currentAttemptReceiptSet(
            long tenantId,
            UUID runId,
            UUID runPublicId,
            int attemptNumber,
            long runRevision) {
        List<ReceiptDigestItem> receipts = jdbc.query("""
                SELECT i.public_id, i.item_sequence, a.receipt_state,
                       a.attempt_ordinal, a.result_digest, a.error_code,
                       NULL::TEXT AS compensation_receipt_digest
                  FROM sys_automation_run_items i
                  JOIN sys_automation_item_attempts a
                    ON a.tenant_id = i.tenant_id
                   AND a.run_id = i.run_id
                   AND a.run_item_id = i.run_item_id
                 WHERE i.tenant_id = ? AND i.run_id = ?
                   AND a.attempt_ordinal = ?
                   AND a.run_revision_snapshot = ?
                   AND a.completed_at IS NOT NULL
                 ORDER BY i.item_sequence, i.public_id
                """, (rs, row) -> new ReceiptDigestItem(
                uuid(rs, "public_id"), rs.getLong("item_sequence"),
                rs.getString("receipt_state"), rs.getInt("attempt_ordinal"),
                rs.getString("result_digest"), rs.getString("error_code"),
                rs.getString("compensation_receipt_digest")),
                tenantId, runId, attemptNumber, runRevision);
        List<Map<String, Object>> items = receipts.stream().map(receipt -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemPublicId", receipt.itemPublicId());
            item.put("itemSequence", Long.toString(receipt.itemSequence()));
            item.put("receiptState", receipt.receiptState());
            item.put("attemptNumber", receipt.attemptNumber());
            item.put("resultDigest", receipt.resultDigest());
            item.put("errorCode", receipt.errorCode());
            item.put("compensationReceiptDigest",
                    receipt.compensationReceiptDigest());
            return item;
        }).toList();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("runPublicId", runPublicId);
        document.put("runRevision", Long.toString(runRevision));
        document.put("items", items);
        long terminal = receipts.stream().filter(receipt -> List.of(
                "SUCCEEDED", "FAILED_RETRYABLE", "FAILED_FINAL", "CANCELLED",
                "DEAD_LETTER").contains(receipt.receiptState())).count();
        return new ReceiptSet(canonical.digest(document), receipts.size(), terminal);
    }
}
