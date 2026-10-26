package com.dwp.services.auth.hrisservicegrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013RequesterIdentityAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1.VerifiedSigningKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class Hrm013RequesterIdentityAuthorityRepositoryPostgresTest {
    @Container private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String KEY_ID = "approval-requester-key";
    private static final String PAYLOAD = "a".repeat(64);
    private static KeyPair keyOne;
    private static KeyPair keyTwo;

    @BeforeAll
    static void prepare() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyOne = generator.generateKeyPair();
        keyTwo = generator.generateKeyPair();
        Flyway.configure().dataSource(adminSource())
                .locations("filesystem:src/main/resources/db/migration")
                .target("271").load().migrate();
    }

    @Test
    void requesterDirectoryAdmitsOnlyExactActiveTenantPerson() throws Exception {
        long tenantId = 7_201L;
        long otherTenant = 7_202L;
        PGSimpleDataSource admin = adminSource();
        createTenant(admin, tenantId);
        createTenant(admin, otherTenant);
        seedGrant(admin, tenantId, 1, 51, 61, null, "ADMIT", keyOne);
        UUID person = UUID.randomUUID();
        long userId = createUser(admin, tenantId, person, "ACTIVE", "TENANT");
        UUID otherPerson = UUID.randomUUID();
        createUser(admin, otherTenant, otherPerson, "ACTIVE", "TENANT");
        var repository = repository(runtimeSource(admin, tenantId));
        Instant expiresAt = Instant.now().plusSeconds(20);

        var request = request(tenantId, person, UUID.randomUUID());
        var proof = proof(request, 51, 61, Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK,
                null);
        var result = repository.resolveIdentity(
                request, proof, signingKey(keyOne), expiresAt, "b".repeat(64));
        assertThat(result.authorityCurrent()).isTrue();
        assertThat(result.requester().requesterUserId()).isEqualTo(userId);
        assertThat(result.requester().requesterPersonPublicId()).isEqualTo(person);
        assertThat(result.requester().accountPlane())
                .isEqualTo(Hrm013RequesterIdentityAuthorityV1.AccountPlane.TENANT);
        assertThat(admit(request, proof, result).status())
                .isEqualTo(Hrm013RequesterIdentityAuthorityV1.Status.ADMIT);

        var crossTenant = request(tenantId, otherPerson, UUID.randomUUID());
        assertDenied(repository.resolveIdentity(crossTenant,
                proof(crossTenant, 51, 61,
                        Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null),
                signingKey(keyOne), expiresAt, "c".repeat(64)));

        JdbcTemplate owner = new JdbcTemplate(admin);
        for (String state : List.of("INACTIVE", "SUSPENDED")) {
            owner.update("UPDATE com_users SET status = ? WHERE tenant_id = ? AND user_id = ?",
                    state, tenantId, userId);
            assertDenied(repository.resolveIdentity(request, proof, signingKey(keyOne),
                    expiresAt, "d".repeat(64)));
        }
        UUID providerPerson = UUID.randomUUID();
        createUser(admin, tenantId, providerPerson, "ACTIVE", "PROVIDER");
        var providerRequest = request(tenantId, providerPerson, UUID.randomUUID());
        assertDenied(repository.resolveIdentity(providerRequest,
                proof(providerRequest, 51, 61,
                        Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null),
                signingKey(keyOne), expiresAt, "e".repeat(64)));
    }

    @Test
    void concurrentRequesterDrainIsGapFreeAndUsesDatabaseRecordedExpiry() throws Exception {
        long tenantId = 7_203L;
        PGSimpleDataSource admin = adminSource();
        createTenant(admin, tenantId);
        UUID grant = seedGrant(admin, tenantId, 1, 71, 81, null, "ADMIT", keyOne);
        UUID person = UUID.randomUUID();
        createUser(admin, tenantId, person, "ACTIVE", "TENANT");
        Instant effectiveAt = Instant.now().minusSeconds(3).truncatedTo(ChronoUnit.MICROS);
        UUID fence = seedRequesterFence(admin, tenantId, grant, effectiveAt);
        var repository = repository(runtimeSource(admin, tenantId));
        Instant proofExpiry = Instant.now().plusSeconds(20);
        var executor = Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        UUID firstCorrelation = UUID.randomUUID();
        UUID secondCorrelation = UUID.randomUUID();
        try {
            var first = executor.submit(() -> {
                start.await();
                return drain(repository, tenantId, person, firstCorrelation, effectiveAt,
                        71, 81, proofExpiry, "1".repeat(64));
            });
            var second = executor.submit(() -> {
                start.await();
                return drain(repository, tenantId, person, secondCorrelation, effectiveAt,
                        71, 81, proofExpiry, "2".repeat(64));
            });
            start.countDown();
            Map<UUID, Hrm013RequesterIdentityAuthorityRepository.DirectoryResult> results = Map.of(
                    firstCorrelation, first.get(10, TimeUnit.SECONDS),
                    secondCorrelation, second.get(10, TimeUnit.SECONDS));
            assertThat(results.values()).allSatisfy(result -> {
                assertThat(result.authorityCurrent()).isTrue();
                assertThat(result.requester()).isNotNull();
                assertThat(result.drainAuthorizationExpiresAt()).isAfter(result.databaseNow());
            });

            JdbcTemplate owner = new JdbcTemplate(admin);
            List<Activity> activities = owner.query("""
                    SELECT activity_revision, correlation_id, recorded_at,
                           service_workload_id, binding_id, caller_workload_id,
                           owner_workload_id, purpose, tenant_grant_revision,
                           authorization_revision
                      FROM auth_hris_service_grant_drain_activities
                     WHERE tenant_id = ? AND quiesce_fence_id = ?
                       AND attesting_service_role = 'OWNER'
                     ORDER BY activity_revision
                    """, (rs, row) -> new Activity(rs.getLong(1),
                    rs.getObject(2, UUID.class), rs.getTimestamp(3).toInstant(),
                    rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                    rs.getString(8), rs.getLong(9), rs.getLong(10)), tenantId, fence);
            assertThat(activities).extracting(Activity::revision).containsExactly(1L, 2L);
            assertThat(activities).allSatisfy(activity -> {
                assertThat(activity.serviceWorkloadId()).isEqualTo("dwp-auth-server");
                assertThat(activity.bindingId()).isEqualTo("SVC-AUT-HRM-013");
                assertThat(activity.callerWorkloadId()).isEqualTo("dwp-approval-server");
                assertThat(activity.ownerWorkloadId()).isEqualTo("dwp-auth-server");
                assertThat(activity.purpose())
                        .isEqualTo("HRIS_SEPARATION_REQUESTER_IDENTITY_RESOLVE");
                assertThat(activity.tenantRevision()).isEqualTo(71);
                assertThat(activity.authorizationRevision()).isEqualTo(81);
                assertThat(results.get(activity.correlationId()).drainAuthorizationExpiresAt())
                        .isEqualTo(activity.recordedAt().plusSeconds(30));
            });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void quiesceAndEmergencyRevokeFailClosedForRequesterWork() throws Exception {
        long tenantId = 7_204L;
        PGSimpleDataSource admin = adminSource();
        createTenant(admin, tenantId);
        UUID grant = seedGrant(admin, tenantId, 1, 91, 101, null, "ADMIT", keyOne);
        UUID person = UUID.randomUUID();
        createUser(admin, tenantId, person, "ACTIVE", "TENANT");
        Instant effectiveAt = Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.MICROS);
        seedRequesterFence(admin, tenantId, grant, effectiveAt);
        var repository = repository(runtimeSource(admin, tenantId));
        Instant expiresAt = Instant.now().plusSeconds(20);
        var newWorkRequest = request(tenantId, person, UUID.randomUUID());
        assertDenied(repository.resolveIdentity(newWorkRequest,
                proof(newWorkRequest, 91, 101,
                        Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null),
                signingKey(keyOne), expiresAt, "3".repeat(64)));

        var mismatchedRootRequest = request(tenantId, person, UUID.randomUUID());
        var mismatchedRootProof = proof(mismatchedRootRequest, 91, 101,
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                drainClaims(UUID.randomUUID(), effectiveAt.minusSeconds(1)));
        assertThatThrownBy(() -> repository.resolveIdentity(
                mismatchedRootRequest, mismatchedRootProof, signingKey(keyOne),
                expiresAt, "9".repeat(64)))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
        assertThat(new JdbcTemplate(admin).queryForObject("""
                SELECT count(*) FROM auth_hris_service_grant_drain_activities
                 WHERE tenant_id = ?
                """, Integer.class, tenantId)).isZero();

        var drainRequest = request(tenantId, person, UUID.randomUUID());
        var drainProof = proof(drainRequest, 91, 101,
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                drainClaims(drainRequest.approvalRequestId(), effectiveAt.minusSeconds(1)));
        assertThat(repository.resolveIdentity(drainRequest, drainProof, signingKey(keyOne),
                expiresAt, "4".repeat(64)).authorityCurrent()).isTrue();

        var overlongRequest = request(tenantId, person, UUID.randomUUID());
        var overlongProof = proof(overlongRequest, 91, 101,
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                drainClaims(overlongRequest.approvalRequestId(), effectiveAt.minusSeconds(1)));
        assertThatThrownBy(() -> repository.resolveIdentity(overlongRequest, overlongProof,
                signingKey(keyOne), Instant.now().plusSeconds(40), "a".repeat(64)))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
        assertThat(new JdbcTemplate(admin).queryForObject("""
                SELECT count(*) FROM auth_hris_service_grant_drain_activities
                 WHERE tenant_id = ? AND attesting_service_role = 'OWNER'
                """, Integer.class, tenantId)).isOne();

        seedGrant(admin, tenantId, 2, 92, 102, grant, "REVOKE", keyOne);
        assertDenied(repository.resolveIdentity(drainRequest, drainProof, signingKey(keyOne),
                expiresAt, "5".repeat(64)));
    }

    @Test
    void requesterRecheckRejectsSameKidWithDifferentRsaVector() throws Exception {
        long tenantId = 7_205L;
        PGSimpleDataSource admin = adminSource();
        createTenant(admin, tenantId);
        UUID old = seedGrant(admin, tenantId, 1, 111, 121, null, "ADMIT", keyOne);
        UUID person = UUID.randomUUID();
        createUser(admin, tenantId, person, "ACTIVE", "TENANT");
        seedGrant(admin, tenantId, 2, 112, 122, old, "ADMIT", keyTwo);
        var repository = repository(runtimeSource(admin, tenantId));
        var request = request(tenantId, person, UUID.randomUUID());
        var proof = proof(request, 112, 122,
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null);

        assertDenied(repository.resolveIdentity(request, proof, signingKey(keyOne),
                Instant.now().plusSeconds(20), "6".repeat(64)));
        assertThat(repository.resolveIdentity(request, proof, signingKey(keyTwo),
                Instant.now().plusSeconds(20), "7".repeat(64)).authorityCurrent()).isTrue();
    }

    @Test
    void blockedDirectoryReadCannotAdmitAfterProofExpiry() throws Exception {
        long tenantId = 7_206L;
        PGSimpleDataSource admin = adminSource();
        createTenant(admin, tenantId);
        seedGrant(admin, tenantId, 1, 131, 141, null, "ADMIT", keyOne);
        UUID person = UUID.randomUUID();
        long userId = createUser(admin, tenantId, person, "ACTIVE", "TENANT");
        var repository = repository(runtimeSource(admin, tenantId));
        var request = request(tenantId, person, UUID.randomUUID());
        var proof = proof(request, 131, 141,
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, null);
        var executor = Executors.newSingleThreadExecutor();

        try (Connection blocker = admin.getConnection()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock = blocker.prepareStatement(
                    "SELECT user_id FROM com_users WHERE tenant_id = ? AND user_id = ? FOR UPDATE")) {
                lock.setLong(1, tenantId);
                lock.setLong(2, userId);
                lock.executeQuery();
            }
            Instant expiresAt = Instant.now().plusMillis(900);
            var future = executor.submit(() -> repository.resolveIdentity(
                    request, proof, signingKey(keyOne), expiresAt, "8".repeat(64)));
            assertThatThrownBy(() -> future.get(4, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .satisfies(error -> assertThat(error.getCause())
                            .isInstanceOf(Hrm013SafeErrorV1.Failure.class));
            blocker.rollback();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void duplicateRequesterReplayIsDeniedAndRollsBackTheOtherDigest() {
        long tenantId = 7_207L;
        PGSimpleDataSource admin = adminSource();
        createTenant(admin, tenantId);
        var repository = repository(runtimeSource(admin, tenantId));
        Instant expiry = Instant.now().plusSeconds(30);
        var first = new Hrm013WorkloadProofV1.ReplayReservation(
                tenantId, "SVC-AUT-HRM-013", "dwp-approval-server",
                "a".repeat(64), "b".repeat(64), expiry);
        var duplicate = new Hrm013WorkloadProofV1.ReplayReservation(
                tenantId, "SVC-AUT-HRM-013", "dwp-approval-server",
                "c".repeat(64), "b".repeat(64), expiry);

        assertThat(repository.reserveReplay(first)).isTrue();
        assertThat(repository.reserveReplay(duplicate)).isFalse();
        JdbcTemplate owner = new JdbcTemplate(admin);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM auth_hris_service_authority_proof_replays
                 WHERE tenant_id = ?
                """, Integer.class, tenantId)).isEqualTo(2);
        assertThat(owner.queryForObject("""
                SELECT count(*) FROM auth_hris_service_authority_proof_replays
                 WHERE tenant_id = ? AND token_digest = ?
                """, Integer.class, tenantId, "c".repeat(64))).isZero();
    }

    private static Hrm013RequesterIdentityAuthorityRepository.DirectoryResult drain(
            Hrm013RequesterIdentityAuthorityRepository repository, long tenantId, UUID person,
            UUID correlation, Instant effectiveAt, long tenantRevision,
            long authorizationRevision, Instant expiresAt, String proofSha256) {
        var request = request(tenantId, person, correlation);
        var proof = proof(request, tenantRevision, authorizationRevision,
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING,
                drainClaims(request.approvalRequestId(), effectiveAt.minusSeconds(1)));
        return repository.resolveIdentity(
                request, proof, signingKey(keyOne), expiresAt, proofSha256);
    }

    private static Hrm013RequesterIdentityAuthorityV1.ResolveResponse admit(
            Hrm013RequesterIdentityAuthorityV1.ResolveRequest request,
            Hrm013RequesterIdentityAuthorityV1.ProofBinding proof,
            Hrm013RequesterIdentityAuthorityRepository.DirectoryResult result) {
        var bound = new Hrm013RequesterIdentityAuthorityV1.ProofBinding(
                proof.bindingId(), proof.callerWorkloadId(), proof.ownerWorkloadId(),
                proof.purpose(), proof.tenantId(), proof.approvalRequestId(),
                proof.requestedByActorPublicId(), proof.correlationId(),
                proof.tenantGrantRevision(), proof.authorizationRevision(),
                proof.grantUseMode(), proof.drainClaims(), proof.enclosingApprovalRequestedAt(),
                proof.enclosingApprovalPayloadSha256(), result.drainAuthorizationExpiresAt(),
                result.authorityCurrent());
        return Hrm013RequesterIdentityAuthorityV1.admit(
                request, bound, result.requester(), result.databaseNow());
    }

    private static void assertDenied(
            Hrm013RequesterIdentityAuthorityRepository.DirectoryResult result) {
        assertThat(result.authorityCurrent()).isFalse();
        assertThat(result.requester()).isNull();
    }

    private static Hrm013RequesterIdentityAuthorityV1.ResolveRequest request(
            long tenantId, UUID person, UUID correlation) {
        return new Hrm013RequesterIdentityAuthorityV1.ResolveRequest(
                tenantId, UUID.randomUUID(), person, correlation);
    }

    private static Hrm013RequesterIdentityAuthorityV1.ProofBinding proof(
            Hrm013RequesterIdentityAuthorityV1.ResolveRequest request,
            long tenantRevision, long authorizationRevision,
            Hrm013WorkloadProofV1.GrantUseMode mode,
            Hrm013WorkloadProofV1.DrainClaims drain) {
        return new Hrm013RequesterIdentityAuthorityV1.ProofBinding(
                "SVC-AUT-HRM-013", "dwp-approval-server", "dwp-auth-server",
                "HRIS_SEPARATION_REQUESTER_IDENTITY_RESOLVE", request.tenantId(),
                request.approvalRequestId(), request.requestedByActorPublicId(),
                request.correlationId(), tenantRevision, authorizationRevision, mode, drain,
                drain == null ? Instant.now() : drain.drainRootCreatedAt(), PAYLOAD, null, true);
    }

    private static Hrm013WorkloadProofV1.DrainClaims drainClaims(
            UUID approvalRequestId, Instant rootCreatedAt) {
        return new Hrm013WorkloadProofV1.DrainClaims(approvalRequestId, rootCreatedAt, PAYLOAD,
                Hrm013WorkloadProofV1.DrainContinuationKind.DEPENDENT_READ,
                approvalRequestId, 0, PAYLOAD);
    }

    private static VerifiedSigningKey signingKey(KeyPair pair) {
        return new VerifiedSigningKey(pair.getPublic(), KEY_ID, "dwp-approval-server");
    }

    private static Hrm013RequesterIdentityAuthorityRepository repository(DataSource source) {
        return new Hrm013RequesterIdentityAuthorityRepository(new JdbcTemplate(source),
                new DataSourceTransactionManager(source), new ObjectMapper());
    }

    private static UUID seedGrant(PGSimpleDataSource source, long tenantId,
            long grantRevision, long tenantRevision, long authorizationRevision,
            UUID predecessor, String decision, KeyPair requesterKey) throws Exception {
        UUID grantSetId = UUID.randomUUID();
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO auth_hris_service_grant_sets (
                      grant_set_id, tenant_id, grant_revision, tenant_grant_revision,
                      authorization_revision, decision, supersedes_grant_set_id,
                      rotation_readiness_digest, incident_evidence_digest,
                      grant_digest, recorded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, transaction_timestamp())
                    """)) {
                statement.setObject(1, grantSetId);
                statement.setLong(2, tenantId);
                statement.setLong(3, grantRevision);
                statement.setLong(4, tenantRevision);
                statement.setLong(5, authorizationRevision);
                statement.setString(6, decision);
                statement.setObject(7, predecessor);
                statement.setString(8, grantRevision == 1 || "REVOKE".equals(decision)
                        ? null : "9".repeat(64));
                statement.setString(9, "REVOKE".equals(decision) ? "8".repeat(64) : null);
                statement.setString(10, "7".repeat(64));
                statement.executeUpdate();
            }
            insertBinding(connection, tenantId, grantSetId,
                    HrisServiceGrantQuiesceService.WAIVER_REQUEST, keyOne, "people-key");
            insertBinding(connection, tenantId, grantSetId,
                    HrisServiceGrantQuiesceService.WAIVER_DECISION, requesterKey, KEY_ID);
            insertBinding(connection, tenantId, grantSetId,
                    HrisServiceGrantQuiesceService.REQUESTER, requesterKey, KEY_ID);
            connection.commit();
        }
        return grantSetId;
    }

    private static void insertBinding(Connection connection, long tenantId, UUID grantSetId,
            com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1.TargetTuple target,
            KeyPair callerKey, String callerKeyId) throws Exception {
        String ownerKeyId = target.ownerWorkloadId().equals("dwp-auth-server")
                ? "auth-key" : "people-owner-key";
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO auth_hris_service_grant_bindings (
                  grant_binding_id, tenant_id, grant_set_id, binding_id,
                  caller_workload_id, caller_spiffe_id, caller_signing_key_id,
                  caller_signing_public_jwk, owner_workload_id, owner_spiffe_id,
                  owner_signing_key_id, owner_signing_public_jwk, purpose, binding_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setLong(2, tenantId);
            statement.setObject(3, grantSetId);
            statement.setString(4, target.bindingId());
            statement.setString(5, target.callerWorkloadId());
            statement.setString(6, "spiffe://dwp/service/" + target.callerWorkloadId());
            statement.setString(7, callerKeyId);
            statement.setString(8, jwk(callerKey, callerKeyId));
            statement.setString(9, target.ownerWorkloadId());
            statement.setString(10, "spiffe://dwp/service/" + target.ownerWorkloadId());
            statement.setString(11, ownerKeyId);
            statement.setString(12, jwk(keyTwo, ownerKeyId));
            statement.setString(13, target.purpose());
            statement.setString(14, "6".repeat(64));
            statement.executeUpdate();
        }
    }

    private static UUID seedRequesterFence(PGSimpleDataSource source, long tenantId,
            UUID oldGrant, Instant effectiveAt) {
        UUID fence = UUID.randomUUID();
        var target = HrisServiceGrantQuiesceService.REQUESTER;
        new JdbcTemplate(source).update("""
                INSERT INTO auth_hris_service_grant_quiesce_fences (
                  quiesce_fence_id, tenant_id, old_grant_set_id, binding_id,
                  caller_workload_id, owner_workload_id, purpose, quiesce_revision,
                  lifecycle_state, effective_at, successor_command_correlation_id,
                  fence_digest, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'QUIESCING', ?, ?, ?, transaction_timestamp())
                """, fence, tenantId, oldGrant, target.bindingId(), target.callerWorkloadId(),
                target.ownerWorkloadId(), target.purpose(), Timestamp.from(effectiveAt),
                UUID.randomUUID(), "5".repeat(64));
        return fence;
    }

    private static long createUser(PGSimpleDataSource source, long tenantId, UUID person,
            String status, String plane) {
        return new JdbcTemplate(source).queryForObject("""
                INSERT INTO com_users (
                  tenant_id, display_name, email, status, identity_plane,
                  person_public_id, version, access_revision)
                VALUES (?, ?, ?, ?, ?, ?, 0, 0) RETURNING user_id
                """, Long.class, tenantId, "Requester " + person,
                person + "@requester.test", status, plane, person);
    }

    private static void createTenant(PGSimpleDataSource source, long tenantId) {
        new JdbcTemplate(source).update(
                "INSERT INTO com_tenants (tenant_id, code, name) VALUES (?, ?, ?)",
                tenantId, "hrm013-requester-" + tenantId, "HRM013 Requester " + tenantId);
    }

    private static PGSimpleDataSource runtimeSource(PGSimpleDataSource admin, long tenantId) {
        String role = "hrm013_requester_" + tenantId;
        JdbcTemplate jdbc = new JdbcTemplate(admin);
        jdbc.execute("CREATE ROLE " + role
                + " LOGIN PASSWORD 'runtime-secret' NOSUPERUSER NOBYPASSRLS");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + role);
        jdbc.execute("GRANT SELECT ON auth_hris_service_grant_sets, "
                + "auth_hris_service_grant_bindings, auth_hris_service_grant_quiesce_fences, "
                + "com_users TO " + role);
        jdbc.execute("GRANT UPDATE (grant_set_id) ON auth_hris_service_grant_sets TO " + role);
        jdbc.execute("GRANT UPDATE (grant_binding_id) ON auth_hris_service_grant_bindings TO "
                + role);
        jdbc.execute("GRANT UPDATE (quiesce_fence_id) ON "
                + "auth_hris_service_grant_quiesce_fences TO " + role);
        jdbc.execute("GRANT UPDATE (user_id) ON com_users TO " + role);
        jdbc.execute("GRANT SELECT, INSERT ON auth_hris_service_grant_drain_activities TO "
                + role);
        jdbc.execute("GRANT SELECT, INSERT ON auth_hris_service_authority_proof_replays TO "
                + role);
        jdbc.execute("GRANT USAGE, SELECT ON "
                + "auth_hris_service_authority_proof_replays_proof_replay_id_seq TO " + role);
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(role);
        source.setPassword("runtime-secret");
        return source;
    }

    private static PGSimpleDataSource adminSource() {
        var source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        return source;
    }

    private static String jwk(KeyPair pair, String keyId) {
        RSAPublicKey key = (RSAPublicKey) pair.getPublic();
        return """
                {"alg":"RS256","e":"%s","kid":"%s","kty":"RSA","n":"%s","use":"sig"}
                """.formatted(unsigned(key.getPublicExponent()), keyId,
                unsigned(key.getModulus())).strip();
    }

    private static String unsigned(BigInteger value) {
        byte[] encoded = value.toByteArray();
        if (encoded.length > 1 && encoded[0] == 0) {
            encoded = java.util.Arrays.copyOfRange(encoded, 1, encoded.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encoded);
    }

    private record Activity(long revision, UUID correlationId, Instant recordedAt,
            String serviceWorkloadId, String bindingId, String callerWorkloadId,
            String ownerWorkloadId, String purpose, long tenantRevision,
            long authorizationRevision) { }
}
