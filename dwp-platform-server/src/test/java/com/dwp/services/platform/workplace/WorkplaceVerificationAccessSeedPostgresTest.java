package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class WorkplaceVerificationAccessSeedPostgresTest {

    @Container
    private final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18.4-alpine");

    @Test
    void grantsLeastPrivilegeOnlyToTheBundledVerificationIdentityAndIsRepeatable() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE sys_service_tenants (
                    tenant_id bigint PRIMARY KEY,
                    tenant_key text NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE cal_identity_links (
                    tenant_id bigint NOT NULL,
                    user_id bigint NOT NULL,
                    person_public_id uuid NOT NULL,
                    PRIMARY KEY (tenant_id, user_id))
                """);
        jdbc.execute("""
                CREATE TABLE wp_sites (
                    site_id uuid PRIMARY KEY,
                    tenant_id bigint NOT NULL,
                    lifecycle_state text NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE wp_site_access_rules (
                    access_rule_id uuid PRIMARY KEY,
                    tenant_id bigint NOT NULL,
                    site_id uuid NOT NULL,
                    subject_type text NOT NULL,
                    subject_user_id bigint,
                    permission_code text NOT NULL,
                    effect text NOT NULL,
                    lifecycle_state text NOT NULL,
                    created_by bigint,
                    updated_by bigint,
                    UNIQUE (tenant_id, site_id, subject_user_id, permission_code))
                """);
        jdbc.update("""
                INSERT INTO sys_service_tenants (tenant_id, tenant_key) VALUES
                    (1, 'default'), (2, 'customer')
                """);
        jdbc.update("""
                INSERT INTO cal_identity_links (tenant_id, user_id, person_public_id) VALUES
                    (1, 900018, '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b'),
                    (1, 7, '00000000-0000-4000-8000-000000000007'),
                    (2, 900018, '8ec1802a-6e3b-3dfc-4075-5c8b0b6e070b')
                """);
        jdbc.update("""
                INSERT INTO wp_sites (site_id, tenant_id, lifecycle_state) VALUES
                    ('00000000-0000-4000-8000-000000000011', 1, 'ACTIVE'),
                    ('00000000-0000-4000-8000-000000000012', 1, 'ACTIVE'),
                    ('00000000-0000-4000-8000-000000000013', 1, 'CLOSED'),
                    ('00000000-0000-4000-8000-000000000021', 2, 'ACTIVE')
                """);

        var migration = new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V336__seed_skax_verification_workplace_access.sql"));
        migration.execute(dataSource);
        migration.execute(dataSource);

        assertThat(jdbc.queryForList("""
                SELECT tenant_id, subject_user_id, permission_code, effect, lifecycle_state
                  FROM wp_site_access_rules
                 ORDER BY site_id
                """))
                .hasSize(2)
                .allSatisfy(rule -> {
                    assertThat(rule.get("tenant_id")).isEqualTo(1L);
                    assertThat(rule.get("subject_user_id")).isEqualTo(900018L);
                    assertThat(rule.get("permission_code")).isEqualTo("BOOK");
                    assertThat(rule.get("effect")).isEqualTo("ALLOW");
                    assertThat(rule.get("lifecycle_state")).isEqualTo("ACTIVE");
                });
    }
}
