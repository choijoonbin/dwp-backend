package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkplaceResourceFavoriteMigrationTest {
    @Test
    void migrationProvidesVersionedFavoritesReplayReceiptsAndAuditLinkage() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V283__govern_workplace_resource_favorites.sql"));
        assertThat(sql).contains("CREATE TABLE wp_resource_favorites")
                .contains("PRIMARY KEY (tenant_id, user_id, resource_id)")
                .contains("version BIGINT NOT NULL DEFAULT 1")
                .contains("CREATE TABLE wp_resource_favorite_commands")
                .contains("UNIQUE (tenant_id, user_id, idempotency_key)")
                .contains("audit_event_id UUID NOT NULL");
        assertThat(sql.toLowerCase()).doesNotContain("secret").doesNotContain("token");
    }
}
