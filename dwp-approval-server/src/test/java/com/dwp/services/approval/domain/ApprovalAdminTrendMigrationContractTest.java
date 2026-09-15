package com.dwp.services.approval.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ApprovalAdminTrendMigrationContractTest {

    @Test
    void v38IndexesEveryBoundedTrendSourceWithoutChangingRows() throws Exception {
        var resource = new ClassPathResource(
                "db/migration/V38__index_approval_admin_trends.sql");
        String migration;
        try (var input = resource.getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration).contains(
                "idx_apr_request_management_submitted_trend",
                "tenant_id, management_resource_set_key, submitted_at, request_id",
                "idx_apr_request_management_completed_trend",
                "status IN ('APPROVED', 'REJECTED', 'WITHDRAWN', 'CANCELLED')",
                "idx_apr_task_due_trend",
                "INCLUDE (status, completed_at)",
                "status IN ('PENDING', 'CLAIMED', 'APPROVED', 'REJECTED')");
        assertThat(migration).doesNotContain(
                "INSERT INTO",
                "UPDATE ",
                "DELETE FROM",
                "DROP ");
    }
}
