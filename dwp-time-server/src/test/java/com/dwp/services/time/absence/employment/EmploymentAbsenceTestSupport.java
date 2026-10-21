package com.dwp.services.time.absence.employment;

import com.dwp.contracts.hris.xcon.v1.Xcon027EmploymentAbsenceSnapshotV1;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.FreshnessState;
import com.dwp.services.time.absence.employment.EmploymentAbsenceContract.RecoveryOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

final class EmploymentAbsenceTestSupport {

    static final long TENANT = 41L;
    static final UUID RELATIONSHIP = UUID.fromString(
            "10000000-0000-4000-8000-000000000041");
    static final UUID WORKER = UUID.fromString(
            "20000000-0000-4000-8000-000000000041");
    static final UUID LEGAL_ENTITY = UUID.fromString(
            "30000000-0000-4000-8000-000000000041");
    static final UUID CORRELATION = UUID.fromString(
            "40000000-0000-4000-8000-000000000041");
    static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String DIGEST_A = "a".repeat(64);
    static final String DIGEST_B = "b".repeat(64);

    private EmploymentAbsenceTestSupport() {
    }

    static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    static EmploymentAbsenceContract.OwnerGrant grant() {
        return new EmploymentAbsenceContract.OwnerGrant(
                TENANT, EmploymentAbsenceContract.GrantLifecycle.ACTIVE,
                EmploymentAbsenceContract.CALLER_SPIFFE, "tim-key",
                EmploymentAbsenceContract.PURPOSE,
                EmploymentAbsenceContract.PROJECTION, "UTC",
                11, 12, 13);
    }

    static EmploymentAbsenceContract.TenantContext context() {
        return new EmploymentAbsenceContract.TenantContext(
                TENANT, "UTC", 11, 12, 13);
    }

    static Xcon027EmploymentAbsenceSnapshotV1 snapshot(
            ObjectMapper mapper,
            long relationshipRevision,
            long minimumRevision) {
        return snapshot(mapper, relationshipRevision, minimumRevision, NOW);
    }

    static Xcon027EmploymentAbsenceSnapshotV1 snapshot(
            ObjectMapper mapper,
            long relationshipRevision,
            long minimumRevision,
            Instant ownerTime) {
        EmploymentAbsenceCanonicalJson canonical =
                new EmploymentAbsenceCanonicalJson(mapper);
        EmploymentAbsenceContract.OwnerRequestProof proof = proof(minimumRevision);
        String scope = canonical.scopeDigest(proof);
        Xcon027EmploymentAbsenceSnapshotV1 unsigned =
                new Xcon027EmploymentAbsenceSnapshotV1(
                        RELATIONSHIP, relationshipRevision, WORKER, "ACTIVE",
                        "EMPLOYEE", "ACTIVE", LEGAL_ENTITY,
                        LocalDate.of(2020, 1, 1), null,
                        LocalDate.of(2026, 10, 21), java.util.List.of(),
                        relationshipRevision, ownerTime, ownerTime.plusSeconds(300),
                        EmploymentAbsenceContract.OWNER_CONTRACT,
                        EmploymentAbsenceContract.OWNER_SERVICE,
                        EmploymentAbsenceContract.PURPOSE, scope, "0".repeat(64));
        String content = canonical.contentDigest(unsigned);
        return new Xcon027EmploymentAbsenceSnapshotV1(
                unsigned.relationshipPublicId(), unsigned.relationshipRevision(),
                unsigned.workerPublicId(), unsigned.workerStatus(),
                unsigned.relationshipType(), unsigned.relationshipStatus(),
                unsigned.legalEntityPublicId(), unsigned.relationshipEffectiveFrom(),
                unsigned.relationshipEffectiveTo(), unsigned.effectiveOn(),
                unsigned.assignments(), unsigned.ownerRevision(), unsigned.asOf(),
                unsigned.validUntil(), unsigned.ownerContractId(), unsigned.ownerService(),
                unsigned.purposeCode(), unsigned.scopeDigest(), content);
    }

    static EmploymentAbsenceContract.OwnerRequestProof proof(long minimumRevision) {
        return proof(minimumRevision, "tim-key");
    }

