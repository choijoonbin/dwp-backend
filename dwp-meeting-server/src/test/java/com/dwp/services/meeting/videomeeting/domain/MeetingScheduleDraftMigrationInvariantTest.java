package com.dwp.services.meeting.videomeeting.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingScheduleDraftMigrationInvariantTest {

    @Test
    void v36BindsPrivateDraftsPersonalChecksAndPhysicalRetentionEvidence()
            throws IOException {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/"
                        + "V36__persist_schedule_drafts_and_personal_preparation.sql"));

        assertThat(migration)
                .contains("UNIQUE (tenant_id, owner_user_id)")
                .contains("REFERENCES vm_people_snapshot (tenant_id, user_id)")
                .contains("REFERENCES vm_meeting_template_revisions")
                .contains("retention_until TIMESTAMPTZ NOT NULL")
                .contains("vm_meeting_schedule_draft_retention_health")
                .contains("vm_meeting_schedule_draft_retention_evidence")
                .contains("vm_meeting_personal_preparations")
                .contains("vm_meeting_personal_preparation_items")
                .contains("PREPARATION_CHECK")
                .contains("ix_vm_meeting_preparation_material_retention")
                .doesNotContain("guest_email", "device_id", "consent_state",
                        "consent BOOLEAN", "access_token",
                        "request_payload", "response_payload", "personal_note");
    }
}
