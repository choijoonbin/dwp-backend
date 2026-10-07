package com.dwp.services.time;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

import com.dwp.core.database.DomainEventLedgerRuntimeGuard;
import com.dwp.core.database.AuxiliaryRoleAclGuard;
import com.dwp.core.database.MigrationAdoptionGuard;
import com.dwp.core.database.MigrationControlRunReceiptGuard;
import com.dwp.core.database.OwnerTriggerExecutionBoundaryGuard;
import com.dwp.core.database.RuntimeMigrationDatabaseGuard;
import com.dwp.core.database.RuntimeMigrationDatabaseGuard.MigrationPrincipalPolicy;
import com.dwp.core.database.TrustedPublisherPolicy;
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
            @Value("${spring.datasource.username}") String applicationUser,
            @Value("${time.projection-publisher.username}")
                    String projectionPublisherUser,
            @Value("${spring.flyway.placeholders.timeProjectionPublisherRole}")
                    String projectionPublisherPlaceholder) {
        requirePublisherPlaceholder(
                projectionPublisherUser, projectionPublisherPlaceholder);
        requireDistinctPublisher(
                properties.getUser(), applicationUser, projectionPublisherUser);
        return SystemFlywayConfigurationGuard.verify(
                properties,
                SystemFlywayConfigurationGuard.freshModuleProfile(Map.of(
                        "timeRuntimeRole", applicationUser,
                        "timeProjectionPublisherRole", projectionPublisherUser)));
    }

    private static void requirePublisherPlaceholder(
            String publisherUser, String placeholderUser) {
        if (!Objects.equals(publisherUser, placeholderUser)) {
            throw new IllegalArgumentException(
                    "spring.flyway.placeholders.timeProjectionPublisherRole "
                            + "must match time.projection-publisher.username");
        }
    }

    private static void requireDistinctPublisher(
            String migrationUser, String applicationUser, String publisherUser) {
        if (publisherUser == null || publisherUser.isBlank()
                || publisherUser.equals(migrationUser)
                || publisherUser.equals(applicationUser)) {
            throw new IllegalArgumentException(
                    "Time migration, runtime and projection publisher roles must be pairwise distinct");
        }
    }

    @Bean
    FlywayMigrationStrategy timeDatabaseMigrationStrategy(
            SystemFlywayConfigurationGuard configurationGuard,
            DataSource applicationDataSource,
            @Value("${spring.datasource.username}") String applicationUser,
            @Value("${time.projection-publisher.username}")
                    String projectionPublisherUser,
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
                projectionPublisherUser,
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
            String projectionPublisherUser,
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
                "unused",
                Set.of(),
                RuntimeMigrationDatabaseGuard.AuxiliaryAuthorityPolicy.NONE,
                publisherPolicy(projectionPublisherUser, false));
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
                "unused",
                Set.of(),
                RuntimeMigrationDatabaseGuard.AuxiliaryAuthorityPolicy.NONE,
                publisherPolicy(projectionPublisherUser, true));
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

    private static TrustedPublisherPolicy publisherPolicy(
            String publisher, boolean requireSteadyState) {
        Set<AuxiliaryRoleAclGuard.AllowedPrivilege> mutable = new LinkedHashSet<>();
        mutable.add(privilege(publisher, "SCHEMA", "public", "public", "USAGE"));
        for (String table : List.of(
                "tim_target_population_projections",
                "tim_target_population_actor_grants",
                "tim_target_population_members")) {
            mutable.add(privilege(publisher, "RELATION", "public",
                    "public." + table, "SELECT"));
            mutable.add(privilege(publisher, "RELATION", "public",
                    "public." + table, "INSERT"));
        }
        mutable.addAll(columnUpdates(publisher, "tim_target_population_projections", Set.of(
                        "projection_revision", "lifecycle_state", "effective_from",
                        "effective_to", "source_digest", "updated_at", "updated_by")));
        mutable.addAll(columnUpdates(publisher, "tim_target_population_actor_grants", Set.of(
                        "population_public_id", "population_revision", "grant_revision",
                        "lifecycle_state", "valid_from", "valid_to", "source_digest",
                        "updated_at", "updated_by")));
        mutable.addAll(columnUpdates(publisher, "tim_target_population_members", Set.of(
                        "population_revision", "people_assignment_revision",
                        "membership_revision", "lifecycle_state", "effective_from",
                        "effective_to", "source_digest", "updated_at", "updated_by")));
        Set<AuxiliaryRoleAclGuard.AllowedPrivilege> allowed = Set.copyOf(mutable);
        return new TrustedPublisherPolicy(
                publisher, allowed, requireSteadyState ? allowed : Set.of());
    }

    private static Set<AuxiliaryRoleAclGuard.AllowedPrivilege> columnUpdates(
            String grantee, String table, Set<String> columns) {
        return columns.stream()
                .map(column -> privilege(grantee, "COLUMN", "public",
                        "public." + table + "." + column, "UPDATE"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static AuxiliaryRoleAclGuard.AllowedPrivilege privilege(
            String grantee,
            String objectClass,
            String schema,
            String identity,
            String privilege) {
        return new AuxiliaryRoleAclGuard.AllowedPrivilege(
                grantee, objectClass, schema, identity, privilege);
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
