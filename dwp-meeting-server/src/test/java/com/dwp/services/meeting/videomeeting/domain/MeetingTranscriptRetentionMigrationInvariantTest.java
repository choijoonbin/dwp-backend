package com.dwp.services.meeting.videomeeting.domain;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingTranscriptRetentionMigrationInvariantTest {

    private static String migration;

    @BeforeAll
    static void readMigration() throws IOException {
        try (var input = MeetingTranscriptRetentionMigrationInvariantTest.class
                .getResourceAsStream(
                        "/db/migration/V25__add_governed_transcript_retention.sql")) {
            assertThat(input).isNotNull();
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void quarantinesEverySnapshotNullTranscriptBeforeAddingItsConstraint() {
        int marker = migration.indexOf(
                "-- No pre-V25 transcript row has a verifiable immutable");
        int quarantine = migration.indexOf(
                "UPDATE vm_meeting_artifacts\n   SET artifact_state = CASE", marker);
        int snapshotConstraint = migration.indexOf(
                "ADD CONSTRAINT ck_vm_transcript_registration_snapshot");

        assertThat(quarantine).isGreaterThan(0);
        assertThat(snapshotConstraint).isGreaterThan(quarantine);
        assertThat(migration.substring(quarantine, snapshotConstraint)).contains(
                "server_side_processing_allowed = FALSE",
                "LEGACY_TRANSCRIPT_SNAPSHOT_MISSING");
        assertThat(migration.substring(marker, quarantine)).contains(
                "apparently legal AVAILABLE row",
                "registration_idempotency_key",
                "server_side_processing_allowed=TRUE");
    }

    @Test
    void legacyAvailableProcessingRowsCannotRemainProcessable() {
        int marker = migration.indexOf(
                "-- No pre-V25 transcript row has a verifiable immutable");
        int quarantine = migration.indexOf(
                "UPDATE vm_meeting_artifacts\n   SET artifact_state = CASE", marker);
        String statement = migration.substring(
                migration.lastIndexOf("UPDATE vm_meeting_artifacts", quarantine),
                migration.indexOf(";", quarantine) + 1);

        assertThat(statement).contains(
                "WHEN registration_idempotency_key IS NOT NULL\n"
                        + "                OR finalization_idempotency_key IS NOT NULL THEN 'FAILED'",
                "server_side_processing_allowed = FALSE",
                "AND transcript_plan_version IS NULL");
    }

    @Test
    void deletionEvidenceRequiresLocatorRemovalAndContentFreeAudit() {
        assertThat(migration).contains(
                "storage_provider IS NULL AND object_key IS NULL",
                "transcript_deleted_at TIMESTAMPTZ",
                "transcript_deletion_provider_code VARCHAR(48)",
                "raw transcript text is never stored here");
    }
}
