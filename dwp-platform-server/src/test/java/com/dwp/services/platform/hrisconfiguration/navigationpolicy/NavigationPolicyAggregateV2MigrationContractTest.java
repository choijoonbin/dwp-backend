package com.dwp.services.platform.hrisconfiguration.navigationpolicy;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NavigationPolicyAggregateV2MigrationContractTest {

    @Test
    void v330LeasesExactAppendOnlyTopologyPairsWithoutBackfill() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V330__accept_controlled_export_navigation_topology_successor.sql"));

        assertThat(sql)
                .contains("DROP CONSTRAINT ck_hris_navigation_policy_v2_revision")
                .contains("ADD CONSTRAINT ck_hris_navigation_policy_v2_revision")
                .contains(NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION)
                .contains(NavigationPolicyAggregateV2Canonical.TOPOLOGY_SHA256)
                .contains(NavigationPolicyTopologySuccessor.TOPOLOGY_REVISION)
                .contains(NavigationPolicyTopologySuccessor.TOPOLOGY_SHA256)
                .contains("OR")
                .doesNotContain("UPDATE hris_navigation_policy_v2_revisions")
                .doesNotContain("DELETE FROM hris_navigation_policy_v2_revisions")
                .doesNotContain("INSERT INTO hris_navigation_policy_v2_revisions");
    }

    @Test
    void v329OwnsAggregateLifecycleAndExplicitlyPreservesV1Evidence() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V329__create_hris_navigation_policy_aggregate_v2.sql"));

        assertThat(sql)
                .contains("hris_navigation_policy_v2_heads")
                .contains("hris_navigation_policy_v2_revisions")
                .contains("hris_navigation_policy_v2_effective")
                .contains("hris_navigation_policy_v2_command_receipts")
                .contains("hris_navigation_policy_v2_audit")
                .contains("hris_navigation_policy_v2_outbox")
                .contains("hris_navigation_policy_v2_legacy_evidence")
                .contains("PRESERVED_V1")
                .contains("WITH (security_invoker = true)")
                .contains("INSTEAD OF INSERT OR UPDATE OR DELETE")
                .contains("approved_by <> authored_by")
                .contains("UNIQUE (tenant_id, subject_id, originating_action, idempotency_key)")
                .contains("published HRIS navigation aggregate v2 is immutable")
                .contains("ENABLE ROW LEVEL SECURITY")
                .contains("FORCE ROW LEVEL SECURITY")
                .contains("jsonb_array_length(policy_payload -> 'workbenches') = 7")
                .contains("policy_payload ->> 'rolloutMode' = rollout_mode")
                .contains("lifecycle_state = 'PUBLISHED' AND published_at IS NOT NULL")
                .contains(NavigationPolicyAggregateV2Canonical.TOPOLOGY_REVISION)
                .contains(NavigationPolicyAggregateV2Canonical.IA_REGISTER_SHA256)
                .contains(NavigationPolicyAggregateV2Canonical.TASK_GROUP_REGISTER_SHA256)
                .contains(NavigationPolicyAggregateV2Canonical.GENERATED_IA_SHA256)
                .contains("immutable HRIS navigation aggregate v2 outbox fact cannot be deleted")
                .contains("effective HRIS navigation aggregate must match exact published revision")
                .contains("r.published_at = NEW.published_at")
                .contains("r.policy_payload ->> 'scopeKind' = NEW.scope_kind")
                .contains("r.policy_payload ->> 'legalEntityPublicId'")
                .contains("FOREIGN KEY (tenant_id, policy_id, policy_revision, revision_id, payload_digest)")
                .doesNotContain("AFTER INSERT ON hris_navigation_policy_revisions")
                .doesNotContain("UPDATE hris_navigation_policy_revisions SET")
                .doesNotContain("UPDATE hris_navigation_policy_command_receipts SET");
    }
}
