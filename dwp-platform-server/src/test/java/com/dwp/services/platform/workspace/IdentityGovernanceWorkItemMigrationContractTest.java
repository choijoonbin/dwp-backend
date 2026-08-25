package com.dwp.services.platform.workspace;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityGovernanceWorkItemMigrationContractTest {

    @Test
    void reviewIsAProjectionWithOwnerSequenceAndNoDecisionColumns() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V180__add_review_workspace_work_items.sql"));

        assertThat(sql)
                .contains("'REVIEW'")
                .contains("source_event_sequence BIGINT NOT NULL DEFAULT 0")
                .contains("tenant_id, source_system, source_reference")
                .contains("\"authorityOwner\":\"dwp-auth-server\"")
                .doesNotContain("decision_reason")
                .doesNotContain("reviewer_accessible");
    }
}
