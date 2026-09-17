package com.dwp.services.auth.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DwaionResearchAuthorizationMigrationTest {

    @Test
    void provisionsTenantResearchViewAndManageForWorkspaceMembers() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V221__authorize_dwaion_research.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("'APP.DWAION_RESEARCH', 'APP', 'DWAI-ON Deep Research'")
                    .contains("('WORKSPACE_MEMBER', 'APP.DWAION_RESEARCH', 'VIEW')")
                    .contains("('WORKSPACE_MEMBER', 'APP.DWAION_RESEARCH', 'MANAGE')")
                    .contains("required_entitlement", "'ai.agent-runtime'")
                    .contains("INSERT INTO com_resources", "INSERT INTO com_role_permissions")
                    .contains("access_revision = access_revision + 1")
                    .doesNotContain("ADMIN.DWAION_RESEARCH");
        }
    }
}
