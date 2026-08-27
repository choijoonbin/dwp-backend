package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PrivilegedAccessDisablementMigrationContractTest {

    @Test
    void migrationRevokesExistingElevationAndRejectsEveryNewActivationPath()
            throws IOException {
        ClassPathResource resource = new ClassPathResource(
                "db/migration/V108__disable_privileged_access_activation.sql");
        String migration;
        try (var input = resource.getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration)
                .contains("UPDATE com_active_privileged_grants")
                .contains("WHERE revoked_at IS NULL")
                .contains("UPDATE com_privileged_access_requests")
                .contains("WHERE lifecycle_state IN ('ACTIVE', 'PENDING_APPROVAL')")
                .contains("activation_mode = 'DISABLED'")
                .contains("emergency_mode = 'DISABLED'")
                .contains("sys_enforce_privileged_access_rollout_disabled")
                .contains("trg_privileged_access_policy_rollout_disabled")
                .contains("trg_privileged_access_request_rollout_disabled")
                .contains("trg_active_privileged_grant_rollout_disabled")
                .contains("USING ERRCODE = '23514'");
    }
}
