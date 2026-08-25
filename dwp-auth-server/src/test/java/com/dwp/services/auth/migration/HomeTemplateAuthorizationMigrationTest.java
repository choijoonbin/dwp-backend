package com.dwp.services.auth.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HomeTemplateAuthorizationMigrationTest {

    @Test
    void provisionsTenantScopedViewAndManageAuthorities() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V86__authorize_home_template_management.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("'ADMIN.HOME_TEMPLATE', 'ADMIN', 'Home templates', 'core.workspace'")
                    .contains("('TENANT_ADMIN', 'ADMIN.HOME_TEMPLATE', 'VIEW', 'ACTIVE')")
                    .contains("('TENANT_ADMIN', 'ADMIN.HOME_TEMPLATE', 'MANAGE', 'ACTIVE')")
                    .contains("INSERT INTO com_resources")
                    .contains("INSERT INTO com_role_permissions")
                    .contains("access_revision = user_record.access_revision + 1");
        }
    }
}
