package com.dwp.services.meeting.videomeeting.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingFollowupMigrationInvariantTest {

    @Test
    void replayEvidenceIsSingleUseContentFreeAndExpiryBound() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V31__authorize_meeting_followup_sources.sql")) {
            assertThat(input).isNotNull();
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(migration).contains(
                    "jti UUID PRIMARY KEY",
                    "key_id VARCHAR(64) NOT NULL",
                    "tenant_id BIGINT NOT NULL",
                    "actor_user_id BIGINT NOT NULL",
                    "candidate_id UUID NOT NULL",
                    "expires_at TIMESTAMPTZ NOT NULL",
                    "expires_at > consumed_at");
            assertThat(migration.toLowerCase()).doesNotContain(
                    "title", "description", "transcript", "payload", "citation", "assignee");
        }
    }
}
