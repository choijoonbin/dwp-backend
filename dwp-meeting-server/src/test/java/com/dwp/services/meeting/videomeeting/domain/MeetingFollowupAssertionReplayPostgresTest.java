package com.dwp.services.meeting.videomeeting.domain;

import com.dwp.core.exception.BaseException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class MeetingFollowupAssertionReplayPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Instant NOW = Instant.parse("2026-09-04T05:00:01Z");

    private JdbcTemplate jdbc;
    private MeetingFollowupAssertionReplayRepository repository;

    @BeforeEach
    void setup() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new MeetingFollowupAssertionReplayRepository(
                jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void consumesEachJtiOnceAndPersistsNoTaskOrMeetingContent() {
        UUID jti = UUID.randomUUID();
        var assertion = assertion(jti, NOW.plusSeconds(30));

        repository.consume(assertion);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM vm_meeting_followup_assertion_replay WHERE jti = ?",
                Integer.class, jti)).isOne();
        assertThatThrownBy(() -> repository.consume(assertion))
                .isInstanceOf(BaseException.class)
                .hasMessage("The Platform Work source assertion has already been consumed.");
        assertThat(jdbc.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name = 'vm_meeting_followup_assertion_replay'
                """, String.class)).doesNotContain(
                        "title", "description", "transcript", "payload", "citation");
    }

    @Test
    void expiredEvidenceIsPurgedBeforeAUniqueFutureAssertionIsConsumed() {
        UUID expiredJti = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vm_meeting_followup_assertion_replay (
                    jti, key_id, tenant_id, actor_user_id, meeting_id, report_id,
                    candidate_id, action, expires_at, consumed_at)
                VALUES (?, 'old-key', 7, 11, ?, ?, ?, 'READ', ?, ?)
                """, expiredJti, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                NOW.minusSeconds(1).atOffset(ZoneOffset.UTC),
                NOW.minusSeconds(30).atOffset(ZoneOffset.UTC));

        repository.consume(assertion(UUID.randomUUID(), NOW.plusSeconds(30)));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM vm_meeting_followup_assertion_replay WHERE jti = ?",
                Integer.class, expiredJti)).isZero();
    }

    private MeetingFollowupAssertionVerifier.VerifiedAssertion assertion(
            UUID jti, Instant expiresAt) {
        return new MeetingFollowupAssertionVerifier.VerifiedAssertion(
                "work-meeting-test-v1", jti, 7, 11,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "CREATE", expiresAt);
    }
}
