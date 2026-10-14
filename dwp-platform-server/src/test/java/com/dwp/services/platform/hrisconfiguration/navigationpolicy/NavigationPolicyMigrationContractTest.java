package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NavigationPolicyMigrationContractTest {

    @Test
    void v326OwnsDedicatedLifecycleDurabilityAndTenantIsolation() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V326__create_hris_navigation_policy_lifecycle.sql"));

        assertThat(sql)
                .contains("hris_navigation_policy_heads")
                .contains("hris_navigation_policy_revisions")
                .contains("revision_id UUID NOT NULL")
                .contains("'DRAFT', 'VALIDATED', 'PENDING_APPROVAL', 'APPROVED', 'PUBLISHED'")
                .contains("approved_by <> authored_by")
                .contains("restored_from_revision")
                .contains("restored_from_digest")
                .contains("supersedes_revision")
                .contains("hris_navigation_policy_effective")
                .contains("hris_navigation_policy_command_receipts")
                .contains("UNIQUE (tenant_id, subject_id, originating_action, idempotency_key)")
                .contains("hris_navigation_policy_audit")
                .contains("hris_navigation_policy_outbox")
                .contains("hris_navigation_policy_step_up_replay")
                .contains("uq_hris_navigation_policy_step_up_nonce")
                .contains("dwp.hris.navigation-policy.published.v1")
                .contains("ENABLE ROW LEVEL SECURITY")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("current_setting('dwp.tenant_id', TRUE)")
                .contains("published HRIS navigation policy is immutable")
                .contains("TG_OP = 'DELETE' OR NEW IS DISTINCT FROM OLD")
                .contains("BEFORE UPDATE OR DELETE ON hris_navigation_policy_revisions")
                .doesNotContain("adm_navigation");
    }
}
