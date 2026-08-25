package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.AppGovernanceDtos;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Clean V1-latest evidence for the canonical APP_RESOURCE_SET physical binding. */
@Testcontainers(disabledWithoutDocker = true)
class ProductAuthorizationResourceSetPostgresIntegrationTest {

    private static final Map<String, String> ROOTS = Map.of(
            "RS_COMMUNICATIONS", "APP.COMMUNICATIONS",
            "RS_SERVICES", "APP.EMPLOYEE_SERVICES",
            "RS_APPROVALS", "APP.APPROVALS",
            "RS_HCM_CONFIG", "APP.HCM");
    private static final Map<String, String> STABLE_ID_ROOTS = Map.of(
            "RS_COMMUNICATIONS", "APP.COMMUNICATIONS",
            "RS_SERVICES", "APP.EMPLOYEE_SERVICES",
            "RS_APPROVALS", "APP.APPROVALS",
            "RS_HCM_CONFIG", "APP.HRIS");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateCleanDatabase() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:src/main/resources/db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void preservesStableIdsAndResolvesActualConfigAdminAssignmentsToProductRoots() {
        Map<String, UUID> actualIds = jdbc.query("""
                SELECT resource_set_key, resource_set_id
                  FROM com_admin_resource_sets
                 WHERE tenant_id = 1
                   AND resource_set_key IN (
                       'RS_COMMUNICATIONS', 'RS_SERVICES',
                       'RS_APPROVALS', 'RS_HCM_CONFIG')
                """, result -> {
            Map<String, UUID> values = new LinkedHashMap<>();
            while (result.next()) {
                values.put(result.getString("resource_set_key"),
                        result.getObject("resource_set_id", UUID.class));
            }
            return values;
        });
        assertThat(actualIds).containsOnlyKeys(ROOTS.keySet());
        STABLE_ID_ROOTS.forEach((canonicalKey, originalProductRoot) -> {
            UUID deterministicId = jdbc.queryForObject(
                    "SELECT md5('app-resource-set:' || 1 || ':' || ?)::uuid",
                    UUID.class,
                    originalProductRoot);
            assertThat(actualIds.get(canonicalKey)).isEqualTo(deterministicId);
        });

        Long actorId = jdbc.queryForObject("""
                SELECT user_id
                  FROM com_users
                 WHERE tenant_id = 1
                   AND email_normalized = 'minseok.jang@sk.com'
                """, Long.class);
        AppGovernanceService governance = new AppGovernanceService(
                jdbc, mock(IdentityAuditService.class));
        List<AppGovernanceDtos.ResourceRole> actual = governance
                .resourceRoles(1L, actorId).stream()
                .filter(role -> "APP_CONFIG_ADMIN".equals(role.responsibilityCode()))
                .filter(role -> ROOTS.containsKey(role.resourceSetKey()))
                .filter(role -> ROOTS.get(role.resourceSetKey()).equals(role.resourceKey()))
                .toList();

        assertThat(actual)
                .extracting(AppGovernanceDtos.ResourceRole::resourceSetKey)
                .containsExactlyInAnyOrderElementsOf(ROOTS.keySet());
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM com_admin_resource_sets
                 WHERE tenant_id = 1
                   AND resource_set_key IN (
                       'APP_COMMUNICATIONS', 'APP_EMPLOYEE_SERVICES',
                       'APP_APPROVALS', 'APP_HRIS')
                """, Integer.class)).isZero();
    }
}
