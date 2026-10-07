package com.dwp.services.people.hr.performance;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceCycleMigrationContractTest {

    @Test
    void preservesPublishedEvidenceWhileAllowingOneSuccessorDraft() throws IOException {
        String migration = migration();

        assertThat(migration)
                .contains("CREATE UNIQUE INDEX ux_prf_cycle_versions_working")
                .contains("WHERE version_state IN ('DRAFT', 'VALIDATED')")
                .contains("CREATE UNIQUE INDEX ux_prf_cycle_versions_published")
                .contains("WHERE version_state = 'PUBLISHED'")
                .contains("version_state IN ('DRAFT', 'VALIDATED', 'PUBLISHED', 'SUPERSEDED')")
                .doesNotContain("version_state IN ('DRAFT', 'VALIDATED', 'PUBLISHED', 'SUPERSEDED', 'RETIRED')")
                .contains("publication_approval_ref UUID")
                .contains("published_at TIMESTAMPTZ")
                .contains("published_by BIGINT");
    }

    @Test
    void freezesFullOwnerProjectionAndExactCommandEvidence() throws IOException {
        assertThat(migration())
                .contains("population_rule_version_id UUID NOT NULL")
                .contains("source_cycle_aggregate_version BIGINT NOT NULL")
                .contains("CREATE UNIQUE INDEX ux_prf_population_previews_ready_snapshot")
                .contains("WHERE preview_state = 'READY'")
                .contains("workforce_status VARCHAR(24) NOT NULL")
                .contains("organization_ref UUID NOT NULL")
                .contains("job_profile_ref UUID")
                .contains("grade_ref UUID")
                .contains("tenant_id, population_preview_id, participant_ref, primary_assignment_ref")
                .contains("applied_aggregate_version BIGINT")
                .contains("command_receipt_id UUID NOT NULL")
                .contains("fk_prf_outbox_events_receipt")
                .contains("ux_prf_outbox_events_command_receipt");
    }

    private String migration() throws IOException {
        return new ClassPathResource(
                "db/performance-migration/V1__per_create_evaluation_cycle_foundation.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
