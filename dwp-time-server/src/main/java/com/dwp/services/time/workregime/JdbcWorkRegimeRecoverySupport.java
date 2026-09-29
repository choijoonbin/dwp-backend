package com.dwp.services.time.workregime;

import com.dwp.services.time.workregime.WorkRegimeModels.LifecycleAction;
import com.dwp.services.time.workregime.WorkRegimeRepository.CommandOutcomeEvidence;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reads exact immutable evidence used to settle indeterminate command receipts. */
final class JdbcWorkRegimeRecoverySupport {

    private final JdbcTemplate jdbc;

    JdbcWorkRegimeRecoverySupport(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
    }

    Optional<CommandOutcomeEvidence> findOutcome(
            long tenantId,
            UUID receiptId,
            UUID aggregateId,
            LifecycleAction operation) {
        String eventType = eventType(operation);
        if (eventType == null) return Optional.empty();
        List<String> digests = jdbc.queryForList("""
                SELECT after_digest
                  FROM tim_work_regime_audit_events
                 WHERE tenant_id = ?
                   AND receipt_public_id = ?
                   AND aggregate_type = 'WORK_REGIME'
                   AND aggregate_public_id = ?
                   AND event_type = ?
                """, String.class, tenantId, receiptId, aggregateId, eventType);
        if (digests.size() > 1) {
            throw new IllegalStateException(
                    "Command receipt resolves to multiple mutation outcomes: " + receiptId);
        }
        return digests.stream().findFirst().map(digest -> new CommandOutcomeEvidence(
                resultCode(operation), digest.trim()));
    }

    private static String eventType(LifecycleAction operation) {
        return switch (operation) {
            case CREATE_DRAFT -> "WORK_REGIME_DRAFT_CREATED";
            case VALIDATE -> "WORK_REGIME_VALIDATED";
            case SUBMIT_REVIEW -> "WORK_REGIME_IN_REVIEW";
            case APPLY_APPROVAL -> "WORK_REGIME_APPROVED";
            case PUBLISH -> "WORK_REGIME_PUBLISHED";
            case REVISE_DRAFT, SIMULATE, ASSIGN -> null;
        };
    }

    private static String resultCode(LifecycleAction operation) {
        return operation == LifecycleAction.CREATE_DRAFT
                ? "DRAFT_CREATED" : operation.name() + "_SUCCEEDED";
    }
}
