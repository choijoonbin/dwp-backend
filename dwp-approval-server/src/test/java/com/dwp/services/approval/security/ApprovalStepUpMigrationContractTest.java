package com.dwp.services.approval.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalStepUpMigrationContractTest {

    @Test
    void v12CreatesTransactionLocalReplayAndRecoveryEvidenceContracts() throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "db/migration/V12__bind_step_up_replay_and_recovery_evidence.sql");
        String migration;
        try (var input = resource.getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration).contains(
                "CREATE TABLE apr_step_up_replay_ledger",
                "UNIQUE (challenge_id, nonce)",
                "actor_user_id BIGINT NOT NULL",
                "target_version BIGINT NOT NULL",
                "idempotency_key VARCHAR(200) NOT NULL",
                "payload_sha256 CHAR(64) NOT NULL",
                "decision_revision VARCHAR(200) NOT NULL",
                "result_receipt JSONB",
                "expires_at TIMESTAMPTZ NOT NULL",
                "idx_apr_step_up_replay_expiry",
                "idx_apr_high_risk_idempotency_expiry",
                "event_originator_user_id BIGINT",
                "assigned_auditor_user_id BIGINT",
                "version BIGINT NOT NULL DEFAULT 0");
        assertThat(migration).doesNotContain(
                "ON DELETE CASCADE",
                "result_payload JSONB");
    }
}
