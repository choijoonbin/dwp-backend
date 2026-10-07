package com.dwp.services.payroll;

import java.util.List;
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

/** Fail-closed ownership boundary for the Payroll database. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FlywayProperties.class)
public class PayrollDatabaseMigrationConfiguration {

    private static final MigrationAdoptionGuard.Contract ADOPTION_CONTRACT =
            new MigrationAdoptionGuard.Contract(
                    "payroll-main",
                    SystemFlywayConfigurationGuard.SCHEMA,
                    SystemFlywayConfigurationGuard.HISTORY_TABLE,
                    List.of(SystemFlywayConfigurationGuard.SCHEMA));

    @Bean
    SystemFlywayConfigurationGuard payrollFlywayConfigurationGuard(
            FlywayProperties properties,
            @Value("${spring.datasource.username}") String applicationUser,
            @Value("${payroll.projection-publisher.username}")
                    String projectionPublisherUser,
            @Value("${spring.flyway.placeholders.payrollProjectionPublisherRole}")
                    String projectionPublisherPlaceholder) {
        requirePublisherPlaceholder(
                projectionPublisherUser, projectionPublisherPlaceholder);
        requireDistinctPublisher(
                properties.getUser(), applicationUser, projectionPublisherUser);
        return SystemFlywayConfigurationGuard.verify(
                properties,
                SystemFlywayConfigurationGuard.freshModuleProfile(Map.of(
                        "payrollRuntimeRole", applicationUser,
                        "payrollProjectionPublisherRole", projectionPublisherUser)));
    }

    private static void requirePublisherPlaceholder(
            String publisherUser, String placeholderUser) {
        if (!Objects.equals(publisherUser, placeholderUser)) {
            throw new IllegalArgumentException(
                    "spring.flyway.placeholders.payrollProjectionPublisherRole "
                            + "must match payroll.projection-publisher.username");
        }
    }

    private static void requireDistinctPublisher(
            String migrationUser, String applicationUser, String publisherUser) {
        if (publisherUser == null || publisherUser.isBlank()
                || publisherUser.equals(migrationUser)
                || publisherUser.equals(applicationUser)) {
            throw new IllegalArgumentException(
                    "Payroll migration, runtime and projection publisher roles must be pairwise distinct");
        }
    }

    @Bean
    FlywayMigrationStrategy payrollDatabaseMigrationStrategy(
            SystemFlywayConfigurationGuard configurationGuard,
            DataSource applicationDataSource,
            @Value("${spring.datasource.username}") String applicationUser,
            @Value("${payroll.projection-publisher.username}")
                    String projectionPublisherUser,
            @Value("${dwp.payroll.database.adoption-receipt-sha256:}")
                    String receiptSha256,
            @Value("${dwp.payroll.database.adoption-control-reference:}")
                    String controlReference,
            @Value("${dwp.payroll.database.control-run-receipt-json:}")
                    String controlRunReceiptJson,
            @Value("${dwp.payroll.database.control-run-receipt-sha256:}")
                    String controlRunReceiptSha256,
            @Value("${dwp.payroll.database.migration-control-reference:}")
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
                "Payroll Flyway dataSource must not be null");
        RuntimeMigrationDatabaseGuard.verify(
                "Payroll",
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
                        "payroll",
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
                "Payroll",
                migrationDataSource,
                applicationDataSource,
                List.of(new RuntimeMigrationDatabaseGuard.HistoryTable(
                        SystemFlywayConfigurationGuard.SCHEMA,
                        SystemFlywayConfigurationGuard.HISTORY_TABLE)));
        RuntimeMigrationDatabaseGuard.verify(
                "Payroll",
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
                "Payroll", applicationDataSource,
                List.of(SystemFlywayConfigurationGuard.SCHEMA), Set.of());
        DomainEventLedgerRuntimeGuard.verify(
                "Payroll", applicationDataSource,
                DomainEventLedgerRuntimeGuard.Profile.ACTIVE);
        OwnerTriggerExecutionBoundaryGuard.verify(
                "Payroll", applicationDataSource,
                List.of(SystemFlywayConfigurationGuard.SCHEMA),
                configurationGuard.migrationUser());
    }

    private static TrustedPublisherPolicy publisherPolicy(
            String publisher, boolean requireSteadyState) {
        Set<AuxiliaryRoleAclGuard.AllowedPrivilege> allowed = Set.of(
                privilege(publisher, "SCHEMA", "public", "public", "USAGE"),
                privilege(publisher, "RELATION", "public",
                        "public.pay_legal_entity_scope_projections", "SELECT"),
                privilege(publisher, "RELATION", "public",
                        "public.pay_legal_entity_scope_projections", "INSERT"),
                privilege(publisher, "COLUMN", "public",
                        "public.pay_legal_entity_scope_projections.status", "UPDATE"),
                privilege(publisher, "COLUMN", "public",
                        "public.pay_legal_entity_scope_projections.valid_until", "UPDATE"),
                privilege(publisher, "RELATION", "public",
                        "public.pay_legal_entity_scope_members", "SELECT"),
                privilege(publisher, "RELATION", "public",
                        "public.pay_legal_entity_scope_members", "INSERT"));
        return new TrustedPublisherPolicy(
                publisher, allowed, requireSteadyState ? allowed : Set.of());
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
    PayrollMigrationInfrastructure payrollMigrationInfrastructure(
            Flyway flyway,
            FlywayMigrationInitializer initializer) {
        return new PayrollMigrationInfrastructure(flyway, initializer);
    }

    record PayrollMigrationInfrastructure(Flyway flyway, FlywayMigrationInitializer initializer) {

        PayrollMigrationInfrastructure {
            Objects.requireNonNull(flyway, "flyway must not be null");
            Objects.requireNonNull(initializer, "initializer must not be null");
        }
    }
}
