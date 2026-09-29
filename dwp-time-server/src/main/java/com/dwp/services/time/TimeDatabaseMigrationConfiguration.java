package com.dwp.services.time;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

import com.dwp.core.database.DomainEventLedgerRuntimeGuard;
import com.dwp.core.database.MigrationAdoptionGuard;
import com.dwp.core.database.MigrationControlRunReceiptGuard;
import com.dwp.core.database.OwnerTriggerExecutionBoundaryGuard;
import com.dwp.core.database.RuntimeMigrationDatabaseGuard;
import com.dwp.core.database.RuntimeMigrationDatabaseGuard.MigrationPrincipalPolicy;
import com.dwp.core.database.RuntimeRoutineExecutionGuard;
import com.dwp.core.database.SystemFlywayConfigurationGuard;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.flyway.FlywayProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fail-closed ownership boundary for the Time database. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FlywayProperties.class)
public class TimeDatabaseMigrationConfiguration {

    private static final MigrationAdoptionGuard.Contract ADOPTION_CONTRACT =
            new MigrationAdoptionGuard.Contract(
                    "time-main",
                    SystemFlywayConfigurationGuard.SCHEMA,
                    SystemFlywayConfigurationGuard.HISTORY_TABLE,
                    List.of(SystemFlywayConfigurationGuard.SCHEMA));

    @Bean
    SystemFlywayConfigurationGuard timeFlywayConfigurationGuard(
            FlywayProperties properties,
            @Value("${spring.datasource.username}") String applicationUser) {
        return SystemFlywayConfigurationGuard.verify(
                properties,
                SystemFlywayConfigurationGuard.freshModuleProfile(
                        "timeRuntimeRole", applicationUser));
    }

    @Bean
    FlywayMigrationStrategy timeDatabaseMigrationStrategy(
            SystemFlywayConfigurationGuard configurationGuard,
            DataSource applicationDataSource,
            @Value("${spring.datasource.username}") String applicationUser,
            @Value("${dwp.time.database.adoption-receipt-sha256:}") String receiptSha256,
            @Value("${dwp.time.database.adoption-control-reference:}")
                    String controlReference,
            @Value("${dwp.time.database.control-run-receipt-json:}")
                    String controlRunReceiptJson,
            @Value("${dwp.time.database.control-run-receipt-sha256:}")
                    String controlRunReceiptSha256,
            @Value("${dwp.time.database.migration-control-reference:}")
                    String migrationControlReference) {
        return flyway -> migrate(
                flyway,
                configurationGuard,
                applicationDataSource,
                applicationUser,
                receiptSha256,
                controlReference,
                controlRunReceiptJson,
                controlRunReceiptSha256,
                migrationControlReference);
    }

    private static void migrate(
            Flyway flyway,
            SystemFlywayConfigurationGuard configurationGuard,
            DataSource applicationDataSource,
            String applicationUser,
            String receiptSha256,
            String controlReference,
            String controlRunReceiptJson,
            String controlRunReceiptSha256,
            String migrationControlReference) {
        configurationGuard.verifyEffectiveConfiguration(flyway);
        DataSource migrationDataSource = Objects.requireNonNull(
                flyway.getConfiguration().getDataSource(),
                "Time Flyway dataSource must not be null");
        RuntimeMigrationDatabaseGuard.verify(
                "Time",
                migrationDataSource,
                applicationDataSource,
                configurationGuard.migrationUser(),
                applicationUser,
                SystemFlywayConfigurationGuard.SCHEMA,
                List.of(SystemFlywayConfigurationGuard.SCHEMA),
                MigrationPrincipalPolicy.STRICT,
                "strict",
                "strict",
                "unused",
                "unused");
        MigrationControlRunReceiptGuard.RunReceipt controlRunReceipt =
                MigrationControlRunReceiptGuard.verify(
                        "time",
                        List.of(ADOPTION_CONTRACT),
                        migrationDataSource,
                        configurationGuard.migrationUser(),
                        migrationControlReference,
                        controlRunReceiptJson,
                        controlRunReceiptSha256);
        MigrationAdoptionGuard.AdoptionState adoption =
                MigrationAdoptionGuard.verifyBeforeMigration(
                        ADOPTION_CONTRACT,
                        migrationDataSource,
                        applicationDataSource,
                        configurationGuard.migrationUser(),
                        receiptSha256,
                        controlReference);
        MigrationControlRunReceiptGuard.verifyAdoptionBinding(
                controlRunReceipt,
                ADOPTION_CONTRACT,
                receiptSha256,
                controlReference,
                adoption.receipt());
        MigrationAdoptionGuard.requireNoPendingBeforeMigration(
                ADOPTION_CONTRACT, flyway, adoption, true);
        flyway.migrate();
        RuntimeMigrationDatabaseGuard.hardenAndVerifyHistoryTables(
                "Time",
                migrationDataSource,
                applicationDataSource,
                List.of(new RuntimeMigrationDatabaseGuard.HistoryTable(
                        SystemFlywayConfigurationGuard.SCHEMA,
                        SystemFlywayConfigurationGuard.HISTORY_TABLE)));
        RuntimeMigrationDatabaseGuard.verify(
                "Time",
                migrationDataSource,
                applicationDataSource,
                configurationGuard.migrationUser(),
                applicationUser,
                SystemFlywayConfigurationGuard.SCHEMA,
                List.of(SystemFlywayConfigurationGuard.SCHEMA),
                MigrationPrincipalPolicy.STRICT,
                "strict",
                "strict",
                "unused",
                "unused");
        MigrationAdoptionGuard.requireNoPendingAfterMigration(
                ADOPTION_CONTRACT, flyway, adoption);
        MigrationAdoptionGuard.verifyAfterMigration(
                ADOPTION_CONTRACT,
                migrationDataSource,
                applicationDataSource,
                configurationGuard.migrationUser(),
                receiptSha256,
                controlReference,
                adoption);
        MigrationControlRunReceiptGuard.verifyAdoptionBinding(
                controlRunReceipt,
                ADOPTION_CONTRACT,
                receiptSha256,
                controlReference,
                adoption.receipt());
        MigrationControlRunReceiptGuard.verifyNativeStateUnchanged(
                controlRunReceipt,
                List.of(ADOPTION_CONTRACT),
                migrationDataSource,
                configurationGuard.migrationUser());
        RuntimeRoutineExecutionGuard.verifyExact(
                "Time", applicationDataSource,
                List.of(SystemFlywayConfigurationGuard.SCHEMA), Set.of());
        DomainEventLedgerRuntimeGuard.verify(
                "Time", applicationDataSource,
                DomainEventLedgerRuntimeGuard.Profile.ACTIVE);
        OwnerTriggerExecutionBoundaryGuard.verify(
                "Time", applicationDataSource,
                List.of(SystemFlywayConfigurationGuard.SCHEMA),
                configurationGuard.migrationUser());
    }

    @Bean
    TimeMigrationInfrastructure timeMigrationInfrastructure(
            Flyway flyway,
            FlywayMigrationInitializer initializer) {
        return new TimeMigrationInfrastructure(flyway, initializer);
    }

    record TimeMigrationInfrastructure(Flyway flyway, FlywayMigrationInitializer initializer) {

        TimeMigrationInfrastructure {
            Objects.requireNonNull(flyway, "flyway must not be null");
            Objects.requireNonNull(initializer, "initializer must not be null");
        }
    }
}
