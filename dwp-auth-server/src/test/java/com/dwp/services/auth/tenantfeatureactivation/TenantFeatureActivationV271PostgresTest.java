package com.dwp.services.auth.tenantfeatureactivation;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class TenantFeatureActivationV271PostgresTest {
    @Container private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String FEATURE = "HRIS_HRM_EXIT_RETURN_WORKBENCH";
    private static final List<String> ROUTES = List.of(
            "route.hcm.operations.exit-return.page",
            "route.hcm.operations.exit-return.data",
            "route.hcm.operations.separation-case.action");
    private static final List<String> AUTHORITY_TABLES = List.of(
            "auth_hris_service_grant_sets",
            "auth_hris_service_grant_bindings",
            "auth_hris_service_authority_proof_replays",
            "auth_hris_service_grant_quiesce_fences",
            "auth_hris_service_grant_drain_activities");

    @Test
    void cleanInstallAppliesV271WithDefaultOffAndNoAuthorityRows() {
        var source = source();
        flyway(source, "271").clean();
        flyway(source, "271").migrate();

        assertCatalogAndEmptyAuthority(new JdbcTemplate(source));
    }

    @Test
    void v270UpgradeAddsOnlySchemaAndImmutableV62CatalogSuccessor() {
        var source = source();
        flyway(source, "271").clean();
        flyway(source, "270").migrate();
        var jdbc = new JdbcTemplate(source);

        int activationBefore = count(jdbc, "auth_product_feature_activation_changes");
        int enrollmentBefore = count(jdbc, "com_tenant_feature_enrollment_changes");
        int grantsBefore = count(jdbc, "com_principal_resource_grants");

        flyway(source, "271").migrate();

        assertCatalogAndEmptyAuthority(jdbc);
        assertThat(count(jdbc, "auth_product_feature_activation_changes"))
                .isEqualTo(activationBefore);
        assertThat(count(jdbc, "com_tenant_feature_enrollment_changes"))
                .isEqualTo(enrollmentBefore);
        assertThat(count(jdbc, "com_principal_resource_grants"))
                .isEqualTo(grantsBefore);
    }

    @Test
    void unexpectedV270RouteOwnerAbortsAndRollsBackV271() {
        var source = source();
        flyway(source, "271").clean();
        flyway(source, "270").migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO sys_product_feature_route_owners (
                    route_contract_key, feature_key)
                VALUES ('route.hcm.operations.unexpected.data', ?)
                """, FEATURE);

        assertThatThrownBy(() -> flyway(source, "271").migrate())
                .rootCause().hasMessageContaining(
                        "V271 conflict: V270 route-owner snapshot differs from the sealed tuples");

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_name = 'auth_hris_service_grant_sets'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_product_authorization_seed_release
                 WHERE bundle_key = 'product-surfaces' AND version = 62
                """, Integer.class)).isZero();
    }

    private void assertCatalogAndEmptyAuthority(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_product_authorization_seed_release
                 WHERE bundle_key = 'product-surfaces' AND version = 62
                   AND checksum =
                       'cb370fd02a896452a64187fca8afb8bb3e3d281e490fba02e76f7a3c0d7f9770'
                   AND auth_seed_artifact =
                       'product-surfaces-v1.bundle-v62.generated.json'
                   AND intended_bundle_status = 'DRAFT'
                   AND automatic_import_enabled = FALSE
                """, Integer.class)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_activation_policies
                 WHERE feature_key = ? AND policy_version = 2
                   AND product_key = 'hcm' AND app_resource_key = 'APP.HCM'
                   AND minimum_bundle_version = 62 AND baseline_state = 'OFF'
                   AND lifecycle_state = 'PUBLISHED' AND owner_key = 'HRM'
                """, Integer.class, FEATURE)).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_activation_policies
                 WHERE feature_key = ?
                """, Integer.class, FEATURE)).isEqualTo(2);
        assertThat(jdbc.queryForList("""
                SELECT route_contract_key FROM sys_product_feature_route_owners
                 WHERE feature_key = ? ORDER BY route_contract_key
                """, String.class, FEATURE)).containsExactlyInAnyOrderElementsOf(ROUTES);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_route_bindings
                 WHERE feature_key = ? AND policy_version = 2
                   AND product_key = 'hcm' AND app_resource_key = 'APP.HCM'
                   AND minimum_bundle_version = 62 AND subject_type = 'PRODUCT'
                   AND surface_key = 'hcm.operations' AND lifecycle_state = 'ACTIVE'
                """, Integer.class, FEATURE)).isEqualTo(3);
        assertThat(jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE route_kind = 'PAGE') AS pages,
                       count(*) FILTER (WHERE route_kind = 'DATA') AS data,
                       count(*) FILTER (WHERE route_kind = 'ACTION') AS actions
                  FROM sys_product_feature_route_bindings
                 WHERE feature_key = ? AND policy_version = 2
                """, FEATURE)).containsEntry("pages", 1L)
                .containsEntry("data", 1L).containsEntry("actions", 1L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_product_feature_route_owners
                 WHERE route_contract_key =
                   'route.approvals.work.hris-separation-waiver-task-approve.action'
                   AND feature_key = ?
                """, Integer.class, FEATURE)).isZero();

        for (String table : AUTHORITY_TABLES) {
            assertThat(count(jdbc, table)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_product_feature_activation_changes
                 WHERE feature_key = ?
                """, Integer.class, FEATURE)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM com_tenant_feature_enrollment_changes
                 WHERE feature_key = ?
                """, Integer.class, FEATURE)).isZero();

        Long tenant = jdbc.queryForObject(
                "SELECT tenant_id FROM com_tenants ORDER BY tenant_id LIMIT 1", Long.class);
        var decision = new TenantFeatureActivationRepository(jdbc).evaluate(
                new TenantFeatureActivationReader.GateRequest(
                        tenant, "hcm", "hcm.operations", ROUTES.getFirst(), 62,
                        Instant.parse("2026-10-27T00:00:00Z")));
        assertThat(decision.featureKey()).isEqualTo(FEATURE);
        assertThat(decision.state())
                .isEqualTo(TenantFeatureActivationReader.State.FEATURE_NOT_ACTIVATED);
        assertThat(decision.reasonCode()).isEqualTo("TENANT_APP_ADOPTION_REQUIRED");
        assertThat(decision.preservesAuthority()).isFalse();
    }

    private int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private PGSimpleDataSource source() {
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private Flyway flyway(PGSimpleDataSource source, String target) {
        return Flyway.configure().dataSource(source)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false).target(target).load();
    }
}
