package com.dwp.services.meeting.videomeeting.domain;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingLocalUiDemoSeedPostgresTest extends MeetingWorkspacePostgresFixture {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("dwp_meetings")
            .withUsername("dwp_user")
            .withPassword("dwp_password");

    @Override
    PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    @Test
    void operatorSeedRollsBackByDefaultAndIsIdempotentWithoutExternalEvidence()
            throws IOException, InterruptedException {
        Path seed = seedPath();
        String source = Files.readString(seed);
        assertThat(source).doesNotContain(
                "INSERT INTO vm_meeting_recording_sessions",
                "INSERT INTO vm_meeting_intelligence_runs",
                "INSERT INTO vm_meeting_intelligence_reports",
                "INSERT INTO vm_meeting_media_operations",
                "INSERT INTO vm_meeting_content_notice_acknowledgements",
                "INSERT INTO vm_meeting_invitation_outbox");
        POSTGRES.copyFileToContainer(
                Transferable.of(Files.readAllBytes(seed), 0444), "/tmp/seed-local-ui-demo.sql");

        org.testcontainers.containers.Container.ExecResult dryRun = runSeed(false, false);
        assertThat(dryRun.getExitCode()).as(dryRun.getStderr()).isZero();
        assertThat(dryRun.getStdout()).contains("DRY RUN PASSED AND ROLLED BACK");
        assertThat(demoCount("vm_meetings")).isZero();

        org.testcontainers.containers.Container.ExecResult first = runSeed(true, false);
        assertThat(first.getExitCode()).as(first.getStderr()).isZero();
        assertThat(first.getStdout()).contains("LOCAL DEMO COMMITTED");
        Map<String, Long> expected = expectedDemoCounts();
        assertThat(actualDemoCounts()).containsExactlyEntriesOf(expected);
        assertManualSyntheticOutcomes();
        assertExternalEvidenceAbsent();

        org.testcontainers.containers.Container.ExecResult replay = runSeed(true, false);
        assertThat(replay.getExitCode()).as(replay.getStderr()).isZero();
        assertThat(actualDemoCounts()).containsExactlyEntriesOf(expected);
        assertThat(replay.getStdout()).containsPattern(
                "inserted_manual_outcomes[\\s\\S]*?\\n[-+ ]+\\n(?:[^\\n]*\\|){2}\\s*0\\s*\\|");
        assertThat(replay.getStdout()).containsPattern("inserted_disabled_plans[\\s\\S]*?\\n[-+ ]+\\n\\s*0\\s*\\|");
        assertManualSyntheticOutcomes();

        simulateUntouchedExpiredSchedule();
        org.testcontainers.containers.Container.ExecResult refreshed = runSeed(true, true);
        assertThat(refreshed.getExitCode()).as(refreshed.getStderr()).isZero();
        assertThat(refreshed.getStdout()).containsPattern("refreshed_schedules[\\s\\S]*?\\n[-+ ]+\\n(?:[^\\n]*\\|){5}\\s*1");
        assertThat(actualDemoCounts()).containsExactlyEntriesOf(expected);
        assertRefreshedInvitationIsReconfirmed();
        assertManualSyntheticOutcomes();

        org.testcontainers.containers.Container.ExecResult refreshReplay = runSeed(true, true);
        assertThat(refreshReplay.getExitCode()).as(refreshReplay.getStderr()).isZero();
        assertThat(refreshReplay.getStdout()).containsPattern("refreshed_schedules[\\s\\S]*?\\n[-+ ]+\\n(?:[^\\n]*\\|){5}\\s*0");
        assertThat(actualDemoCounts()).containsExactlyEntriesOf(expected);
        assertManualSyntheticOutcomes();
        assertExternalEvidenceAbsent();
    }

    private org.testcontainers.containers.Container.ExecResult runSeed(boolean apply, boolean refresh)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                "psql", "-X", "-v", "ON_ERROR_STOP=1"));
        if (apply) command.addAll(List.of("-v", "apply_seed=true"));
        if (refresh) command.addAll(List.of("-v", "refresh_demo_schedule=true"));
        command.addAll(List.of(
                "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
                "-f", "/tmp/seed-local-ui-demo.sql"));
        return POSTGRES.execInContainer(command.toArray(String[]::new));
    }

    private void simulateUntouchedExpiredSchedule() {
        String meetingId = jdbc.queryForObject("""
                SELECT meeting_id::text FROM vm_meetings
                 WHERE tenant_id = 1 AND correlation_id = 'meeting-ui-demo-v1'
                   AND lifecycle_state = 'SCHEDULED'
                 ORDER BY scheduled_start_at, meeting_id LIMIT 1
                """, String.class);
        jdbc.execute("ALTER TABLE vm_meetings DISABLE TRIGGER vm_reconfirm_changed_meeting_invitation");
        try {
            jdbc.update("""
                    UPDATE vm_meetings
                       SET scheduled_start_at = CURRENT_TIMESTAMP - INTERVAL '2 hours',
                           scheduled_end_at = CURRENT_TIMESTAMP - INTERVAL '1 hour'
                     WHERE tenant_id = 1 AND meeting_id = ?::uuid
                    """, meetingId);
        } finally {
            jdbc.execute("ALTER TABLE vm_meetings ENABLE TRIGGER vm_reconfirm_changed_meeting_invitation");
        }
        jdbc.update("""
                DELETE FROM vm_meeting_personal_preparations
                 WHERE tenant_id = 1 AND meeting_id = ?::uuid
                """, meetingId);
        jdbc.update("""
                DELETE FROM vm_meeting_preparation_materials
                 WHERE tenant_id = 1 AND meeting_id = ?::uuid
                """, meetingId);
        jdbc.update("""
                DELETE FROM vm_meeting_events
                 WHERE tenant_id = 1 AND meeting_id = ?::uuid
                """, meetingId);
    }

    private void assertRefreshedInvitationIsReconfirmed() {
        Map<String, Long> revisionCounts = jdbc.query("""
                SELECT response_state, count(*) AS count
                  FROM vm_meeting_invitation_responses response
                  JOIN vm_meetings meeting USING (tenant_id, meeting_id)
                 WHERE meeting.tenant_id = 1
                   AND meeting.correlation_id = 'meeting-ui-demo-v1'
                   AND meeting.scheduled_start_at > CURRENT_TIMESTAMP
                   AND response.invitation_revision = 2 AND response.version = 1
                 GROUP BY response_state
                """, result -> {
            Map<String, Long> counts = new LinkedHashMap<>();
            while (result.next()) counts.put(result.getString(1), result.getLong(2));
            return counts;
        });
        assertThat(revisionCounts).containsKeys("ACCEPTED", "RECONFIRM_REQUIRED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM vm_meetings
                 WHERE tenant_id = 1 AND correlation_id = 'meeting-ui-demo-v1'
                   AND lifecycle_state = 'SCHEDULED'
                   AND scheduled_start_at > CURRENT_TIMESTAMP
                """, Long.class)).isEqualTo(16);
    }

    private Map<String, Long> expectedDemoCounts() {
        return Map.ofEntries(
                Map.entry("vm_meetings", 30L),
                Map.entry("vm_meeting_content_plans", 30L),
                Map.entry("vm_meeting_preparation_materials", 6L),
                Map.entry("vm_meeting_personal_preparations", 6L),
                Map.entry("vm_meeting_personal_preparation_items", 12L),
                Map.entry("vm_meeting_collaboration_sequences", 2L),
                Map.entry("vm_meeting_chat_messages", 4L),
                Map.entry("vm_meeting_hand_requests", 2L),
                Map.entry("vm_meeting_hand_events", 4L),
                Map.entry("vm_meeting_facilitation_states", 2L),
                Map.entry("vm_meeting_facilitation_questions", 2L),
                Map.entry("vm_meeting_facilitation_question_upvotes", 4L),
                Map.entry("vm_meeting_facilitation_polls", 2L),
                Map.entry("vm_meeting_facilitation_poll_options", 6L),
                Map.entry("vm_meeting_facilitation_poll_votes", 8L),
                Map.entry("vm_meeting_events", 40L));
    }

    private Map<String, Long> actualDemoCounts() {
        Map<String, Long> actual = new LinkedHashMap<>();
        expectedDemoCounts().keySet().forEach(table -> actual.put(table, demoCount(table)));
        return actual;
    }

    private long demoCount(String table) {
        if (!table.matches("vm_[a-z_]+")) throw new IllegalArgumentException("Unexpected table");
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " child "
                + "JOIN vm_meetings meeting USING (tenant_id, meeting_id) "
                + "WHERE meeting.correlation_id = 'meeting-ui-demo-v1'", Long.class);
    }

    private void assertExternalEvidenceAbsent() {
        for (String table : List.of(
                "vm_meeting_recording_sessions", "vm_meeting_intelligence_runs",
                "vm_meeting_media_operations", "vm_meeting_content_notice_acknowledgements",
                "vm_meeting_invitation_outbox")) {
            assertThat(demoCount(table)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM vm_meeting_artifacts artifact
                  JOIN vm_meetings meeting USING (tenant_id, meeting_id)
                 WHERE meeting.correlation_id = 'meeting-ui-demo-v1'
                   AND (artifact.artifact_state NOT IN ('NONE', 'UNAVAILABLE')
                        OR artifact.object_key IS NOT NULL
                        OR artifact.server_side_processing_allowed)
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM vm_meeting_preparation_materials material
                  JOIN vm_meetings meeting USING (tenant_id, meeting_id)
                 WHERE meeting.correlation_id = 'meeting-ui-demo-v1'
                   AND (material.access_verification_state <> 'PENDING_REVALIDATION'
                        OR material.last_verified_at IS NOT NULL
                        OR material.size_bytes IS NOT NULL OR material.content_sha256 IS NOT NULL
                        OR material.opaque_reference LIKE 'http%')
                """, Long.class)).isZero();
    }

    private void assertManualSyntheticOutcomes() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM vm_meetings meeting
                 WHERE meeting.tenant_id = 1
                   AND meeting.correlation_id = 'meeting-ui-demo-v1'
                   AND meeting.lifecycle_state = 'ENDED'
                   AND jsonb_array_length(meeting.decisions) = 2
                   AND jsonb_array_length(meeting.follow_up_actions) = 2
                   AND meeting.decisions @> '[{"demo":true,"synthetic":true,"origin":"MANUAL_SEED_NOT_AI"}]'::jsonb
                   AND meeting.follow_up_actions @> '[{"demo":true,"synthetic":true,"origin":"MANUAL_SEED_NOT_WORK"}]'::jsonb
                """, Long.class)).isEqualTo(10L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM vm_meetings meeting
                 CROSS JOIN LATERAL jsonb_array_elements(meeting.decisions) decision
                 WHERE meeting.tenant_id = 1
                   AND meeting.correlation_id = 'meeting-ui-demo-v1'
                   AND meeting.lifecycle_state = 'ENDED'
                   AND decision ->> 'decision'
                        LIKE '[화면점검 · 수동 기록 · AI 결과 아님]%'
                   AND EXISTS (
                       SELECT 1 FROM vm_meeting_participants participant
                        WHERE participant.tenant_id = meeting.tenant_id
                          AND participant.meeting_id = meeting.meeting_id
                          AND participant.user_id = (decision ->> 'ownerUserId')::BIGINT)
                """, Long.class)).isEqualTo(20L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM vm_meetings meeting
                 CROSS JOIN LATERAL jsonb_array_elements(meeting.follow_up_actions) follow_up
                 WHERE meeting.tenant_id = 1
                   AND meeting.correlation_id = 'meeting-ui-demo-v1'
                   AND meeting.lifecycle_state = 'ENDED'
                   AND follow_up ->> 'action'
                        LIKE '[화면점검 · 수동 후속 초안 · 실제 업무 아님]%'
                   AND EXISTS (
                       SELECT 1 FROM vm_meeting_participants participant
                        WHERE participant.tenant_id = meeting.tenant_id
                          AND participant.meeting_id = meeting.meeting_id
                          AND participant.user_id = (follow_up ->> 'ownerUserId')::BIGINT)
                """, Long.class)).isEqualTo(20L);
    }

    private Path seedPath() {
        return List.of(
                        Path.of("dwp-meeting-server/scripts/seed-local-ui-demo.sql"),
                        Path.of("scripts/seed-local-ui-demo.sql"))
                .stream().filter(Files::isRegularFile).findFirst().orElseThrow();
    }
}
