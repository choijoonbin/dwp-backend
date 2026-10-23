package com.dwp.services.people.hris.performance.review;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.DraftCommand;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.ReviewState;
import com.dwp.services.people.hris.performance.review.PerformanceReviewDtos.SubmitCommand;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PerformanceReviewPostgresTest {

    private static final String PURPOSE = HcmPerformanceReviewAuthorityAdapter.PURPOSE;
    private static final String POPULATION = "TARGET_POPULATION";
    private static final String DECISION_REVISION = "psr-" + "c".repeat(64);
    private static final String SAVE_KEY = "review-save-command-0001";
    private static final String SUBMIT_KEY = "review-submit-command-0001";
    private static final Instant NOW = Instant.parse("2027-05-02T00:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault(
                    "DWP_TEST_POSTGRES_IMAGE", "postgres:16-alpine"));

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private ObjectMapper mapper;
    private PerformanceReviewCanonicalizer canonicalizer;
    private PerformanceReviewRepository repository;
    private PerformanceReviewService service;

    @BeforeEach
    void migrateAndSeedAssignment() {
        dataSource = dataSource();
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA IF EXISTS hris_performance CASCADE");
        jdbc.execute("CREATE SCHEMA hris_performance");
        com.dwp.services.people.hris.performance.support
                .PerformanceMigrationRuntimeRoleFixture.prepare(dataSource);
        Flyway.configure(getClass().getClassLoader())
                .dataSource(dataSource)
                .locations("classpath:db/performance-migration")
                .defaultSchema("hris_performance")
                .schemas("hris_performance")
                .table("flyway_performance_schema_history")
                .validateOnMigrate(true)
                .load()
                .migrate();
        mapper = new ObjectMapper().findAndRegisterModules();
        canonicalizer = new PerformanceReviewCanonicalizer(mapper);
        repository = new PerformanceReviewRepository(
                new NamedParameterJdbcTemplate(dataSource), mapper);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        service = new PerformanceReviewService(
                repository, this::authorize, canonicalizer,
                Clock.fixed(NOW, ZoneOffset.UTC));
        setContext(PerformanceReviewTestFixtures.TENANT,
                PerformanceReviewTestFixtures.SUBJECT);
        seedAssignment();
    }

    @AfterEach
    void clearRequestContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void commitsCommandsOnceAndReturnsStableResultsForExactRetries() throws Exception {
        PerformanceReviewService.VersionedReview initial = inTransaction(() ->
                service.review(PerformanceReviewTestFixtures.REVIEW, PURPOSE, POPULATION));
        assertThat(initial.review().aggregateVersion()).isZero();

        DraftCommand draft = new DraftCommand(
                PerformanceReviewTestFixtures.completeAnswers());
        PerformanceReviewService.VersionedReview saved = inTransaction(() ->
                service.saveDraft(
                        PerformanceReviewTestFixtures.REVIEW, 0, SAVE_KEY,
                        DECISION_REVISION, PURPOSE, POPULATION, draft));
        PerformanceReviewService.VersionedReview retriedSave = inTransaction(() ->
                service.saveDraft(
                        PerformanceReviewTestFixtures.REVIEW, 0, SAVE_KEY,
                        DECISION_REVISION, PURPOSE, POPULATION, draft));

        assertThat(retriedSave).isEqualTo(saved);
        assertThat(saved.review().aggregateVersion()).isEqualTo(1);
        assertThat(saved.review().state()).isEqualTo(ReviewState.IN_PROGRESS);
        assertThat(saved.review().answers()).hasSize(4);
        assertCode(() -> inTransaction(() -> service.saveDraft(
                        PerformanceReviewTestFixtures.REVIEW, 0,
                        "review-save-command-0002", DECISION_REVISION,
                        PURPOSE, POPULATION, draft)),
                ErrorCode.OBJECT_VERSION_CONFLICT);
        assertThat(count("prf_command_receipts")).isEqualTo(1);
        assertThat(count("prf_review_command_results")).isEqualTo(1);
        assertThat(count("prf_outbox_events")).isZero();

        SubmitCommand submit = new SubmitCommand(
                saved.review().draftContentHash(), true);
        PerformanceReviewDtos.ReviewSubmission submitted = inTransaction(() ->
                service.submit(
                        PerformanceReviewTestFixtures.REVIEW, 1, SUBMIT_KEY,
                        DECISION_REVISION, PURPOSE, POPULATION, submit));
        PerformanceReviewDtos.ReviewSubmission retriedSubmit = inTransaction(() ->
                service.submit(
                        PerformanceReviewTestFixtures.REVIEW, 1, SUBMIT_KEY,
                        DECISION_REVISION, PURPOSE, POPULATION, submit));

        assertThat(retriedSubmit).isEqualTo(submitted);
        assertThat(submitted.submissionNo()).isEqualTo(1);
        assertThat(submitted.submittedAt()).isEqualTo(NOW);
        assertThat(jdbc.queryForMap("""
                SELECT aggregate_version, review_state, writable
                  FROM hris_performance.prf_review_assignments
                 WHERE tenant_id = ? AND review_id = ?
                """, PerformanceReviewTestFixtures.TENANT,
                PerformanceReviewTestFixtures.REVIEW))
                .containsEntry("aggregate_version", 2L)
                .containsEntry("review_state", "SUBMITTED")
                .containsEntry("writable", false);
        assertThat(count("prf_command_receipts")).isEqualTo(2);
        assertThat(count("prf_review_command_results")).isEqualTo(2);
        assertThat(count("prf_review_submissions")).isEqualTo(1);
        assertThat(count("prf_outbox_events")).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
                SELECT reviewer_assignment_id, template_version_id,
                       template_content_hash, content_hash
                  FROM hris_performance.prf_review_submissions
                 WHERE tenant_id = ? AND submission_id = ?
                """, PerformanceReviewTestFixtures.TENANT, submitted.submissionId()))
                .containsEntry("reviewer_assignment_id",
                        PerformanceReviewTestFixtures.ASSIGNMENT)
                .containsEntry("template_version_id",
                        PerformanceReviewTestFixtures.TEMPLATE)
                .containsEntry("template_content_hash", "a".repeat(64))
                .containsEntry("content_hash", submitted.contentHash());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM hris_performance.prf_command_receipts
                 WHERE tenant_id = ? AND aggregate_id = ?
                   AND receipt_state = 'SUCCEEDED'
                   AND completed_at IS NOT NULL
                   AND applied_aggregate_version IS NOT NULL
                   AND result_ref IS NOT NULL
                """, Integer.class, PerformanceReviewTestFixtures.TENANT,
                PerformanceReviewTestFixtures.REVIEW)).isEqualTo(2);

        JsonNode event = mapper.readTree(jdbc.queryForObject("""
                SELECT payload::text
                  FROM hris_performance.prf_outbox_events
                 WHERE tenant_id = ? AND aggregate_id = ?
                   AND event_type = 'PerformanceReviewSubmitted.v1'
                """, String.class, PerformanceReviewTestFixtures.TENANT,
                PerformanceReviewTestFixtures.REVIEW));
        List<String> fields = new ArrayList<>();
        event.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder(
                "reviewId", "participantId", "reviewerAssignmentId",
                "submissionId", "templateVersionId", "contentHash");
        assertThat(event.path("submissionId").asText())
                .isEqualTo(submitted.submissionId().toString());

        assertCode(() -> inTransaction(() -> service.submit(
                        PerformanceReviewTestFixtures.REVIEW, 1, SUBMIT_KEY,
                        DECISION_REVISION, PURPOSE, POPULATION,
                        new SubmitCommand(saved.review().draftContentHash(), null))),
                ErrorCode.RESOURCE_CONFLICT);
        assertThat(count("prf_command_receipts")).isEqualTo(2);
        assertThat(count("prf_review_submissions")).isEqualTo(1);
        assertThat(count("prf_outbox_events")).isEqualTo(1);
    }

    @Test
    void lateResultFailureRollsBackCasReceiptSubmissionAndOutboxAtomically() {
        PerformanceReviewService.VersionedReview saved = inTransaction(() ->
                service.saveDraft(
                        PerformanceReviewTestFixtures.REVIEW, 0, SAVE_KEY,
                        DECISION_REVISION, PURPOSE, POPULATION,
                        new DraftCommand(PerformanceReviewTestFixtures.completeAnswers())));
        installFailingResultTrigger();

        assertThatThrownBy(() -> inTransaction(() -> service.submit(
                PerformanceReviewTestFixtures.REVIEW, 1, SUBMIT_KEY,
                DECISION_REVISION, PURPOSE, POPULATION,
                new SubmitCommand(saved.review().draftContentHash(), true))))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbc.queryForMap("""
                SELECT aggregate_version, review_state, writable
                  FROM hris_performance.prf_review_assignments
                 WHERE tenant_id = ? AND review_id = ?
                """, PerformanceReviewTestFixtures.TENANT,
                PerformanceReviewTestFixtures.REVIEW))
                .containsEntry("aggregate_version", 1L)
                .containsEntry("review_state", "IN_PROGRESS")
                .containsEntry("writable", true);
        assertThat(count("prf_command_receipts")).isEqualTo(1);
        assertThat(count("prf_review_command_results")).isEqualTo(1);
        assertThat(count("prf_review_submissions")).isZero();
        assertThat(count("prf_outbox_events")).isZero();

        removeFailingResultTrigger();
        PerformanceReviewDtos.ReviewSubmission recovered = inTransaction(() ->
                service.submit(
                        PerformanceReviewTestFixtures.REVIEW, 1, SUBMIT_KEY,
                        DECISION_REVISION, PURPOSE, POPULATION,
                        new SubmitCommand(saved.review().draftContentHash(), true)));
        assertThat(recovered.submissionNo()).isEqualTo(1);
        assertThat(count("prf_command_receipts")).isEqualTo(2);
        assertThat(count("prf_review_submissions")).isEqualTo(1);
        assertThat(count("prf_outbox_events")).isEqualTo(1);
    }

    @Test
    void assignedLookupIsOpaqueAcrossSubjectAndTenantBoundaries() {
        setContext(PerformanceReviewTestFixtures.TENANT,
                java.util.UUID.fromString("10000000-0000-0000-0000-000000000099"));
        assertCode(() -> inTransaction(() -> service.review(
                        PerformanceReviewTestFixtures.REVIEW, PURPOSE, POPULATION)),
                ErrorCode.NOT_FOUND);

        setContext(PerformanceReviewTestFixtures.TENANT + 1,
                PerformanceReviewTestFixtures.SUBJECT);
        assertCode(() -> inTransaction(() -> service.review(
                        PerformanceReviewTestFixtures.REVIEW, PURPOSE, POPULATION)),
                ErrorCode.NOT_FOUND);
    }

    @Test
    void completedAndCancelledAssignmentsAreOpaqueBeforeCommandReceiptCreation() {
        DraftCommand draft = new DraftCommand(
                PerformanceReviewTestFixtures.completeAnswers());
        for (String state : List.of("COMPLETED", "CANCELLED")) {
            jdbc.update("""
                    UPDATE hris_performance.prf_review_assignments
                       SET review_state = ?, writable = FALSE
                     WHERE tenant_id = ? AND review_id = ?
                    """, state, PerformanceReviewTestFixtures.TENANT,
                    PerformanceReviewTestFixtures.REVIEW);

            assertCode(() -> inTransaction(() -> service.review(
                            PerformanceReviewTestFixtures.REVIEW, PURPOSE, POPULATION)),
                    ErrorCode.NOT_FOUND);
            assertCode(() -> inTransaction(() -> service.saveDraft(
                            PerformanceReviewTestFixtures.REVIEW, 0, SAVE_KEY,
                            DECISION_REVISION, PURPOSE, POPULATION, draft)),
                    ErrorCode.NOT_FOUND);
            assertThat(count("prf_command_receipts")).isZero();
        }
    }

    @Test
    void selfAssignmentCannotBeReadThroughTheAssignedReviewerPopulation() {
        jdbc.update("""
                UPDATE hris_performance.prf_review_assignments
                   SET reviewer_type = 'SELF', participant_id = ?,
                       reviewer_principal_public_id = ?
                 WHERE tenant_id = ? AND review_id = ?
                """, PerformanceReviewTestFixtures.SUBJECT,
                PerformanceReviewTestFixtures.SUBJECT,
                PerformanceReviewTestFixtures.TENANT,
                PerformanceReviewTestFixtures.REVIEW);

        assertCode(() -> inTransaction(() -> service.review(
                        PerformanceReviewTestFixtures.REVIEW, PURPOSE, POPULATION)),
                ErrorCode.NOT_FOUND);
    }

    private void seedAssignment() {
        PerformanceReviewDtos.AssignedReview seed = PerformanceReviewTestFixtures.review(
                canonicalizer, 0, ReviewState.NOT_STARTED, true, List.of());
        transactions.executeWithoutResult(status -> {
            repository.bindTenant(PerformanceReviewTestFixtures.TENANT);
            jdbc.update("""
                    INSERT INTO hris_performance.prf_review_assignments (
                        tenant_id, review_id, participant_id,
                        reviewer_assignment_id, reviewer_principal_public_id,
                        organization_public_id, reviewer_type, stage_key,
                        review_state, template_version_id, template_content_hash,
                        aggregate_version, writable, draft_content_hash,
                        form_definition, draft_answers, due_at, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?)
                    """,
                    PerformanceReviewTestFixtures.TENANT, seed.reviewId(),
                    seed.participantId(), seed.reviewerAssignmentId(),
                    PerformanceReviewTestFixtures.SUBJECT,
                    PerformanceReviewTestFixtures.ORGANIZATION,
                    seed.reviewerType().name(), seed.stageKey(), seed.state().name(),
                    seed.templateVersionId(), seed.templateContentHash(),
                    seed.aggregateVersion(), seed.writable(), seed.draftContentHash(),
                    json(seed.form()), json(seed.answers()), Timestamp.from(seed.dueAt()),
                    PerformanceReviewTestFixtures.ACTOR,
                    PerformanceReviewTestFixtures.ACTOR);
        });
    }

    private PerformanceReviewAuthorityPort.AuthorityEvidence authorize(
            PerformanceReviewAuthorityPort.AuthorityRequest request) {
        String route = switch (request.operation()) {
            case PerformanceReviewService.GET_OPERATION ->
                    HcmPerformanceReviewAuthorityAdapter.GET_ROUTE;
            case PerformanceReviewService.SAVE_OPERATION ->
                    HcmPerformanceReviewAuthorityAdapter.PATCH_ROUTE;
            case PerformanceReviewService.SUBMIT_OPERATION ->
                    HcmPerformanceReviewAuthorityAdapter.SUBMIT_ROUTE;
            default -> throw new IllegalArgumentException(request.operation());
        };
        String projection = request.operation().equals(PerformanceReviewService.GET_OPERATION)
                ? route + ".assigned-reviewer.projection.v1" : null;
        return new PerformanceReviewAuthorityPort.AuthorityEvidence(
                request.tenantId(), request.actorId(), request.subjectPrincipalPublicId(),
                request.reviewId(), request.operation(), request.purposeCode(),
                request.populationKind(), "assigned-reviewer", route, projection,
                "b".repeat(64), 17L, 23L);
    }

    private void setContext(long tenantId, java.util.UUID subject) {
        PeopleRequestContext.set(
                PerformanceReviewTestFixtures.ACTOR, tenantId, subject,
                Set.of("EMPLOYEE"), Set.of(
                        HcmPerformanceReviewAuthorityAdapter.PRODUCT_PERMISSION,
                        HcmPerformanceReviewAuthorityAdapter.RESOURCE_PERMISSION));
    }

    private void installFailingResultTrigger() {
        jdbc.execute("""
                CREATE FUNCTION hris_performance.prf_review_test_fail_result()
                RETURNS TRIGGER AS $$
                BEGIN
                    RAISE EXCEPTION 'TEST_LATE_RESULT_FAILURE';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_prf_review_test_fail_result
                BEFORE INSERT ON hris_performance.prf_review_command_results
                FOR EACH ROW EXECUTE FUNCTION
                    hris_performance.prf_review_test_fail_result()
                """);
    }

    private void removeFailingResultTrigger() {
        jdbc.execute("""
                DROP TRIGGER trg_prf_review_test_fail_result
                ON hris_performance.prf_review_command_results
                """);
        jdbc.execute("DROP FUNCTION hris_performance.prf_review_test_fail_result()");
    }

    private int count(String table) {
        return inTransaction(() -> {
            repository.bindTenant(PerformanceReviewTestFixtures.TENANT);
            Integer value = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM hris_performance." + table,
                    Integer.class);
            return value == null ? 0 : value;
        });
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    private void assertCode(Supplier<?> action, ErrorCode code) {
        assertThatThrownBy(action::get)
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(code);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static DataSource dataSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }
}
