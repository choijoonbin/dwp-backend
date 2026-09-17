package com.dwp.services.platform.workplace.workplacenavigation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class WorkplaceAccessPassMigrationTest {
    @Test
    void migrationPersistsOnlyOneWayCredentialEvidenceAndDurableReceipts() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V279__govern_workplace_user_access_passes.sql"));
        String lower = sql.toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("CREATE TABLE wp_navigation_access_passes")
                .contains("credential_sha256 CHAR(64) NOT NULL")
                .contains("pairing_code_hash CHAR(64)")
                .contains("pairing_code_salt CHAR(32)")
                .contains("CREATE UNIQUE INDEX uk_wp_navigation_active_user_access_pass")
                .contains("CREATE TABLE wp_navigation_access_pass_previews")
                .contains("UNIQUE (tenant_id, actor_user_id, idempotency_key)")
                .contains("CREATE TABLE wp_navigation_access_pass_commands")
                .contains("RESULT_UNKNOWN")
                .contains("raw one-time credential is returned once and is never persisted")
                .contains("PBKDF2-SHA256 verification value with a per-pass random salt");
        assertThat(lower)
                .doesNotContain("pairing_code_last")
                .doesNotContain("raw_credential")
                .doesNotContain("plaintext")
                .doesNotContain("access_token")
                .doesNotContain("refresh_token");
    }
}
