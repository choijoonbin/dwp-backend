package com.dwp.services.meeting.videomeeting.domain;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises V25 against rows produced by the pre-V25 schema, not only a clean latest schema. */
@Testcontainers(disabledWithoutDocker = true)
class MeetingTranscriptRetentionMigrationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void snapshotNullLegacyRowsAreQuarantinedBeforeV25Constraints() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).target("24").load().clean();
        Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).target("24").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        List<Seed> seeds = jdbc.query("""
                SELECT artifact_id, meeting_id, created_by
                  FROM vm_meeting_artifacts
                 WHERE artifact_type = 'TRANSCRIPT'
                 ORDER BY artifact_id
                 LIMIT 2
                """, (row, index) -> new Seed(
                row.getObject("artifact_id", UUID.class),
                row.getObject("meeting_id", UUID.class), row.getLong("created_by")));
        assertThat(seeds).hasSize(2);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (int index = 0; index < seeds.size(); index++) {
            Seed seed = seeds.get(index);
            UUID noticeId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO vm_meeting_content_notices (
                        notice_id, tenant_id, meeting_id, notice_revision,
                        recording_disclosed, transcription_disclosed,
                        ai_summary_disclosed, published_by)
                    VALUES (?, 1, ?, 1, FALSE, TRUE, TRUE, ?)
                    """, noticeId, seed.meetingId(), seed.createdBy());
            String objectKey = "legacy/transcript/" + seed.artifactId();
            jdbc.update("""
                    UPDATE vm_meeting_artifacts
                       SET artifact_state = ?, storage_provider = 'BROKER',
                           object_key = ?, content_type = 'application/json',
                           size_bytes = 512, sha256 = ?, retention_until = ?,
                           server_side_processing_allowed = TRUE,
                           processing_region = 'ap-northeast-2',
                           content_notice_id = ?, consent_snapshot_sha256 = ?,
                           updated_at = CURRENT_TIMESTAMP
                     WHERE artifact_id = ?
                    """, index == 0 ? "AVAILABLE" : "PROCESSING", objectKey,
                    "a".repeat(64), now.plusDays(30), noticeId, "b".repeat(64),
                    seed.artifactId());
        }

        Flyway.configure().dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).load().migrate();

        for (Seed seed : seeds) {
            LegacyState state = jdbc.queryForObject("""
                    SELECT artifact_state, server_side_processing_allowed,
                           registration_idempotency_key, object_key,
                           metadata ->> 'reason' AS reason
                      FROM vm_meeting_artifacts WHERE artifact_id = ?
                    """, (row, index) -> new LegacyState(
                    row.getString("artifact_state"),
                    row.getBoolean("server_side_processing_allowed"),
                    row.getString("registration_idempotency_key"),
                    row.getString("object_key"), row.getString("reason")),
                    seed.artifactId());
            assertThat(state.state()).isEqualTo("UNAVAILABLE");
            assertThat(state.processing()).isFalse();
            assertThat(state.registrationKey()).isNull();
            assertThat(state.objectKey()).isNotNull();
            assertThat(state.reason()).isEqualTo("LEGACY_TRANSCRIPT_SNAPSHOT_MISSING");
        }
    }

    private record Seed(UUID artifactId, UUID meetingId, long createdBy) { }

    private record LegacyState(
            String state, boolean processing, String registrationKey,
            String objectKey, String reason) { }
}
