package com.dwp.services.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import com.dwp.core.database.SystemFlywayConfigurationGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PayrollServerApplicationContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues(
                    "spring.autoconfigure.exclude="
                            + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                            + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
            .withPropertyValues(canonicalProperties())
            .withUserConfiguration(PayrollServerApplication.class);

    @Test
    void rejectsServiceContextWithoutRequiredMigrationInfrastructure() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("payrollDatabaseMigrationStrategy")
                    .hasStackTraceContaining("DataSource");
        });
    }

    @Test
    void canonicalConfigurationCreatesOneGuardAndStrategy() {
        migrationContext().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SystemFlywayConfigurationGuard.class);
            assertThat(context).hasSingleBean(FlywayMigrationStrategy.class);
            assertThat(context).hasSingleBean(
                    PayrollDatabaseMigrationConfiguration
                            .PayrollMigrationInfrastructure.class);
        });
    }

    @Test
    void configurationRejectsSameRolePlaceholderDrift() {
        migrationContext().withPropertyValues(
                        "spring.flyway.placeholders.payrollRuntimeRole=other_runtime")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("spring.flyway.placeholders");
                });
    }

    private static ApplicationContextRunner migrationContext() {
        return new ApplicationContextRunner()
                .withUserConfiguration(PayrollDatabaseMigrationConfiguration.class)
                .withBean(Flyway.class, () -> org.mockito.Mockito.mock(Flyway.class))
                .withBean(FlywayMigrationInitializer.class,
                        () -> org.mockito.Mockito.mock(FlywayMigrationInitializer.class))
                .withBean(DataSource.class, () -> org.mockito.Mockito.mock(DataSource.class))
                .withPropertyValues(canonicalProperties());
    }

    private static String[] canonicalProperties() {
        return new String[] {
            "spring.datasource.username=payroll_runtime",
            "spring.flyway.enabled=true",
            "spring.flyway.url=jdbc:postgresql://localhost:5432/dwp_payroll",
            "spring.flyway.user=payroll_migration",
            "spring.flyway.password=migration_password",
            "spring.flyway.locations=classpath:db/migration",
            "spring.flyway.default-schema=public",
            "spring.flyway.schemas=public",
            "spring.flyway.table=flyway_schema_history",
            "spring.flyway.baseline-on-migrate=false",
            "spring.flyway.baseline-version=0",
            "spring.flyway.validate-on-migrate=true",
            "spring.flyway.out-of-order=false",
            "spring.flyway.fail-on-missing-locations=true",
            "spring.flyway.clean-disabled=true",
            "spring.flyway.create-schemas=false",
            "spring.flyway.placeholder-replacement=true",
            "spring.flyway.validate-migration-naming=true",
            "spring.flyway.execute-in-transaction=true",
            "spring.flyway.output-query-results=false",
            "spring.flyway.community-db-support-enabled=false",
            "spring.flyway.skip-executing-migrations=false",
            "spring.flyway.ignore-migration-patterns=",
            "spring.flyway.connect-retries=3",
            "spring.flyway.lock-retry-count=10",
            "spring.flyway.placeholders.payrollRuntimeRole=payroll_runtime"
        };
    }
}
