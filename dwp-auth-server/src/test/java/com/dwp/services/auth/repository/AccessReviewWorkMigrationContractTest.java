package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AccessReviewWorkMigrationContractTest {

    @Test
    void migrationCreatesOpaqueRevocableNamedReviewerEvidence() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V88__outbox_access_review_work_items.sql"));

        assertThat(sql)
                .contains("work_item_ref UUID NOT NULL DEFAULT gen_random_uuid()")
                .contains("work_event_sequence BIGINT NOT NULL DEFAULT 0")
                .contains("UNIQUE (tenant_id, work_item_ref)")
                .contains("reviewer_assignment_state IN ('ACTIVE', 'REVOKED')")
                .contains("CHECK (work_event_sequence >= 0)")
                .doesNotContain("tenant_admin")
                .doesNotContain("role_id UUID");
    }
}
