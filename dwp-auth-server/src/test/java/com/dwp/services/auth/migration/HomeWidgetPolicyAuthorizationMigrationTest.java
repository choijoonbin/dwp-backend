package com.dwp.services.auth.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HomeWidgetPolicyAuthorizationMigrationTest {
    @Test
    void registersTheDedicatedTenantResourceAndEveryRoutePermission() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V218__authorize_home_widget_policy.sql"));

        assertThat(sql)
                .contains("'ADMIN.HOME_WIDGET_POLICY', 'ADMIN', 'Home widget policy', 'core.workspace'")
                .contains("('TENANT_ADMIN', 'ADMIN.HOME_WIDGET_POLICY', 'VIEW', 'ACTIVE')")
                .contains("('TENANT_ADMIN', 'ADMIN.HOME_WIDGET_POLICY', 'MANAGE', 'ACTIVE')")
                .contains("('TENANT_ADMIN', 'ADMIN.HOME_WIDGET_POLICY', 'PUBLISH', 'ACTIVE')")
                .contains("('TENANT_ADMIN', 'ADMIN.HOME_WIDGET_POLICY', 'EXPLAIN', 'ACTIVE')")
                .contains("('TENANT_ADMIN', 'ADMIN.HOME_WIDGET_POLICY', 'AUDIT', 'ACTIVE')")
                .contains("access_revision = user_record.access_revision + 1");
    }
}
