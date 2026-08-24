package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class OidcStepUpAmrMigrationContractTest {

    @Test
    void existingProvidersRemainIncompatibleUntilAnExplicitClosedAmrAllowlistExists()
            throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "db/migration/V89_2__require_closed_step_up_amr_allowlist.sql");
        String migration;
        try (var input = resource.getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration).contains(
                "ADD COLUMN step_up_accepted_amr_values VARCHAR(500)",
                "known values only and at least one strong MFA pattern",
                "NULL is intentionally incompatible");
        assertThat(migration).doesNotContain("DEFAULT", "UPDATE sys_identity_providers");
    }
}
