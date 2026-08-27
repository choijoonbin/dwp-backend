package com.dwp.services.platform.home;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class HomeExperiencePresentationMigrationPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateLegacyRowsAcrossTheContentAlignmentBoundary() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());

        Flyway beforeAlignmentMigration = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("199")
                .cleanDisabled(false)
                .load();
        beforeAlignmentMigration.clean();
        beforeAlignmentMigration.migrate();

        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO adm_home_experiences (tenant_id, background_position)
                VALUES
                    (91001, 'LEFT'),
                    (91002, 'CENTER'),
                    (91003, 'RIGHT')
                """);
        jdbc.update("""
                INSERT INTO adm_home_experiences (
                    tenant_id, background_position, content_alignment)
                VALUES (91004, 'CENTER', 'RIGHT')
                """);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .target("200")
                .load()
                .migrate();
    }

    @Test
    void v200AlignsOnlyDefaultLegacyContentAndPreservesExplicitAlignment() {
        List<HomeAlignment> alignments = jdbc.query("""
                SELECT tenant_id, background_position, content_alignment
                  FROM adm_home_experiences
                 WHERE tenant_id BETWEEN 91001 AND 91004
                 ORDER BY tenant_id
                """, (row, ignored) -> new HomeAlignment(
                row.getLong("tenant_id"),
                row.getString("background_position"),
                row.getString("content_alignment")));

        assertThat(alignments).containsExactly(
                new HomeAlignment(91001L, "LEFT", "RIGHT"),
                new HomeAlignment(91002L, "CENTER", "CENTER"),
                new HomeAlignment(91003L, "RIGHT", "LEFT"),
                new HomeAlignment(91004L, "CENTER", "RIGHT"));
    }

    private record HomeAlignment(
            long tenantId,
            String backgroundPosition,
            String contentAlignment) {
    }
}
