package com.dwp.services.meeting.videomeeting.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class VideoMeetingContentMigrationInvariantTest {

    @Test
    void separatesGovernedIntentNoticeConsentAndRecordingCommandState() throws IOException {
        String migration = migration();

        assertThat(migration)
                .contains("CREATE TABLE vm_meeting_content_plans")
                .contains("CREATE TABLE vm_meeting_content_notices")
                .contains("CREATE TABLE vm_meeting_content_notice_acknowledgements")
                .contains("CREATE TABLE vm_meeting_recording_sessions")
                .contains("CREATE TABLE vm_meeting_content_commands")
                .contains("uk_vm_recording_session_active")
                .contains("uk_vm_content_notice_ack")
                .contains("request_hash CHAR(64) NOT NULL");
    }

    @Test
    void neverSeedsOrTransitionsAnArtifactToProcessingOrAvailable() throws IOException {
        String migration = migration();

        assertThat(migration)
                .doesNotContain("INSERT INTO vm_meeting_artifacts")
                .doesNotContain("UPDATE vm_meeting_artifacts")
                .doesNotContain("artifact_state")
                .doesNotContain("'PROCESSING'")
                .doesNotContain("'AVAILABLE'");

        String sessions = migration.substring(
                migration.indexOf("CREATE TABLE vm_meeting_recording_sessions"),
                migration.indexOf("CREATE TABLE vm_meeting_content_commands"));
        assertThat(sessions)
                .contains("'REQUESTED'", "'STOP_REQUESTED'")
                .doesNotContain("object_key", "storage_provider", "content_type", "sha256");
    }

    @Test
    void keepsCommandReceiptsContentFreeAndFailClosed() throws IOException {
        String migration = migration();
        String commands = migration.substring(
                migration.indexOf("CREATE TABLE vm_meeting_content_commands"),
                migration.indexOf("CREATE INDEX ix_vm_content_notice_ack_count"));

        assertThat(commands)
                .contains("command_outcome IN ('ACCEPTED', 'BLOCKED')")
                .contains("http_status IN (200, 409, 503)")
                .contains("blocker_codes")
                .doesNotContain("payload", "title", "description", "transcript", "summary_text");
    }

    private String migration() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V19__add_meeting_content_control_plane.sql")) {
            if (input == null) throw new IOException("Missing V19 content migration.");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
