package com.dwp.services.auth.hrisservicegrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.GrantBinding;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.SuccessorGrant;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantRotationReadinessVerifier.ReadinessItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class HrisServiceGrantAuthorityRepositoryPostgresTest {
    @Container private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String MODULUS =
            "1WuF1l_k-pcnIjxEQTyeJX1et0auB46t3l7NjeyRte9_oVL66Rq8Uo11rzv9iBlL"
            + "DkvTmj0GXDbaWbeWAStunX3OzvKzqn_vczThwE7YRCLi_l7zuyQwwiw74m0oeYm"
            + "Ti_IyE8vALsioFiCMDkfJoLQVAkowCuoKJXP0NpMkklO-EFxQf-OGa2Fx70DewRS"
            + "FA8sNMxyMbXC6YQV02JVN_QJdfcd9AdsqCd6BCTwBRj1uGaJAPIi43aMBSLr7ja"
            + "oU7RHvR9AtFwQLby_WmTNd4U3Iw4p0kdhcCSRJZahwcx-o47n5ziZ7BhENVBpnG"
            + "_sLGPkylV6fNL4R-gAfNDQoxQ";
    private static final HrisServiceGrantAuthorityV1.TargetTuple TARGET =
            new HrisServiceGrantAuthorityV1.TargetTuple(
                    "SVC-TST-HRM-001", "dwp-people-server", "dwp-auth-server",
                    "HRIS_TEST_ROTATION");

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(adminSource())
                .locations("filesystem:src/main/resources/db/migration")
                .target("271").load().migrate();
    }

    @Test
    void probeUsesTransactionLocalRlsAndSecondReplayConflictRollsBackThePair() throws Exception {
        long tenantId = 7_101L;
        PGSimpleDataSource admin = adminSource();
        createTenant(admin, tenantId);
        PGSimpleDataSource runtime = runtimeSource(admin, tenantId, false);

        try (Connection connection = runtime.getConnection()) {
            var single = new SingleConnectionDataSource(connection, true);
            var jdbc = new JdbcTemplate(single);
            var repository = repository(single);
            var response = repository.resolve(probe(tenantId),
                    replay(tenantId, "a".repeat(64), "b".repeat(64)), "c".repeat(64));

            assertThat(response.status()).isEqualTo(HrisServiceGrantAuthorityV1.Status.MISSING);
            assertThat(jdbc.queryForObject(
                    "SELECT current_setting('dwp.auth.hrm013.tenant', true)", String.class))
                    .isNullOrEmpty();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM auth_hris_service_authority_proof_replays",
                    Integer.class)).isZero();
        }
        JdbcTemplate adminJdbc = new JdbcTemplate(admin);
        assertThat(replayCount(adminJdbc, tenantId)).isEqualTo(2);

        var repository = repository(runtime);
        assertThatThrownBy(() -> repository.resolve(probe(tenantId),
                replay(tenantId, "d".repeat(64), "b".repeat(64)), "c".repeat(64)))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(value -> ((Hrm013SafeErrorV1.Failure) value).errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.AUTHORITY_DENIED);
        assertThat(adminJdbc.queryForObject("""
                SELECT count(*) FROM auth_hris_service_authority_proof_replays
                 WHERE tenant_id = ? AND token_digest = ?
                """, Integer.class, tenantId, "d".repeat(64))).isZero();
        assertThat(replayCount(adminJdbc, tenantId)).isEqualTo(2);
    }

    @Test
    void resolveWaitsForGrantLockBeforeTakingTheReplayLock() throws Exception {
        long tenantId = 7_110L;
        PGSimpleDataSource admin = adminSource();
        createTenant(admin, tenantId);
        PGSimpleDataSource runtime = runtimeSource(admin, tenantId, false);
        var executor = Executors.newSingleThreadExecutor();
        try (Connection grantLocker = admin.getConnection();
                Connection resolverConnection = runtime.getConnection()) {
            grantLocker.setAutoCommit(false);
            var lockerJdbc = new JdbcTemplate(
                    new SingleConnectionDataSource(grantLocker, true));
            var resolverSource = new SingleConnectionDataSource(resolverConnection, true);
            int resolverPid = new JdbcTemplate(resolverSource)
                    .queryForObject("SELECT pg_backend_pid()", Integer.class);
            lockerJdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    ignored -> { }, "DWP_AUTH_HRM013_GRANT_V1:" + tenantId);
            var blocked = executor.submit(() -> repository(resolverSource).resolve(
                    probe(tenantId), replay(tenantId, "e".repeat(64), "f".repeat(64)),
                    "1".repeat(64)));
            try {
                assertThat(waitForAdvisoryWait(lockerJdbc, resolverPid)).isTrue();
                assertThat(lockerJdbc.queryForObject(
                        "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                        Boolean.class, "DWP_AUTH_HRM013_REPLAY_V1:" + tenantId)).isTrue();
            } finally {
                grantLocker.commit();
            }
            assertThat(blocked.get(5, TimeUnit.SECONDS).status())
                    .isEqualTo(HrisServiceGrantAuthorityV1.Status.MISSING);
        } finally {
            executor.shutdownNow();
        }
        assertThat(replayCount(new JdbcTemplate(admin), tenantId)).isEqualTo(2);
    }

    @Test
    void quiesceSerializesNewWorkAndDrainUsesDatabaseClockBeforeAbort() throws Exception {
        long tenantId = 7_102L;
        PGSimpleDataSource admin = adminSource();
        createTenant(admin, tenantId);
        UUID grantSetId = seedGrant(admin, tenantId, 1, 11, 21, "a".repeat(64));
        PGSimpleDataSource runtime = runtimeSource(admin, tenantId, true);
        var repository = repository(runtime);
        var batch = repository.beginQuiesce(tenantId, List.of(TARGET), UUID.randomUUID());

        var executor = Executors.newSingleThreadExecutor();
        try (Connection locker = admin.getConnection()) {
            locker.setAutoCommit(false);
            try (PreparedStatement statement = locker.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1,
                        HrisServiceGrantAuthorityRepository.fenceLockKey(tenantId, TARGET));
                statement.execute();
            }
            var blocked = executor.submit(() -> repository.resolve(newWork(tenantId),
                    replay(tenantId, "a".repeat(64), "b".repeat(64)), "c".repeat(64)));
            try {
                assertThatThrownBy(() -> blocked.get(300, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            } finally {
                locker.commit();
            }
            assertThat(blocked.get(5, TimeUnit.SECONDS).status())
                    .isEqualTo(HrisServiceGrantAuthorityV1.Status.QUIESCING);
        } finally {
            executor.shutdownNow();
        }

        var drained = repository.resolve(drain(tenantId, batch.effectiveAt()),
                replay(tenantId, "d".repeat(64), "e".repeat(64)), "f".repeat(64));
        assertThat(drained.status()).isEqualTo(HrisServiceGrantAuthorityV1.Status.ADMIT);
        assertThat(drained.grantAuthority().grantSetId()).isEqualTo(grantSetId);
        assertThat(drained.drainAdmission().drainAuthorizationExpiresAt())
                .isEqualTo(drained.drainAdmission().drainActivityRecordedAt().plusSeconds(30));
        assertThat(drained.drainAdmission().drainSettleUntil())
                .isEqualTo(drained.drainAdmission().drainActivityRecordedAt().plusSeconds(65));
        assertThat(new JdbcTemplate(admin).queryForObject("""
                SELECT count(*) FROM auth_hris_service_grant_drain_activities
                 WHERE tenant_id = ? AND old_grant_set_id = ?
                """, Integer.class, tenantId, grantSetId)).isOne();

        repository.abortQuiesce(tenantId, List.of(TARGET), UUID.randomUUID());
        assertThat(repository.resolve(newWork(tenantId),
                replay(tenantId, "1".repeat(64), "2".repeat(64)), "3".repeat(64)).status())
                .isEqualTo(HrisServiceGrantAuthorityV1.Status.ADMIT);
    }

    @Test
    void emergencyRevokeImmediatelyClosesExternalAndAuthLocalOldGrantModes() throws Exception {
        long tenantId = 7_105L;
        PGSimpleDataSource admin = adminSource();
        createTenant(admin, tenantId);
        List<GrantBinding> bindings = List.of(
                binding(HrisServiceGrantQuiesceService.IDENTITY_DISPATCH, "people-id", "auth-id"),
                binding(HrisServiceGrantQuiesceService.IDENTITY_RECEIPT,
                        "auth-receipt", "people-receipt"));
        seedGrant(admin, tenantId, 1, 71, 81, "7".repeat(64), bindings);
        var repository = repository(runtimeSource(admin, tenantId, true));
        var service = new HrisServiceGrantQuiesceService(repository);
        var batch = service.beginQuiesce(tenantId,
                List.of(HrisServiceGrantQuiesceService.IDENTITY_DISPATCH), UUID.randomUUID());
        var target = HrisServiceGrantQuiesceService.IDENTITY_DISPATCH;
        var externalDrain = grantRequest(tenantId, target, target.callerWorkloadId(),
                HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING,
                71, 81, batch.effectiveAt());
        var externalReadiness = grantRequest(tenantId, target, target.callerWorkloadId(),
                HrisServiceGrantAuthorityV1.ResolutionMode.READINESS_OBSERVE,
                71, 81, batch.effectiveAt());
        var localDrain = grantRequest(tenantId, target, target.ownerWorkloadId(),
                HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING,
                71, 81, batch.effectiveAt());
        var localReadiness = grantRequest(tenantId, target, target.ownerWorkloadId(),
                HrisServiceGrantAuthorityV1.ResolutionMode.READINESS_OBSERVE,
                71, 81, batch.effectiveAt());
        repository.emergencyRevoke(tenantId,
                "8".repeat(64), "9".repeat(64), UUID.randomUUID());

        assertThat(repository.resolve(externalDrain,
                replay(tenantId, "1".repeat(64), "2".repeat(64)), "3".repeat(64)).status())
                .isEqualTo(HrisServiceGrantAuthorityV1.Status.REVOKE);
        assertThat(repository.resolve(externalReadiness,
                replay(tenantId, "4".repeat(64), "5".repeat(64)), "6".repeat(64)).status())
                .isEqualTo(HrisServiceGrantAuthorityV1.Status.REVOKE);
        var evidence = new Hrm013WorkloadProofV1.ReplayReservation(tenantId,
                target.bindingId(), target.ownerWorkloadId(), "a".repeat(64),
                "b".repeat(64), Instant.now().plusSeconds(30));
        assertThat(service.authorizeLocalDrain(localDrain, evidence, "c".repeat(64)).status())
                .isEqualTo(HrisServiceGrantAuthorityV1.Status.REVOKE);
        assertThat(service.observeLocalReadiness(localReadiness, evidence, "d".repeat(64)).status())
                .isEqualTo(HrisServiceGrantAuthorityV1.Status.REVOKE);
    }

    @Test
    void localPreparationIsReadOnlyAndSameTransactionAuthorizationAppendsOnce()
            throws Exception {
        long tenantId = 7_113L;
        PGSimpleDataSource admin = adminSource();
        createTenant(admin, tenantId);
        List<GrantBinding> bindings = List.of(
                binding(HrisServiceGrantQuiesceService.IDENTITY_DISPATCH,
                        "people-id", "auth-id"),
                binding(HrisServiceGrantQuiesceService.IDENTITY_RECEIPT,
                        "auth-receipt", "people-receipt"));
        UUID grantSet = seedGrant(admin, tenantId, 1, 121, 131,
                "7".repeat(64), bindings);
        PGSimpleDataSource runtime = runtimeSource(admin, tenantId, true);
        var repository = repository(runtime);
        var service = new HrisServiceGrantQuiesceService(repository);
        var batch = service.beginQuiesce(tenantId,
                List.of(HrisServiceGrantQuiesceService.IDENTITY_DISPATCH),
                UUID.randomUUID());
        var request = grantRequest(tenantId,
                HrisServiceGrantQuiesceService.IDENTITY_DISPATCH,
                HrisServiceGrantQuiesceService.IDENTITY_DISPATCH.ownerWorkloadId(),
                HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING,
                121, 131, batch.effectiveAt());
        JdbcTemplate adminJdbc = new JdbcTemplate(admin);
        JdbcTemplate runtimeJdbc = new JdbcTemplate(runtime);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(runtime));

        var admission = tx.execute(status -> {
            var preparation = service.prepareLocalVerification(request);
            assertThat(runtimeJdbc.queryForObject("""
                    SELECT count(*) FROM auth_hris_service_grant_drain_activities
                     WHERE tenant_id=? AND old_grant_set_id=?
                    """, Integer.class, tenantId, grantSet)).isZero();
            var admitted = service.authorizeLocalVerified(
                    preparation, "d".repeat(64));
            service.recheckLocal(admitted);
            assertThat(runtimeJdbc.queryForObject("""
                    SELECT count(*) FROM auth_hris_service_grant_drain_activities
                     WHERE tenant_id=? AND old_grant_set_id=?
                    """, Integer.class, tenantId, grantSet)).isOne();
            return admitted;
        });
        assertThat(admission.response().drainAdmission().attestingServiceRole())
                .isEqualTo(HrisServiceGrantAuthorityV1.ServiceRole.OWNER);
        assertThat(adminJdbc.queryForObject("""
                SELECT count(*) FROM auth_hris_service_grant_drain_activities
                 WHERE tenant_id=? AND old_grant_set_id=?
                """, Integer.class, tenantId, grantSet)).isOne();

        var stale = tx.execute(status -> service.prepareLocalVerification(request));
        assertThatThrownBy(() -> service.authorizeLocalVerified(
                stale, "e".repeat(64)))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
        assertThat(adminJdbc.queryForObject("""
                SELECT count(*) FROM auth_hris_service_grant_drain_activities
                 WHERE tenant_id=? AND old_grant_set_id=?
                """, Integer.class, tenantId, grantSet)).isOne();
    }

    @Test
    void oldQuiescingGrantIsStaleAfterAConnectedAdmitSuccessor() throws Exception {
        long tenantId = 7_106L;
        PGSimpleDataSource admin = adminSource();
        createTenant(admin, tenantId);
        UUID oldGrant = seedGrant(admin, tenantId, 1, 91, 101, "a".repeat(64));
        Instant effectiveAt = Instant.now().minusSeconds(70).truncatedTo(ChronoUnit.MICROS);
        seedFence(admin, tenantId, oldGrant, effectiveAt);
        seedSuccessor(admin, tenantId, oldGrant, 2, 92, 102,
                "b".repeat(64), List.of(binding("people-v2", "auth-v2")));
        var repository = repository(runtimeSource(admin, tenantId, true));

        assertThat(repository.resolve(grantRequest(tenantId, TARGET,
                TARGET.callerWorkloadId(), HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING,
                91, 101, effectiveAt), replay(tenantId, "c".repeat(64), "d".repeat(64)),
                "e".repeat(64)).status()).isEqualTo(HrisServiceGrantAuthorityV1.Status.STALE);
        assertThat(repository.resolveLocal(grantRequest(tenantId, TARGET,
                TARGET.ownerWorkloadId(),
                HrisServiceGrantAuthorityV1.ResolutionMode.READINESS_OBSERVE,
                91, 101, effectiveAt), "f".repeat(64)).status())
                .isEqualTo(HrisServiceGrantAuthorityV1.Status.STALE);
    }

    @Test
    void futureSkewMaximumLifetimeCutoverClampsReplayAndPersistsAtomicSuccessors()
            throws Exception {
        long tenantId = 7_103L;
        PGSimpleDataSource admin = adminSource();
        createTenant(admin, tenantId);
        KeyPair callerKey = rsaKeyPair(); KeyPair ownerKey = rsaKeyPair();
        GrantBinding oldBinding = binding(TARGET, "people-v1", callerKey,
                "auth-v1", ownerKey);
        UUID oldGrant = seedGrant(admin, tenantId, 1, 31, 41,
                "4".repeat(64), List.of(oldBinding));
        Instant effectiveAt = Instant.now().minusSeconds(70).truncatedTo(ChronoUnit.MICROS);
        UUID fenceId = seedFence(admin, tenantId, oldGrant, effectiveAt);
        PGSimpleDataSource runtime = runtimeSource(admin, tenantId, true);
        var repository = repository(runtime);
        var verifier = new HrisServiceGrantRotationReadinessVerifier(new ObjectMapper());
        List<HrisServiceGrantAuthorityV1.ReadinessObservation> observations = List.of(
                observation(tenantId, oldGrant, fenceId, effectiveAt,
                        HrisServiceGrantAuthorityV1.ServiceRole.CALLER, 31, 41),
                observation(tenantId, oldGrant, fenceId, effectiveAt,
                        HrisServiceGrantAuthorityV1.ServiceRole.OWNER, 31, 41));
        List<ReadinessItem> initialReadinessItems = signedReadinessItems(
                tenantId, oldGrant, fenceId, effectiveAt, observations,
                callerKey, ownerKey, oldBinding);
        String groupReadiness = readinessDigest(initialReadinessItems);
        var extraTarget = new HrisServiceGrantAuthorityV1.TargetTuple(
                "SVC-TST-HRM-002", "dwp-people-server", "dwp-auth-server",
                "HRIS_TEST_EXTRA");
        var overbroad = new SuccessorGrant(UUID.randomUUID(), 32, 42,
                "5".repeat(64), groupReadiness, List.of(
                binding("people-v2", "auth-v2"),
                binding(extraTarget, "people-extra", "auth-extra")));
        assertThatThrownBy(() -> repository.cutover(tenantId, List.of(TARGET),
                initialReadinessItems, overbroad, UUID.randomUUID(), verifier))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(value -> ((Hrm013SafeErrorV1.Failure) value).errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.INVALID_STATE);

        Instant boundaryObservedAt = new JdbcTemplate(admin).queryForObject(
                "SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant())
                .truncatedTo(ChronoUnit.SECONDS);
        List<ReadinessItem> readinessItems = signedReadinessItems(
                tenantId, oldGrant, fenceId, effectiveAt,
                observations, callerKey, ownerKey, oldBinding, boundaryObservedAt, 5);
        UUID successorId = UUID.randomUUID();
        SuccessorGrant successor = new SuccessorGrant(successorId, 32, 42,
                "5".repeat(64), readinessDigest(readinessItems),
                List.of(binding("people-v2", "auth-v2")));
        var cutover = repository.cutover(tenantId, List.of(TARGET), readinessItems, successor,
                UUID.randomUUID(), verifier);
        assertThat(cutover.grantSetId()).isEqualTo(successorId);
        assertThat(cutover.fenceIds()).hasSize(1);
        JdbcTemplate adminJdbc = new JdbcTemplate(admin);
        List<Instant> expectedReplayExpiries = new ArrayList<>();
        for (ReadinessItem item : readinessItems) {
            ObjectNode claims = (ObjectNode) new ObjectMapper().readTree(item.proofClaimBytes());
            Instant issuedAt = Instant.ofEpochSecond(claims.path("iat").longValue());
            Instant proofExpiry = Instant.ofEpochSecond(claims.path("exp").longValue());
            assertThat(issuedAt).isAfter(cutover.recordedAt());
            assertThat(proofExpiry).isEqualTo(issuedAt.plusSeconds(300));
            assertThat(cutover.recordedAt().plusSeconds(300)).isBeforeOrEqualTo(proofExpiry);
            expectedReplayExpiries.add(cutover.recordedAt().plusSeconds(300));
            expectedReplayExpiries.add(cutover.recordedAt().plusSeconds(300));
        }
        expectedReplayExpiries.sort(Instant::compareTo);
        assertThat(adminJdbc.query("""
                SELECT expires_at FROM auth_hris_service_authority_proof_replays
                 WHERE tenant_id = ? AND proof_scope = 'ROTATION_READINESS'
                 ORDER BY expires_at
                """, (rs, row) -> rs.getTimestamp(1).toInstant(), tenantId))
                .containsExactlyElementsOf(expectedReplayExpiries);
        assertThat(adminJdbc.queryForObject("""
                SELECT decision FROM auth_hris_service_grant_sets
                 WHERE tenant_id = ? ORDER BY grant_revision DESC LIMIT 1
                """, String.class, tenantId)).isEqualTo("ADMIT");

        var revoked = repository.emergencyRevoke(tenantId,
                "6".repeat(64), "7".repeat(64), UUID.randomUUID());
        assertThat(revoked.grantRevision()).isEqualTo(3);
        assertThat(adminJdbc.queryForObject("""
                SELECT decision FROM auth_hris_service_grant_sets
                 WHERE tenant_id = ? ORDER BY grant_revision DESC LIMIT 1
                """, String.class, tenantId)).isEqualTo("REVOKE");
        assertThat(adminJdbc.queryForObject("""
                SELECT count(*) FROM auth_hris_service_grant_bindings
                 WHERE tenant_id = ? AND grant_set_id = ?
                """, Integer.class, tenantId, revoked.grantSetId())).isOne();
    }

    @Test
    void cutoverRejectsExpiredReadinessAgainstWallClockInsideLongTransaction() throws Exception {
        long tenantId = 7_111L;
        PGSimpleDataSource admin = adminSource();
        createTenant(admin, tenantId);
        KeyPair callerKey = rsaKeyPair(); KeyPair ownerKey = rsaKeyPair();
        GrantBinding oldBinding = binding(TARGET, "people-v1", callerKey,
                "auth-v1", ownerKey);
        UUID oldGrant = seedGrant(admin, tenantId, 1, 31, 41,
                "4".repeat(64), List.of(oldBinding));
        Instant effectiveAt = Instant.now().minusSeconds(70).truncatedTo(ChronoUnit.MICROS);
        UUID fenceId = seedFence(admin, tenantId, oldGrant, effectiveAt);
        PGSimpleDataSource runtime = runtimeSource(admin, tenantId, true);
        var verifier = new HrisServiceGrantRotationReadinessVerifier(new ObjectMapper());
        List<HrisServiceGrantAuthorityV1.ReadinessObservation> observations = List.of(
                observation(tenantId, oldGrant, fenceId, effectiveAt,
                        HrisServiceGrantAuthorityV1.ServiceRole.CALLER, 31, 41),
                observation(tenantId, oldGrant, fenceId, effectiveAt,
                        HrisServiceGrantAuthorityV1.ServiceRole.OWNER, 31, 41));
        Instant observedAt = new JdbcTemplate(admin).queryForObject(
                "SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant())
                .truncatedTo(ChronoUnit.SECONDS);
        List<ReadinessItem> expiringItems = signedReadinessItems(
                tenantId, oldGrant, fenceId, effectiveAt,
                observations, callerKey, ownerKey, oldBinding, observedAt, 1, 5);
        String expiringDigest = readinessDigest(expiringItems);
        var transactionManager = new DataSourceTransactionManager(runtime);
        var transactionalRepository = new HrisServiceGrantAuthorityRepository(
                new JdbcTemplate(runtime), transactionManager, new ObjectMapper());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            Instant transactionStart = new JdbcTemplate(runtime).queryForObject(
                    "SELECT transaction_timestamp()",
                    (rs, row) -> rs.getTimestamp(1).toInstant());
            assertThat(transactionStart).isBefore(observedAt.plusSeconds(5));
            new JdbcTemplate(runtime).execute("SELECT pg_sleep(6.2)");
            return transactionalRepository.cutover(tenantId, List.of(TARGET),
                    expiringItems,
                    new SuccessorGrant(UUID.randomUUID(), 32, 42,
                            "5".repeat(64), expiringDigest,
                            List.of(binding("people-v2-expired", "auth-v2-expired"))),
                    UUID.randomUUID(), verifier);
        })).isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(value -> ((Hrm013SafeErrorV1.Failure) value).errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.INVALID_STATE);
        assertThat(new JdbcTemplate(admin).queryForObject("""
                SELECT count(*) FROM auth_hris_service_grant_sets
                 WHERE tenant_id = ?
                """, Integer.class, tenantId)).isOne();
    }

    @Test
    void pairCutoverPreservesOutsideFullSnapshot() throws Exception {
        long tenantId = 7_107L;
        PGSimpleDataSource admin = adminSource(); createTenant(admin, tenantId);
        KeyPair timeCaller = rsaKeyPair(), timeOwner = rsaKeyPair();
        KeyPair receiptCaller = rsaKeyPair(), receiptOwner = rsaKeyPair();
        GrantBinding timeDispatch = binding(HrisServiceGrantQuiesceService.TIME_DISPATCH,
                "time-c1", timeCaller, "time-o1", timeOwner);
        GrantBinding timeReceipt = binding(HrisServiceGrantQuiesceService.TIME_RECEIPT,
                "receipt-c1", receiptCaller, "receipt-o1", receiptOwner);
        GrantBinding payDispatch = binding(HrisServiceGrantQuiesceService.PAYROLL_DISPATCH,
                "pay-c1", "pay-o1");
        GrantBinding payReceipt = binding(HrisServiceGrantQuiesceService.PAYROLL_RECEIPT,
                "pay-r1", "people-r1");
        List<GrantBinding> current = List.of(
                timeDispatch, timeReceipt, payDispatch, payReceipt);
        UUID oldGrant = seedGrant(admin, tenantId, 1, 31, 41, "a".repeat(64), current);
        Instant effectiveAt = Instant.now().minusSeconds(70).truncatedTo(ChronoUnit.MICROS);
        List<HrisServiceGrantAuthorityV1.TargetTuple> affected =
                HrisServiceGrantQuiesceService.expandedLockOrder(
                        List.of(HrisServiceGrantQuiesceService.TIME_DISPATCH));
        List<ReadinessItem> items = new ArrayList<>();
        UUID dispatchFence = seedFence(admin, tenantId, oldGrant, effectiveAt,
                HrisServiceGrantQuiesceService.TIME_DISPATCH);
        UUID receiptFence = seedFence(admin, tenantId, oldGrant, effectiveAt,
                HrisServiceGrantQuiesceService.TIME_RECEIPT);
        items.addAll(signedReadinessItems(tenantId, oldGrant, dispatchFence, effectiveAt,
                observations(tenantId, oldGrant, dispatchFence, effectiveAt,
                        HrisServiceGrantQuiesceService.TIME_DISPATCH, 31, 41),
                timeCaller, timeOwner, timeDispatch));
        items.addAll(signedReadinessItems(tenantId, oldGrant, receiptFence, effectiveAt,
                observations(tenantId, oldGrant, receiptFence, effectiveAt,
                        HrisServiceGrantQuiesceService.TIME_RECEIPT, 31, 41),
                receiptCaller, receiptOwner, timeReceipt));
        String digest = readinessDigest(items);
        GrantBinding rotatedDispatch = binding(HrisServiceGrantQuiesceService.TIME_DISPATCH,
                "time-c2", "time-o2");
        GrantBinding rotatedReceipt = binding(HrisServiceGrantQuiesceService.TIME_RECEIPT,
                "receipt-c2", "receipt-o2");
        var repository = repository(runtimeSource(admin, tenantId, true));
        var verifier = new HrisServiceGrantRotationReadinessVerifier(new ObjectMapper());
        assertThatThrownBy(() -> repository.cutover(tenantId, affected, items,
                successor(digest, List.of(rotatedDispatch, rotatedReceipt, payDispatch)),
                UUID.randomUUID(), verifier)).isInstanceOf(Hrm013SafeErrorV1.Failure.class);
        assertThatThrownBy(() -> repository.cutover(tenantId, affected, items,
                successor(digest, List.of(rotatedDispatch, rotatedReceipt,
                        binding(HrisServiceGrantQuiesceService.PAYROLL_DISPATCH,
                                "pay-c2", "pay-o2"), payReceipt)),
                UUID.randomUUID(), verifier)).isInstanceOf(Hrm013SafeErrorV1.Failure.class);
        var result = repository.cutover(tenantId, affected, items,
                successor(digest, List.of(rotatedDispatch, rotatedReceipt,
                        payDispatch, payReceipt)), UUID.randomUUID(), verifier);
        JdbcTemplate jdbc = new JdbcTemplate(admin);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_hris_service_grant_bindings
                 WHERE tenant_id = ? AND grant_set_id = ?
                """, Integer.class, tenantId, result.grantSetId())).isEqualTo(4);
        assertThat(repository.resolve(grantRequest(tenantId,
                HrisServiceGrantQuiesceService.PAYROLL_DISPATCH, "dwp-people-server",
                HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK, 32, 42, effectiveAt),
                replay(tenantId, "1".repeat(64), "2".repeat(64)), "3".repeat(64)).status())
                .isEqualTo(HrisServiceGrantAuthorityV1.Status.ADMIT);
    }

    @Test
    void pairCutoverMayOmitTheCompleteSelectedGroup() throws Exception {
        long tenantId = 7_108L;
        PGSimpleDataSource admin = adminSource(); createTenant(admin, tenantId);
        KeyPair timeCaller = rsaKeyPair(), timeOwner = rsaKeyPair();
        KeyPair receiptCaller = rsaKeyPair(), receiptOwner = rsaKeyPair();
        GrantBinding timeDispatch = binding(HrisServiceGrantQuiesceService.TIME_DISPATCH,
                "time-c1", timeCaller, "time-o1", timeOwner);
        GrantBinding timeReceipt = binding(HrisServiceGrantQuiesceService.TIME_RECEIPT,
                "receipt-c1", receiptCaller, "receipt-o1", receiptOwner);
        GrantBinding payDispatch = binding(HrisServiceGrantQuiesceService.PAYROLL_DISPATCH,
                "pay-c1", "pay-o1");
        GrantBinding payReceipt = binding(HrisServiceGrantQuiesceService.PAYROLL_RECEIPT,
                "pay-r1", "people-r1");
        UUID oldGrant = seedGrant(admin, tenantId, 1, 31, 41, "b".repeat(64),
                List.of(timeDispatch, timeReceipt, payDispatch, payReceipt));
        Instant effectiveAt = Instant.now().minusSeconds(70).truncatedTo(ChronoUnit.MICROS);
        UUID dispatchFence = seedFence(admin, tenantId, oldGrant, effectiveAt,
                HrisServiceGrantQuiesceService.TIME_DISPATCH);
        UUID receiptFence = seedFence(admin, tenantId, oldGrant, effectiveAt,
                HrisServiceGrantQuiesceService.TIME_RECEIPT);
        List<ReadinessItem> items = new ArrayList<>();
        items.addAll(signedReadinessItems(tenantId, oldGrant, dispatchFence, effectiveAt,
                observations(tenantId, oldGrant, dispatchFence, effectiveAt,
                        HrisServiceGrantQuiesceService.TIME_DISPATCH, 31, 41),
                timeCaller, timeOwner, timeDispatch));
        items.addAll(signedReadinessItems(tenantId, oldGrant, receiptFence, effectiveAt,
                observations(tenantId, oldGrant, receiptFence, effectiveAt,
                        HrisServiceGrantQuiesceService.TIME_RECEIPT, 31, 41),
                receiptCaller, receiptOwner, timeReceipt));
        var result = repository(runtimeSource(admin, tenantId, true)).cutover(tenantId,
                HrisServiceGrantQuiesceService.expandedLockOrder(
                        List.of(HrisServiceGrantQuiesceService.TIME_RECEIPT)),
                items, successor(readinessDigest(items), List.of(payDispatch, payReceipt)),
                UUID.randomUUID(),
                new HrisServiceGrantRotationReadinessVerifier(new ObjectMapper()));
        assertThat(new JdbcTemplate(admin).queryForObject("""
                SELECT count(*) FROM auth_hris_service_grant_bindings
                 WHERE tenant_id = ? AND grant_set_id = ?
                """, Integer.class, tenantId, result.grantSetId())).isEqualTo(2);
    }

    @Test
    void selectingOneTaskSideQuiescesTheExactPairOnly() throws Exception {
        long tenantId = 7_109L;
        PGSimpleDataSource admin = adminSource(); createTenant(admin, tenantId);
        seedGrant(admin, tenantId, 1, 31, 41, "c".repeat(64), List.of(
                binding(HrisServiceGrantQuiesceService.TIME_DISPATCH, "tc1", "to1"),
                binding(HrisServiceGrantQuiesceService.TIME_RECEIPT, "tr1", "tp1"),
                binding(HrisServiceGrantQuiesceService.PAYROLL_DISPATCH, "pc1", "po1"),
                binding(HrisServiceGrantQuiesceService.PAYROLL_RECEIPT, "pr1", "pp1")));
        var batch = new HrisServiceGrantQuiesceService(
                repository(runtimeSource(admin, tenantId, true))).beginQuiesce(tenantId,
                List.of(HrisServiceGrantQuiesceService.TIME_RECEIPT), UUID.randomUUID());
        assertThat(batch.fences()).hasSize(2);
        assertThat(new JdbcTemplate(admin).queryForObject("""
                SELECT count(*) FROM auth_hris_service_grant_quiesce_fences
                 WHERE tenant_id = ?
                """, Integer.class, tenantId)).isEqualTo(2);
    }

    @Test
    void requesterRecheckRejectsSameKidWithDifferentRsaVector() throws Exception {
        long tenantId = 7_104L;
        PGSimpleDataSource admin = adminSource();
        createTenant(admin, tenantId);
        seedRequesterGrant(admin, tenantId);
        PGSimpleDataSource runtime = runtimeSource(admin, tenantId, true);
        var repository = new Hrm013RequesterIdentityAuthorityRepository(
                new JdbcTemplate(runtime), new DataSourceTransactionManager(runtime),
                new ObjectMapper());
        var profile = Hrm013WorkloadProofV1.requesterIdentity();
        var authority = new Hrm013WorkloadProofV1.BusinessAuthority(
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, 51, 61);
        var selector = new Hrm013WorkloadProofV1.UntrustedSelector(
                tenantId, authority, null);
        var current = repository.resolveSigningKey(profile, selector, "approval-key");
        var claims = new Hrm013WorkloadProofV1.ProofClaims(
                null, null, null, null, null, null, null, null, null, null, null, null,
                tenantId, null, null, null, null, "approval-key", authority, null, Map.of());

        assertThat(repository.remainsCurrent(profile, current, claims)).isTrue();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2_048);
        var sameKidDifferentVector = new Hrm013WorkloadProofV1.VerifiedSigningKey(
                generator.generateKeyPair().getPublic(), current.keyId(), current.workloadId());
        assertThat(repository.remainsCurrent(profile, sameKidDifferentVector, claims)).isFalse();
    }

    private static HrisServiceGrantAuthorityRepository repository(DataSource source) {
        return new HrisServiceGrantAuthorityRepository(new JdbcTemplate(source),
                new DataSourceTransactionManager(source), new ObjectMapper());
    }

    private static HrisServiceGrantAuthorityV1.ResolveRequest probe(long tenantId) {
        return new HrisServiceGrantAuthorityV1.ResolveRequest(tenantId, "dwp-people-server",
                TARGET.callerWorkloadId(), TARGET.ownerWorkloadId(), TARGET.bindingId(),
                TARGET.purpose(), HrisServiceGrantAuthorityV1.ResolutionMode.PROBE_MISSING,
                0, 0, UUID.randomUUID(), null);
    }

    private static HrisServiceGrantAuthorityV1.ResolveRequest newWork(long tenantId) {
        return new HrisServiceGrantAuthorityV1.ResolveRequest(tenantId, "dwp-people-server",
                TARGET.callerWorkloadId(), TARGET.ownerWorkloadId(), TARGET.bindingId(),
                TARGET.purpose(), HrisServiceGrantAuthorityV1.ResolutionMode.NEW_WORK,
                11, 21, UUID.randomUUID(), null);
    }

    private static HrisServiceGrantAuthorityV1.ResolveRequest drain(
            long tenantId, Instant effectiveAt) {
        var fields = new HrisServiceGrantAuthorityV1.DrainFields(UUID.randomUUID(),
                effectiveAt.minusSeconds(1), "8".repeat(64),
                HrisServiceGrantAuthorityV1.DrainContinuationKind.ROOT_DELIVERY,
                UUID.randomUUID(), 0, "9".repeat(64));
        return new HrisServiceGrantAuthorityV1.ResolveRequest(tenantId, "dwp-people-server",
                TARGET.callerWorkloadId(), TARGET.ownerWorkloadId(), TARGET.bindingId(),
                TARGET.purpose(), HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING,
                11, 21, UUID.randomUUID(), fields);
    }

    private static HrisServiceGrantAuthorityV1.ResolveRequest grantRequest(long tenantId,
            HrisServiceGrantAuthorityV1.TargetTuple target, String requester,
            HrisServiceGrantAuthorityV1.ResolutionMode mode, long tenantRevision,
            long authorizationRevision, Instant effectiveAt) {
        HrisServiceGrantAuthorityV1.DrainFields fields = mode
                == HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING
                ? new HrisServiceGrantAuthorityV1.DrainFields(UUID.randomUUID(),
                effectiveAt.minusSeconds(1), "8".repeat(64),
                HrisServiceGrantAuthorityV1.DrainContinuationKind.ROOT_DELIVERY,
                UUID.randomUUID(), 0, "9".repeat(64)) : null;
        return new HrisServiceGrantAuthorityV1.ResolveRequest(tenantId, requester,
                target.callerWorkloadId(), target.ownerWorkloadId(), target.bindingId(),
                target.purpose(), mode, tenantRevision, authorizationRevision,
                UUID.randomUUID(), fields);
    }

    private static Hrm013WorkloadProofV1.ReplayReservation replay(
            long tenantId, String jti, String nonce) {
        return new Hrm013WorkloadProofV1.ReplayReservation(tenantId, "SVC-AUT-HRM-011",
                "dwp-people-server", jti, nonce, Instant.now().plusSeconds(120));
    }

    private static HrisServiceGrantAuthorityV1.ReadinessObservation observation(
            long tenantId, UUID grantSetId, UUID fenceId, Instant effectiveAt,
            HrisServiceGrantAuthorityV1.ServiceRole role, long tenantRevision,
            long authorizationRevision) {
        return observation(tenantId, grantSetId, fenceId, effectiveAt, TARGET, role,
                tenantRevision, authorizationRevision);
    }

    private static List<HrisServiceGrantAuthorityV1.ReadinessObservation> observations(
            long tenantId, UUID grantSetId, UUID fenceId, Instant effectiveAt,
            HrisServiceGrantAuthorityV1.TargetTuple target, long tenantRevision,
            long authorizationRevision) {
        return List.of(observation(tenantId, grantSetId, fenceId, effectiveAt, target,
                HrisServiceGrantAuthorityV1.ServiceRole.CALLER,
                tenantRevision, authorizationRevision),
                observation(tenantId, grantSetId, fenceId, effectiveAt, target,
                        HrisServiceGrantAuthorityV1.ServiceRole.OWNER,
                        tenantRevision, authorizationRevision));
    }

    private static HrisServiceGrantAuthorityV1.ReadinessObservation observation(
            long tenantId, UUID grantSetId, UUID fenceId, Instant effectiveAt,
            HrisServiceGrantAuthorityV1.TargetTuple target,
            HrisServiceGrantAuthorityV1.ServiceRole role, long tenantRevision,
            long authorizationRevision) {
        var request = new HrisServiceGrantAuthorityV1.ResolveRequest(tenantId,
                role == HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                        ? target.callerWorkloadId() : target.ownerWorkloadId(),
                target.callerWorkloadId(), target.ownerWorkloadId(), target.bindingId(),
                target.purpose(), HrisServiceGrantAuthorityV1.ResolutionMode.READINESS_OBSERVE,
                tenantRevision, authorizationRevision, UUID.randomUUID(), null);
        var fence = new HrisServiceGrantQuiesceService.Fence(
                fenceId, grantSetId, 1, "QUIESCING", effectiveAt);
        Instant eligibleAt = effectiveAt.plusSeconds(65);
        String digest = HrisServiceGrantRotationReadinessVerifier.observationDigest(request,
                grantSetId, tenantRevision, authorizationRevision, fence, role, 0,
                effectiveAt, eligibleAt);
        return new HrisServiceGrantAuthorityV1.ReadinessObservation(tenantId, grantSetId,
                tenantRevision, authorizationRevision, target.callerWorkloadId(),
                target.ownerWorkloadId(), target.bindingId(), target.purpose(), fenceId, 1,
                effectiveAt, role, 0, effectiveAt, eligibleAt, digest);
    }

    private static GrantBinding binding(String callerKey, String ownerKey) {
        return binding(TARGET, callerKey, ownerKey);
    }

    private static GrantBinding binding(HrisServiceGrantAuthorityV1.TargetTuple target,
            String callerKey, String ownerKey) {
        return new GrantBinding(target, "spiffe://dwp/service/" + target.callerWorkloadId(),
                callerKey, jwk(callerKey),
                "spiffe://dwp/service/" + target.ownerWorkloadId(), ownerKey, jwk(ownerKey),
                "a".repeat(64));
    }

    private static GrantBinding binding(HrisServiceGrantAuthorityV1.TargetTuple target,
            String callerKeyId, KeyPair callerKey, String ownerKeyId, KeyPair ownerKey) {
        return new GrantBinding(target, "spiffe://dwp/service/" + target.callerWorkloadId(),
                callerKeyId, jwk(callerKey, callerKeyId),
                "spiffe://dwp/service/" + target.ownerWorkloadId(), ownerKeyId,
                jwk(ownerKey, ownerKeyId), "a".repeat(64));
    }

    private static HrisServiceGrantAuthorityV1.RsaPublicJwk jwk(String keyId) {
        return new HrisServiceGrantAuthorityV1.RsaPublicJwk(
                "RS256", "AQAB", keyId, "RSA", MODULUS, "sig");
    }

    private static SuccessorGrant successor(String readinessDigest,
            List<GrantBinding> bindings) {
        return new SuccessorGrant(UUID.randomUUID(), 32, 42,
                "5".repeat(64), readinessDigest, bindings);
    }

    private static UUID seedGrant(PGSimpleDataSource source, long tenantId,
            long grantRevision, long tenantRevision, long authorizationRevision,
            String grantDigest) throws Exception {
        return seedGrant(source, tenantId, grantRevision, tenantRevision,
                authorizationRevision, grantDigest,
                List.of(binding("people-v1", "auth-v1")));
    }

    private static UUID seedGrant(PGSimpleDataSource source, long tenantId,
            long grantRevision, long tenantRevision, long authorizationRevision,
            String grantDigest, List<GrantBinding> bindings) throws Exception {
        UUID grantSetId = UUID.randomUUID();
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            insertGrant(connection, tenantId, grantSetId, grantRevision, tenantRevision,
                    authorizationRevision, grantDigest);
            for (GrantBinding binding : bindings)
                insertBinding(connection, tenantId, grantSetId, binding);
            connection.commit();
        }
        return grantSetId;
    }

    private static UUID seedRequesterGrant(PGSimpleDataSource source, long tenantId)
            throws Exception {
        UUID grantSetId = UUID.randomUUID();
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            insertGrant(connection, tenantId, grantSetId, 1, 51, 61, "c".repeat(64));
            insertBinding(connection, tenantId, grantSetId, binding(
                    HrisServiceGrantQuiesceService.WAIVER_REQUEST,
                    "people-key", "approval-key"));
            insertBinding(connection, tenantId, grantSetId, binding(
                    HrisServiceGrantQuiesceService.WAIVER_DECISION,
                    "approval-key", "people-key"));
            insertBinding(connection, tenantId, grantSetId, binding(
                    HrisServiceGrantQuiesceService.REQUESTER,
                    "approval-key", "auth-key"));
            connection.commit();
        }
        return grantSetId;
    }

    private static void insertGrant(Connection connection, long tenantId, UUID grantSetId,
            long grantRevision, long tenantRevision, long authorizationRevision,
            String grantDigest) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO auth_hris_service_grant_sets (
                  grant_set_id, tenant_id, grant_revision, tenant_grant_revision,
                  authorization_revision, decision, grant_digest, recorded_at)
                VALUES (?, ?, ?, ?, ?, 'ADMIT', ?, transaction_timestamp())
                """)) {
            statement.setObject(1, grantSetId); statement.setLong(2, tenantId);
            statement.setLong(3, grantRevision); statement.setLong(4, tenantRevision);
            statement.setLong(5, authorizationRevision); statement.setString(6, grantDigest);
            statement.executeUpdate();
        }
    }

    private static UUID seedSuccessor(PGSimpleDataSource source, long tenantId,
            UUID predecessor, long grantRevision, long tenantRevision,
            long authorizationRevision, String grantDigest, List<GrantBinding> bindings)
            throws Exception {
        UUID grantSetId = UUID.randomUUID();
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO auth_hris_service_grant_sets (
                      grant_set_id, tenant_id, grant_revision, tenant_grant_revision,
                      authorization_revision, decision, supersedes_grant_set_id,
                      rotation_readiness_digest, grant_digest, recorded_at)
                    VALUES (?, ?, ?, ?, ?, 'ADMIT', ?, ?, ?, transaction_timestamp())
                    """)) {
                statement.setObject(1, grantSetId); statement.setLong(2, tenantId);
                statement.setLong(3, grantRevision); statement.setLong(4, tenantRevision);
                statement.setLong(5, authorizationRevision); statement.setObject(6, predecessor);
                statement.setString(7, "1".repeat(64)); statement.setString(8, grantDigest);
                statement.executeUpdate();
            }
            for (GrantBinding binding : bindings)
                insertBinding(connection, tenantId, grantSetId, binding);
            connection.commit();
        }
        return grantSetId;
    }

    private static void insertBinding(Connection connection, long tenantId, UUID grantSetId,
            GrantBinding value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO auth_hris_service_grant_bindings (
                  grant_binding_id, tenant_id, grant_set_id, binding_id, caller_workload_id,
                  caller_spiffe_id, caller_signing_key_id, caller_signing_public_jwk,
                  owner_workload_id, owner_spiffe_id, owner_signing_key_id,
                  owner_signing_public_jwk, purpose, binding_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID()); statement.setLong(2, tenantId);
            statement.setObject(3, grantSetId); statement.setString(4, value.target().bindingId());
            statement.setString(5, value.target().callerWorkloadId());
            statement.setString(6, value.callerSpiffeId());
            statement.setString(7, value.callerSigningKeyId());
            statement.setString(8, jwkJson(value.callerSigningPublicJwk()));
            statement.setString(9, value.target().ownerWorkloadId());
            statement.setString(10, value.ownerSpiffeId());
            statement.setString(11, value.ownerSigningKeyId());
            statement.setString(12, jwkJson(value.ownerSigningPublicJwk()));
            statement.setString(13, value.target().purpose());
            statement.setString(14, value.bindingDigest()); statement.executeUpdate();
        }
    }

    private static UUID seedFence(PGSimpleDataSource source, long tenantId, UUID grantSetId,
            Instant effectiveAt) {
        return seedFence(source, tenantId, grantSetId, effectiveAt, TARGET);
    }

    private static UUID seedFence(PGSimpleDataSource source, long tenantId, UUID grantSetId,
            Instant effectiveAt, HrisServiceGrantAuthorityV1.TargetTuple target) {
        UUID fenceId = UUID.randomUUID();
        new JdbcTemplate(source).update("""
                INSERT INTO auth_hris_service_grant_quiesce_fences (
                  quiesce_fence_id, tenant_id, old_grant_set_id, binding_id,
                  caller_workload_id, owner_workload_id, purpose, quiesce_revision,
                  lifecycle_state, effective_at, successor_command_correlation_id,
                  fence_digest, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'QUIESCING', ?, ?, ?, transaction_timestamp())
                """, fenceId, tenantId, grantSetId, target.bindingId(),
                target.callerWorkloadId(), target.ownerWorkloadId(), target.purpose(),
                OffsetDateTime.ofInstant(effectiveAt, ZoneOffset.UTC), UUID.randomUUID(),
                "b".repeat(64));
        return fenceId;
    }

    private static int replayCount(JdbcTemplate jdbc, long tenantId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM auth_hris_service_authority_proof_replays
                 WHERE tenant_id = ?
                """, Integer.class, tenantId);
    }

    private static boolean waitForAdvisoryWait(JdbcTemplate jdbc, int backendPid)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            Boolean waiting = jdbc.queryForObject("""
                    SELECT wait_event_type = 'Lock' AND wait_event = 'advisory'
                      FROM pg_stat_activity WHERE pid = ?
                    """, Boolean.class, backendPid);
            if (Boolean.TRUE.equals(waiting)) return true;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private static void createTenant(PGSimpleDataSource source, long tenantId) {
        new JdbcTemplate(source).update(
                "INSERT INTO com_tenants (tenant_id, code, name) VALUES (?, ?, ?)",
                tenantId, "hrm013-" + tenantId, "HRM013 " + tenantId);
    }

    private static PGSimpleDataSource runtimeSource(
            PGSimpleDataSource admin, long tenantId, boolean authorityAccess) {
        String role = "hrm013_runtime_" + tenantId;
        JdbcTemplate jdbc = new JdbcTemplate(admin);
        jdbc.execute("CREATE ROLE " + role
                + " LOGIN PASSWORD 'runtime-secret' NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + role);
        jdbc.execute("GRANT INSERT, SELECT ON auth_hris_service_authority_proof_replays TO "
                + role);
        jdbc.execute("GRANT USAGE, SELECT ON auth_hris_service_authority_proof_replays_proof_replay_id_seq TO "
                + role);
        if (authorityAccess) {
            jdbc.execute("GRANT SELECT, INSERT, UPDATE ON auth_hris_service_grant_sets, "
                    + "auth_hris_service_grant_bindings, auth_hris_service_grant_quiesce_fences, "
                    + "auth_hris_service_grant_drain_activities TO " + role);
        }
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl()); source.setUser(role);
        source.setPassword("runtime-secret");
        return source;
    }

    private static PGSimpleDataSource adminSource() {
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl()); source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private static List<ReadinessItem> signedReadinessItems(long tenantId, UUID grantSetId,
            UUID fenceId, Instant effectiveAt,
            List<HrisServiceGrantAuthorityV1.ReadinessObservation> observations,
            KeyPair callerKey, KeyPair ownerKey, GrantBinding binding) {
        return signedReadinessItems(tenantId, grantSetId, fenceId, effectiveAt, observations,
                callerKey, ownerKey, binding,
                Instant.now().minusSeconds(1).truncatedTo(ChronoUnit.SECONDS), 1);
    }

    private static List<ReadinessItem> signedReadinessItems(long tenantId, UUID grantSetId,
            UUID fenceId, Instant effectiveAt,
            List<HrisServiceGrantAuthorityV1.ReadinessObservation> observations,
            KeyPair callerKey, KeyPair ownerKey, GrantBinding binding,
            Instant observedAt, long issueDelaySeconds) {
        return signedReadinessItems(tenantId, grantSetId, fenceId, effectiveAt,
                observations, callerKey, ownerKey, binding, observedAt,
                issueDelaySeconds, 180);
    }

    private static List<ReadinessItem> signedReadinessItems(long tenantId, UUID grantSetId,
            UUID fenceId, Instant effectiveAt,
            List<HrisServiceGrantAuthorityV1.ReadinessObservation> observations,
            KeyPair callerKey, KeyPair ownerKey, GrantBinding binding,
            Instant observedAt, long issueDelaySeconds,
            long receiptLifetimeSeconds) {
        return observations.stream().map(observation -> {
            var role = observation.attestingServiceRole();
            return signedReadinessItem(tenantId, grantSetId, fenceId, effectiveAt,
                    observation, role == HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                            ? callerKey : ownerKey,
                    role == HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                            ? binding.callerSigningKeyId() : binding.ownerSigningKeyId(),
                    observedAt, issueDelaySeconds, receiptLifetimeSeconds);
        }).toList();
    }

    private static ReadinessItem signedReadinessItem(long tenantId, UUID grantSetId,
            UUID fenceId, Instant effectiveAt,
            HrisServiceGrantAuthorityV1.ReadinessObservation observation,
            KeyPair signer, String keyId, Instant observedAt, long issueDelaySeconds,
            long receiptLifetimeSeconds) {
        try {
            var role = observation.attestingServiceRole();
            String workload = role == HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                    ? observation.callerWorkloadId() : observation.ownerWorkloadId();
            Instant expiresAt = observedAt.plusSeconds(receiptLifetimeSeconds);
            UUID correlation = UUID.randomUUID();
            ObjectNode receipt = new ObjectMapper().createObjectNode();
            receipt.put("schemaName", HrisServiceGrantRotationReadinessVerifier.SCHEMA);
            receipt.put("schemaVersion", 1); receipt.put("tenantId", tenantId);
            receipt.put("bindingId", observation.bindingId());
            receipt.put("callerWorkloadId", observation.callerWorkloadId());
            receipt.put("ownerWorkloadId", observation.ownerWorkloadId());
            receipt.put("bindingPurpose", observation.purpose());
            receipt.put("serviceWorkloadId", workload);
            receipt.put("attestingServiceRole", role.name());
            receipt.put("oldGrantSetId", grantSetId.toString());
            receipt.put("quiesceFenceId", fenceId.toString());
            receipt.put("quiesceRevision", observation.quiesceRevision());
            receipt.put("quiesceEffectiveAt", effectiveAt.toString());
            receipt.put("observedDrainActivityRevision", observation.drainActivityRevision());
            receipt.put("drainQuietSince", observation.drainQuietSince().toString());
            receipt.put("readinessEligibleAt", observation.readinessEligibleAt().toString());
            receipt.put("readinessObservationSha256", observation.readinessObservationSha256());
            receipt.put("observedGrantRevision", 1);
            receipt.put("tenantGrantRevision", observation.tenantGrantRevision());
            receipt.put("authorizationRevision", observation.authorizationRevision());
            receipt.put("pendingCount", 0); receipt.putNull("oldestPendingAt");
            receipt.put("observedAt", observedAt.toString());
            receipt.put("expiresAt", expiresAt.toString());
            receipt.put("correlationId", correlation.toString());
            receipt.put("evidenceSha256", Hrm013WorkloadProofV1.sha256(
                    HrisServiceGrantRotationReadinessVerifier.canonicalBytes(receipt)));
            byte[] receiptBytes = HrisServiceGrantRotationReadinessVerifier.canonicalBytes(receipt);

            ObjectNode claims = new ObjectMapper().createObjectNode();
            Instant issuedAt = observedAt.plusSeconds(issueDelaySeconds);
            claims.put("iss", workload); claims.put("aud", "dwp-auth-server");
            claims.put("sub", workload); claims.put("iat", issuedAt.getEpochSecond());
            claims.put("nbf", issuedAt.getEpochSecond());
            claims.put("exp", issuedAt.plusSeconds(300).getEpochSecond());
            claims.put("jti", UUID.randomUUID().toString());
            claims.put("requestNonce", UUID.randomUUID().toString());
            for (String field : List.of("tenantId", "bindingId", "callerWorkloadId",
                    "ownerWorkloadId", "bindingPurpose", "serviceWorkloadId",
                    "attestingServiceRole", "oldGrantSetId", "quiesceFenceId",
                    "quiesceRevision", "quiesceEffectiveAt", "observedDrainActivityRevision",
                    "drainQuietSince", "readinessEligibleAt", "readinessObservationSha256",
                    "observedGrantRevision", "tenantGrantRevision", "authorizationRevision",
                    "evidenceSha256", "correlationId")) claims.set(field, receipt.get(field));
            claims.put("proofPurpose", HrisServiceGrantRotationReadinessVerifier.PROOF_PURPOSE);
            byte[] claimBytes = HrisServiceGrantRotationReadinessVerifier.canonicalBytes(claims);
            ObjectNode header = new ObjectMapper().createObjectNode();
            header.put("alg", "RS256");
            header.put("typ", HrisServiceGrantRotationReadinessVerifier.PROOF_TYPE);
            header.put("kid", keyId);
            String encodedHeader = encode(HrisServiceGrantRotationReadinessVerifier
                    .canonicalBytes(header));
            String signingInput = encodedHeader + "." + encode(claimBytes);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(signer.getPrivate());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return new ReadinessItem(receiptBytes, claimBytes,
                    encodedHeader + ".." + encode(signature.sign()));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String readinessDigest(List<ReadinessItem> items) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<ObjectNode> receipts = items.stream().map(item -> {
            try { return (ObjectNode) mapper.readTree(item.receiptBytes()); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }).sorted(java.util.Comparator.comparing(value -> String.join("\n",
                value.path("bindingId").asText(), value.path("callerWorkloadId").asText(),
                value.path("ownerWorkloadId").asText(), value.path("bindingPurpose").asText(),
                value.path("attestingServiceRole").asText(),
                value.path("serviceWorkloadId").asText(),
                value.path("evidenceSha256").asText()))).toList();
        ArrayNode evidence = mapper.createArrayNode();
        receipts.forEach(value -> evidence.add(value.path("evidenceSha256").asText()));
        return Hrm013WorkloadProofV1.sha256(
                HrisServiceGrantRotationReadinessVerifier.canonicalBytes(evidence));
    }

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2_048); return generator.generateKeyPair();
    }

    private static HrisServiceGrantAuthorityV1.RsaPublicJwk jwk(
            KeyPair pair, String keyId) {
        RSAPublicKey key = (RSAPublicKey) pair.getPublic();
        return new HrisServiceGrantAuthorityV1.RsaPublicJwk("RS256",
                encode(unsigned(key.getPublicExponent().toByteArray())), keyId, "RSA",
                encode(unsigned(key.getModulus().toByteArray())), "sig");
    }

    private static byte[] unsigned(byte[] value) {
        return value.length > 1 && value[0] == 0
                ? Arrays.copyOfRange(value, 1, value.length) : value;
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String jwkJson(String keyId) {
        return jwkJson(jwk(keyId));
    }

    private static String jwkJson(HrisServiceGrantAuthorityV1.RsaPublicJwk value) {
        return """
                {"alg":"RS256","e":"AQAB","kid":"%s","kty":"RSA","n":"%s","use":"sig"}
                """.formatted(value.kid(), value.n()).strip();
    }
}
