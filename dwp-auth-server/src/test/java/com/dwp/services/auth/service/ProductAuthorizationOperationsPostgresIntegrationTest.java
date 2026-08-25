package com.dwp.services.auth.service;

import com.dwp.core.exception.BaseException;
import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

@Testcontainers(disabledWithoutDocker = true)
class ProductAuthorizationOperationsPostgresIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transaction;
    private static ProductAuthorizationContractService service;

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
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ProductAuthorizationContractRepository repository =
                new ProductAuthorizationContractRepository(
                        jdbc, new ObjectMapper().findAndRegisterModules());
        service = new ProductAuthorizationContractService(
                repository, mock(ProductAuthorizationContractValidator.class));
    }

    @Test
    void approveActivateRollbackAndReactivatePreserveExactImmutableLineage() {
        String key = "ops-lifecycle";
        String checksum1 = "1".repeat(64);
        String checksum2 = "2".repeat(64);
        UUID bundle1 = draft(key, 1, checksum1);
        UUID bundle2 = draft(key, 2, checksum2);

        inTransaction(() -> service.approveGoverned(
                key, 1, checksum1, "maker-v1", "checker-v1", "CHG-LIFE-1"));
        inTransaction(() -> service.approveGoverned(
                key, 2, checksum2, "maker-v2", "checker-v2", "CHG-LIFE-2"));
        assertThat(inTransaction(() -> service.activateGoverned(
                key, 1, checksum1, "release-v1", 0, "CHG-LIFE-1")).revision())
                .isEqualTo(1);
        assertThat(inTransaction(() -> service.activateGoverned(
                key, 2, checksum2, "release-v2", 1, "CHG-LIFE-2")).revision())
                .isEqualTo(2);
        assertThat(inTransaction(() -> service.rollbackGoverned(
                key, 1, checksum1, "incident-operator", 2, "INC-LIFE-1",
                "Rollback the failed second authorization release.")).revision())
                .isEqualTo(3);
        assertThat(inTransaction(() -> service.activateGoverned(
                key, 2, checksum2, "release-v2-retry", 3, "CHG-LIFE-2")).revision())
                .isEqualTo(4);

        ProductAuthorizationContractDtos.BundleView active =
                inTransaction(() -> service.active(key));
        assertThat(active.bundleId()).isEqualTo(bundle2);
        assertThat(active.version()).isEqualTo(2);
        assertThat(active.bundleStatus()).isEqualTo("ACTIVE");
        assertThat(active.activeRevision()).isEqualTo(4);
        assertThat(status(bundle1)).isEqualTo("APPROVED");
        assertThat(status(bundle2)).isEqualTo("ACTIVE");
        assertThat(count("auth_product_authorization_activation_event", key)).isEqualTo(4);
        assertThat(count("auth_product_authorization_governance_event", key)).isEqualTo(6);
        assertThat(jdbc.query("""
                SELECT resulting_revision
                  FROM auth_product_authorization_governance_event
                 WHERE bundle_id = ? AND operation = 'ACTIVATE'
                 ORDER BY resulting_revision
                """, (result, ignored) -> result.getLong(1), bundle2))
                .containsExactly(2L, 4L);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE auth_product_authorization_governance_event
                   SET change_ref = 'CHG-TAMPER'
                 WHERE bundle_key = ? AND operation = 'ACTIVATE'
                """, key)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM auth_product_authorization_governance_event
                 WHERE bundle_key = ? AND operation = 'ROLLBACK'
                """, key)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO auth_product_authorization_governance_event (
                    bundle_key, bundle_id, version, checksum, operation,
                    expected_revision, resulting_revision, requester_ref,
                    decision_actor_ref, change_ref, caller_service_identity)
                VALUES (?, ?, 2, ?, 'ACTIVATE', 98, 99,
                        'maker-v2', 'forged-release', 'CHG-LIFE-2',
                        'dwp-platform-server')
                """, key, bundle2, checksum2)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO auth_product_authorization_governance_event (
                    bundle_key, bundle_id, version, checksum, operation,
                    expected_revision, resulting_revision, requester_ref,
                    decision_actor_ref, change_ref, caller_service_identity)
                VALUES (?, ?, 2, ?, 'ACTIVATE', 4, NULL,
                        'maker-v2', 'forged-release', 'CHG-LIFE-2',
                        'dwp-platform-server')
                """, key, bundle2, checksum2)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void staleConcurrentCasHasExactlyOneWinner() throws Exception {
        String key = "ops-concurrent-cas";
        String checksum = "3".repeat(64);
        UUID bundle = draft(key, 1, checksum);
        inTransaction(() -> service.approveGoverned(
                key, 1, checksum, "cas-maker", "cas-checker", "CHG-CAS-1"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> first = activationAttempt(
                ready, start, key, checksum, "cas-release-a");
        Callable<Boolean> second = activationAttempt(
                ready, start, key, checksum, "cas-release-b");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = List.of(executor.submit(first), executor.submit(second));
            ready.await();
            start.countDown();
            assertThat(List.of(results.get(0).get(), results.get(1).get()))
                    .containsExactlyInAnyOrder(true, false);
        }

        ProductAuthorizationContractDtos.BundleView active =
                inTransaction(() -> service.active(key));
        assertThat(active.bundleId()).isEqualTo(bundle);
        assertThat(active.activeRevision()).isEqualTo(1);
        assertThat(count("auth_product_authorization_activation_event", key)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_product_authorization_governance_event
                 WHERE bundle_key = ? AND operation = 'ACTIVATE'
                """, Integer.class, key)).isEqualTo(1);
    }

    @Test
    void governanceFailureRollsBackPointerStatusAndActivationLineageTogether() {
        String key = "ops-atomic-audit";
        String checksum = "4".repeat(64);
        UUID bundle = draft(key, 1, checksum);
        inTransaction(() -> service.approveGoverned(
                key, 1, checksum, "atomic-maker", "atomic-checker", "CHG-FAIL"));
        jdbc.execute("""
                CREATE FUNCTION dwp_test_reject_governed_activation()
                RETURNS TRIGGER AS $$
                BEGIN
                    IF NEW.operation = 'ACTIVATE' AND NEW.change_ref = 'CHG-FAIL' THEN
                        RAISE EXCEPTION 'forced governance audit failure';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_test_reject_governed_activation
                    BEFORE INSERT ON auth_product_authorization_governance_event
                    FOR EACH ROW EXECUTE FUNCTION dwp_test_reject_governed_activation()
                """);
        try {
            assertThatThrownBy(() -> inTransaction(() -> service.activateGoverned(
                    key, 1, checksum, "atomic-release", 0, "CHG-FAIL")))
                    .isInstanceOf(DataAccessException.class);

            assertThat(status(bundle)).isEqualTo("APPROVED");
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM auth_product_authorization_active
                     WHERE bundle_key = ?
                    """, Integer.class, key)).isZero();
            assertThat(count("auth_product_authorization_activation_event", key)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM auth_product_authorization_governance_event
                     WHERE bundle_key = ? AND operation = 'APPROVE'
                    """, Integer.class, key)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM auth_product_authorization_governance_event
                     WHERE bundle_key = ? AND operation = 'ACTIVATE'
                    """, Integer.class, key)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER trg_test_reject_governed_activation "
                    + "ON auth_product_authorization_governance_event");
            jdbc.execute("DROP FUNCTION dwp_test_reject_governed_activation()");
        }
    }

    @Test
    void draftAndLegacyApprovalNeverPassGovernedReleasePreflight() {
        String draftKey = "ops-draft-preflight";
        String legacyKey = "ops-legacy-preflight";
        draft(draftKey, 1, "5".repeat(64));
        legacyApproved(legacyKey, 1, "6".repeat(64));

        assertThatThrownBy(() -> inTransaction(() ->
                service.governedReleaseVersion(draftKey, 1)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("approval evidence");
        assertThatThrownBy(() -> inTransaction(() ->
                service.governedReleaseVersion(legacyKey, 1)))
                .isInstanceOf(BaseException.class)
                .hasMessageContaining("governed approval evidence");
    }

    @Test
    void databaseGuardRejectsReleaseEvidenceDetachedFromProviderApproval() {
        String key = "ops-db-evidence";
        String checksum = "7".repeat(64);
        UUID bundle = draft(key, 1, checksum);
        inTransaction(() -> service.approveGoverned(
                key, 1, checksum, "db-maker", "db-checker", "CHG-DB-1"));
        prepareManualActivationLineage(key, bundle);

        assertDatabaseFailure(() -> governanceActivation(
                        key, bundle, checksum, "different-maker", "db-release", "CHG-DB-1"),
                "must reuse the provider approval requester");
        assertDatabaseFailure(() -> governanceActivation(
                        key, bundle, checksum, "db-maker", "db-checker", "CHG-DB-1"),
                "requester, approver and release actor separation");
        assertDatabaseFailure(() -> governanceActivation(
                        key, bundle, checksum, "db-maker", "db-release", "CHG-WRONG"),
                "must reuse the provider approval change reference");
        assertThat(governanceActivation(
                key, bundle, checksum, "db-maker", "db-release", "CHG-DB-1"))
                .isEqualTo(1);

        String legacyKey = "ops-db-no-approval";
        String legacyChecksum = "8".repeat(64);
        UUID legacyBundle = legacyApproved(legacyKey, 1, legacyChecksum);
        prepareManualActivationLineage(legacyKey, legacyBundle);
        assertDatabaseFailure(() -> governanceActivation(
                        legacyKey, legacyBundle, legacyChecksum,
                        "legacy-maker", "legacy-release", "CHG-LEGACY"),
                "requires exact provider approval evidence");
    }

    private Callable<Boolean> activationAttempt(
            CountDownLatch ready,
            CountDownLatch start,
            String key,
            String checksum,
            String actor) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                inTransaction(() -> service.activateGoverned(
                        key, 1, checksum, actor, 0, "CHG-CAS-1"));
                return true;
            } catch (BaseException expectedStaleCas) {
                return false;
            }
        };
    }

    private UUID draft(String key, long version, String checksum) {
        return jdbc.queryForObject("""
                INSERT INTO auth_product_authorization_bundle (
                    bundle_key, version, bundle_status, schema_version,
                    checksum_algorithm, checksum, owner)
                VALUES (?, ?, 'DRAFT', 1, 'SHA-256', ?, 'Identity + Security')
                RETURNING bundle_id
                """, UUID.class, key, version, checksum);
    }

    private UUID legacyApproved(String key, long version, String checksum) {
        return jdbc.queryForObject("""
                INSERT INTO auth_product_authorization_bundle (
                    bundle_key, version, bundle_status, schema_version,
                    checksum_algorithm, checksum, owner, approved_by, approved_at)
                VALUES (?, ?, 'APPROVED', 1, 'SHA-256', ?, 'Identity + Security',
                        'legacy-checker', CURRENT_TIMESTAMP)
                RETURNING bundle_id
                """, UUID.class, key, version, checksum);
    }

    private void prepareManualActivationLineage(String key, UUID bundle) {
        jdbc.update("""
                UPDATE auth_product_authorization_bundle
                   SET bundle_status = 'ACTIVE', activated_at = CURRENT_TIMESTAMP
                 WHERE bundle_id = ?
                """, bundle);
        jdbc.update("""
                INSERT INTO auth_product_authorization_activation_event (
                    bundle_key, from_bundle_id, to_bundle_id, operation,
                    expected_revision, resulting_revision, actor_ref)
                VALUES (?, NULL, ?, 'ACTIVATE', 0, 1, 'db-lineage-writer')
                """, key, bundle);
    }

    private int governanceActivation(
            String key,
            UUID bundle,
            String checksum,
            String requester,
            String actor,
            String changeRef) {
        return jdbc.update("""
                INSERT INTO auth_product_authorization_governance_event (
                    bundle_key, bundle_id, version, checksum, operation,
                    expected_revision, resulting_revision, requester_ref,
                    decision_actor_ref, change_ref, caller_service_identity)
                VALUES (?, ?, 1, ?, 'ACTIVATE', 0, 1, ?, ?, ?,
                        'dwp-platform-server')
                """, key, bundle, checksum, requester, actor, changeRef);
    }

    private void assertDatabaseFailure(Runnable operation, String expectedMessage) {
        Throwable failure = catchThrowable(operation::run);
        assertThat(failure).isInstanceOf(DataAccessException.class);
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        assertThat(root.getMessage()).contains(expectedMessage);
    }

    private String status(UUID bundleId) {
        return jdbc.queryForObject("""
                SELECT bundle_status FROM auth_product_authorization_bundle
                 WHERE bundle_id = ?
                """, String.class, bundleId);
    }

    private int count(String table, String key) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE bundle_key = ?",
                Integer.class,
                key);
    }

    private <T> T inTransaction(Supplier<T> operation) {
        return transaction.execute(ignored -> operation.get());
    }
}
