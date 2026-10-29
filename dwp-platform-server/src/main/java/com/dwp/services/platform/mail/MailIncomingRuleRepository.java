package com.dwp.services.platform.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.mail.MailOrganizationTypes.RuleMatchMode;
import static com.dwp.services.platform.mail.MailTypes.Importance;

@Repository
class MailIncomingRuleRepository {

    private final JdbcTemplate jdbc;
    private final MailJsonCodec json;

    MailIncomingRuleRepository(JdbcTemplate jdbc, MailJsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    int enqueue(long tenantId, UUID threadId, UUID messageId) {
        return jdbc.update("""
                INSERT INTO mail_rule_runs (
                    run_id, tenant_id, rule_id, trigger_kind, run_status,
                    account_id, thread_id, message_id, rule_version, rule_priority,
                    rule_match_mode, rule_conditions, rule_actions, stop_processing,
                    next_attempt_at, initiated_by)
                SELECT gen_random_uuid(), thread.tenant_id, rule.rule_id, 'INCOMING', 'QUEUED',
                       thread.account_id, thread.thread_id, message.message_id,
                       rule.version, rule.priority, rule.match_mode,
                       rule.conditions, rule.actions, rule.stop_processing,
                       CURRENT_TIMESTAMP, account.owner_user_id
                  FROM mail_threads thread
                  JOIN mail_messages message
                    ON message.tenant_id = thread.tenant_id
                   AND message.thread_id = thread.thread_id
                   AND message.message_id = ?
                   AND message.message_direction = 'INBOUND'
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                   AND account.connection_state = 'ACTIVE'
                  JOIN mail_rules rule
                    ON rule.tenant_id = thread.tenant_id
                   AND rule.account_id = thread.account_id
                   AND rule.owner_user_id = account.owner_user_id
                   AND rule.lifecycle_state = 'ACTIVE'
                   AND rule.enabled = TRUE
                 WHERE thread.tenant_id = ? AND thread.thread_id = ?
                ON CONFLICT (tenant_id, message_id, rule_id)
                    WHERE trigger_kind = 'INCOMING' DO NOTHING
                """, messageId, tenantId, threadId);
    }

    @Transactional
    List<IncomingRun> claim(String workerId, int batchSize, int leaseSeconds) {
        return jdbc.query("""
                WITH candidates AS (
                    SELECT run.run_id
                      FROM mail_rule_runs run
                     WHERE run.trigger_kind = 'INCOMING'
                       AND run.run_status IN ('QUEUED', 'RETRY_WAIT')
                       AND run.next_attempt_at <= CURRENT_TIMESTAMP
                       AND NOT EXISTS (
                           SELECT 1
                             FROM mail_rule_runs earlier
                            WHERE earlier.trigger_kind = 'INCOMING'
                              AND earlier.tenant_id = run.tenant_id
                              AND earlier.message_id = run.message_id
                              AND (earlier.rule_priority < run.rule_priority
                                   OR (earlier.rule_priority = run.rule_priority
                                       AND earlier.rule_id < run.rule_id))
                              AND earlier.run_status IN (
                                  'QUEUED', 'LEASED', 'RETRY_WAIT'))
                     ORDER BY run.next_attempt_at, run.started_at,
                              run.message_id, run.rule_priority, run.rule_id
                     FOR UPDATE OF run SKIP LOCKED
                     LIMIT ?
                ), leased AS (
                    UPDATE mail_rule_runs run
                       SET run_status = 'LEASED', attempt_count = attempt_count + 1,
                           lease_owner = ?,
                           lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second')
                      FROM candidates
                     WHERE run.run_id = candidates.run_id
                    RETURNING run.*
                )
                SELECT * FROM leased
                 ORDER BY started_at, message_id, rule_priority, rule_id
                """, (result, ignored) -> incomingRun(result), batchSize, workerId, leaseSeconds);
    }

    int releaseExpiredLeases(int maximumAttempts) {
        return jdbc.update("""
                UPDATE mail_rule_runs
                   SET run_status = CASE WHEN attempt_count >= ? THEN 'FAILED'
                                         ELSE 'RETRY_WAIT' END,
                       completed_at = CASE WHEN attempt_count >= ?
                                           THEN CURRENT_TIMESTAMP ELSE NULL END,
                       next_attempt_at = CASE WHEN attempt_count >= ? THEN next_attempt_at
                                              ELSE CURRENT_TIMESTAMP END,
                       error_code = 'INCOMING_RULE_LEASE_EXPIRED',
                       lease_owner = NULL, lease_expires_at = NULL
                 WHERE trigger_kind = 'INCOMING' AND run_status = 'LEASED'
                   AND lease_expires_at < CURRENT_TIMESTAMP
                """, maximumAttempts, maximumAttempts, maximumAttempts);
    }

    Optional<MailOrganizationQueryRepository.RuleCandidate> candidate(IncomingRun run) {
        return jdbc.query("""
                SELECT thread.thread_id, thread.version, message.sender_email AS sender,
                       account.email_address AS recipient, thread.subject,
                       message.body_content AS body, thread.has_attachments,
                       thread.importance, thread.created_at
                  FROM mail_threads thread
                  JOIN mail_accounts account
                    ON account.tenant_id = thread.tenant_id
                   AND account.account_id = thread.account_id
                  JOIN mail_messages message
                    ON message.tenant_id = thread.tenant_id
                   AND message.thread_id = thread.thread_id
                   AND message.message_id = ?
                   AND message.message_direction = 'INBOUND'
                 WHERE thread.tenant_id = ? AND thread.account_id = ?
                   AND thread.thread_id = ?
                   AND thread.workflow_state NOT IN ('DRAFT', 'TRASHED', 'SPAM')
                """, (result, ignored) -> new MailOrganizationQueryRepository.RuleCandidate(
                result.getObject("thread_id", UUID.class), result.getLong("version"),
                result.getString("sender"), result.getString("recipient"),
                result.getString("subject"), result.getString("body"),
                result.getBoolean("has_attachments"),
                Importance.valueOf(result.getString("importance")),
                result.getObject("created_at", OffsetDateTime.class)),
                run.messageId(), run.tenantId(), run.accountId(), run.threadId())
                .stream().findFirst();
    }

    int complete(
            IncomingRun run, String workerId, boolean matched, boolean changed) {
        int updated = jdbc.update("""
                UPDATE mail_rule_runs
                   SET run_status = 'SUCCEEDED', scanned_count = 1,
                       matched_count = ?, changed_count = ?, matched = ?,
                       error_code = NULL, completed_at = CURRENT_TIMESTAMP,
                       lease_owner = NULL, lease_expires_at = NULL
                 WHERE run_id = ? AND run_status = 'LEASED' AND lease_owner = ?
                """, matched ? 1 : 0, changed ? 1 : 0, matched, run.runId(), workerId);
        if (updated == 1) {
            jdbc.update("""
                    UPDATE mail_rules
                       SET last_run_at = CURRENT_TIMESTAMP, last_match_count = ?,
                           last_error_code = NULL, updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = ? AND rule_id = ? AND version = ?
                    """, matched ? 1 : 0, run.tenantId(), run.ruleId(), run.ruleVersion());
        }
        return updated;
    }

    int fail(
            IncomingRun run, String workerId, int maximumAttempts,
            String errorCode) {
        boolean terminal = run.attemptCount() >= maximumAttempts;
        int updated = jdbc.update("""
                UPDATE mail_rule_runs
                   SET run_status = ?, error_code = ?,
                       next_attempt_at = CASE WHEN ? THEN next_attempt_at
                           ELSE CURRENT_TIMESTAMP + (? * INTERVAL '1 second') END,
                       completed_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                       lease_owner = NULL, lease_expires_at = NULL
                 WHERE run_id = ? AND run_status = 'LEASED' AND lease_owner = ?
                """, terminal ? "FAILED" : "RETRY_WAIT", errorCode, terminal,
                backoffSeconds(run.attemptCount()), terminal, run.runId(), workerId);
        if (updated == 1 && terminal) {
            jdbc.update("""
                    UPDATE mail_rules
                       SET last_run_at = CURRENT_TIMESTAMP, last_match_count = 0,
                           last_error_code = ?, updated_at = CURRENT_TIMESTAMP
                     WHERE tenant_id = ? AND rule_id = ? AND version = ?
                    """, errorCode, run.tenantId(), run.ruleId(), run.ruleVersion());
        }
        return updated;
    }

    int skipRemaining(IncomingRun run) {
        return jdbc.update("""
                UPDATE mail_rule_runs later
                   SET run_status = 'SKIPPED', scanned_count = 0,
                       matched_count = 0, changed_count = 0, matched = FALSE,
                       error_code = 'INCOMING_RULE_STOP_PROCESSING',
                       completed_at = CURRENT_TIMESTAMP,
                       lease_owner = NULL, lease_expires_at = NULL
                 WHERE later.trigger_kind = 'INCOMING'
                   AND later.tenant_id = ? AND later.message_id = ?
                   AND later.run_status IN ('QUEUED', 'RETRY_WAIT')
                   AND (later.rule_priority > ?
                        OR (later.rule_priority = ? AND later.rule_id > ?))
                """, run.tenantId(), run.messageId(), run.priority(),
                run.priority(), run.ruleId());
    }

    private IncomingRun incomingRun(java.sql.ResultSet result) throws java.sql.SQLException {
        return new IncomingRun(
                result.getObject("run_id", UUID.class), result.getLong("tenant_id"),
                result.getObject("account_id", UUID.class),
                result.getObject("thread_id", UUID.class),
                result.getObject("message_id", UUID.class),
                result.getLong("initiated_by"), result.getObject("rule_id", UUID.class),
                result.getLong("rule_version"), result.getInt("rule_priority"),
                RuleMatchMode.valueOf(result.getString("rule_match_mode")),
                json.ruleConditions(result.getString("rule_conditions")),
                json.ruleActions(result.getString("rule_actions")),
                result.getBoolean("stop_processing"), result.getInt("attempt_count"));
    }

    private long backoffSeconds(int attempt) {
        return Math.min(300L, 5L * (1L << Math.min(6, Math.max(0, attempt - 1))));
    }

    record IncomingRun(
            UUID runId,
            long tenantId,
            UUID accountId,
            UUID threadId,
            UUID messageId,
            long ownerUserId,
            UUID ruleId,
            long ruleVersion,
            int priority,
            RuleMatchMode matchMode,
            List<MailOrganizationDtos.RuleCondition> conditions,
            List<MailOrganizationDtos.RuleAction> actions,
            boolean stopProcessing,
            int attemptCount) {

        MailOrganizationDtos.RuleSummary snapshot() {
            return new MailOrganizationDtos.RuleSummary(
                    ruleId, accountId, "incoming-rule-snapshot", priority, matchMode,
                    conditions, actions, stopProcessing, true,
                    MailOrganizationTypes.ProviderSyncState.LOCAL_ONLY,
                    null, 0, ruleVersion);
        }
    }
}
