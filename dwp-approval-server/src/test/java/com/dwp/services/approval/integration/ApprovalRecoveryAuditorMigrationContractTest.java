package com.dwp.services.approval.integration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRecoveryAuditorMigrationContractTest {

    @Test
    void v13AddsLeasedImmutableFailClosedAssignmentEvidence() throws Exception {
        ClassPathResource resource = new ClassPathResource(
                "db/migration/V13__assign_recovery_auditor_asynchronously.sql");
        String migration;
        try (var input = resource.getInputStream()) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(migration).contains(
                "DEFAULT 'LEGACY_UNASSIGNED'",
                "SET DEFAULT 'PENDING'",
                "recovery_auditor_assignment_state",
                "recovery_auditor_resource_set_key",
                "recovery_auditor_assignment_revision",
                "recovery_auditor_assigned_at",
                "recovery_auditor_assignment_epoch",
                "recovery_auditor_assignment_locked_until",
                "recovery_auditor_assignment_exhausted_at",
                "recovery_auditor_assignment_next_probe_at",
                "idx_apr_recovery_auditor_assignment_claim",
                "idx_apr_recovery_auditor_assignment_probe",
                "CREATE TABLE apr_recovery_auditor_assignment_events",
                "AUTOMATIC_PROBE_EPOCH_OPENED",
                "trg_apr_recovery_assignment_events_append_only",
                "'EXHAUSTED'",
                "'NOT_REQUIRED'",
                "'ASSIGNED'",
                "'LEGACY_UNASSIGNED'");
        assertThat(migration).doesNotContain("ON DELETE CASCADE");
    }
}
