package com.dwp.services.people.hris.performance.per008;

import com.dwp.services.people.hris.performance.per008.Per008Contracts.*;
import com.dwp.services.people.hris.performance.per008.Per008Domain.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Per008AnonymityThresholdTest {
    private static final UUID REQUEST = new UUID(0, 100);

    @Test
    void aggregateFailsClosedBeforeSnapshotWhenDistinctResponsesAreBelowFrozenThreshold() {
        Per008Repository repository = new Per008TestSupport.RepositoryStub() {
            @Override public void lockIdempotency(AuthorityEvidence authority, String action,
                    String key) { }
            @Override public Optional<ReceiptRecord> receipt(AuthorityEvidence authority,
                    String action, String key) { return Optional.empty(); }
            @Override public RequestRecord request(AuthorityEvidence authority, UUID requestId,
                    boolean forUpdate) {
                RequestRecord base = Per008TestSupport.request(REQUEST, RequestState.CLOSED, 4,
                        UUID.randomUUID(), UUID.randomUUID());
                return new RequestRecord(base.tenantId(), base.publicId(), base.aggregateVersion(),
                        base.cycleVersionId(), base.subjectWorkerPublicId(),
                        base.subjectAssignmentPublicId(), base.authorizationPrincipalPublicId(),
                        base.templateArtifactPublicId(), base.templateContentHash(),
                        base.requestTitle(), base.state(), base.anonymityMode(), 3,
                        base.privacyPolicyPublicId(), base.thresholdPolicyRevision(),
                        base.thresholdPolicyDigest(), base.opensAt(), base.closesAt(),
                        base.createdAt(), null, null);
            }
            @Override public List<StoredResponse> submittedResponses(AuthorityEvidence authority,
                    UUID requestId) {
                return List.of(response(1), response(2));
            }
            private StoredResponse response(long value) {
                return new StoredResponse(new UUID(0, value), new UUID(1, value), "b".repeat(64),
                        null, OffsetDateTime.parse("2026-10-20T00:00:00Z"));
            }
        };
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        Per008Protection protection = new Per008Protection(new ObjectMapper(),
                empty.getBeanProvider(com.dwp.core.crypto.EnvelopeEncryptionService.class),
                empty.getBeanProvider(Per008Protection.TenantDigestKeyProvider.class), "local");
        Per008Service.OwnerLifecycleAuthorityPort lifecycle =
                (key, operation, principal, tenant, request) -> new AuthorityEvidence(
                        tenant, 9, new UUID(0, 3), new UUID(0, 4), new UUID(0, 5),
                        Per008Service.LIFECYCLE_AUTHORITY, Per008Service.LIFECYCLE_PRINCIPAL,
                        Per008Service.LIFECYCLE_AUTHORITY + ':' + operation,
                        "PERFORMANCE_FEEDBACK_LIFECYCLE", "scope", "auth", "field",
                        "a".repeat(64), Map.of(), Set.of(), Set.of(PepLayer.values()));
        Per008Service service = new Per008Service(ignored -> { throw new AssertionError(); },
                repository, protection, null, new ObjectMapper(), lifecycle,
                Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.aggregateFeedbackRequest(new LifecycleCommand(
                Per008Service.LIFECYCLE_AUTHORITY, 7, REQUEST, 4,
                "aggregate-key-0001")))
                .isInstanceOf(Per008Problem.class)
                .hasMessage("FEEDBACK_RELEASE_THRESHOLD_NOT_MET");
    }
}
