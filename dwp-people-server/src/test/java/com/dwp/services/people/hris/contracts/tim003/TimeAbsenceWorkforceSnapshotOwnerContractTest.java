package com.dwp.services.people.hris.contracts.tim003;

import com.dwp.contracts.hris.xcon.v1.Xcon027EmploymentAbsenceSnapshotV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TimeAbsenceWorkforceSnapshotOwnerContractTest {

    private static final UUID RELATIONSHIP =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID WORKER =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID LEGAL_ENTITY =
            UUID.fromString("10000000-0000-4000-8000-000000000003");
    private static final Instant AS_OF = Instant.parse("2026-10-20T15:00:00.123456Z");

    @Test
    void returnsGeneratedXcon027OnlyAfterReplayAuthoritySigningAndAudit() {
        List<String> order = new ArrayList<>();
        TimeAbsenceOwnerContract.VerifiedRequest request = verified(7L);
        TimeAbsenceOwnerContract.OwnerGrant grant = grant();
        TimeAbsenceOwnerContract.OwnerFacts facts = facts(9L);

        TimeAbsenceOwnerService service = service(
                tenant -> {
                    order.add("grant");
                    return Optional.of(grant);
                },
                verified -> {
                    order.add("replay");
                    return true;
                },
                (verified, currentGrant) -> {
                    order.add("facts");
                    return Optional.of(facts);
                },
                signer(order),
                (verified, outcome, digest) -> {
                    order.add("audit");
                    return "SUCCESS".equals(outcome);
                });

        TimeAbsenceOwnerContract.Success success = service.read(request);

        assertThat(success.data()).isInstanceOf(Xcon027EmploymentAbsenceSnapshotV1.class);
        assertThat(success.data().relationshipRevision()).isEqualTo(9L);
        assertThat(success.data().ownerRevision()).isEqualTo(9L);
        assertThat(success.data().effectiveOn()).isEqualTo(LocalDate.parse("2026-10-21"));
        assertThat(success.data().validUntil()).isEqualTo(AS_OF.plusSeconds(300));
        assertThat(success.data().assignments()).extracting(
                        Xcon027EmploymentAbsenceSnapshotV1.EmploymentAbsenceAssignment::assignmentPublicId)
                .containsExactly(
                        UUID.fromString("20000000-0000-4000-8000-000000000001"),
                        UUID.fromString("30000000-0000-4000-8000-000000000001"));
        assertThat(success.data().scopeDigest()).matches("[0-9a-f]{64}");
        assertThat(success.data().contentSha256()).matches("[0-9a-f]{64}");
        assertThat(success.evidence().contentSha256())
                .isEqualTo(success.data().contentSha256());
        assertThat(order).containsExactly("replay", "grant", "facts", "sign", "audit");
    }

    @Test
    void staleAuthorityAndMinimumRevisionReturnNoOwnerFacts() {
        AtomicInteger factsCalls = new AtomicInteger();
        TimeAbsenceOwnerService stale = service(
                tenant -> Optional.of(new TimeAbsenceOwnerContract.OwnerGrant(
                        71L, TimeAbsenceOwnerContract.GrantLifecycle.ACTIVE,
                        TimeAbsenceOwnerContract.CALLER_SPIFFE, "other-key",
                        TimeAbsenceOwnerContract.PURPOSE,
                        TimeAbsenceOwnerContract.PROJECTION, "Asia/Seoul",
                        3L, 4L, 5L)),
                request -> true,
                (request, grant) -> {
                    factsCalls.incrementAndGet();
                    return Optional.of(facts(9L));
                }, signer(new ArrayList<>()),
                (request, outcome, digest) -> true);

        assertThatThrownBy(() -> stale.read(verified(7L)))
                .isInstanceOfSatisfying(TimeAbsenceOwnerContract.Failure.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("OWNER_AUTHORITY_STALE"));
        assertThat(factsCalls).hasValue(0);

        TimeAbsenceOwnerService belowMinimum = service(
                tenant -> Optional.of(grant()), request -> true,
                (request, grant) -> Optional.of(facts(6L)),
                signer(new ArrayList<>()), (request, outcome, digest) -> true);
        assertThatThrownBy(() -> belowMinimum.read(verified(7L)))
                .isInstanceOfSatisfying(TimeAbsenceOwnerContract.Failure.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("OWNER_REVISION_BELOW_MINIMUM"));
    }

    @Test
    void classifiesInfrastructureRuntimeAsRetryable503AndOwnerValidationAs422() {
        List<TimeAbsenceOwnerService> outages = List.of(
                service(tenant -> Optional.of(grant()), request -> {
                    throw new IllegalStateException("replay store unavailable");
                }, (request, grant) -> Optional.of(facts(9L)),
                        signer(new ArrayList<>()), (request, outcome, digest) -> true),
                service(tenant -> {
                    throw new IllegalStateException("authority store unavailable");
                }, request -> true, (request, grant) -> Optional.of(facts(9L)),
                        signer(new ArrayList<>()), (request, outcome, digest) -> true),
                service(tenant -> Optional.of(grant()), request -> true,
                        (request, grant) -> {
                            throw new IllegalStateException("owner database unavailable");
                        }, signer(new ArrayList<>()),
                        (request, outcome, digest) -> true),
                service(tenant -> Optional.of(grant()), request -> true,
                        (request, grant) -> Optional.of(facts(9L)), runtimeSigner(),
                        (request, outcome, digest) -> true),
                service(tenant -> Optional.of(grant()), request -> true,
                        (request, grant) -> Optional.of(facts(9L)),
                        signer(new ArrayList<>()), (request, outcome, digest) -> {
                            throw new IllegalStateException("audit store unavailable");
                        }));
        for (TimeAbsenceOwnerService outage : outages) {
            assertThatThrownBy(() -> outage.read(verified(7L)))
                    .isInstanceOfSatisfying(TimeAbsenceOwnerContract.Failure.class,
                            failure -> {
                                assertThat(failure.status()).isEqualTo(503);
                                assertThat(failure.code())
                                        .isEqualTo("OWNER_TEMPORARILY_UNAVAILABLE");
                                assertThat(failure.retryable()).isTrue();
                            });
        }

        TimeAbsenceOwnerService invalidOwnerData = service(
                tenant -> Optional.of(grant()), request -> true,
                (request, grant) -> {
                    throw new IllegalArgumentException("invalid owner row");
                }, signer(new ArrayList<>()), (request, outcome, digest) -> true);
        assertThatThrownBy(() -> invalidOwnerData.read(verified(7L)))
                .isInstanceOfSatisfying(TimeAbsenceOwnerContract.Failure.class,
                        failure -> {
                            assertThat(failure.status()).isEqualTo(422);
                            assertThat(failure.code()).isEqualTo("OWNER_CONTRACT_INVALID");
                            assertThat(failure.retryable()).isFalse();
                        });
    }

    @Test
    void replayCollisionPerformsZeroAuthorityAndOwnerFactReads() {
        AtomicInteger grantCalls = new AtomicInteger();
        AtomicInteger factCalls = new AtomicInteger();
        TimeAbsenceOwnerService service = service(
                tenant -> {
                    grantCalls.incrementAndGet();
                    return Optional.of(grant());
                }, request -> false,
                (request, grant) -> {
                    factCalls.incrementAndGet();
                    return Optional.of(facts(9L));
                }, signer(new ArrayList<>()), (request, outcome, digest) -> true);

        assertThatThrownBy(() -> service.read(verified(7L)))
                .isInstanceOfSatisfying(TimeAbsenceOwnerContract.Failure.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("INVALID_WORKLOAD_PROOF"));
        assertThat(grantCalls).hasValue(0);
        assertThat(factCalls).hasValue(0);
    }

    @Test
    void replayPairCollisionRollsBackBothSharedInboxReservations() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        OffsetDateTime databaseInstant = OffsetDateTime.parse(
                "2026-10-20T15:00:00.123456Z");
        when(jdbc.queryForObject(
                eq("SELECT pg_catalog.transaction_timestamp()"),
                anyMap(), eq(OffsetDateTime.class))).thenReturn(databaseInstant);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(1, 0);
        JdbcTimeAbsenceReplayReservation reservation =
                new JdbcTimeAbsenceReplayReservation(
                        jdbc, transactions, new TimeAbsenceOwnerCanonicalJson(
                                new ObjectMapper().findAndRegisterModules()));

        assertThatThrownBy(() -> reservation.reserve(verified(7L)))
                .isInstanceOfSatisfying(TimeAbsenceOwnerContract.Failure.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("INVALID_WORKLOAD_PROOF"));

        ArgumentCaptor<MapSqlParameterSource> parameters =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc, times(2)).update(anyString(), parameters.capture());
        Object digest = parameters.getAllValues().getFirst()
                .getValue("payloadSha256");
        assertThat(parameters.getAllValues()).extracting(
                        value -> value.getValue("payloadSha256"))
                .containsOnly(digest);
        assertThat(parameters.getAllValues()).extracting(value -> value.getValue("at"))
                .containsOnly(databaseInstant);
        verify(transactions).rollback(status);
        verify(transactions, never()).commit(status);
    }

    @Test
    void rejectsSuccessEvidenceDigestAndJwsPayloadMismatch() {
        for (TimeAbsenceOwnerPorts.ResponseSigner malformed : List.of(
                malformedSigner(true, false), malformedSigner(false, true))) {
            TimeAbsenceOwnerService service = service(
                    tenant -> Optional.of(grant()), request -> true,
                    (request, grant) -> Optional.of(facts(9L)), malformed,
                    (request, outcome, digest) -> true);

            assertThatThrownBy(() -> service.read(verified(7L)))
                    .isInstanceOfSatisfying(TimeAbsenceOwnerContract.Failure.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("OWNER_TEMPORARILY_UNAVAILABLE"));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void providerSetsExactTrustedTenantBeforeAnyOwnerFactQuery() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(status);
        when(jdbc.queryForObject(contains("set_config"),
                any(MapSqlParameterSource.class), eq(String.class))).thenReturn("71");
        when(jdbc.query(contains("configuration"), any(MapSqlParameterSource.class),
                any(ResultSetExtractor.class)))
                .thenThrow(TimeAbsenceOwnerContract.Failure.unavailable());
        JdbcTimeAbsenceOwnerFactProvider provider = new JdbcTimeAbsenceOwnerFactProvider(
                jdbc, transactions, Clock.fixed(AS_OF, ZoneOffset.UTC));

        assertThatThrownBy(() -> provider.readCurrent(verified(7L), grant()))
                .isInstanceOf(TimeAbsenceOwnerContract.Failure.class);

        ArgumentCaptor<TransactionDefinition> definition =
                ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        assertThat(definition.getValue().getIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(definition.getValue().isReadOnly()).isTrue();
        InOrder order = inOrder(jdbc);
        order.verify(jdbc).queryForObject(contains("set_config"),
                any(MapSqlParameterSource.class), eq(String.class));
        order.verify(jdbc).query(contains("configuration"),
                any(MapSqlParameterSource.class), any(ResultSetExtractor.class));
    }

    private static TimeAbsenceOwnerService service(
            TimeAbsenceOwnerPorts.OwnerGrantVerifierAuthority grants,
            TimeAbsenceOwnerPorts.ReplayReservation replay,
            TimeAbsenceOwnerPorts.OwnerFactProvider facts,
            TimeAbsenceOwnerPorts.ResponseSigner signer,
            TimeAbsenceOwnerPorts.Audit audit) {
        return new TimeAbsenceOwnerService(grants, replay, facts, signer, audit,
                new TimeAbsenceOwnerCanonicalJson(
                        new ObjectMapper().findAndRegisterModules()),
                Clock.fixed(AS_OF, ZoneOffset.UTC));
    }

    private static TimeAbsenceOwnerPorts.ResponseSigner signer(List<String> order) {
        return new TimeAbsenceOwnerPorts.ResponseSigner() {
            @Override
            public Optional<TimeAbsenceOwnerContract.WorkloadResponseEvidence> signSuccess(
                    TimeAbsenceOwnerContract.VerifiedRequest request,
                    Xcon027EmploymentAbsenceSnapshotV1 snapshot) {
                order.add("sign");
                String keyId = "response-key";
                String evidenceDigest = evidenceDigest(request, snapshot, keyId);
                return Optional.of(new TimeAbsenceOwnerContract.WorkloadResponseEvidence(
                        request.tenantId(), request.callerSpiffeId(), request.audience(),
                        request.tenantGrantRevision(), request.businessTimeZone(),
                        request.purposeCode(), request.projectionId(),
                        request.authorizationRevision(), request.fieldPolicyRevision(),
                        request.correlationId(), snapshot.asOf(), snapshot.asOf(),
                        snapshot.validUntil(), snapshot.scopeDigest(),
                        snapshot.contentSha256(), evidenceDigest, keyId,
                        jws("dwp-workload-response+jws", keyId, evidenceDigest)));
            }

            @Override
            public Optional<TimeAbsenceOwnerContract.Problem> signProblem(
                    TimeAbsenceOwnerContract.VerifiedRequest request,
                    TimeAbsenceOwnerContract.Failure failure,
                    Instant issuedAt) {
                return Optional.empty();
            }
        };
    }

    private static TimeAbsenceOwnerPorts.ResponseSigner malformedSigner(
            boolean digestMismatch,
            boolean payloadMismatch) {
        return new TimeAbsenceOwnerPorts.ResponseSigner() {
            @Override
            public Optional<TimeAbsenceOwnerContract.WorkloadResponseEvidence> signSuccess(
                    TimeAbsenceOwnerContract.VerifiedRequest request,
                    Xcon027EmploymentAbsenceSnapshotV1 snapshot) {
                String keyId = "response-key";
                String expected = evidenceDigest(request, snapshot, keyId);
                String stated = digestMismatch ? "b".repeat(64) : expected;
                String payload = payloadMismatch ? "c".repeat(64) : stated;
                return Optional.of(new TimeAbsenceOwnerContract.WorkloadResponseEvidence(
                        request.tenantId(), request.callerSpiffeId(), request.audience(),
                        request.tenantGrantRevision(), request.businessTimeZone(),
                        request.purposeCode(), request.projectionId(),
                        request.authorizationRevision(), request.fieldPolicyRevision(),
                        request.correlationId(), snapshot.asOf(), snapshot.asOf(),
                        snapshot.validUntil(), snapshot.scopeDigest(),
                        snapshot.contentSha256(), stated, keyId,
                        jws("dwp-workload-response+jws", keyId, payload)));
            }

            @Override
            public Optional<TimeAbsenceOwnerContract.Problem> signProblem(
                    TimeAbsenceOwnerContract.VerifiedRequest request,
                    TimeAbsenceOwnerContract.Failure failure,
                    Instant issuedAt) {
                return Optional.empty();
            }
        };
    }

    private static TimeAbsenceOwnerPorts.ResponseSigner runtimeSigner() {
        return new TimeAbsenceOwnerPorts.ResponseSigner() {
            @Override
            public Optional<TimeAbsenceOwnerContract.WorkloadResponseEvidence> signSuccess(
                    TimeAbsenceOwnerContract.VerifiedRequest request,
                    Xcon027EmploymentAbsenceSnapshotV1 snapshot) {
                throw new IllegalStateException("signing key unavailable");
            }

            @Override
            public Optional<TimeAbsenceOwnerContract.Problem> signProblem(
                    TimeAbsenceOwnerContract.VerifiedRequest request,
                    TimeAbsenceOwnerContract.Failure failure,
                    Instant issuedAt) {
                return Optional.empty();
            }
        };
    }

    private static TimeAbsenceOwnerContract.VerifiedRequest verified(long minimum) {
        TimeAbsenceOwnerContract.Request ownerRequest =
                new TimeAbsenceOwnerContract.Request(
                        RELATIONSHIP, minimum, LocalDate.parse("2026-10-21"));
        Instant issuedAt = Instant.parse("2026-10-20T15:00:00Z");
        return new TimeAbsenceOwnerContract.VerifiedRequest(
                "GET", TimeAbsenceOwnerContract.PATH_PREFIX + RELATIONSHIP,
                "f".repeat(64), TimeAbsenceOwnerContract.BINDING_ID,
                UUID.fromString("40000000-0000-4000-8000-000000000001"),
                UUID.fromString("50000000-0000-4000-8000-000000000001"),
                UUID.fromString("40000000-0000-4000-8000-000000000001"),
                issuedAt, issuedAt.plusSeconds(30), 71L,
                TimeAbsenceOwnerContract.CALLER_SPIFFE,
                TimeAbsenceOwnerContract.AUDIENCE, "time-key-2026-10",
                3L, "Asia/Seoul", TimeAbsenceOwnerContract.PURPOSE,
                TimeAbsenceOwnerContract.PROJECTION, 4L, 5L, ownerRequest);
    }

    private static TimeAbsenceOwnerContract.OwnerGrant grant() {
        return new TimeAbsenceOwnerContract.OwnerGrant(
                71L, TimeAbsenceOwnerContract.GrantLifecycle.ACTIVE,
                TimeAbsenceOwnerContract.CALLER_SPIFFE, "time-key-2026-10",
                TimeAbsenceOwnerContract.PURPOSE, TimeAbsenceOwnerContract.PROJECTION,
                "Asia/Seoul", 3L, 4L, 5L);
    }

    private static TimeAbsenceOwnerContract.OwnerFacts facts(long revision) {
        return new TimeAbsenceOwnerContract.OwnerFacts(
                RELATIONSHIP, revision, WORKER, "ACTIVE", "EMPLOYEE", "ACTIVE",
                LEGAL_ENTITY, LocalDate.parse("2020-01-01"), null,
                List.of(
                        assignment("20000000-0000-4000-8000-000000000001", 2L),
                        assignment("30000000-0000-4000-8000-000000000001", 3L)),
                AS_OF);
    }

    private static TimeAbsenceOwnerContract.AssignmentFact assignment(
            String id, long revision) {
        return new TimeAbsenceOwnerContract.AssignmentFact(
                UUID.fromString(id), revision, "ACTIVE", LEGAL_ENTITY,
                null, null, null, LocalDate.parse("2020-01-01"), null);
    }

    private static String evidenceDigest(
            TimeAbsenceOwnerContract.VerifiedRequest request,
            Xcon027EmploymentAbsenceSnapshotV1 snapshot,
            String keyId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tenantId", request.tenantId());
        fields.put("callerSpiffeId", request.callerSpiffeId());
        fields.put("audience", request.audience());
        fields.put("tenantGrantRevision", request.tenantGrantRevision());
        fields.put("businessTimeZone", request.businessTimeZone());
        fields.put("purposeCode", request.purposeCode());
        fields.put("projectionId", request.projectionId());
        fields.put("authorizationRevision", request.authorizationRevision());
        fields.put("fieldPolicyRevision", request.fieldPolicyRevision());
        fields.put("correlationId", request.correlationId());
        fields.put("issuedAt", snapshot.asOf());
        fields.put("asOf", snapshot.asOf());
        fields.put("validUntil", snapshot.validUntil());
        fields.put("scopeDigest", snapshot.scopeDigest());
        fields.put("contentSha256", snapshot.contentSha256());
        fields.put("keyId", keyId);
        return new TimeAbsenceOwnerCanonicalJson(
                new ObjectMapper().findAndRegisterModules()).digest(fields);
    }

    private static String jws(String type, String keyId, String digest) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = "{\"alg\":\"RS256\",\"typ\":\"" + type
                + "\",\"kid\":\"" + keyId + "\"}";
        byte[] signature = new byte[256];
        Arrays.fill(signature, (byte) 1);
        return encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(digest.getBytes(StandardCharsets.US_ASCII)) + "."
                + encoder.encodeToString(signature);
    }
}
