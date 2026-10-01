package com.dwp.services.platform.personalsettings;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class PersonalPrivacyRequestRepositoryPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.locations",
                () -> "filesystem:src/main/resources/db/migration");
    }

    @Autowired private PersonalPrivacyRequestRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void returnsBothOlderOpenTypesBeforeNewerClosedHistoryAtTheBoundedEdge() {
        long tenantId = 98_711L;
        long userId = 42L;
        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 12, 0);
        UUID exportId = new UUID(1L, 1L);
        UUID deletionId = new UUID(1L, 2L);
        insert(exportId, tenantId, userId, "DATA_EXPORT", "RECEIVED", now.minusDays(30));
        insert(deletionId, tenantId, userId, "ACCOUNT_DELETION", "RECEIVED", now.minusDays(31));
        insert(new UUID(1L, 3L), tenantId, userId,
                "DATA_EXPORT", "CANCELLED", now.minusHours(1));
        insert(new UUID(1L, 4L), tenantId, userId,
                "ACCOUNT_DELETION", "CANCELLED", now.minusHours(2));

        var page = repository.findOwnerPageWithOpenRequestsFirst(
                tenantId, userId, PageRequest.of(0, 3));

        assertThat(page).hasSize(3);
        assertThat(page.subList(0, 2)).extracting(PersonalPrivacyRequest::getId)
                .containsExactly(exportId, deletionId);
        assertThat(page.subList(0, 2)).extracting(PersonalPrivacyRequest::getRequestState)
                .containsOnly("RECEIVED");
        assertThat(page.get(2).getRequestState()).isEqualTo("CANCELLED");
    }

    private void insert(
            UUID id,
            long tenantId,
            long userId,
            String requestType,
            String requestState,
            LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO usr_personal_privacy_requests (
                    personal_privacy_request_id, tenant_id, user_id, request_type,
                    request_state, requested_scope, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'ALL_PERSONAL_DATA', 0, ?, ?)
                """, id, tenantId, userId, requestType, requestState,
                Timestamp.valueOf(createdAt), Timestamp.valueOf(createdAt));
    }
}
