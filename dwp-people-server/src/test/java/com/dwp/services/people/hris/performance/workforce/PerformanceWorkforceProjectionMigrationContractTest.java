package com.dwp.services.people.hris.performance.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PerformanceWorkforceProjectionMigrationContractTest {

    private static final String MIGRATION =
            "db/performance-migration/V16__per_base_tfr_per_016.sql";

    @Test
    void exactLeaseCreatesRevisionBoundPerOwnedProjection() throws IOException {
        String sql = migration();
        String heads = tableBody(sql, "prf_workforce_projection_heads");

        assertThat(sql)
                .contains("BASE-TFR-PER-016 / W2-20261013-PER-016-START-R1")
                .contains("CREATE TABLE hris_performance.prf_workforce_projection_heads")
                .contains("CREATE TABLE hris_performance.prf_workforce_projection_revisions")
                .contains("CREATE TABLE hris_performance.prf_workforce_projection_members")
                .contains("CREATE TABLE hris_performance.prf_workforce_projection_inbox")
                .contains("purpose_code = 'HRIS_WORKFORCE_SNAPSHOT_BOOTSTRAP'")
                .contains("projection_code = 'PERFORMANCE_V1'")
                .contains("'READY'")
                .contains("'STALE'")
                .contains("'BLOCKED_DEPENDENCY_GAP'")
                .contains("'QUARANTINED'");

        assertThat(heads)
                .contains("current_local_revision BIGINT NOT NULL DEFAULT 0")
                .contains("current_owner_revision BIGINT,")
                .doesNotContain("current_owner_revision BIGINT NOT NULL")
                .contains("current_local_revision = 0")
                .contains("current_owner_revision IS NULL")
                .contains("current_content_hash IS NULL")
                .contains("snapshot_as_of IS NULL")
                .contains("current_local_revision > 0")
                .contains("current_owner_revision IS NOT NULL")
                .contains("current_content_hash IS NOT NULL")
                .contains("snapshot_as_of IS NOT NULL")
                .contains("projection_state <> 'READY' OR current_local_revision > 0");
    }

    @Test
    void revisionHistoryRetainsOwnerSnapshotAndReplayEvidence() throws IOException {
        String revisions = tableBody(
                migration(), "prf_workforce_projection_revisions");

        assertThat(revisions)
                .contains("local_revision BIGINT NOT NULL")
                .contains("owner_revision BIGINT NOT NULL")
                .contains("snapshot_as_of TIMESTAMPTZ NOT NULL")
                .contains("purpose_code VARCHAR(80) NOT NULL")
                .contains("projection_code VARCHAR(40) NOT NULL")
                .contains("owner_page_digest CHAR(64) NOT NULL")
                .contains("content_hash CHAR(64) NOT NULL")
                .contains("member_count INTEGER NOT NULL")
                .contains("replay_of_local_revision BIGINT")
                .contains("materialized_at TIMESTAMPTZ NOT NULL")
                .contains("tenant_id, projection_head_id, local_revision")
                .contains("replay_of_local_revision < local_revision")
                .contains("owner_page_digest ~ '^[0-9a-f]{64}$'")
                .contains("content_hash ~ '^[0-9a-f]{64}$'");
    }

    @Test
    void memberProjectionContainsExactlyTheSevenSealedWorkforceFields()
            throws IOException {
        String members = tableBody(
                migration(), "prf_workforce_projection_members");

        assertThat(members)
                .contains("worker_public_id UUID NOT NULL")
                .contains("assignment_public_id UUID NOT NULL")
                .contains("workforce_status VARCHAR(24) NOT NULL")
                .contains("organization_public_id UUID NOT NULL")
                .contains("manager_assignment_public_id UUID")
                .contains("job_profile_public_id UUID")
                .contains("grade_public_id UUID")
                .contains("'PENDING', 'ACTIVE', 'INACTIVE', 'TERMINATED'")
                .doesNotContain("person_public_id")
                .doesNotContain("legal_entity_public_id")
                .doesNotContain("relationship_public_id")
                .doesNotContain("location_public_id")
                .doesNotContain("time_zone")
                .doesNotContain("employment_terms")
                .doesNotContain("compensation");
    }

    @Test
    void inboxDurablyBindsDedupRevisionGapAndPayloadConflictEvidence()
            throws IOException {
        String inbox = tableBody(
                migration(), "prf_workforce_projection_inbox");

        assertThat(inbox)
                .contains("CONSTRAINT uk_prf_workforce_projection_inbox_event UNIQUE")
                .contains("tenant_id, event_id")
                .doesNotContain("uk_prf_workforce_projection_inbox_revision")
                .contains("aggregate_public_id UUID NOT NULL")
                .contains("aggregate_revision BIGINT")
                .contains("organization_revision_public_id UUID")
                .contains("effective_date DATE NOT NULL")
                .contains("payload_hash CHAR(64) NOT NULL")
                .contains("conflicting_payload_hash CHAR(64)")
                .contains("conflict_detected_at TIMESTAMPTZ")
                .contains("'REPLAY_REQUIRED'")
                .contains("'QUARANTINED'")
                .contains("'IGNORED_OLDER_REVISION'")
                .contains("aggregate_revision IS NULL")
                .contains("organization_revision_public_id IS NOT NULL")
                .contains("conflicting_payload_hash <> payload_hash")
                .contains("processing_state = 'QUARANTINED'")
                .contains("WorkerChanged.v1")
                .contains("EmploymentChanged.v1")
                .contains("AssignmentChanged.v1")
                .contains("OrganizationChanged.v1");
    }

    @Test
    void everyProjectionTableIsForcedThroughTenantRls() throws IOException {
        String sql = migration();

        for (String table : new String[] {
                "heads", "revisions", "members", "inbox"
        }) {
            String qualified = "hris_performance.prf_workforce_projection_" + table;
            assertThat(sql)
                    .contains("ALTER TABLE " + qualified
                            + "\n    ENABLE ROW LEVEL SECURITY")
                    .contains("ALTER TABLE " + qualified
                            + "\n    FORCE ROW LEVEL SECURITY")
                    .contains("CREATE POLICY prf_workforce_projection_" + table
                            + "_tenant_isolation")
                    .contains("ON " + qualified);
        }
        assertThat(sql.lines()
                .filter(line -> line.contains(
                        "current_setting('dwp.tenant_id', TRUE)")))
                .hasSize(8);
    }

    @Test
    void migrationHasNoCrossContextPersistenceOrFrozenPopulationMutation()
            throws IOException {
        String sql = migration().toLowerCase(java.util.Locale.ROOT);

        assertThat(sql)
                .doesNotContain("ppl_")
                .doesNotContain("hrm_")
                .doesNotContain("references public.")
                .doesNotContain("insert into ")
                .doesNotContain("update ")
                .doesNotContain("delete from ")
                .doesNotContain("merge into ")
                .doesNotContain("truncate ")
                .doesNotContain("prf_population_freeze")
                .doesNotContain("insert into hris_performance.prf_population_freezes")
                .doesNotContain("update hris_performance.prf_population_freezes")
                .doesNotContain("delete from hris_performance.prf_population_freezes")
                .doesNotContain("insert into hris_performance.prf_population_freeze_members")
                .doesNotContain("update hris_performance.prf_population_freeze_members")
                .doesNotContain("delete from hris_performance.prf_population_freeze_members")
                .doesNotContain("http://")
                .doesNotContain("https://")
                .doesNotContain("dblink");
    }

    private String migration() throws IOException {
        return new ClassPathResource(MIGRATION)
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private String tableBody(String sql, String table) {
        String marker = "CREATE TABLE hris_performance." + table + " (";
        int start = sql.indexOf(marker);
        int end = sql.indexOf("\n);", start);
        assertThat(start).as("table start for %s", table).isNotNegative();
        assertThat(end).as("table end for %s", table).isGreaterThan(start);
        return sql.substring(start, end);
    }
}