    static EmploymentAbsenceContract.OwnerRequestProof proof(
            long minimumRevision,
            String requestSigningKeyId) {
        return new EmploymentAbsenceContract.OwnerRequestProof(
                TENANT, RELATIONSHIP, minimumRevision,
                LocalDate.of(2026, 10, 21), "UTC", 11, 12, 13,
                requestSigningKeyId, CORRELATION,
                UUID.fromString("50000000-0000-4000-8000-000000000041"),
                NOW, NOW.plusSeconds(30), "GET",
                EmploymentAbsenceContract.PATH_PREFIX + RELATIONSHIP,
                EmploymentAbsenceContract.PURPOSE,
                EmploymentAbsenceContract.PROJECTION,
                "a.b.c", DIGEST_A);
    }

    static SignedOwner signedOwner(long revision, long minimumRevision) {
        return signedOwnerAt(revision, minimumRevision, NOW);
    }

    static SignedOwner signedOwnerAt(
            long revision,
            long minimumRevision,
            Instant ownerTime) {
        try {
            ObjectMapper mapper = mapper();
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keys = generator.generateKeyPair();
            Xcon027EmploymentAbsenceSnapshotV1 snapshot =
                    snapshot(mapper, revision, minimumRevision, ownerTime);
            EmploymentAbsenceCanonicalJson canonical =
                    new EmploymentAbsenceCanonicalJson(mapper);
            EmploymentAbsenceContract.ResponseEvidence unsigned =
                    new EmploymentAbsenceContract.ResponseEvidence(
                            TENANT, EmploymentAbsenceContract.CALLER_SPIFFE,
                            EmploymentAbsenceContract.OWNER_SERVICE, 11, "UTC",
                            EmploymentAbsenceContract.PURPOSE,
                            EmploymentAbsenceContract.PROJECTION, 12, 13,
                            CORRELATION, ownerTime, ownerTime,
                            ownerTime.plusSeconds(300),
                            snapshot.scopeDigest(), snapshot.contentSha256(),
                            "0".repeat(64), "people-key", "a.b.c");
            String evidenceDigest = canonical.evidenceDigest(unsigned);
            String signature = sign(keys, "dwp-workload-response+jws",
                    "people-key", evidenceDigest, mapper);
            EmploymentAbsenceContract.ResponseEvidence evidence =
                    new EmploymentAbsenceContract.ResponseEvidence(
                            TENANT, EmploymentAbsenceContract.CALLER_SPIFFE,
                            EmploymentAbsenceContract.OWNER_SERVICE, 11, "UTC",
                            EmploymentAbsenceContract.PURPOSE,
                            EmploymentAbsenceContract.PROJECTION, 12, 13,
                            CORRELATION, ownerTime, ownerTime,
                            ownerTime.plusSeconds(300),
                            snapshot.scopeDigest(), snapshot.contentSha256(),
                            evidenceDigest, "people-key", signature);
            EmploymentAbsenceContract.OwnerSuccess success =
                    new EmploymentAbsenceContract.OwnerSuccess(
                            snapshot, evidence,
                            Map.of("content-type", "application/json",
                                    "cache-control", "no-store",
                                    "pragma", "no-cache"),
                            "TLSv1.3", EmploymentAbsenceContract.OWNER_SPIFFE);
            EmploymentAbsenceContract.ResponseSigningKey binding =
                    new EmploymentAbsenceContract.ResponseSigningKey(
                            "people-key", EmploymentAbsenceContract.OWNER_SPIFFE,
                            EmploymentAbsenceContract.BINDING_ID,
                            EmploymentAbsenceContract.OWNER_RESPONSE_KEY_PURPOSE,
                            true, keys.getPublic());
            return new SignedOwner(success, binding);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    static SignedProblem signedProblem(int status, String code, boolean retryable) {
        try {
            ObjectMapper mapper = mapper();
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keys = generator.generateKeyPair();
            EmploymentAbsenceCanonicalJson canonical =
                    new EmploymentAbsenceCanonicalJson(mapper);
            Map<String, String> headers = Map.of(
                    "content-type", "application/problem+json",
                    "cache-control", "no-store", "pragma", "no-cache");
            EmploymentAbsenceContract.OwnerProblem unsigned =
                    new EmploymentAbsenceContract.OwnerProblem(
                            status, code, retryable, CORRELATION, NOW,
                            "0".repeat(64), "people-key", "a.b.c", headers,
                            "TLSv1.3", EmploymentAbsenceContract.OWNER_SPIFFE);
            String digest = canonical.problemDigest(unsigned);
            String signature = sign(keys, "dwp-workload-error+jws",
                    "people-key", digest, mapper);
            EmploymentAbsenceContract.OwnerProblem problem =
                    new EmploymentAbsenceContract.OwnerProblem(
                            status, code, retryable, CORRELATION, NOW,
                            digest, "people-key", signature, headers,
                            "TLSv1.3", EmploymentAbsenceContract.OWNER_SPIFFE);
            EmploymentAbsenceContract.ResponseSigningKey binding =
                    new EmploymentAbsenceContract.ResponseSigningKey(
                            "people-key", EmploymentAbsenceContract.OWNER_SPIFFE,
                            EmploymentAbsenceContract.BINDING_ID,
                            EmploymentAbsenceContract.OWNER_RESPONSE_KEY_PURPOSE,
                            true, keys.getPublic());
            return new SignedProblem(problem, binding);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    static EmploymentAbsenceOwnerClient successfulOwnerClient(
            long revision,
            long minimumRevision) {
        SignedOwner signed = signedOwner(revision, minimumRevision);
        ObjectMapper mapper = mapper();
        return new EmploymentAbsenceOwnerClient(
                tenant -> Optional.of(grant()),
                tenant -> Optional.of(context()),
                (grant, relationship, minimum, date, correlation) ->
                        Optional.of(proof(minimum)),
                proof -> new EmploymentAbsenceContract.WireSuccess(signed.success),
                keyId -> Optional.of(signed.key),
                (tenant, relationship, correlation, outcome, digest, evidence) -> true,
                mapper, CLOCK);
    }

    static EmploymentAbsenceProjection currentProjection(
            long relationshipRevision,
            long eventSequence) {
        ObjectMapper mapper = mapper();
        SignedOwner signed = signedOwner(relationshipRevision, eventSequence);
        EmploymentAbsenceProjection.InvalidationEvidence invalidation =
                eventSequence == 0 ? null
                        : new EmploymentAbsenceProjection.InvalidationEvidence(
                                eventSequence,
                                UUID.fromString("60000000-0000-4000-8000-000000000041"),
                                "ASSIGNMENT_CHANGED", LocalDate.of(2026, 10, 1),
                                DIGEST_B, CORRELATION.toString());
        return new EmploymentAbsenceProjection(
                TENANT, RELATIONSHIP, FreshnessState.CURRENT,
                eventSequence, eventSequence, eventSequence, null,
                signed.success.data(),
                EmploymentAbsenceProjection.StoredOwnerEvidence.from(
                        signed.success.evidence()),
                "UTC",
                eventSequence, invalidation, RecoveryOutcome.SUCCEEDED,
                null, NOW, 1, NOW.minusSeconds(60), NOW);
    }

    static DomainEventEnvelope event(long sequence) {
        ObjectMapper mapper = mapper();
        var data = mapper.createObjectNode()
                .put("changeType", "ASSIGNMENT_CHANGED")
                .put("effectiveDate", "2026-10-21")
                .put("newAggregateVersion", sequence)
                .put("relationshipPublicId", RELATIONSHIP.toString());
        return new DomainEventEnvelope(
                "1.0", UUID.randomUUID(), EmploymentAbsenceContract.EVENT_SOURCE,
                EmploymentAbsenceContract.EVENT_TYPE, 1, NOW,
                EmploymentAbsenceContract.AGGREGATE_TYPE + "/" + RELATIONSHIP,
                TENANT, EmploymentAbsenceContract.AGGREGATE_TYPE,
                RELATIONSHIP.toString(), sequence, CORRELATION.toString(),
                UUID.randomUUID().toString(), null, data,
                Map.of("xcon", "XCON-004", "profile", "EmploymentChanged.v1"));
    }

    static String sign(
            KeyPair keyPair,
            String type,
            String keyId,
            String digest,
            ObjectMapper mapper) throws Exception {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mapper.writeValueAsBytes(Map.of(
                        "alg", "RS256", "typ", type, "kid", keyId)));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                digest.getBytes(StandardCharsets.US_ASCII));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        String signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signer.sign());
        return header + "." + payload + "." + signature;
    }

    static final class InMemoryProjectionStore
            implements EmploymentAbsenceProjectionStore {
        private final Map<UUID, EmploymentAbsenceProjection> values = new HashMap<>();
        boolean unresolved;
        boolean blocking;
        boolean anyInbox;
        boolean corruptRead;
        OptionalLong offset = OptionalLong.empty();

        @Override
        public void lockTuple(long tenantId, UUID relationshipPublicId) {
        }

        @Override
        public Optional<EmploymentAbsenceProjection> find(long tenantId, UUID relationship) {
            EmploymentAbsenceProjection value = values.get(relationship);
            return value != null && value.tenantId() == tenantId
                    ? Optional.of(value) : Optional.empty();
        }

        @Override
        public EvaluationRead readForEvaluation(
                long tenantId,
                UUID relationshipPublicId) {
            if (corruptRead) {
                long version = find(tenantId, relationshipPublicId)
                        .map(EmploymentAbsenceProjection::projectionVersion)
                        .orElse(1L);
                return new EvaluationRead.Corrupt(version);
            }
            return EmploymentAbsenceProjectionStore.super.readForEvaluation(
                    tenantId, relationshipPublicId);
        }

        @Override
        public EmploymentAbsenceProjection insertExpectedZero(
                EmploymentAbsenceProjection candidate) {
            if (values.containsKey(candidate.relationshipPublicId())) {
                throw new JdbcEmploymentAbsenceProjectionStore.ProjectionCasLost("race");
            }
            EmploymentAbsenceProjection stored = version(candidate, 1);
            values.put(stored.relationshipPublicId(), stored);
            return stored;
        }

        @Override
        public EmploymentAbsenceProjection updateExpectedVersion(
                EmploymentAbsenceProjection candidate,
                long expectedProjectionVersion) {
            EmploymentAbsenceProjection current = values.get(candidate.relationshipPublicId());
            if (current == null || current.projectionVersion() != expectedProjectionVersion) {
                throw new JdbcEmploymentAbsenceProjectionStore.ProjectionCasLost("race");
            }
            EmploymentAbsenceProjection stored = version(candidate,
                    expectedProjectionVersion + 1);
            values.put(stored.relationshipPublicId(), stored);
            return stored;
        }

        @Override
        public boolean hasUnresolvedInbox(long tenantId, UUID relationshipPublicId) {
            return unresolved;
        }

        @Override
        public boolean hasBlockingInbox(long tenantId, UUID relationshipPublicId) {
            return blocking;
        }

        @Override
        public boolean hasAnyInbox(long tenantId, UUID relationshipPublicId) {
            return anyInbox || unresolved || blocking;
        }

        @Override
        public OptionalLong sharedOffset(long tenantId, UUID relationshipPublicId) {
            return offset;
        }

        void put(EmploymentAbsenceProjection value) {
            values.put(value.relationshipPublicId(), value);
        }

        private EmploymentAbsenceProjection version(
                EmploymentAbsenceProjection value,
                long version) {
            return new EmploymentAbsenceProjection(
                    value.tenantId(), value.relationshipPublicId(), value.freshness(),
                    value.lastObservedEventSequence(),
                    value.requiredRelationshipRevision(), value.sharedOffsetSequence(),
                    value.gapExpectedSequence(), value.ownerSnapshot(),
                    value.ownerEvidence(), value.ownerBusinessTimeZone(),
                    value.ownerMinimumRelationshipRevision(), value.lastInvalidation(),
                    value.recoveryOutcome(), value.lastRecoveryErrorCode(),
                    value.lastRecoveryAt(), version,
                    value.createdAt() == null ? NOW : value.createdAt(), NOW);
        }
    }

    static final class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition)
                throws TransactionException {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) throws TransactionException {
        }

        @Override
        public void rollback(TransactionStatus status) throws TransactionException {
        }
    }

    record SignedOwner(
            EmploymentAbsenceContract.OwnerSuccess success,
            EmploymentAbsenceContract.ResponseSigningKey key) {
    }

    record SignedProblem(
            EmploymentAbsenceContract.OwnerProblem problem,
            EmploymentAbsenceContract.ResponseSigningKey key) {
    }
}
