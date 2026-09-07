package com.dwp.services.meeting.videomeeting.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingChatRetentionMigrationInvariantTest {

    @Test
    void retentionSchemaCarriesLeaseFenceAndContentFreeEvidenceOnly() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V35__purge_expired_meeting_chat_content.sql"));
        String evidence = migration.substring(
                migration.indexOf("CREATE TABLE vm_meeting_chat_retention_evidence"));

        assertThat(migration)
                .contains("active_fence UUID", "active_worker_id VARCHAR(120)",
                        "active_lease_expires_at TIMESTAMPTZ",
                        "overdue_remaining BOOLEAN NOT NULL DEFAULT FALSE");
        assertThat(evidence)
                .contains("tenant_id BIGINT NOT NULL", "meeting_id UUID NOT NULL",
                        "message_id UUID NOT NULL", "fence_token UUID NOT NULL")
                .doesNotContain("message_text", "request_hash", "sender_display_name",
                        "sender_user_id", "deletion_reason_text");
    }
}
