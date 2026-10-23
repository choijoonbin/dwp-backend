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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Per008FeedbackLifecycleTest {
    private static final UUID REQUEST = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID RECEIPT = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String KEY = "lifecycle-key-0001";

    @Test
    void transitionUsesExpectedVersionCasAndSameCommandReplaysBeforeStateResolution() {
        LifecycleRepository repository = new LifecycleRepository();
        Per008Service service = service(repository);
        LifecycleCommand command = new LifecycleCommand(Per008Service.LIFECYCLE_AUTHORITY,
                7, REQUEST, 3, KEY);

        LifecycleReceipt first = service.openFeedbackRequest(command);
        LifecycleReceipt replay = service.openFeedbackRequest(command);

        assertThat(first).isEqualTo(new LifecycleReceipt(RECEIPT, REQUEST, RequestState.OPEN,
                4, OffsetDateTime.parse("2026-10-23T00:00:00Z"), false));
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.receiptId()).isEqualTo(RECEIPT);
        assertThat(repository.requestCalls).isEqualTo(1);
        assertThat(repository.transitionExpectedVersion).isEqualTo(3);
        assertThat(repository.transitionExpectedState).isEqualTo(RequestState.DRAFT);
        assertThat(repository.transitionTargetState).isEqualTo(RequestState.OPEN);
    }

    @Test
    void rejectsActionSuffixedOrNearMatchCallerAuthorityKey() {
        Per008Service service = service(new LifecycleRepository());

        assertThatThrownBy(() -> service.openFeedbackRequest(new LifecycleCommand(
                Per008Service.LIFECYCLE_AUTHORITY + ":open", 7, REQUEST, 3, KEY)))
                .isInstanceOf(Per008Problem.class)
                .hasMessage("PER008_LIFECYCLE_AUTHORITY_REQUIRED");
    }

    private Per008Service service(LifecycleRepository repository) {
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        Per008Protection protection = new Per008Protection(new ObjectMapper(),
                empty.getBeanProvider(com.dwp.core.crypto.EnvelopeEncryptionService.class),
                empty.getBeanProvider(Per008Protection.TenantDigestKeyProvider.class), "local");
        Per008Service.OwnerLifecycleAuthorityPort lifecycle =
                (authorityKey, operation, principalKey, tenantId, requestId) ->
                        new AuthorityEvidence(tenantId, 9, new UUID(0, 30), new UUID(0, 31),
                                new UUID(0, 32), Per008Service.LIFECYCLE_AUTHORITY,
                                Per008Service.LIFECYCLE_PRINCIPAL,
                                Per008Service.LIFECYCLE_AUTHORITY + ':' + operation,
                                "PERFORMANCE_FEEDBACK_LIFECYCLE", "scope", "auth", "field",
                                "a".repeat(64), Map.of(), Set.of(), Set.of(PepLayer.values()));
        return new Per008Service(request -> { throw new AssertionError("public PEP not used"); },
                repository, protection, null, new ObjectMapper(), lifecycle,
                Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC));
    }

    private static final class LifecycleRepository extends Per008TestSupport.RepositoryStub {
        private ReceiptRecord stored;
        private int requestCalls;
        private long transitionExpectedVersion;
        private RequestState transitionExpectedState;
        private RequestState transitionTargetState;

        @Override public void lockIdempotency(AuthorityEvidence authority, String action,
                String idempotencyKey) {
            assertThat(action).isEqualTo(Per008Service.LIFECYCLE_AUTHORITY + ":open");
            assertThat(idempotencyKey).isEqualTo(KEY);
        }

        @Override public Optional<ReceiptRecord> receipt(AuthorityEvidence authority, String action,
                String idempotencyKey) { return Optional.ofNullable(stored); }

        @Override public RequestRecord request(AuthorityEvidence authority, UUID requestId,
                boolean forUpdate) {
            requestCalls++;
            assertThat(forUpdate).isTrue();
            return Per008TestSupport.request(REQUEST, RequestState.DRAFT, 3,
                    UUID.randomUUID(), UUID.randomUUID());
        }

        @Override public UUID createLifecycleReceipt(AuthorityEvidence authority,
                RequestRecord request, String action, String idempotencyKey, String digest,
                OffsetDateTime now) {
            stored = new ReceiptRecord(RECEIPT, digest, REQUEST, REQUEST, 4, now, "SUCCEEDED");
            return RECEIPT;
        }

        @Override public void transitionRequest(AuthorityEvidence authority, UUID requestId,
                RequestState expected, RequestState target, long expectedVersion,
                UUID lifecycleReceiptId, OffsetDateTime now) {
            transitionExpectedState = expected;
            transitionTargetState = target;
            transitionExpectedVersion = expectedVersion;
            assertThat(lifecycleReceiptId).isEqualTo(RECEIPT);
        }
    }
}
