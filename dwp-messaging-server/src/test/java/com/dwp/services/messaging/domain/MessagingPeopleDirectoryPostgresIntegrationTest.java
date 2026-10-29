package com.dwp.services.messaging.domain;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "DWP_MESSAGING_INTEGRATION_DB_URL", matches = ".+")
class MessagingPeopleDirectoryPostgresIntegrationTest {

    private static final long TENANT = 96_420;
    private static final long OTHER_TENANT = 96_421;
    private static final long VIEWER = 100;
    private static JdbcTemplate jdbc;
    private MessagingQueryRepository queries;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(
                System.getenv("DWP_MESSAGING_INTEGRATION_DB_URL"),
                System.getenv().getOrDefault(
                        "DWP_MESSAGING_INTEGRATION_DB_USERNAME", "postgres"),
                System.getenv().getOrDefault(
                        "DWP_MESSAGING_INTEGRATION_DB_PASSWORD", "postgres"));
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM msg_people_snapshot WHERE tenant_id IN (?, ?)",
                TENANT, OTHER_TENANT);
        insert(TENANT, VIEWER, "Current Viewer", "Platform", "Engineer", "ACTIVE");
        insert(TENANT, 201, "Alpha Person", "Risk Operations", "Risk Analyst", "ACTIVE");
        insert(TENANT, 202, "Bravo Person", "Risk Operations", "Risk Analyst", "ACTIVE");
        insert(TENANT, 203, "Charlie Person", "Risk Operations", "Risk Analyst", "ACTIVE");
        insert(TENANT, 204, "Inactive Person", "Risk Operations", "Risk Analyst", "INACTIVE");
        insert(OTHER_TENANT, 205, "Other Tenant", "Risk Operations", "Risk Analyst", "ACTIVE");
        queries = new MessagingQueryRepository(jdbc, new MessagingMessageQueryRepository(jdbc));
    }

    @Test
    void filtersWithinTenantAndContinuesByStableDisplayNameKeyset() {
        MessagingQueryRepository.PeoplePage first = queries.peoplePage(
                TENANT, VIEWER, "risk", "risk operations", "analyst", null, 0, 2);

        assertThat(first.total()).isEqualTo(3);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.items()).extracting(MessagingDtos.PersonSummary::displayName)
                .containsExactly("Alpha Person", "Bravo Person");

        MessagingPeopleCursor.Cursor cursor = MessagingPeopleCursor.decode(
                MessagingPeopleCursor.encode(
                        first.items().get(1), TENANT, VIEWER,
                        "risk", "risk operations", "analyst", "ALL"),
                TENANT, VIEWER, "risk", "risk operations", "analyst", "ALL");
        MessagingQueryRepository.PeoplePage second = queries.peoplePage(
                TENANT, VIEWER, "risk", "risk operations", "analyst", cursor, 99, 2);

        assertThat(second.total()).isEqualTo(3);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.items()).extracting(MessagingDtos.PersonSummary::displayName)
                .containsExactly("Charlie Person");
    }

    @Test
    void offsetFallbackRemainsCompatibleUntilAKeysetCursorIsSupplied() {
        MessagingQueryRepository.PeoplePage page = queries.peoplePage(
                TENANT, VIEWER, "", "risk", "analyst", null, 1, 1);

        assertThat(page.items()).extracting(MessagingDtos.PersonSummary::displayName)
                .containsExactly("Bravo Person");
        assertThat(page.hasMore()).isTrue();
    }

    private void insert(
            long tenantId,
            long userId,
            String displayName,
            String organization,
            String jobTitle,
            String lifecycle) {
        jdbc.update("""
                INSERT INTO msg_people_snapshot (
                    tenant_id, user_id, person_public_id, email_address, display_name,
                    job_title, organization_name, lifecycle_state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                userId,
                UUID.randomUUID(),
                "person" + userId + "-" + tenantId + "@example.com",
                displayName,
                jobTitle,
                organization,
                lifecycle);
    }
}
