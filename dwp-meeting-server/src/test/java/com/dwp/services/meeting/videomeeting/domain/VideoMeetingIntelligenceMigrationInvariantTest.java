package com.dwp.services.meeting.videomeeting.domain;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class VideoMeetingIntelligenceMigrationInvariantTest {

    private static String migration;

    @BeforeAll
    static void readMigration() throws IOException {
        try (var input = VideoMeetingIntelligenceMigrationInvariantTest.class
                .getResourceAsStream("/db/migration/V20__add_governed_meeting_intelligence.sql")) {
            assertThat(input).isNotNull();
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void addsTrustedTranscriptProcessingEvidence() {
        assertThat(migration).contains(
                "server_side_processing_allowed BOOLEAN NOT NULL DEFAULT FALSE",
                "processing_region VARCHAR(32)",
                "content_notice_id UUID",
                "consent_snapshot_sha256 CHAR(64)",
                "ck_vm_artifact_processing_evidence");
    }

    @Test
    void transcriptFinalizationPersistsIdempotentProducerAndReplayEvidence() {
        assertThat(migration).contains(
                "finalization_idempotency_key VARCHAR(160)",
                "finalization_request_sha256 CHAR(64)",
                "ck_vm_artifact_finalization_evidence",
                "vm_meeting_transcript_finalization_assertion_replay",
                "jti UUID PRIMARY KEY",
                "artifact_id UUID NOT NULL");
    }

    @Test
    void existingArtifactsStayFailClosed() {
        assertThat(migration).contains("DEFAULT FALSE");
        assertThat(migration).doesNotContain(
                "UPDATE vm_meeting_artifacts SET server_side_processing_allowed = TRUE",
                "INSERT INTO vm_meeting_intelligence_reports");
    }

    @Test
    void runStateRequiresTerminalEvidence() {
        assertThat(migration).contains(
                "run_state IN ('RUNNING', 'SUCCEEDED', 'FAILED')",
                "ck_vm_intelligence_run_terminal",
                "completed_at IS NOT NULL AND failure_code IS NULL",
                "completed_at IS NOT NULL AND failure_code IS NOT NULL");
    }

    @Test
    void runPersistsConsentRegionProviderAndSchemaProvenance() {
        assertThat(migration).contains(
                "consent_snapshot_sha256 CHAR(64) NOT NULL",
                "processing_region VARCHAR(32) NOT NULL",
                "provider_code VARCHAR(48) NOT NULL",
                "provider_model VARCHAR(120) NOT NULL",
                "prompt_version VARCHAR(48) NOT NULL",
                "schema_version VARCHAR(32) NOT NULL");
    }

    @Test
    void idempotencyIsTenantMeetingActorScoped() {
        assertThat(migration).contains(
                "uk_vm_intelligence_run_idempotency UNIQUE",
                "tenant_id, meeting_id, requested_by, idempotency_key");
    }

    @Test
    void activeSourceHasCrossTabDuplicateProtection() {
        assertThat(migration).contains(
                "CREATE UNIQUE INDEX uk_vm_intelligence_run_active_source",
                "source_artifact_id, source_sha256",
                "analysis_profile, content_notice_id",
                "WHERE run_state = 'RUNNING'");
    }

    @Test
    void retentionWorkerHasDurableLeaseAndFailureEvidence() {
        assertThat(migration).contains(
                "active_fence UUID",
                "active_lease_expires_at TIMESTAMPTZ",
                "ck_vm_intelligence_retention_lease",
                "vm_meeting_intelligence_deletions");
    }

    @Test
    void reportPayloadIsEncryptedInsteadOfJsonb() {
        String reportTable = between(
                "CREATE TABLE vm_meeting_intelligence_reports",
                "CREATE TABLE vm_meeting_intelligence_reviews");

        assertThat(reportTable).contains("encrypted_payload TEXT", "payload_sha256 CHAR(64)");
        assertThat(reportTable).doesNotContain("JSONB");
    }

    @Test
    void publishedReportMustHaveApprovalAndParticipantAudience() {
        assertThat(migration).contains(
                "ck_vm_intelligence_report_approval",
                "ck_vm_intelligence_report_publish",
                "audience = 'MEETING_PARTICIPANTS'",
                "approved_at IS NOT NULL AND approved_by IS NOT NULL");
    }

    @Test
    void deleteStateRequiresCryptographicPayloadShredding() {
        assertThat(migration).contains(
                "report_state = 'DELETED' AND encrypted_payload IS NULL",
                "payload_sha256 IS NULL AND deleted_at IS NOT NULL");
    }

    @Test
    void legalHoldRowsAreExcludedFromRetentionPurgeIndex() {
        assertThat(migration).contains(
                "ix_vm_intelligence_report_retention",
                "report_state <> 'DELETED' AND legal_hold = FALSE");
    }

    @Test
    void reviewsAreImmutableContentFreeEvidence() {
        String reviews = between(
                "CREATE TABLE vm_meeting_intelligence_reviews",
                "CREATE TABLE vm_meeting_intelligence_deletions");

        assertThat(reviews).contains(
                "reviewed_report_version BIGINT NOT NULL",
                "reviewed_payload_sha256 CHAR(64) NOT NULL",
                "decision VARCHAR(20) NOT NULL",
                "reason_code VARCHAR(48) NOT NULL");
        assertThat(reviews).doesNotContain("review_text", "transcript", "JSONB");
    }

    @Test
    void aclIsContentSpecificAndRevocable() {
        assertThat(migration).contains(
                "content_type = 'INTELLIGENCE_REPORT'",
                "permission IN ('VIEW', 'REVIEW', 'MANAGE')",
                "expires_at TIMESTAMPTZ",
                "revoked_at TIMESTAMPTZ",
                "fk_vm_content_acl_report");
    }

    @Test
    void activeAclUniquenessDoesNotReviveRevokedGrants() {
        assertThat(migration).contains(
                "CREATE UNIQUE INDEX uk_vm_content_acl_active",
                "WHERE revoked_at IS NULL");
    }

    @Test
    void schemaContainsNoPersonEmotionOrBiometricColumns() {
        assertThat(migration.toLowerCase()).doesNotContain(
                "speaker_emotion", "participant_emotion", "biometric_template",
                "facial", "sentiment_score", "employee_score");
    }

    private static String between(String start, String end) {
        return migration.substring(migration.indexOf(start), migration.indexOf(end));
    }
}
