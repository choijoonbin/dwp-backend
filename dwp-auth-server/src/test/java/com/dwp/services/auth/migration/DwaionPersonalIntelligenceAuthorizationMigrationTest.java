package com.dwp.services.auth.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DwaionPersonalIntelligenceAuthorizationMigrationTest {

    @Test
    void provisionsOnlySelfServiceApplicationAuthoritiesForWorkspaceMembers() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V210__authorize_dwaion_personal_intelligence.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("'APP.DWAION_ROUTINES', 'APP'", "'APP.DWAION_MEMORY', 'APP'")
                    .contains("'APP.DWAION_PRIVACY', 'APP'", "'APP.DWAION_ARTIFACTS', 'APP'")
                    .contains("('WORKSPACE_MEMBER', 'APP.DWAION_ROUTINES', 'VIEW')")
                    .contains("('WORKSPACE_MEMBER', 'APP.DWAION_ROUTINES', 'MANAGE')")
                    .contains("('WORKSPACE_MEMBER', 'APP.DWAION_MEMORY', 'VIEW')")
                    .contains("('WORKSPACE_MEMBER', 'APP.DWAION_MEMORY', 'MANAGE')")
                    .contains("('WORKSPACE_MEMBER', 'APP.DWAION_PRIVACY', 'VIEW')")
                    .contains("('WORKSPACE_MEMBER', 'APP.DWAION_PRIVACY', 'MANAGE')")
                    .contains("('WORKSPACE_MEMBER', 'APP.DWAION_ARTIFACTS', 'VIEW')")
                    .contains("('WORKSPACE_MEMBER', 'APP.DWAION_ARTIFACTS', 'CREATE')")
                    .contains("('WORKSPACE_MEMBER', 'APP.DWAION_ARTIFACTS', 'UPDATE')")
                    .contains("('WORKSPACE_MEMBER', 'APP.DWAION_ARTIFACTS', 'PUBLISH')")
                    .contains("('WORKSPACE_MEMBER', 'APP.DWAION_ARTIFACTS', 'EXPORT')")
                    .contains("required_entitlement", "'ai.agent-runtime'")
                    .contains("INSERT INTO com_resources", "INSERT INTO com_role_permissions")
                    .contains("access_revision = access_revision + 1")
                    .doesNotContain("('WORKSPACE_MEMBER', 'ADMIN.DWAION_RETENTION'")
                    .doesNotContain("ADMIN.DWAION_DATA_GOVERNANCE");
        }
    }
}
