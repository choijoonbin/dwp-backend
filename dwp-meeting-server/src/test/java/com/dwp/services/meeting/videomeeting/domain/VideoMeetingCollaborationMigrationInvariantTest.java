package com.dwp.services.meeting.videomeeting.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class VideoMeetingCollaborationMigrationInvariantTest {

    @Test
    void givesChatAnIndependentBoundedTenantRetentionPolicy() throws IOException {
        String migration = migration();

        assertThat(migration)
                .contains("ADD COLUMN chat_retention_days INTEGER")
                .contains("SET chat_retention_days = LEAST(90, retention_days)")
                .contains("ALTER COLUMN chat_retention_days SET DEFAULT 90")
                .contains("ALTER COLUMN chat_retention_days SET NOT NULL")
                .contains("chat_retention_days BETWEEN 0 AND 365")
                .contains("chat_retention_days <= retention_days")
                .doesNotContain("chat_retention_days <= artifact_retention_days");
    }

    @Test
    void storesSenderSnapshotsAndDeletionTombstonesWithoutMutableContent() throws IOException {
        String migration = migration();

        assertThat(migration)
                .contains("sender_person_public_id UUID")
                .contains("sender_display_name VARCHAR(160) NOT NULL")
                .contains("sender_role VARCHAR(20) NOT NULL")
                .contains("message_state IN ('ACTIVE', 'DELETED')")
                .contains("message_state = 'DELETED' AND message_text IS NULL")
                .contains("retention_until TIMESTAMPTZ NOT NULL");
    }

    @Test
    void serializesCollaborationAndKeepsHandHistoryContentFree() throws IOException {
        String migration = migration();

        assertThat(migration)
                .contains("CREATE TABLE vm_meeting_collaboration_sequences")
                .contains("CREATE TABLE vm_meeting_collaboration_commands")
                .contains("request_hash CHAR(64) NOT NULL")
                .contains("CREATE TABLE vm_meeting_hand_events")
                .contains("uk_vm_hand_event_sequence")
                .contains("uk_vm_hand_active_participant");

        String handEvents = migration.substring(
                migration.indexOf("CREATE TABLE vm_meeting_hand_events"),
                migration.indexOf("CREATE TABLE vm_meeting_collaboration_commands"));
        assertThat(handEvents).doesNotContain("message_text", "payload", "content");
    }

    private String migration() throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V18__persist_meeting_collaboration.sql")) {
            if (input == null) throw new IOException("Missing V18 collaboration migration.");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
