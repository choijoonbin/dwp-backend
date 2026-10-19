package com.dwp.services.people.hris.workbench.hrm011;

import com.dwp.services.people.hr.HcmPopulationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL proof that IAQ-008 filters, orders, and pages before truncation. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcHrm011QuerySourcePostgresTest {
    private static final long FILTER_TENANT = 59_121L;
    private static final long PAGE_TENANT = 59_122L;
    private static final LocalDate EFFECTIVE_ON = LocalDate.parse("2026-10-19");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private static NamedParameterJdbcTemplate named;
    private static TransactionTemplate transactions;
    private static JdbcHrm011QuerySource source;

    @BeforeAll
    static void migrateOnceThroughV59() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway flyway = Flyway.configure(
                        JdbcHrm011QuerySourcePostgresTest.class.getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("59");

        named = new NamedParameterJdbcTemplate(dataSource);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        source = new JdbcHrm011QuerySource(named);
    }

    @Test
    void filterAndSortPrecedeLimitBeyondFiveHundredAndRetainTypedUnsafeCandidate() {
        List<RowPlan> plans = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            plans.add(RowPlan.safe(new BigDecimal("100"), "USD"));
        }
        plans.add(RowPlan.incomplete());
        plans.add(RowPlan.safe(new BigDecimal("100"), "KRW"));
        List<UUID> relationships = seed(FILTER_TENANT, "filter", plans);
        UUID unsafe = relationships.get(500);
        UUID matching = relationships.get(501);

        Hrm011QuerySource.Page page = read(
                FILTER_TENANT, new Hrm011QueryModels.MoneyFilter("KRW", null, null),
                List.of("itemPublicId:ASC"), 100, null);

        assertThat(page.rows())
                .extracting(row -> row.relationship().relationshipPublicId())
                .containsExactly(unsafe, matching);
        assertThat(page.rows().getFirst().compensationBasis()).isNull();
        assertThat(page.rows().getLast().compensationBasis()).isNotNull();
        assertThat(page.rows().getLast().authoritativeCandidateCount()).isEqualTo(1);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void equalAmountsCrossAscAndDescPageBoundariesWithoutGapDuplicateOrAmbiguousSelection() {
        List<RowPlan> plans = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            plans.add(index == 3
                    ? RowPlan.ambiguous(new BigDecimal("100"), "KRW")
                    : RowPlan.safe(new BigDecimal("100"), "KRW"));
        }
        List<UUID> expected = seed(PAGE_TENANT, "page", plans);

        for (String direction : List.of("ASC", "DESC")) {
            Hrm011QuerySource.Page first = read(
                    PAGE_TENANT, null,
                    List.of("compensationBasis:" + direction), 4, null);
            Hrm011QuerySource.Page second = read(
                    PAGE_TENANT, null,
                    List.of("compensationBasis:" + direction), 4,
                    first.lastScannedRelationshipPublicId());
            List<Hrm011QueryModels.SourceRow> all = new ArrayList<>(first.rows());
            all.addAll(second.rows());

            assertThat(first.hasMore()).isTrue();
            assertThat(second.cursorPositionFound()).isTrue();
            assertThat(second.hasMore()).isFalse();
            assertThat(all)
                    .extracting(row -> row.relationship().relationshipPublicId())
                    .containsExactlyElementsOf(expected);
            assertThat(new HashSet<>(all.stream()
                    .map(row -> row.relationship().relationshipPublicId()).toList()))
                    .hasSize(expected.size());
            assertThat(all.stream()
                    .filter(row -> row.authoritativeCandidateCount() != 1).toList())
                    .singleElement()
                    .satisfies(row -> assertThat(row.authoritativeCandidateCount())
                            .isEqualTo(2));
        }
    }

    private Hrm011QuerySource.Page read(
            long tenantId,
            Hrm011QueryModels.MoneyFilter money,
            List<String> sort,
            int limit,
            UUID after) {
        return Objects.requireNonNull(transactions.execute(status -> source.read(
                tenantId,
                new Hrm011QuerySource.QuerySpec(
                        EFFECTIVE_ON, money, sort, limit, after),
                new HcmPopulationRepository.PopulationScope(
                        1L, null, true, Set.of(),
                        Set.of("EMPLOYMENT", "COMPENSATION", "DOCUMENT_CONTENT"),
                        "policy-v1"))));
    }

    private static List<UUID> seed(
            long tenantId, String key, List<RowPlan> plans) {
        long base = tenantId * 100_000L;
        long employerId = base + 1;
        UUID employerPublicId = publicId(6, tenantId);
        new JdbcTemplate(named.getJdbcTemplate().getDataSource()).update("""
                INSERT INTO ppl_legal_employers(
                    legal_employer_id,public_id,tenant_id,employer_key,legal_name,version)
                VALUES(?,?,?,?,?,1)
                """, employerId, employerPublicId, tenantId,
                key + "-employer", key + " employer");

        List<MapSqlParameterSource> persons = new ArrayList<>();
        List<MapSqlParameterSource> workers = new ArrayList<>();
        List<MapSqlParameterSource> relationships = new ArrayList<>();
        List<MapSqlParameterSource> assignments = new ArrayList<>();
        List<MapSqlParameterSource> bases = new ArrayList<>();
        List<UUID> relationshipPublicIds = new ArrayList<>();
        for (int index = 0; index < plans.size(); index++) {
            long ordinal = index + 1L;
            long personId = base + 10_000 + ordinal;
            long workerId = base + 20_000 + ordinal;
            long relationshipId = base + 30_000 + ordinal;
            long assignmentId = base + 40_000 + ordinal;
            long basisId = base + 50_000 + ordinal * 2;
            long publicSequence = tenantId * 1_000 + ordinal;
            UUID relationshipPublicId = publicId(3, publicSequence);
            relationshipPublicIds.add(relationshipPublicId);
            persons.add(parameters(tenantId, ordinal)
                    .addValue("id", personId)
                    .addValue("publicId", publicId(1, publicSequence))
                    .addValue("key", key + "-person-" + ordinal));
            workers.add(parameters(tenantId, ordinal)
                    .addValue("id", workerId)
                    .addValue("personId", personId)
                    .addValue("publicId", publicId(2, publicSequence))
                    .addValue("key", key + "-worker-" + ordinal));
            relationships.add(parameters(tenantId, ordinal)
                    .addValue("id", relationshipId)
                    .addValue("workerId", workerId)
                    .addValue("employerId", employerId)
                    .addValue("publicId", relationshipPublicId)
                    .addValue("key", key + "-relationship-" + ordinal));
            assignments.add(parameters(tenantId, ordinal)
                    .addValue("id", assignmentId)
                    .addValue("relationshipId", relationshipId)
                    .addValue("publicId", publicId(4, publicSequence))
                    .addValue("key", key + "-assignment-" + ordinal));
            RowPlan plan = plans.get(index);
            bases.add(basisParameters(
                    tenantId, ordinal, basisId, assignmentId,
                    publicId(5, publicSequence), "SALARY", plan));
            if (plan.ambiguous()) {
                bases.add(basisParameters(
                        tenantId, ordinal, basisId + 1, assignmentId,
                        publicId(7, publicSequence), "HOURLY", plan));
            }
        }
        batch("""
                INSERT INTO ppl_persons(
                    person_id,public_id,tenant_id,person_key,display_name,version)
                VALUES(:id,:publicId,:tenant,:key,:key,1)
                """, persons);
        batch("""
                INSERT INTO ppl_workers(
                    worker_id,public_id,tenant_id,person_id,worker_number,
                    worker_type,worker_status,version)
                VALUES(:id,:publicId,:tenant,:personId,:key,'EMPLOYEE','ACTIVE',1)
                """, workers);
        batch("""
                INSERT INTO ppl_work_relationships(
                    work_relationship_id,public_id,tenant_id,relationship_key,
                    worker_id,legal_employer_id,relationship_type,
                    primary_relationship,start_date,version)
                VALUES(:id,:publicId,:tenant,:key,:workerId,:employerId,
                    'EMPLOYEE',TRUE,DATE '2026-01-01',1)
                """, relationships);
        batch("""
                INSERT INTO ppl_assignments(
                    assignment_id,public_id,tenant_id,assignment_key,
                    work_relationship_id,effective_start_date,effective_sequence,
                    assignment_status,primary_assignment,business_title,version)
                VALUES(:id,:publicId,:tenant,:key,:relationshipId,
                    DATE '2026-01-01',1,'ACTIVE',TRUE,:key,1)
                """, assignments);
        batch("""
                INSERT INTO ppl_compensation_basis(
                    compensation_basis_id,public_id,tenant_id,assignment_id,
                    basis_type,amount,currency_code,frequency_code,valid_from,
                    version,created_by,updated_by)
                VALUES(:id,:publicId,:tenant,:assignmentId,:basisType,:amount,
                    :currency,:frequency,DATE '2026-01-01',1,7,7)
                """, bases);
        return List.copyOf(relationshipPublicIds);
    }

    private static MapSqlParameterSource parameters(long tenantId, long ordinal) {
        return new MapSqlParameterSource()
                .addValue("tenant", tenantId)
                .addValue("ordinal", ordinal);
    }

    private static MapSqlParameterSource basisParameters(
            long tenantId,
            long ordinal,
            long id,
            long assignmentId,
            UUID publicId,
            String basisType,
            RowPlan plan) {
        return parameters(tenantId, ordinal)
                .addValue("id", id)
                .addValue("assignmentId", assignmentId)
                .addValue("publicId", publicId)
                .addValue("basisType", basisType)
                .addValue("amount", plan.amount())
                .addValue("currency", plan.currency())
                .addValue("frequency", plan.amount() == null ? null : "ANNUAL");
    }

    private static void batch(String sql, List<MapSqlParameterSource> parameters) {
        named.batchUpdate(sql, parameters.toArray(SqlParameterSource[]::new));
    }

    private static UUID publicId(int namespace, long sequence) {
        return UUID.fromString(String.format(
                "%08x-0000-4000-8000-%012x", namespace, sequence));
    }

    private record RowPlan(BigDecimal amount, String currency, boolean ambiguous) {
        static RowPlan safe(BigDecimal amount, String currency) {
            return new RowPlan(amount, currency, false);
        }

        static RowPlan incomplete() {
            return new RowPlan(null, null, false);
        }

        static RowPlan ambiguous(BigDecimal amount, String currency) {
            return new RowPlan(amount, currency, true);
        }
    }
}
