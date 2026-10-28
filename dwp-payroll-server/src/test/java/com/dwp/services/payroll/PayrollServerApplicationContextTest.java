package com.dwp.services.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.sql.DataSource;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.dwp.core.database.SystemFlywayConfigurationGuard;
import com.dwp.core.database.AuxiliaryRoleAclGuard;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
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
    void publishesStableOpenApiIdentity() {
        var definition = PayrollServerApplication.class.getAnnotation(OpenAPIDefinition.class);

        assertThat(definition).isNotNull();
        assertThat(definition.info().title()).isEqualTo("DWP Payroll Service API");
        assertThat(definition.info().version()).isEqualTo("1.0.0");
    }

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

    @Test
    void configurationRejectsPublisherPlaceholderDrift() {
        migrationContext().withPropertyValues(
                        "spring.flyway.placeholders.payrollProjectionPublisherRole=other_publisher")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("spring.flyway.placeholders");
                });
    }

    @Test
    void configurationRejectsPublisherThatEqualsRuntime() {
        migrationContext().withPropertyValues(
                        "spring.datasource.username=payroll_runtime",
                        "payroll.projection-publisher.username=payroll_runtime",
                        "spring.flyway.placeholders.payrollRuntimeRole=payroll_runtime",
                        "spring.flyway.placeholders.payrollProjectionPublisherRole=payroll_runtime")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("pairwise distinct");
                });
    }

    @Test
    void publisherPolicyAcceptsOnlyTheExactProjectionObjectsAndPrivileges() {
        String publisher = "payroll_projection_publisher";
        var policy = PayrollDatabaseMigrationConfiguration.publisherPolicy(
                publisher, true);

        assertThat(policy.allowedAclPrivileges().stream()
                .map(privilege -> privilege.objectClass() + ":"
                        + privilege.objectIdentity() + ":"
                        + privilege.privilege()))
                .containsExactlyInAnyOrder(
                        "SCHEMA:public:USAGE",
                        "RELATION:public.pay_legal_entity_scope_projections:SELECT",
                        "RELATION:public.pay_legal_entity_scope_projections:INSERT",
                        "COLUMN:public.pay_legal_entity_scope_projections.status:UPDATE",
                        "COLUMN:public.pay_legal_entity_scope_projections.valid_until:UPDATE",
                        "RELATION:public.pay_legal_entity_scope_members:SELECT",
                        "RELATION:public.pay_legal_entity_scope_members:INSERT",
                        "RELATION:public.pay_population_source_revisions:SELECT",
                        "RELATION:public.pay_population_source_revisions:INSERT",
                        "RELATION:public.pay_population_source_members:SELECT",
                        "RELATION:public.pay_population_source_members:INSERT",
                        "RELATION:public.pay_semantic_source_revisions:SELECT",
                        "RELATION:public.pay_semantic_source_revisions:INSERT",
                        "RELATION:public.pay_population_source_revisio_"
                                + "population_source_internal_id_seq:USAGE",
                        "RELATION:public.pay_population_source_revisio_"
                                + "population_source_internal_id_seq:SELECT",
                        "RELATION:public.pay_population_source_members_"
                                + "population_member_internal_id_seq:USAGE",
                        "RELATION:public.pay_population_source_members_"
                                + "population_member_internal_id_seq:SELECT",
                        "RELATION:public.pay_semantic_source_revisions_"
                                + "semantic_source_internal_id_seq:USAGE",
                        "RELATION:public.pay_semantic_source_revisions_"
                                + "semantic_source_internal_id_seq:SELECT",
                        "RELATION:public.pay_country_pack_version_snapshots:SELECT",
                        "RELATION:public.pay_country_pack_version_snapshots:INSERT",
                        "RELATION:public.pay_country_pack_activation_versions:SELECT",
                        "RELATION:public.pay_country_pack_activation_versions:INSERT",
                        "RELATION:public.pay_country_pack_command_receipts:SELECT",
                        "RELATION:public.pay_country_pack_audit_outbox_events:SELECT",
                        "RELATION:public.pay_social_insurance_result_facts:SELECT",
                        "RELATION:public.pay_social_insurance_result_facts:INSERT",
                        "RELATION:public.pay_country_pack_version_snap_"
                                + "country_pack_snapshot_interna_seq:USAGE",
                        "RELATION:public.pay_country_pack_version_snap_"
                                + "country_pack_snapshot_interna_seq:SELECT",
                        "RELATION:public.pay_country_pack_activation_v_"
                                + "country_pack_activation_inter_seq:USAGE",
                        "RELATION:public.pay_country_pack_activation_v_"
                                + "country_pack_activation_inter_seq:SELECT",
                        "RELATION:public.pay_social_insurance_result_f_"
                                + "social_insurance_fact_interna_seq:USAGE",
                        "RELATION:public.pay_social_insurance_result_f_"
                                + "social_insurance_fact_interna_seq:SELECT",
                        "RELATION:public.pay_tax_result_facts:SELECT",
                        "RELATION:public.pay_tax_result_facts:INSERT",
                        "RELATION:public.pay_tax_result_facts_"
                                + "tax_result_fact_internal_id_seq:USAGE",
                        "RELATION:public.pay_tax_result_facts_"
                                + "tax_result_fact_internal_id_seq:SELECT");
        assertThat(policy.requiredAclPrivileges())
                .isEqualTo(policy.allowedAclPrivileges());
        assertThat(policy.allowedAclPrivileges()).doesNotContain(
                new AuxiliaryRoleAclGuard.AllowedPrivilege(
                        publisher, "RELATION", "public",
                        "public.pay_population_source_revisions", "UPDATE"),
                new AuxiliaryRoleAclGuard.AllowedPrivilege(
                        publisher, "RELATION", "public",
                        "public.pay_population_source_members", "DELETE"),
                new AuxiliaryRoleAclGuard.AllowedPrivilege(
                        publisher, "RELATION", "public",
                        "public.unlisted_payroll_projection", "SELECT"));
    }

    @Test
    void publisherAclGuardAcceptsDeclaredRowsAndRejectsAnActualOverGrant()
            throws Exception {
        String publisher = "payroll_projection_publisher";
        var policy = PayrollDatabaseMigrationConfiguration.publisherPolicy(
                publisher, false);
        Set<AuxiliaryRoleAclGuard.AllowedPrivilege> declaredPrivileges =
                policy.allowedAclPrivileges();

        AuxiliaryRoleAclGuard.verify(
                "Payroll", aclCatalog(declaredPrivileges), List.of("public"),
                Set.of(publisher), policy.allowedAclPrivileges(), declaredPrivileges);

        List<AuxiliaryRoleAclGuard.AllowedPrivilege> overGranted =
                new ArrayList<>(declaredPrivileges);
        overGranted.add(new AuxiliaryRoleAclGuard.AllowedPrivilege(
                publisher, "RELATION", "public",
                "public.pay_population_source_revisions", "UPDATE"));
        assertThatThrownBy(() -> AuxiliaryRoleAclGuard.verify(
                "Payroll", aclCatalog(overGranted), List.of("public"),
                Set.of(publisher), policy.allowedAclPrivileges(), Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "auxiliary-role ACL exceeds the exact privilege surface")
                .hasMessageContaining("pay_population_source_revisions:UPDATE");
    }

    private static DataSource aclCatalog(
            Iterable<AuxiliaryRoleAclGuard.AllowedPrivilege> privileges)
            throws Exception {
        List<AuxiliaryRoleAclGuard.AllowedPrivilege> rows = new ArrayList<>();
        privileges.forEach(rows::add);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        Array roles = mock(Array.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createArrayOf(eq("text"), any())).thenReturn(roles);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        AtomicInteger index = new AtomicInteger(-1);
        when(result.next()).thenAnswer(ignored ->
                index.incrementAndGet() < rows.size());
        when(result.getString(anyInt())).thenAnswer(invocation -> {
            AuxiliaryRoleAclGuard.AllowedPrivilege row = rows.get(index.get());
            return switch (invocation.getArgument(0, Integer.class)) {
                case 1 -> row.grantee();
                case 2 -> row.objectClass();
                case 3 -> row.schema();
                case 4 -> row.objectIdentity();
                case 5 -> row.privilege();
                default -> throw new AssertionError("unexpected catalog column");
            };
        });
        when(result.getBoolean(6)).thenReturn(false);
        return dataSource;
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
            "spring.flyway.placeholders.payrollRuntimeRole=payroll_runtime",
            "payroll.projection-publisher.username=payroll_projection_publisher",
            "spring.flyway.placeholders.payrollProjectionPublisherRole=payroll_projection_publisher"
        };
    }
}
