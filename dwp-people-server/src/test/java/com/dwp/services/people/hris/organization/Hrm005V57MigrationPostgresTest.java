package com.dwp.services.people.hris.organization;

import com.dwp.services.people.workforce.WorkforceAccessPolicyRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class Hrm005V57MigrationPostgresTest {

    private static final long TENANT_ONE = 57_001L;
    private static final long TENANT_TWO = 57_002L;

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private Flyway migrated;

    @AfterEach
    void clean() {
        if (migrated != null) migrated.clean();
    }

    @Test
    void cleanMigrationThroughV57AcceptsOrganizationAndKeepsTenantResolutionClosed() {
        DataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        migrated = flyway(source, null);
        migrated.clean();

        migrated.migrate();

        assertThat(migrated.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("57"));
        insertPolicy(jdbc, TENANT_ONE, "HR_ORGANIZATION_VIEWER",
                "{DIRECTORY,ORGANIZATION}");
        insertPolicy(jdbc, TENANT_TWO, "HR_ORGANIZATION_VIEWER",
                "{DIRECTORY,ORGANIZATION}");
        WorkforceAccessPolicyRepository repository =
                new WorkforceAccessPolicyRepository(
                        new NamedParameterJdbcTemplate(source));

        assertThat(repository.resolve(
                TENANT_ONE, 1001L, Set.of("HR_ORGANIZATION_VIEWER"), Instant.now()))
                .singleElement().satisfies(policy -> {
                    assertThat(policy.fieldGroups())
                            .containsExactly("DIRECTORY", "ORGANIZATION");
                    assertThat(policy.subjectRef()).isEqualTo("HR_ORGANIZATION_VIEWER");
                });
        assertThat(repository.resolve(
                TENANT_TWO, 1001L, Set.of("HR_ORGANIZATION_VIEWER"), Instant.now()))
                .hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ppl_workforce_access_policies WHERE tenant_id=?",
                Integer.class, TENANT_ONE)).isEqualTo(1);
        assertThat(constraint(jdbc))
                .contains("DIRECTORY")
                .contains("WORKER_IDENTIFIERS")
                .contains("EMPLOYMENT")
                .contains("JOB_GRADE")
                .contains("ORGANIZATION")
                .contains("cardinality(field_groups) > 0");

        assertThatThrownBy(() -> insertPolicy(
                jdbc, TENANT_ONE, "HR_UNKNOWN_VIEWER", "{DIRECTORY,UNKNOWN}"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_workforce_access_policy_fields");
        assertThatThrownBy(() -> insertPolicy(
                jdbc, TENANT_ONE, "HR_EMPTY_VIEWER", "{}"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("ck_workforce_access_policy_fields");
    }

    @Test
    void upgradeFromV56PreservesRowsAndHistoricalChecksumsWithoutElevation() {
        DataSource source = dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Flyway throughV56 = flyway(source, "56");
        throughV56.clean();
        throughV56.migrate();
        Map<String, Integer> historical = checksums(throughV56);
        insertPolicy(jdbc, TENANT_ONE, "HR_LEGACY_VIEWER",
                "{DIRECTORY,WORKER_IDENTIFIERS,EMPLOYMENT,JOB_GRADE}");
        String before = policyFields(jdbc, TENANT_ONE, "HR_LEGACY_VIEWER");

        migrated = flyway(source, null);
        assertThat(migrated.migrate().migrationsExecuted).isEqualTo(1);

        assertThat(migrated.info().current().getVersion())
                .isEqualTo(MigrationVersion.fromVersion("57"));
        assertThat(checksums(migrated)).containsAllEntriesOf(historical);
        assertThat(policyFields(jdbc, TENANT_ONE, "HR_LEGACY_VIEWER"))
                .isEqualTo(before)
                .doesNotContain("ORGANIZATION");
        assertThat(jdbc.queryForObject("""
                SELECT version FROM ppl_workforce_access_policies
                 WHERE tenant_id=? AND subject_ref='HR_LEGACY_VIEWER'
                """, Long.class, TENANT_ONE)).isZero();
        insertPolicy(jdbc, TENANT_ONE, "HR_ORGANIZATION_VIEWER",
                "{DIRECTORY,ORGANIZATION}");
        assertThat(policyFields(jdbc, TENANT_ONE, "HR_ORGANIZATION_VIEWER"))
                .isEqualTo("{DIRECTORY,ORGANIZATION}");
    }

    private void insertPolicy(
            JdbcTemplate jdbc,
            long tenantId,
            String subjectRef,
            String fieldGroups) {
        jdbc.update("""
                INSERT INTO ppl_workforce_access_policies(
                    tenant_id,subject_type,subject_ref,population_type,
                    field_groups,action_codes,justification)
                VALUES(?,'ROLE',?,'TENANT',CAST(? AS VARCHAR[]),
                       ARRAY['READ']::VARCHAR[],'HRM-005 migration proof')
                """, tenantId, subjectRef, fieldGroups);
    }

    private String policyFields(
            JdbcTemplate jdbc,
            long tenantId,
            String subjectRef) {
        return jdbc.queryForObject("""
                SELECT field_groups::text FROM ppl_workforce_access_policies
                 WHERE tenant_id=? AND subject_ref=?
                """, String.class, tenantId, subjectRef);
    }

    private String constraint(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT pg_get_constraintdef(constraint_record.oid)
                  FROM pg_constraint constraint_record
                  JOIN pg_class relation_record
                    ON relation_record.oid=constraint_record.conrelid
                 WHERE relation_record.relname='ppl_workforce_access_policies'
                   AND constraint_record.conname='ck_workforce_access_policy_fields'
                """, String.class);
    }

    private Map<String, Integer> checksums(Flyway flyway) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (MigrationInfo info : flyway.info().applied()) {
            if (info.getVersion() != null
                    && Integer.parseInt(info.getVersion().getVersion()) <= 56) {
                result.put(info.getVersion().getVersion(), info.getChecksum());
            }
        }
        return result;
    }

    private Flyway flyway(DataSource source, String target) {
        var configuration = Flyway.configure(getClass().getClassLoader())
                .dataSource(source)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}
