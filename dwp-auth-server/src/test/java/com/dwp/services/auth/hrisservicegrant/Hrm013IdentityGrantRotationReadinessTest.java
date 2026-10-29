package com.dwp.services.auth.hrisservicegrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.GrantBinding;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.SuccessorGrant;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class Hrm013IdentityGrantRotationReadinessTest {
    private static final String RUNTIME_ROLE = "auth_v272_readiness_runtime";
    private static final long TENANT = 7_213L;
    private static final long TENANT_REVISION = 31L;
    private static final long AUTHORIZATION_REVISION = 41L;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void migrate() throws Exception {
        try (Connection connection = adminSource().getConnection();
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + RUNTIME_ROLE
                    + " LOGIN PASSWORD 'readiness-runtime' NOSUPERUSER NOBYPASSRLS");
        }
        Flyway.configure().dataSource(adminSource())
                .locations("filesystem:src/main/resources/db/migration")
                .target("272").load().migrate();
    }

    @Test
    void authComplementsPeopleReadinessAndCompletesTheActualIdentityPairCutover()
            throws Exception {
        PGSimpleDataSource admin = adminSource();
        JdbcTemplate jdbc = new JdbcTemplate(admin);
        jdbc.update("INSERT INTO com_tenants(tenant_id,code,name) VALUES(?,?,?)",
                TENANT, "hrm013-readiness-" + TENANT, "HRM013 readiness");

        KeyPair dispatchCaller = rsa();
        KeyPair dispatchOwner = rsa();
        KeyPair receiptCaller = rsa();
        KeyPair receiptOwner = rsa();
        GrantBinding dispatchBinding = binding(
                HrisServiceGrantQuiesceService.IDENTITY_DISPATCH,
                "people-dispatch-v1", dispatchCaller,
                "auth-dispatch-v1", dispatchOwner);
        GrantBinding receiptBinding = binding(
                HrisServiceGrantQuiesceService.IDENTITY_RECEIPT,
                "auth-receipt-v1", receiptCaller,
                "people-receipt-v1", receiptOwner);
        UUID oldGrant = seedGrant(admin, List.of(dispatchBinding, receiptBinding));

        var repository = new HrisServiceGrantAuthorityRepository(
                jdbc, new DataSourceTransactionManager(admin), JSON);
        var quiesce = new HrisServiceGrantQuiesceService(repository);
        Instant effectiveAt = databaseNow(jdbc).minusSeconds(70)
                .truncatedTo(ChronoUnit.MICROS);
        seedFence(jdbc, oldGrant,
                HrisServiceGrantQuiesceService.IDENTITY_DISPATCH, effectiveAt);
        seedFence(jdbc, oldGrant,
                HrisServiceGrantQuiesceService.IDENTITY_RECEIPT, effectiveAt);

        HrisServiceGrantAuthorityV1.GrantAuthority dispatchAuthority = authority(
                oldGrant, dispatchBinding, effectiveAt.minusSeconds(5));
        HrisServiceGrantAuthorityV1.GrantAuthority receiptAuthority = authority(
                oldGrant, receiptBinding, effectiveAt.minusSeconds(5));
        AtomicInteger dispatchRechecks = new AtomicInteger();
        AtomicInteger receiptRechecks = new AtomicInteger();
        Hrm013IdentityGrantRotationReadiness.ReadinessAuthority readinessAuthority =
                (query, correlation) -> quiesce.observeLocalReadiness(
                        query, replay(query), digest(UUID.randomUUID()));

        var producer = new Hrm013IdentityGrantRotationReadiness(
                new NamedParameterJdbcTemplate(admin),
                new DataSourceTransactionManager(admin), readinessAuthority,
                Clock.systemUTC(), JSON);
        UUID dispatchCorrelation = UUID.randomUUID();
        var dispatchQuery = readinessQuery(
                HrisServiceGrantQuiesceService.IDENTITY_DISPATCH,
                HrisServiceGrantAuthorityV1.ServiceRole.OWNER,
                dispatchCorrelation);
        ReadinessItem authOwner = assertDoesNotThrow(() -> producer.observe(
                new Hrm013IdentityGrantRotationReadiness.ReadinessRequest(
                        dispatchQuery, dispatchAuthority,
                        HrisServiceGrantAuthorityV1.ServiceRole.OWNER,
                        signer(dispatchOwner, "auth-dispatch-v1", dispatchRechecks,
                                dispatchAuthority,
                                HrisServiceGrantAuthorityV1.ServiceRole.OWNER),
                        dispatchCorrelation)), "Auth OWNER readiness");

        UUID receiptCorrelation = UUID.randomUUID();
        var receiptQuery = readinessQuery(
                HrisServiceGrantQuiesceService.IDENTITY_RECEIPT,
                HrisServiceGrantAuthorityV1.ServiceRole.CALLER,
                receiptCorrelation);
        ReadinessItem authCaller = assertDoesNotThrow(() -> producer.observe(
                new Hrm013IdentityGrantRotationReadiness.ReadinessRequest(
                        receiptQuery, receiptAuthority,
                        HrisServiceGrantAuthorityV1.ServiceRole.CALLER,
                        signer(receiptCaller, "auth-receipt-v1", receiptRechecks,
                                receiptAuthority,
                                HrisServiceGrantAuthorityV1.ServiceRole.CALLER),
                        receiptCorrelation)), "Auth CALLER readiness");

        ReadinessItem peopleCaller = assertDoesNotThrow(() -> externalComplement(repository,
                HrisServiceGrantQuiesceService.IDENTITY_DISPATCH,
                HrisServiceGrantAuthorityV1.ServiceRole.CALLER,
                dispatchAuthority, dispatchCaller, "people-dispatch-v1"),
                "People CALLER complement");
        ReadinessItem peopleOwner = assertDoesNotThrow(() -> externalComplement(repository,
                HrisServiceGrantQuiesceService.IDENTITY_RECEIPT,
                HrisServiceGrantAuthorityV1.ServiceRole.OWNER,
                receiptAuthority, receiptOwner, "people-receipt-v1"),
                "People OWNER complement");
        List<ReadinessItem> allItems = List.of(
                authOwner, authCaller, peopleCaller, peopleOwner);

        KeyPair dispatchCallerV2 = rsa();
        KeyPair dispatchOwnerV2 = rsa();
        KeyPair receiptCallerV2 = rsa();
        KeyPair receiptOwnerV2 = rsa();
        List<GrantBinding> successorBindings = List.of(
                binding(HrisServiceGrantQuiesceService.IDENTITY_DISPATCH,
                        "people-dispatch-v2", dispatchCallerV2,
                        "auth-dispatch-v2", dispatchOwnerV2),
                binding(HrisServiceGrantQuiesceService.IDENTITY_RECEIPT,
                        "auth-receipt-v2", receiptCallerV2,
                        "people-receipt-v2", receiptOwnerV2));
        UUID successorId = UUID.randomUUID();
        SuccessorGrant successor = new SuccessorGrant(successorId,
                TENANT_REVISION + 1, AUTHORIZATION_REVISION + 1,
                "5".repeat(64), readinessDigest(allItems), successorBindings);

        // A callback authorized after CALLER readiness must invalidate that receipt.
        // Roll the activity back after proving rejection so the original happy path
        // remains an independent cutover proof in this same migrated fixture.
        var transaction = new TransactionTemplate(
                new DataSourceTransactionManager(admin));
        transaction.executeWithoutResult(status -> {
            var callbackDrain = callbackDrainQuery(effectiveAt);
            var admitted = quiesce.authorizeLocalDrain(
                    callbackDrain, replay(callbackDrain), digest(UUID.randomUUID()));
            assertThat(admitted.status())
                    .isEqualTo(HrisServiceGrantAuthorityV1.Status.ADMIT);
            assertThat(admitted.drainAdmission().attestingServiceRole())
                    .isEqualTo(HrisServiceGrantAuthorityV1.ServiceRole.CALLER);
            assertThat(admitted.drainAdmission().drainActivityRevision()).isOne();
            assertThatThrownBy(() -> quiesce.cutover(TENANT,
                    List.of(HrisServiceGrantQuiesceService.IDENTITY_DISPATCH),
                    allItems, successor, UUID.randomUUID()))
                    .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_hris_service_grant_drain_activities
                 WHERE tenant_id=? AND binding_id=?
                   AND attesting_service_role='CALLER'
                """, Integer.class, TENANT,
                HrisServiceGrantQuiesceService.IDENTITY_RECEIPT.bindingId()))
                .isZero();

        var result = assertDoesNotThrow(() -> quiesce.cutover(TENANT,
                List.of(HrisServiceGrantQuiesceService.IDENTITY_DISPATCH),
                allItems, successor, UUID.randomUUID()),
                "identity-pair cutover");

        assertThat(result.grantSetId()).isEqualTo(successorId);
        assertThat(result.fenceIds()).hasSize(2);
        assertThat(dispatchRechecks).hasValue(3);
        assertThat(receiptRechecks).hasValue(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_hrm013_identity_task_audit_events
                 WHERE tenant_id=? AND action_code='ROTATION_READINESS_OBSERVED'
                """, Integer.class, TENANT)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM auth_hris_service_authority_proof_replays
                 WHERE tenant_id=? AND proof_scope='ROTATION_READINESS'
                """, Integer.class, TENANT)).isEqualTo(8);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE auth_hrm013_identity_task_audit_events
                   SET outcome_code='FAILED'
                 WHERE tenant_id=? AND action_code='ROTATION_READINESS_OBSERVED'
                """, TENANT)).hasMessageContaining("append-only");
    }

    private static Hrm013IdentityGrantRotationReadiness.ReadinessSigner signer(
            KeyPair pair, String keyId, AtomicInteger rechecks,
            HrisServiceGrantAuthorityV1.GrantAuthority expected,
            HrisServiceGrantAuthorityV1.ServiceRole expectedRole) {
        return new Hrm013IdentityGrantRotationReadiness.ReadinessSigner() {
            @Override
            public String keyId() {
                return keyId;
            }

            @Override
            public byte[] sign(byte[] exactInput) throws Exception {
                Signature signature = Signature.getInstance("SHA256withRSA");
                signature.initSign(pair.getPrivate());
                signature.update(exactInput);
                return signature.sign();
            }

            @Override
            public void recheckCurrent(
                    HrisServiceGrantAuthorityV1.GrantAuthority authority,
                    HrisServiceGrantAuthorityV1.ServiceRole role) {
                assertThat(authority).isEqualTo(expected);
                assertThat(role).isEqualTo(expectedRole);
                rechecks.incrementAndGet();
            }
        };
    }

    private static ReadinessItem externalComplement(
            HrisServiceGrantAuthorityRepository repository,
            HrisServiceGrantAuthorityV1.TargetTuple target,
            HrisServiceGrantAuthorityV1.ServiceRole role,
            HrisServiceGrantAuthorityV1.GrantAuthority authority,
            KeyPair signer, String keyId) {
        UUID correlation = UUID.randomUUID();
        var query = readinessQuery(target, role, correlation);
        var response = repository.resolve(query, peopleResolverReplay(query),
                digest(UUID.randomUUID()));
        assertThat(response.status())
                .isEqualTo(HrisServiceGrantAuthorityV1.Status.READINESS_OBSERVED);
        return signItem(response.readinessObservation(), authority, signer, keyId,
                correlation, databaseNow(new JdbcTemplate(adminSource())));
    }

    private static HrisServiceGrantAuthorityV1.ResolveRequest readinessQuery(
            HrisServiceGrantAuthorityV1.TargetTuple target,
            HrisServiceGrantAuthorityV1.ServiceRole role, UUID correlation) {
        String workload = role == HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                ? target.callerWorkloadId() : target.ownerWorkloadId();
        return new HrisServiceGrantAuthorityV1.ResolveRequest(
                TENANT, workload, target.callerWorkloadId(), target.ownerWorkloadId(),
                target.bindingId(), target.purpose(),
                HrisServiceGrantAuthorityV1.ResolutionMode.READINESS_OBSERVE,
                TENANT_REVISION, AUTHORIZATION_REVISION, correlation, null);
    }

    private static HrisServiceGrantAuthorityV1.ResolveRequest callbackDrainQuery(
            Instant effectiveAt) {
        var target = HrisServiceGrantQuiesceService.IDENTITY_RECEIPT;
        var drain = new HrisServiceGrantAuthorityV1.DrainFields(
                UUID.randomUUID(), effectiveAt.minusSeconds(1), "6".repeat(64),
                HrisServiceGrantAuthorityV1.DrainContinuationKind.TERMINAL_CHILD,
                UUID.randomUUID(), 1, "7".repeat(64));
        return new HrisServiceGrantAuthorityV1.ResolveRequest(
                TENANT, target.callerWorkloadId(), target.callerWorkloadId(),
                target.ownerWorkloadId(), target.bindingId(), target.purpose(),
                HrisServiceGrantAuthorityV1.ResolutionMode.DRAIN_EXISTING,
                TENANT_REVISION, AUTHORIZATION_REVISION, UUID.randomUUID(), drain);
    }

    private static ReadinessItem signItem(
            HrisServiceGrantAuthorityV1.ReadinessObservation observation,
            HrisServiceGrantAuthorityV1.GrantAuthority authority,
            KeyPair signer, String keyId, UUID correlation, Instant observedAt) {
        try {
            Instant issuedAt = observedAt.plusSeconds(1).truncatedTo(ChronoUnit.SECONDS);
            Instant expiresAt = issuedAt.plusSeconds(120);
            String workload = observation.attestingServiceRole()
                    == HrisServiceGrantAuthorityV1.ServiceRole.CALLER
                            ? observation.callerWorkloadId()
                            : observation.ownerWorkloadId();
            Map<String, Object> receipt = evidence(observation, authority, workload);
            receipt.put("schemaName", Hrm013IdentityGrantRotationReadiness.SCHEMA);
            receipt.put("schemaVersion", 1);
            receipt.put("pendingCount", 0);
            receipt.put("oldestPendingAt", null);
            receipt.put("observedAt", observedAt.toString());
            receipt.put("expiresAt", expiresAt.toString());
            receipt.put("correlationId", correlation.toString());
            String evidenceSha = Hrm013WorkloadProofV1.sha256(canonical(receipt));
            receipt.put("evidenceSha256", evidenceSha);
            byte[] receiptBytes = canonical(receipt);

            Map<String, Object> claims = evidence(observation, authority, workload);
            claims.put("iss", workload);
            claims.put("aud", "dwp-auth-server");
            claims.put("sub", workload);
            claims.put("iat", issuedAt.getEpochSecond());
            claims.put("nbf", issuedAt.getEpochSecond());
            claims.put("exp", expiresAt.getEpochSecond());
            UUID proofId = UUID.randomUUID();
            UUID nonce;
            do {
                nonce = UUID.randomUUID();
            } while (nonce.equals(proofId));
            claims.put("jti", proofId.toString());
            claims.put("requestNonce", nonce.toString());
            claims.put("evidenceSha256", evidenceSha);
            claims.put("correlationId", correlation.toString());
            claims.put("proofPurpose", Hrm013IdentityGrantRotationReadiness.PROOF_PURPOSE);
            byte[] claimBytes = canonical(claims);
            byte[] header = canonical(Map.of(
                    "alg", "RS256",
                    "typ", Hrm013IdentityGrantRotationReadiness.PROOF_TYPE,
                    "kid", keyId));
            String encodedHeader = encode(header);
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

    private static Map<String, Object> evidence(
            HrisServiceGrantAuthorityV1.ReadinessObservation value,
            HrisServiceGrantAuthorityV1.GrantAuthority authority,
            String workload) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", value.tenantId());
        result.put("bindingId", value.bindingId());
        result.put("callerWorkloadId", value.callerWorkloadId());
        result.put("ownerWorkloadId", value.ownerWorkloadId());
        result.put("bindingPurpose", value.purpose());
        result.put("serviceWorkloadId", workload);
        result.put("attestingServiceRole", value.attestingServiceRole().name());
        result.put("oldGrantSetId", authority.grantSetId().toString());
        result.put("quiesceFenceId", value.quiesceFenceId().toString());
        result.put("quiesceRevision", value.quiesceRevision());
        result.put("quiesceEffectiveAt", value.quiesceEffectiveAt().toString());
        result.put("observedDrainActivityRevision", value.drainActivityRevision());
        result.put("drainQuietSince", value.drainQuietSince().toString());
        result.put("readinessEligibleAt", value.readinessEligibleAt().toString());
        result.put("readinessObservationSha256", value.readinessObservationSha256());
        result.put("observedGrantRevision", authority.grantRevision());
        result.put("tenantGrantRevision", value.tenantGrantRevision());
        result.put("authorizationRevision", value.authorizationRevision());
        return result;
    }

    private static String readinessDigest(List<ReadinessItem> items) throws Exception {
        List<Map<String, String>> receipts = new ArrayList<>();
        for (ReadinessItem item : items) {
            var receipt = JSON.readTree(item.receiptBytes());
            Map<String, String> ordering = new LinkedHashMap<>();
            for (String field : List.of("bindingId", "callerWorkloadId",
                    "ownerWorkloadId", "bindingPurpose", "attestingServiceRole",
                    "serviceWorkloadId", "evidenceSha256")) {
                ordering.put(field, receipt.path(field).asText());
            }
            receipts.add(ordering);
        }
        receipts.sort(java.util.Comparator.comparing(value -> String.join("\n",
                value.get("bindingId"), value.get("callerWorkloadId"),
                value.get("ownerWorkloadId"), value.get("bindingPurpose"),
                value.get("attestingServiceRole"), value.get("serviceWorkloadId"),
                value.get("evidenceSha256"))));
        var evidence = JSON.createArrayNode();
        receipts.forEach(value -> evidence.add(value.get("evidenceSha256")));
        return Hrm013WorkloadProofV1.sha256(
                HrisServiceGrantRotationReadinessVerifier.canonicalBytes(evidence));
    }

    private static UUID seedFence(JdbcTemplate jdbc, UUID grantSetId,
            HrisServiceGrantAuthorityV1.TargetTuple target, Instant effectiveAt) {
        UUID fenceId = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        String fenceDigest = HrisServiceGrantQuiesceService.fenceDigest(
                TENANT, grantSetId, target, 1, "QUIESCING", effectiveAt,
                correlation);
        jdbc.update("""
                INSERT INTO auth_hris_service_grant_quiesce_fences(
                  quiesce_fence_id,tenant_id,old_grant_set_id,binding_id,
                  caller_workload_id,owner_workload_id,purpose,quiesce_revision,
                  lifecycle_state,effective_at,successor_command_correlation_id,
                  fence_digest,recorded_at)
                VALUES(?,?,?,?,?,?,?,1,'QUIESCING',?,?,?,transaction_timestamp())
                """, fenceId, TENANT, grantSetId, target.bindingId(),
                target.callerWorkloadId(), target.ownerWorkloadId(), target.purpose(),
                Timestamp.from(effectiveAt), correlation, fenceDigest);
        return fenceId;
    }

    private static Hrm013WorkloadProofV1.ReplayReservation replay(
            HrisServiceGrantAuthorityV1.ResolveRequest query) {
        return replay(query, query.targetBindingId());
    }

    private static Hrm013WorkloadProofV1.ReplayReservation peopleResolverReplay(
            HrisServiceGrantAuthorityV1.ResolveRequest query) {
        return replay(query, HrisServiceGrantAuthorityV1.bootstrapProfile(
                "SVC-AUT-HRM-011").bindingId());
    }

    private static Hrm013WorkloadProofV1.ReplayReservation replay(
            HrisServiceGrantAuthorityV1.ResolveRequest query, String bindingId) {
        String first = digest(UUID.randomUUID());
        String second;
        do {
            second = digest(UUID.randomUUID());
        } while (second.equals(first));
        return new Hrm013WorkloadProofV1.ReplayReservation(
                query.tenantId(), bindingId, query.requestingWorkloadId(),
                first, second, Instant.now().plusSeconds(30));
    }

    private static String digest(UUID value) {
        return Hrm013WorkloadProofV1.sha256(
                value.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] canonical(Map<String, Object> value) throws Exception {
        return Hrm013WorkloadProofV1.rfc8785CanonicalBytes(
                JSON, JSON.writeValueAsBytes(value));
    }

    private static GrantBinding binding(
            HrisServiceGrantAuthorityV1.TargetTuple target,
            String callerKeyId, KeyPair callerKey,
            String ownerKeyId, KeyPair ownerKey) {
        return new GrantBinding(target,
                "spiffe://dwp/service/" + target.callerWorkloadId(),
                callerKeyId, jwk(callerKey, callerKeyId),
                "spiffe://dwp/service/" + target.ownerWorkloadId(),
                ownerKeyId, jwk(ownerKey, ownerKeyId), "a".repeat(64));
    }

    private static HrisServiceGrantAuthorityV1.GrantAuthority authority(
            UUID grantSetId, GrantBinding binding, Instant recordedAt) {
        return new HrisServiceGrantAuthorityV1.GrantAuthority(
                grantSetId, 1, TENANT, TENANT_REVISION, AUTHORIZATION_REVISION,
                "ADMIT", binding.target().callerWorkloadId(), binding.callerSpiffeId(),
                binding.callerSigningKeyId(), binding.callerSigningPublicJwk(),
                binding.target().ownerWorkloadId(), binding.ownerSpiffeId(),
                binding.ownerSigningKeyId(), binding.ownerSigningPublicJwk(),
                binding.target().bindingId(), binding.target().purpose(), null,
                "4".repeat(64), recordedAt);
    }

    private static UUID seedGrant(PGSimpleDataSource source,
            List<GrantBinding> bindings) throws Exception {
        UUID grantSet = UUID.randomUUID();
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO auth_hris_service_grant_sets(
                      grant_set_id,tenant_id,grant_revision,tenant_grant_revision,
                      authorization_revision,decision,grant_digest,recorded_at)
                    VALUES(?,?,1,?,?,'ADMIT',?,transaction_timestamp())
                    """)) {
                statement.setObject(1, grantSet);
                statement.setLong(2, TENANT);
                statement.setLong(3, TENANT_REVISION);
                statement.setLong(4, AUTHORIZATION_REVISION);
                statement.setString(5, "4".repeat(64));
                statement.executeUpdate();
            }
            for (GrantBinding binding : bindings) {
                insertBinding(connection, grantSet, binding);
            }
            connection.commit();
        }
        return grantSet;
    }

    private static void insertBinding(Connection connection, UUID grantSet,
            GrantBinding value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO auth_hris_service_grant_bindings(
                  grant_binding_id,tenant_id,grant_set_id,binding_id,
                  caller_workload_id,caller_spiffe_id,caller_signing_key_id,
                  caller_signing_public_jwk,owner_workload_id,owner_spiffe_id,
                  owner_signing_key_id,owner_signing_public_jwk,purpose,binding_digest)
                VALUES(?,?,?,?,?,?,?,?::jsonb,?,?,?,?::jsonb,?,?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setLong(2, TENANT);
            statement.setObject(3, grantSet);
            statement.setString(4, value.target().bindingId());
            statement.setString(5, value.target().callerWorkloadId());
            statement.setString(6, value.callerSpiffeId());
            statement.setString(7, value.callerSigningKeyId());
            statement.setString(8, JSON.writeValueAsString(
                    value.callerSigningPublicJwk()));
            statement.setString(9, value.target().ownerWorkloadId());
            statement.setString(10, value.ownerSpiffeId());
            statement.setString(11, value.ownerSigningKeyId());
            statement.setString(12, JSON.writeValueAsString(
                    value.ownerSigningPublicJwk()));
            statement.setString(13, value.target().purpose());
            statement.setString(14, value.bindingDigest());
            statement.executeUpdate();
        }
    }

    private static HrisServiceGrantAuthorityV1.RsaPublicJwk jwk(
            KeyPair pair, String keyId) {
        RSAPublicKey key = (RSAPublicKey) pair.getPublic();
        return new HrisServiceGrantAuthorityV1.RsaPublicJwk(
                "RS256", encode(unsigned(key.getPublicExponent().toByteArray())),
                keyId, "RSA", encode(unsigned(key.getModulus().toByteArray())), "sig");
    }

    private static byte[] unsigned(byte[] value) {
        return value.length > 1 && value[0] == 0
                ? Arrays.copyOfRange(value, 1, value.length) : value;
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2_048);
        return generator.generateKeyPair();
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static Instant databaseNow(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT clock_timestamp()",
                (row, ignored) -> row.getTimestamp(1).toInstant());
    }

    private static PGSimpleDataSource adminSource() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(POSTGRES.getJdbcUrl());
        source.setUser(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        source.setOptions("-c dwp.auth.hrm013_runtime_role=" + RUNTIME_ROLE);
        return source;
    }
}
