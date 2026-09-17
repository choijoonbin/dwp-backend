package com.dwp.services.auth.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DwaionRoutineApproverAuthorizationMigrationTest {

    @Test
    void provisionsDedicatedApproverWithoutBroadWorkspaceGrant() throws IOException {
        try (var stream = getClass().getResourceAsStream(
                "/db/migration/V222__authorize_dwaion_routine_approvers.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(sql)
                    .contains("'DWAION_ROUTINE_APPROVER', 'DWAI-ON routine approver'")
                    .contains("'DWAION_ROUTINE_APPROVER', 'APP.DWAION_ROUTINES', 'APPROVE'")
                    .contains("'DWAION_ROUTINE_APPROVER', 'APP.ASK', 'VIEW'")
                    .contains("'APPROVAL', 'ACTIVE'")
                    .contains("role.code = 'WORKSPACE_MEMBER'")
                    .contains("permission.code = 'APPROVE'")
                    .doesNotContain("('WORKSPACE_MEMBER', 'APP.DWAION_ROUTINES', 'APPROVE'");
        }
    }
}
