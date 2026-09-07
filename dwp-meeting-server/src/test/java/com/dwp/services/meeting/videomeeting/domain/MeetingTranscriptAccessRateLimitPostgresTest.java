package com.dwp.services.meeting.videomeeting.domain;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class MeetingTranscriptAccessRateLimitPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private MeetingTranscriptAccessRepository repository;

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
        repository = new MeetingTranscriptAccessRepository(new JdbcTemplate(dataSource));
    }

    @Test
    void enforcesThirtyReadsPerTenantUserMeetingWindowWithoutContentColumns() {
        UUID meetingId = UUID.randomUUID();
        OffsetDateTime window = OffsetDateTime.parse("2026-09-04T01:00:00Z");

        for (int index = 0; index < MeetingTranscriptAccessRepository.REQUESTS_PER_MINUTE;
                index++) {
            assertThat(repository.consume(1L, meetingId, 10L, window)).isTrue();
        }
        assertThat(repository.consume(1L, meetingId, 10L, window)).isFalse();
        assertThat(repository.consume(1L, meetingId, 11L, window)).isTrue();
        assertThat(repository.consume(2L, meetingId, 10L, window)).isTrue();
        assertThat(repository.consume(1L, UUID.randomUUID(), 10L, window)).isTrue();
        assertThat(repository.consume(1L, meetingId, 10L, window.plusMinutes(1))).isTrue();
    }
}
