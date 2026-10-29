package com.dwp.services.platform.workplace.exceptionconsole;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceExceptionConsoleRepositoryPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static WorkplaceExceptionConsoleRepository repository;

    @BeforeAll
    static void migrateAndBuildRepository() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration",
                        "filesystem:../dwp-core/src/main/resources/db/migration")
                .load().migrate();
        repository = new WorkplaceExceptionConsoleRepository(new JdbcTemplate(source));
    }

    @Test
    void everyUnresolvedOperationalSourceQueryExecutesAgainstTheCurrentSchema() {
        long absentTenant = 9_993_431L;
        UUID absentId = UUID.randomUUID();

        assertThat(repository.activeSafety(absentTenant)).isEmpty();
        assertThat(repository.bookingFailures(absentTenant)).isEmpty();
        assertThat(repository.connectorFailures(absentTenant)).isEmpty();
        assertThat(repository.deviceCommandFailures(absentTenant)).isEmpty();
        assertThat(repository.serviceOrderFailures(absentTenant)).isEmpty();
        assertThat(repository.serviceProviderFailures(absentTenant)).isEmpty();
        assertThat(repository.serviceRefundFailures(absentTenant)).isEmpty();
        assertThat(repository.visitProviderFailures(absentTenant)).isEmpty();
        assertThat(repository.accessPassFailures(absentTenant)).isEmpty();
        assertThat(repository.unresolvedCounts(absentTenant).total()).isZero();

        assertThat(repository.activeSafety(absentTenant, absentId)).isEmpty();
        assertThat(repository.bookingFailure(absentTenant, absentId)).isEmpty();
        assertThat(repository.connectorFailure(absentTenant, "CALENDAR")).isEmpty();
        assertThat(repository.deviceCommandFailure(absentTenant, absentId)).isEmpty();
        assertThat(repository.serviceOrderFailure(absentTenant, absentId)).isEmpty();
        assertThat(repository.serviceProviderFailure(absentTenant, "absent-provider")).isEmpty();
        assertThat(repository.serviceRefundFailure(absentTenant, absentId)).isEmpty();
        assertThat(repository.visitProviderFailure(absentTenant, absentId)).isEmpty();
        assertThat(repository.accessPassFailure(absentTenant, absentId)).isEmpty();
    }
}
