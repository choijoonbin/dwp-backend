package com.dwp.services.meeting.videomeeting.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingLiveFacilitationMigrationInvariantTest {

    @Test
    void migrationBindsTenantMeetingParticipantVersionsRetentionAndContentFreeReceipts()
            throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V34__add_live_meeting_facilitation.sql"));

        assertThat(migration)
                .contains("FOREIGN KEY (tenant_id, meeting_id, author_participant_id)")
                .contains("FOREIGN KEY (tenant_id, meeting_id, voter_participant_id)")
                .contains("PRIMARY KEY (tenant_id, meeting_id, poll_id, voter_user_id)")
                .contains("timer_version BIGINT NOT NULL DEFAULT 0")
                .contains("ballot_version BIGINT NOT NULL DEFAULT 0")
                .contains("retention_until TIMESTAMPTZ NOT NULL")
                .contains("request_sha256 CHAR(64) NOT NULL")
                .doesNotContain("request_payload", "response_payload", "question_text JSON");
    }
}
