package com.dwp.services.people.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HcmStepUpMigrationContractTest {

    @Test
    void replayLedgerAndSyncRunRevisionAreMigrationOwned() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V44__bind_hcm_step_up_replay_and_sync_versions.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql)
                    .contains("CREATE TABLE ppl_step_up_replay_ledger")
                    .contains("UNIQUE (challenge_id, nonce)")
                    .contains("ADD COLUMN version BIGINT NOT NULL DEFAULT 0");
        }
    }
}
