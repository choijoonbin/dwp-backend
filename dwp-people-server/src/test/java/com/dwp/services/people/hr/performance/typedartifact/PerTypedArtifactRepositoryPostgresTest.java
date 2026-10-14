package com.dwp.services.people.hr.performance.typedartifact;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PerTypedArtifactRepositoryPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private static PerTypedArtifactRepository repository;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure(PerTypedArtifactRepositoryPostgresTest.class.getClassLoader())
                .dataSource((DataSource) dataSource)
                .locations("classpath:db/performance-migration")
                .defaultSchema("hris_performance")
                .schemas("hris_performance")
                .table("flyway_performance_schema_history")
                .validateOnMigrate(true)
                .load()
                .migrate();
        repository = new PerTypedArtifactRepository(
                new NamedParameterJdbcTemplate(dataSource));
    }

    @Test
    void executesMoreThanThePostgresBindParameterCeilingAsOneArrayInput() {
        List<UUID> artifactIds = LongStream.range(1L, 65_537L)
                .mapToObj(value -> new UUID(0L, value))
                .toList();

        assertThat(repository.findAll(41L, artifactIds)).isEmpty();
    }
}
