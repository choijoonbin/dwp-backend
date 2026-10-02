package com.dwp.migration.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.dwp.core.database.MigrationAdoptionGuard;
import com.dwp.core.database.MigrationAdoptionGuard.HistoryDigest;
import com.dwp.core.database.MigrationAdoptionGuard.Receipt;
import com.dwp.core.database.MigrationAdoptedPredecessorControllerVerifier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Targeted ACTIVE adoption fence regression; not full Auth Flyway/issuer acceptance. */
@Testcontainers(disabledWithoutDocker = true)
class MigrationControlAdoptionActiveFenceV1PostgresTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            System.getenv().getOrDefault("DWP_CONTROL_POSTGRES_TEST_IMAGE", "postgres:16-alpine"))
            .withDatabaseName("adoption_active_bootstrap")
            .withUsername("adoption_active_admin")
            .withPassword("owned-test-bootstrap-only");
    private AdoptionActiveFenceFixtureV1 fixture;

    @BeforeEach
    void resetOwnedFixture() { fixture = new AdoptionActiveFenceFixtureV1(POSTGRES); }

    @Test
    void existingV2PredecessorPassesReadOnlyPreflightWhileBaselineRuntimeCanConnect() throws Exception {
        fixture.provision(true);
        String snapshot = fixture.predecessorSnapshot();
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            ControlPreflight.verify(bootstrap, fixture.environment());
            assertEquals(1, AdoptionSealer.legacyBoundary(bootstrap, fixture.stream(), fixture.environment()));
            DatabaseConnectionFence.requireDatabaseAcl(bootstrap, fixture.environment(), DatabaseConnectionFence.State.BASELINE);
            DatabaseConnectionFence.requireNoForeignSessions(bootstrap, fixture.environment());
        }
        assertEquals(snapshot, fixture.predecessorSnapshot());
    }

    @Test
    void initialAdoptionWithoutReceiptReadsLegacyBoundaryUnderActiveWithoutRuntimeProof() throws Exception {
        fixture.provision(false);
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            ControlPreflight.verify(bootstrap, fixture.environment());
            ControlCredentials credentials = ServiceConnectionControl.activateExclusiveControlFence(bootstrap, fixture.environment());
            try {
                ControlEnvironment active = fixture.environment().withControlCredentials(credentials);
                assertEquals(1, AdoptionSealer.legacyBoundary(bootstrap, fixture.stream(), active));
                SQLException denied = assertThrows(SQLException.class, () -> fixture.runtime(credentials.runtimePassword()));
                assertEquals("42501", denied.getSQLState());
                DatabaseConnectionFence.requireDatabaseAcl(bootstrap, active, DatabaseConnectionFence.State.ACTIVE);
                DatabaseConnectionFence.requireNoForeignSessions(bootstrap, active);
            } finally {
                ServiceConnectionControl.restoreServiceConnections(bootstrap, fixture.environment(), credentials);
            }
        }
    }

    @Test
    void existingReceiptRowRemainsReadableByBootstrapWhileRuntimeConnectIsActivelyDenied() throws Exception {
        fixture.provision(true);
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            ControlPreflight.verify(bootstrap, fixture.environment());
            ControlCredentials credentials = ServiceConnectionControl.activateExclusiveControlFence(bootstrap, fixture.environment());
            try {
                assertEquals(1L, DatabaseControl.scalarLong(bootstrap,
                        "SELECT legacy_max_installed_rank FROM dwp_migration_control.adoption_receipt WHERE stream_key='auth-main'"));
                SQLException denied = assertThrows(SQLException.class, () -> fixture.runtime(credentials.runtimePassword()));
                assertEquals("42501", denied.getSQLState());
                DatabaseConnectionFence.requireDatabaseAcl(bootstrap, fixture.environment(), DatabaseConnectionFence.State.ACTIVE);
                DatabaseConnectionFence.requireNoForeignSessions(bootstrap, fixture.environment());
            } finally {
                ServiceConnectionControl.restoreServiceConnections(bootstrap, fixture.environment(), credentials);
            }
        }
    }

    @Test
    void immutableProofContainsNoCredentialConnectionOrDataSourceAndIsSingleUse() throws Exception {
        assertEquals(
                List.of("streamKey", "mode", "legacyMaxInstalledRank", "receipt", "initialHistory"),
                Arrays.stream(AdoptionSealer.PreparedLegacyBoundary.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertFalse(Arrays.stream(AdoptionSealer.PreparedLegacyBoundary.class.getRecordComponents())
                .map(component -> component.getType())
                .anyMatch(type -> Connection.class.isAssignableFrom(type)
                        || javax.sql.DataSource.class.isAssignableFrom(type)
                        || ControlEnvironment.class.isAssignableFrom(type)));

        fixture.provision(true);
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            ControlPreflight.verify(bootstrap, fixture.environment());
            AdoptionSealer.PreparedLegacyBoundaries prepared =
                    AdoptionSealer.prepareLegacyBoundaries(bootstrap, fixture.environment());
            ControlCredentials credentials = ServiceConnectionControl.activateExclusiveControlFence(
                    bootstrap, fixture.environment());
            try {
                ControlEnvironment active = fixture.environment().withControlCredentials(credentials);
                assertEquals(List.of(1), consumeInTransaction(bootstrap, active, prepared));
                IllegalStateException reused = assertThrows(
                        IllegalStateException.class,
                        () -> consumeInTransaction(bootstrap, active, prepared));
                assertTrue(reused.getMessage().contains("already consumed"));
                DatabaseConnectionFence.requireDatabaseAcl(
                        bootstrap, active, DatabaseConnectionFence.State.ACTIVE);
                DatabaseConnectionFence.requireNoForeignSessions(bootstrap, active);
            } finally {
                ServiceConnectionControl.restoreServiceConnections(
                        bootstrap, fixture.environment(), credentials);
            }
        }
    }

    @Test
    void activeControllerRejectsReceiptMutationAfterBaselineProof() throws Exception {
        fixture.provision(true);
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            ControlPreflight.verify(bootstrap, fixture.environment());
            AdoptionSealer.PreparedLegacyBoundaries prepared =
                    AdoptionSealer.prepareLegacyBoundaries(bootstrap, fixture.environment());
            ControlCredentials credentials = ServiceConnectionControl.activateExclusiveControlFence(
                    bootstrap, fixture.environment());
            try {
                ControlEnvironment active = fixture.environment().withControlCredentials(credentials);
                AdoptionActiveFenceFixtureV1.sql(bootstrap,
                        "UPDATE dwp_migration_control.adoption_receipt SET control_reference='"
                                + AdoptionActiveFenceFixtureV1.CURRENT_REFERENCE
                                + "' WHERE stream_key='auth-main'");
                assertThrows(
                        IllegalStateException.class,
                        () -> consumeInTransaction(bootstrap, active, prepared));
            } finally {
                ServiceConnectionControl.restoreServiceConnections(
                        bootstrap, fixture.environment(), credentials);
            }
        }
    }

    @Test
    void activeControllerRejectsReceiptDigestMutationAfterBaselineProof() throws Exception {
        fixture.provision(true);
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            ControlPreflight.verify(bootstrap, fixture.environment());
            AdoptionSealer.PreparedLegacyBoundaries prepared =
                    AdoptionSealer.prepareLegacyBoundaries(bootstrap, fixture.environment());
            ControlCredentials credentials = ServiceConnectionControl.activateExclusiveControlFence(
                    bootstrap, fixture.environment());
            try {
                ControlEnvironment active = fixture.environment().withControlCredentials(credentials);
                AdoptionActiveFenceFixtureV1.sql(bootstrap,
                        "UPDATE dwp_migration_control.adoption_receipt SET receipt_sha256='"
                                + "c".repeat(64) + "' WHERE stream_key='auth-main'");
                assertThrows(
                        IllegalStateException.class,
                        () -> consumeInTransaction(bootstrap, active, prepared));
            } finally {
                ServiceConnectionControl.restoreServiceConnections(
                        bootstrap, fixture.environment(), credentials);
            }
        }
    }

    @Test
    void activeControllerRejectsHistoryMutationAfterBaselineProof() throws Exception {
        fixture.provision(true);
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            ControlPreflight.verify(bootstrap, fixture.environment());
            AdoptionSealer.PreparedLegacyBoundaries prepared =
                    AdoptionSealer.prepareLegacyBoundaries(bootstrap, fixture.environment());
            ControlCredentials credentials = ServiceConnectionControl.activateExclusiveControlFence(
                    bootstrap, fixture.environment());
            try {
                ControlEnvironment active = fixture.environment().withControlCredentials(credentials);
                AdoptionActiveFenceFixtureV1.sql(bootstrap,
                        "UPDATE public.flyway_schema_history SET checksum=999 "
                                + "WHERE installed_rank=1");
                assertThrows(
                        IllegalStateException.class,
                        () -> consumeInTransaction(bootstrap, active, prepared));
            } finally {
                ServiceConnectionControl.restoreServiceConnections(
                        bootstrap, fixture.environment(), credentials);
            }
        }
    }

    @Test
    void activeControllerRejectsRuntimeControlMetadataPrivilegeDrift() throws Exception {
        fixture.provision(true);
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            ControlPreflight.verify(bootstrap, fixture.environment());
            AdoptionSealer.PreparedLegacyBoundaries prepared =
                    AdoptionSealer.prepareLegacyBoundaries(bootstrap, fixture.environment());
            ControlCredentials credentials = ServiceConnectionControl.activateExclusiveControlFence(
                    bootstrap, fixture.environment());
            try {
                ControlEnvironment active = fixture.environment().withControlCredentials(credentials);
                AdoptionActiveFenceFixtureV1.sql(bootstrap,
                        "GRANT SELECT ON dwp_migration_control.adoption_receipt TO "
                                + AdoptionActiveFenceFixtureV1.RUNTIME);
                assertThrows(
                        IllegalStateException.class,
                        () -> consumeInTransaction(bootstrap, active, prepared));
            } finally {
                ServiceConnectionControl.restoreServiceConnections(
                        bootstrap, fixture.environment(), credentials);
            }
        }
    }

    @Test
    void activeControllerRejectsLiveInventoryMutationAfterBaselineProof() throws Exception {
        fixture.provision(true);
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            ControlPreflight.verify(bootstrap, fixture.environment());
            AdoptionSealer.PreparedLegacyBoundaries prepared =
                    AdoptionSealer.prepareLegacyBoundaries(bootstrap, fixture.environment());
            ControlCredentials credentials = ServiceConnectionControl.activateExclusiveControlFence(
                    bootstrap, fixture.environment());
            try {
                ControlEnvironment active = fixture.environment().withControlCredentials(credentials);
                AdoptionActiveFenceFixtureV1.sql(bootstrap,
                        "ALTER TABLE public.adoption_domain_record "
                                + "RENAME TO adoption_domain_record_changed");
                assertThrows(
                        IllegalStateException.class,
                        () -> consumeInTransaction(bootstrap, active, prepared));
            } finally {
                ServiceConnectionControl.restoreServiceConnections(
                        bootstrap, fixture.environment(), credentials);
            }
        }
    }

    @Test
    void controllerObservationRejectsMissingAndWrongControllerIdentityUnderActiveFence()
            throws Exception {
        fixture.provision(true);
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            ControlPreflight.verify(bootstrap, fixture.environment());
            AdoptionSealer.prepareLegacyBoundaries(bootstrap, fixture.environment());
            ControlCredentials credentials = ServiceConnectionControl.activateExclusiveControlFence(
                    bootstrap, fixture.environment());
            try {
                ControlEnvironment active = fixture.environment().withControlCredentials(credentials);
                Receipt expected = fixture.receipt();
                assertThrows(
                        IllegalArgumentException.class,
                        () -> MigrationAdoptedPredecessorControllerVerifier.verify(
                                ControlContracts.adoption(fixture.stream()),
                                bootstrap,
                                null,
                                active.migrationPrincipal(),
                                active.runtimePrincipal(),
                                expected));
                IllegalStateException wrong = assertThrows(
                        IllegalStateException.class,
                        () -> MigrationAdoptedPredecessorControllerVerifier.verify(
                                ControlContracts.adoption(fixture.stream()),
                                bootstrap,
                                "wrong_controller",
                                active.migrationPrincipal(),
                                active.runtimePrincipal(),
                                expected));
                assertTrue(wrong.getMessage().contains("identity"));
                SQLException denied = assertThrows(
                        SQLException.class,
                        () -> fixture.runtime(credentials.runtimePassword()));
                assertEquals("42501", denied.getSQLState());
                DatabaseConnectionFence.requireDatabaseAcl(
                        bootstrap, active, DatabaseConnectionFence.State.ACTIVE);
            } finally {
                ServiceConnectionControl.restoreServiceConnections(
                        bootstrap, fixture.environment(), credentials);
            }
        }
    }

    @Test
    void initialProofRevalidatesExactHistoryAndRejectsReuse() throws Exception {
        fixture.provision(false);
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            ControlPreflight.verify(bootstrap, fixture.environment());
            AdoptionSealer.PreparedLegacyBoundaries prepared =
                    AdoptionSealer.prepareLegacyBoundaries(bootstrap, fixture.environment());
            ControlCredentials credentials = ServiceConnectionControl.activateExclusiveControlFence(
                    bootstrap, fixture.environment());
            try {
                ControlEnvironment active = fixture.environment().withControlCredentials(credentials);
                assertEquals(List.of(1), consumeInTransaction(bootstrap, active, prepared));
                assertThrows(
                        IllegalStateException.class,
                        () -> consumeInTransaction(bootstrap, active, prepared));
            } finally {
                ServiceConnectionControl.restoreServiceConnections(
                        bootstrap, fixture.environment(), credentials);
            }
        }
    }

    @Test
    void initialProofRejectsHistoryMutationAfterBaseline() throws Exception {
        fixture.provision(false);
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            ControlPreflight.verify(bootstrap, fixture.environment());
            AdoptionSealer.PreparedLegacyBoundaries prepared =
                    AdoptionSealer.prepareLegacyBoundaries(bootstrap, fixture.environment());
            ControlCredentials credentials = ServiceConnectionControl.activateExclusiveControlFence(
                    bootstrap, fixture.environment());
            try {
                ControlEnvironment active = fixture.environment().withControlCredentials(credentials);
                AdoptionActiveFenceFixtureV1.sql(bootstrap,
                        "UPDATE public.flyway_schema_history SET checksum=999 "
                                + "WHERE installed_rank=1");
                assertThrows(
                        IllegalStateException.class,
                        () -> consumeInTransaction(bootstrap, active, prepared));
            } finally {
                ServiceConnectionControl.restoreServiceConnections(
                        bootstrap, fixture.environment(), credentials);
            }
        }
    }

    @Test
    void initialProofRejectsUnsuccessfulOrEmptyHistoryAtBaseline() throws Exception {
        fixture.provision(false);
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            AdoptionActiveFenceFixtureV1.sql(
                    bootstrap, "UPDATE public.flyway_schema_history SET success=false");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AdoptionSealer.prepareLegacyBoundaries(
                            bootstrap, fixture.environment()));
        }

        fixture.provision(false);
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            AdoptionActiveFenceFixtureV1.sql(
                    bootstrap, "TRUNCATE TABLE public.flyway_schema_history");
            IllegalStateException empty = assertThrows(
                    IllegalStateException.class,
                    () -> AdoptionSealer.prepareLegacyBoundaries(
                            bootstrap, fixture.environment()));
            assertTrue(empty.getMessage().contains("existing successful"));
        }
    }

    @Test
    void existingProofRejectsUnsuccessfulHistoryAtBaseline() throws Exception {
        fixture.provision(true);
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            AdoptionActiveFenceFixtureV1.sql(
                    bootstrap, "UPDATE public.flyway_schema_history SET success=false");
            assertThrows(
                    IllegalStateException.class,
                    () -> AdoptionSealer.prepareLegacyBoundaries(
                            bootstrap, fixture.environment()));
        }
    }

    @Test
    void missingProofCountFailsBeforeAnyAdoptionMutation() throws Exception {
        fixture.provision(false);
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            ControlPreflight.verify(bootstrap, fixture.environment());
            AdoptionSealer.PreparedLegacyBoundaries missing =
                    new AdoptionSealer.PreparedLegacyBoundaries(List.of());
            ControlCredentials credentials = ServiceConnectionControl.activateExclusiveControlFence(
                    bootstrap, fixture.environment());
            try {
                ControlEnvironment active = fixture.environment().withControlCredentials(credentials);
                IllegalStateException rejected = assertThrows(
                        IllegalStateException.class,
                        () -> consumeInTransaction(bootstrap, active, missing));
                assertTrue(rejected.getMessage().contains("count"));
                assertEquals("1:101:legacy_operator:true:NO_RECEIPT", fixture.predecessorSnapshot());
            } finally {
                ServiceConnectionControl.restoreServiceConnections(
                        bootstrap, fixture.environment(), credentials);
            }
        }
    }

    @Test
    void wrongProofStreamKeyFailsBeforeAnyAdoptionMutation() throws Exception {
        fixture.provision(false);
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            ControlPreflight.verify(bootstrap, fixture.environment());
            int max = DatabaseControl.historyMax(bootstrap, fixture.stream());
            var history = com.dwp.core.database.MigrationAdoptionGuard.digestHistory(
                    bootstrap,
                    ControlContracts.adoption(fixture.stream()),
                    max,
                    max,
                    fixture.environment().migrationPrincipal());
            AdoptionSealer.PreparedLegacyBoundaries wrong =
                    new AdoptionSealer.PreparedLegacyBoundaries(List.of(
                            new AdoptionSealer.PreparedLegacyBoundary(
                                    "wrong-main",
                                    AdoptionSealer.PredecessorMode.INITIAL,
                                    max,
                                    null,
                                    history)));
            ControlCredentials credentials = ServiceConnectionControl.activateExclusiveControlFence(
                    bootstrap, fixture.environment());
            try {
                ControlEnvironment active = fixture.environment().withControlCredentials(credentials);
                IllegalStateException rejected = assertThrows(
                        IllegalStateException.class,
                        () -> consumeInTransaction(bootstrap, active, wrong));
                assertTrue(rejected.getMessage().contains("order or mode"));
                assertEquals("1:101:legacy_operator:true:NO_RECEIPT", fixture.predecessorSnapshot());
            } finally {
                ServiceConnectionControl.restoreServiceConnections(
                        bootstrap, fixture.environment(), credentials);
            }
        }
    }

    @Test
    void swappedAndDuplicateProofOrderFailBeforeDatabaseObservation() throws Exception {
        fixture.provision(false);
        ControlEnvironment people = withoutPreviousState(
                fixture.environment(), ControlPlan.forService("people"));
        HistoryDigest history = new HistoryDigest(
                1, 1, "d".repeat(64), true, true, true);
        AdoptionSealer.PreparedLegacyBoundary main =
                new AdoptionSealer.PreparedLegacyBoundary(
                        "people-main", AdoptionSealer.PredecessorMode.INITIAL,
                        1, null, history);
        AdoptionSealer.PreparedLegacyBoundary performance =
                new AdoptionSealer.PreparedLegacyBoundary(
                        "people-performance", AdoptionSealer.PredecessorMode.INITIAL,
                        1, null, history);

        IllegalStateException swapped = assertThrows(
                IllegalStateException.class,
                () -> new AdoptionSealer.PreparedLegacyBoundaries(
                        List.of(performance, main)).consume(null, people));
        assertTrue(swapped.getMessage().contains("order or mode"));
        IllegalStateException duplicate = assertThrows(
                IllegalStateException.class,
                () -> new AdoptionSealer.PreparedLegacyBoundaries(
                        List.of(main, main)).consume(null, people));
        assertTrue(duplicate.getMessage().contains("order or mode"));
    }

    @Test
    void existingAndInitialProofModesCannotBeMixed() throws Exception {
        fixture.provision(true);
        ControlEnvironment initial = withoutPreviousState(
                fixture.environment(), fixture.environment().plan());
        Receipt receipt = fixture.receipt();
        AdoptionSealer.PreparedLegacyBoundary existing =
                new AdoptionSealer.PreparedLegacyBoundary(
                        receipt.streamKey(), AdoptionSealer.PredecessorMode.EXISTING,
                        receipt.legacyMaxInstalledRank(), receipt, null);
        IllegalStateException mixed = assertThrows(
                IllegalStateException.class,
                () -> new AdoptionSealer.PreparedLegacyBoundaries(
                        List.of(existing)).consume(null, initial));
        assertTrue(mixed.getMessage().contains("order or mode"));
    }

    @Test
    void existingProofHistoryWriterCannotCrossAtomicTransferBoundary() throws Exception {
        assertHistoryWriterCannotCrossAtomicTransferBoundary(true);
    }

    @Test
    void initialProofHistoryWriterCannotCrossAtomicTransferBoundary() throws Exception {
        assertHistoryWriterCannotCrossAtomicTransferBoundary(false);
    }

    private void assertHistoryWriterCannotCrossAtomicTransferBoundary(boolean existing)
            throws Exception {
        fixture.provision(existing);
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            ControlPreflight.verify(bootstrap, fixture.environment());
            AdoptionSealer.PreparedLegacyBoundaries prepared =
                    AdoptionSealer.prepareLegacyBoundaries(bootstrap, fixture.environment());
            ControlCredentials credentials = ServiceConnectionControl.activateExclusiveControlFence(
                    bootstrap, fixture.environment());
            ExecutorService writerExecutor = Executors.newSingleThreadExecutor();
            try {
                ControlEnvironment active = fixture.environment().withControlCredentials(credentials);
                bootstrap.setAutoCommit(false);
                try {
                    assertEquals(List.of(1), prepared.consume(bootstrap, active));
                    Future<Integer> writer = writerExecutor.submit(() -> {
                        try (Connection migration = fixture.migration(
                                credentials.migrationPassword())) {
                            AdoptionActiveFenceFixtureV1.sql(
                                    migration,
                                    "UPDATE public.flyway_schema_history SET checksum=202 "
                                            + "WHERE installed_rank=1");
                            return 1;
                        }
                    });
                    long waiting = 0L;
                    for (int attempt = 0; attempt < 200 && waiting == 0L; attempt++) {
                        AdoptionActiveFenceFixtureV1.sql(
                                bootstrap, "SELECT pg_catalog.pg_stat_clear_snapshot()");
                        waiting = DatabaseControl.scalarLong(bootstrap,
                                "SELECT COUNT(*) FROM pg_catalog.pg_stat_activity "
                                        + "WHERE datname=current_database() "
                                        + "AND usename='" + AdoptionActiveFenceFixtureV1.MIGRATION
                                        + "' AND wait_event_type='Lock' "
                                        + "AND query LIKE 'UPDATE public.flyway_schema_history%'");
                        if (waiting == 0L) {
                            Thread.sleep(25L);
                        }
                    }
                    assertEquals(1L, waiting);
                    assertFalse(writer.isDone());
                    ProtectedObjectOwnershipControl.transfer(bootstrap, active);
                    assertFalse(writer.isDone());
                    bootstrap.commit();
                    assertEquals(1, writer.get(5, TimeUnit.SECONDS));
                } catch (Exception exception) {
                    bootstrap.rollback();
                    throw exception;
                } finally {
                    bootstrap.setAutoCommit(true);
                }
                assertEquals(202L, DatabaseControl.scalarLong(
                        bootstrap,
                        "SELECT checksum FROM public.flyway_schema_history "
                                + "WHERE installed_rank=1"));
                DatabaseConnectionFence.requireNoForeignSessions(bootstrap, active);
            } finally {
                writerExecutor.shutdownNow();
                writerExecutor.awaitTermination(5, TimeUnit.SECONDS);
                ServiceConnectionControl.restoreServiceConnections(
                        bootstrap, fixture.environment(), credentials);
            }
        }
    }

    private static List<Integer> consumeInTransaction(
            Connection bootstrap,
            ControlEnvironment environment,
            AdoptionSealer.PreparedLegacyBoundaries prepared) throws Exception {
        bootstrap.setAutoCommit(false);
        try {
            List<Integer> boundaries = prepared.consume(bootstrap, environment);
            bootstrap.commit();
            return boundaries;
        } catch (Exception exception) {
            bootstrap.rollback();
            throw exception;
        } finally {
            bootstrap.setAutoCommit(true);
        }
    }

    private static ControlEnvironment withoutPreviousState(
            ControlEnvironment source, ControlPlan plan) {
        return new ControlEnvironment(
                ControlEnvironment.Mode.ADOPT_OR_UPGRADE,
                plan,
                source.jdbcUrl(),
                source.database(),
                source.bootstrapPrincipal(),
                source.bootstrapPassword(),
                source.migrationPrincipal(),
                source.migrationPassword(),
                source.runtimePrincipal(),
                source.runtimePassword(),
                source.controlReference(),
                Map.of(),
                "",
                Map.of(),
                "");
    }

    @Test
    void actualPublicMainMustNotReopenFencedRuntimeToReverifyAlreadyVerifiedAdoptedPredecessor() throws Exception {
        fixture.provision(true);
        String snapshot = fixture.predecessorSnapshot();
        // Prerequisite: the real, unchanged preflight verifies this native v2 predecessor.
        try (Connection bootstrap = fixture.admin()) {
            DatabaseControl.acquireExclusiveControlLock(bootstrap, fixture.environment());
            ControlPreflight.verify(bootstrap, fixture.environment());
            DatabaseConnectionFence.requireDatabaseAcl(bootstrap, fixture.environment(), DatabaseConnectionFence.State.BASELINE);
            DatabaseConnectionFence.requireNoForeignSessions(bootstrap, fixture.environment());
        }
        AdoptionActiveFenceFixtureV1.ChildResult child = fixture.forkActualPublicMain();
        System.out.println("ADOPTION_REPRO_CHILD=" + child);
        assertFalse(child.timedOut());
        assertTrue(child.actualPublicMainEntered());
        assertTrue(child.processGroupAtEnd().isEmpty(), "Exact owned child process group must be absent");
        assertTrue(child.exitCode() == 0 || child.stderr().contains("MigrationControlMain.runAdoption"),
                "An unrelated child/preflight fixture failure cannot establish ACTIVE adoption evidence");
        assertEquals(snapshot, fixture.predecessorSnapshot());
        if (child.runtime42501InActiveAdoption()) {
            try (Connection bootstrap = fixture.admin()) {
                DatabaseConnectionFence.requireDatabaseAcl(bootstrap, fixture.environment(), DatabaseConnectionFence.State.FAILED);
                DatabaseConnectionFence.requireNoForeignSessions(bootstrap, fixture.environment());
            }
        }
        // Targeted expectation only. Passing it would not establish all Auth sources or downstream Flyway execution.
        assertFalse(child.runtime42501InActiveAdoption(),
                "Read-only BASELINE predecessor verification must not be repeated through runtime login after ACTIVE denies CONNECT: " + child);
    }
}
