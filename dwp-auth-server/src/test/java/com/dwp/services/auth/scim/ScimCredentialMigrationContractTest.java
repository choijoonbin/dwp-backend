package com.dwp.services.auth.scim;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ScimCredentialMigrationContractTest {

    @Test
    void legacyRowsAreBackfilledBeforeCredentialColumnsBecomeRequired() throws IOException {
        String migration = Files.readString(repositoryRoot().resolve(
                "dwp-auth-server/src/main/resources/db/migration/"
                        + "V216__govern_scim_connector_credentials.sql"));

        int backfill = migration.indexOf("credential_issued_at = COALESCE(created_at, CURRENT_TIMESTAMP)");
        int required = migration.indexOf("ALTER COLUMN credential_issued_at SET NOT NULL");

        assertThat(backfill).isGreaterThan(0);
        assertThat(required).isGreaterThan(backfill);
        assertThat(migration)
                .contains("credential_expires_at = GREATEST(")
                .contains("COALESCE(created_at, CURRENT_TIMESTAMP) + INTERVAL '180 days'")
                .contains("CURRENT_TIMESTAMP + INTERVAL '30 days'")
                .contains("Legacy rows receive at least 30 days of migration grace")
                .contains("owner_user_id = created_by")
                .contains("Raw token material is never persisted")
                .contains("'[\"USERS\"]'::jsonb")
                .contains("'[\"GROUPS\"]'::jsonb");
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))
                    && Files.isDirectory(current.resolve("dwp-auth-server"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the backend repository root.");
    }
}
