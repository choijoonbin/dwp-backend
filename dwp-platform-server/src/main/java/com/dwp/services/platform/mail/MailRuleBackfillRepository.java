package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class MailRuleBackfillRepository {

    record Claim(
            UUID executionId,
            UUID requestId,
            UUID accountId,
            long generation,
            UUID leaseToken,
            MailRuleBackfillDtos.Result replay) {

        boolean replayed() {
            return replay != null;
        }
    }

    private record ExecutionRow(
            UUID executionId,
            UUID requestId,
            UUID accountId,
            String requestFingerprint,
            String status,
            long generation,
            UUID leaseToken,
            boolean leaseActive,
            int scannedCount,
            int matchedThreadCount,
            int applicationCount,
            int changedCount,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt) {
    }

    private final JdbcTemplate jdbc;

    MailRuleBackfillRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Claim claim(
            Long tenantId,
            Long userId,
            UUID accountId,
            UUID requestId,
            String requestFingerprint,
            String previewFingerprint) {
        lockRequestScope(tenantId, accountId, requestId);
        Optional<ExecutionRow> existing = execution(tenantId, userId, accountId, requestId, true);
        if (existing.isPresent()) {
            ExecutionRow row = existing.orElseThrow();
            if (!row.requestFingerprint().equals(requestFingerprint)) {
                throw conflict("The request ID was already used for a different backfill preview.");
            }
            if ("SUCCEEDED".equals(row.status())) {
                return new Claim(
                        row.executionId(), row.requestId(), row.accountId(), row.generation(),
                        row.leaseToken(), result(row, true));
            }
            if ("RUNNING".equals(row.status()) && row.leaseActive()) {
                throw conflict("This backfill request is already running.");
            }
            UUID leaseToken = UUID.randomUUID();
            long generation = row.generation() + 1;
            int updated = jdbc.update("""
                    UPDATE mail_rule_backfill_executions
                       SET execution_status = 'RUNNING', generation = ?, lease_token = ?,
                           lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                           error_code = NULL, completed_at = NULL,
                           started_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                     WHERE execution_id = ? AND generation = ?
                    """, generation, leaseToken, row.executionId(), row.generation());
            if (updated != 1) throw conflict("The backfill lease changed. Refresh and try again.");
            return new Claim(
                    row.executionId(), requestId, accountId, generation, leaseToken, null);
        }

        UUID executionId = UUID.randomUUID();
        UUID leaseToken = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO mail_rule_backfill_executions (
                    execution_id, tenant_id, account_id, owner_user_id, request_id,
                    request_fingerprint, preview_fingerprint, execution_status,
                    generation, lease_token, lease_expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'RUNNING', 1, ?,
                        CURRENT_TIMESTAMP + INTERVAL '5 minutes')
                """, executionId, tenantId, accountId, userId, requestId,
                requestFingerprint, previewFingerprint, leaseToken);
        return new Claim(executionId, requestId, accountId, 1, leaseToken, null);
    }

    void requireActiveLease(Long tenantId, Long userId, Claim claim) {
        List<UUID> rows = jdbc.query("""
                SELECT execution_id
                  FROM mail_rule_backfill_executions
                 WHERE tenant_id = ? AND owner_user_id = ? AND account_id = ?
                   AND execution_id = ? AND request_id = ?
                   AND generation = ? AND lease_token = ?
                   AND execution_status = 'RUNNING'
                   AND lease_expires_at > CURRENT_TIMESTAMP
                 FOR UPDATE
                """, (result, ignored) -> result.getObject("execution_id", UUID.class),
                tenantId, userId, claim.accountId(), claim.executionId(), claim.requestId(),
                claim.generation(), claim.leaseToken());
        if (rows.size() != 1) {
            throw conflict("The backfill lease expired. Refresh the preview and try again.");
        }
    }

    void requireActivePersonalAccount(Long tenantId, Long userId, UUID accountId) {
        List<UUID> rows = jdbc.query("""
                SELECT account_id
                  FROM mail_accounts
                 WHERE tenant_id = ? AND owner_user_id = ? AND account_id = ?
                   AND account_kind = 'PERSONAL' AND connection_state = 'ACTIVE'
                 FOR SHARE
                """, (result, ignored) -> result.getObject("account_id", UUID.class),
                tenantId, userId, accountId);
        if (rows.size() != 1) throw new BaseException(ErrorCode.NOT_FOUND);
    }

    void recordApplication(
            Claim claim,
            Long tenantId,
            UUID threadId,
            UUID ruleId,
            long ruleVersion,
            long beforeThreadVersion,
            boolean changed) {
        jdbc.update("""
                INSERT INTO mail_rule_backfill_applications (
                    execution_id, tenant_id, account_id, thread_id, rule_id,
                    rule_version, before_thread_version, after_thread_version, changed)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, claim.executionId(), tenantId, claim.accountId(), threadId, ruleId,
                ruleVersion, beforeThreadVersion, beforeThreadVersion + (changed ? 1 : 0), changed);
    }

    void recordRuleRun(
            Long tenantId,
            Long userId,
            MailOrganizationDtos.RuleSummary rule,
            int scanned,
            int matched,
            int changed) {
        jdbc.update("""
                INSERT INTO mail_rule_runs (
                    run_id, tenant_id, rule_id, trigger_kind, run_status,
                    scanned_count, matched_count, changed_count,
                    started_at, completed_at, initiated_by)
                VALUES (?, ?, ?, 'BACKFILL', 'SUCCEEDED', ?, ?, ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                """, UUID.randomUUID(), tenantId, rule.ruleId(), scanned, matched, changed, userId);
        int updated = jdbc.update("""
                UPDATE mail_rules
                   SET last_run_at = CURRENT_TIMESTAMP, last_match_count = ?,
                       version = version + 1, updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND owner_user_id = ? AND rule_id = ?
                   AND lifecycle_state = 'ACTIVE' AND enabled
                   AND version = ?
                """, matched, userId, tenantId, userId, rule.ruleId(), rule.version());
        if (updated != 1) {
            throw conflict("A mail rule changed while the backfill was running.");
        }
    }

    MailRuleBackfillDtos.Result complete(
            Long tenantId,
            Long userId,
            Claim claim,
            int scanned,
            int matchedThreads,
            int applications,
            int changed) {
        int updated = jdbc.update("""
                UPDATE mail_rule_backfill_executions
                   SET execution_status = 'SUCCEEDED', scanned_count = ?,
                       matched_thread_count = ?, application_count = ?, changed_count = ?,
                       completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND owner_user_id = ? AND account_id = ?
                   AND execution_id = ? AND request_id = ?
                   AND generation = ? AND lease_token = ?
                   AND execution_status = 'RUNNING'
                """, scanned, matchedThreads, applications, changed,
                tenantId, userId, claim.accountId(), claim.executionId(), claim.requestId(),
                claim.generation(), claim.leaseToken());
        if (updated != 1) throw conflict("The backfill completion lease is no longer valid.");
        return execution(tenantId, userId, claim.accountId(), claim.requestId(), false)
                .map(row -> result(row, false))
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    void fail(Long tenantId, Long userId, Claim claim, String errorCode) {
        jdbc.update("""
                UPDATE mail_rule_backfill_executions
                   SET execution_status = 'FAILED', error_code = ?,
                       completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND owner_user_id = ? AND account_id = ?
                   AND execution_id = ? AND request_id = ?
                   AND generation = ? AND lease_token = ?
                   AND execution_status = 'RUNNING'
                """, errorCode, tenantId, userId, claim.accountId(), claim.executionId(),
                claim.requestId(), claim.generation(), claim.leaseToken());
    }

    private Optional<ExecutionRow> execution(
            Long tenantId, Long userId, UUID accountId, UUID requestId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbc.query("""
                SELECT execution_id, request_id, account_id, request_fingerprint,
                       execution_status, generation, lease_token,
                       lease_expires_at > CURRENT_TIMESTAMP AS lease_active,
                       scanned_count, matched_thread_count, application_count, changed_count,
                       started_at, completed_at
                  FROM mail_rule_backfill_executions
                 WHERE tenant_id = ? AND owner_user_id = ? AND account_id = ? AND request_id = ?
                """ + suffix, (result, ignored) -> execution(result),
                tenantId, userId, accountId, requestId).stream().findFirst();
    }

    private ExecutionRow execution(ResultSet result) throws SQLException {
        return new ExecutionRow(
                result.getObject("execution_id", UUID.class),
                result.getObject("request_id", UUID.class),
                result.getObject("account_id", UUID.class),
                result.getString("request_fingerprint"),
                result.getString("execution_status"),
                result.getLong("generation"),
                result.getObject("lease_token", UUID.class),
                result.getBoolean("lease_active"),
                result.getInt("scanned_count"),
                result.getInt("matched_thread_count"),
                result.getInt("application_count"),
                result.getInt("changed_count"),
                result.getObject("started_at", OffsetDateTime.class),
                result.getObject("completed_at", OffsetDateTime.class));
    }

    private MailRuleBackfillDtos.Result result(ExecutionRow row, boolean replayed) {
        return new MailRuleBackfillDtos.Result(
                row.executionId(), row.requestId(), row.accountId(), row.status(), replayed,
                row.scannedCount(), row.matchedThreadCount(), row.applicationCount(),
                row.changedCount(), row.startedAt(), row.completedAt());
    }

    private void lockRequestScope(Long tenantId, UUID accountId, UUID requestId) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, ?))",
                result -> { },
                accountId + ":" + requestId,
                tenantId);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
