package com.dwp.services.payroll.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Hrm013PayrollTaskReceiptRelayTest {
    @Test
    void performsOneBoundedTenantClaimWithoutTenantEnumeration() {
        var store = mock(Hrm013PayrollTaskDispatchStore.class);
        Instant now = Instant.parse("2026-10-27T03:00:00Z");
        when(store.claimNext(41L, "worker-a", now, Duration.ofSeconds(30)))
                .thenReturn(Optional.empty());
        var relay = new Hrm013PayrollTaskReceiptRelay(store,
                mock(AuthHrm013ServiceAuthorityClient.class),
                mock(Hrm013PayrollWorkloadCredentialProvider.class),
                "https://people.internal", Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofSeconds(30), 10);

        assertThat(relay.relayTenant(41L, "worker-a")).isFalse();
        verify(store).claimNext(41L, "worker-a", now, Duration.ofSeconds(30));
    }

    @Test
    void scheduledPollUsesOnlyTheExplicitBoundedTenantEnrollment() {
        var store = mock(Hrm013PayrollTaskDispatchStore.class);
        Instant now = Instant.parse("2026-10-27T03:00:00Z");
        when(store.claimNext(41L, "worker-fixed", now, Duration.ofSeconds(30)))
                .thenReturn(Optional.empty());
        when(store.claimNext(42L, "worker-fixed", now, Duration.ofSeconds(30)))
                .thenReturn(Optional.empty());
        var relay = new Hrm013PayrollTaskReceiptRelay(store,
                mock(AuthHrm013ServiceAuthorityClient.class),
                mock(Hrm013PayrollWorkloadCredentialProvider.class),
                "https://people.internal", Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofSeconds(30), 10, () -> List.of(41L, 42L), 8,
                "worker-fixed");

        relay.poll();

        verify(store).claimNext(41L, "worker-fixed", now, Duration.ofSeconds(30));
        verify(store).claimNext(42L, "worker-fixed", now, Duration.ofSeconds(30));
    }

    @Test
    void exhaustedNonemptyClaimEndsAsDurableResultUnknown() {
        var store = mock(Hrm013PayrollTaskDispatchStore.class);
        Instant now = Instant.parse("2026-10-27T03:00:00Z");
        var claim = claim(now, 10);
        when(store.claimNext(41L, "worker-a", now, Duration.ofSeconds(30)))
                .thenReturn(Optional.of(claim));
        var relay = new Hrm013PayrollTaskReceiptRelay(store,
                mock(AuthHrm013ServiceAuthorityClient.class),
                mock(Hrm013PayrollWorkloadCredentialProvider.class),
                "https://people.internal", Clock.fixed(now, ZoneOffset.UTC),
                Duration.ofSeconds(30), 10);

        assertThat(relay.relayTenant(41L, "worker-a")).isTrue();

        verify(store).retry(eq(claim), eq(0L),
                eq(Hrm013PayrollTaskReceiptRelay.RESULT_UNKNOWN), eq(true));
    }

    @Test
    void jitterIsDeterministicAttemptBoundedAndFullRangeCapped() {
        UUID callback = UUID.fromString("8c128dfa-aa60-42bd-85e8-3aeb92ce5e55");
        for (int attempt = 1; attempt < 10; attempt++) {
            long first = Hrm013PayrollTaskReceiptRelay.boundedFullJitterSeconds(
                    callback, attempt);
            long second = Hrm013PayrollTaskReceiptRelay.boundedFullJitterSeconds(
                    callback, attempt);
            long cap = Math.min(300L, 1L << Math.min(attempt, 9));
            assertThat(first).isEqualTo(second).isBetween(0L, cap);
        }
        assertThatThrownBy(() ->
                Hrm013PayrollTaskReceiptRelay.boundedFullJitterSeconds(callback, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEveryForgedStoredCallbackBindingAndTerminalChildIdentity() {
        Instant occurred = Instant.parse("2026-10-27T03:00:00Z");
        var claim = claim(occurred, 1);
        var valid = Hrm013SeparationBridgeV1.decodeTaskReceipt(
                claim.exactCallbackBytes());
        List<Hrm013SeparationBridgeV1.SeparationTaskReceiptV2> forgeries = List.of(
                seal(valid, UUID.randomUUID(), valid.casePublicId(),
                        valid.taskPublicId(), valid.taskType(), valid.status(),
                        valid.ownerRevision(), valid.originatingPayloadSha256(),
                        valid.occurredAt(), valid.correlationId()),
                seal(valid, valid.callbackEventId(), UUID.randomUUID(),
                        valid.taskPublicId(), valid.taskType(), valid.status(),
                        valid.ownerRevision(), valid.originatingPayloadSha256(),
                        valid.occurredAt(), valid.correlationId()),
                seal(valid, valid.callbackEventId(), valid.casePublicId(),
                        UUID.randomUUID(), valid.taskType(), valid.status(),
                        valid.ownerRevision(), valid.originatingPayloadSha256(),
                        valid.occurredAt(), valid.correlationId()),
                seal(valid, valid.callbackEventId(), valid.casePublicId(),
                        valid.taskPublicId(), "TIME_FINALIZATION", valid.status(),
                        valid.ownerRevision(), valid.originatingPayloadSha256(),
                        valid.occurredAt(), valid.correlationId()),
                seal(valid, valid.callbackEventId(), valid.casePublicId(),
                        valid.taskPublicId(), valid.taskType(), "FAILED",
                        valid.ownerRevision(), valid.originatingPayloadSha256(),
                        valid.occurredAt(), valid.correlationId()),
                seal(valid, valid.callbackEventId(), valid.casePublicId(),
                        valid.taskPublicId(), valid.taskType(), valid.status(), 2,
                        valid.originatingPayloadSha256(), valid.occurredAt(),
                        valid.correlationId()),
                seal(valid, valid.callbackEventId(), valid.casePublicId(),
                        valid.taskPublicId(), valid.taskType(), valid.status(),
                        valid.ownerRevision(), "f".repeat(64), valid.occurredAt(),
                        valid.correlationId()),
                seal(valid, valid.callbackEventId(), valid.casePublicId(),
                        valid.taskPublicId(), valid.taskType(), valid.status(),
                        valid.ownerRevision(), valid.originatingPayloadSha256(),
                        valid.occurredAt().plusSeconds(1), valid.correlationId()),
                seal(valid, valid.callbackEventId(), valid.casePublicId(),
                        valid.taskPublicId(), valid.taskType(), valid.status(),
                        valid.ownerRevision(), valid.originatingPayloadSha256(),
                        valid.occurredAt(), UUID.randomUUID()),
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        valid.callbackEventId(), valid.casePublicId(),
                        valid.taskPublicId(), valid.taskType(), valid.status(),
                        valid.ownerRevision(), valid.externalReceiptPublicId(),
                        valid.originatingPayloadSha256(), valid.occurredAt(),
                        "f".repeat(64), valid.correlationId()));
        for (var forged : forgeries) {
            assertThatThrownBy(() ->
                    Hrm013PayrollTaskReceiptRelay.requireStoredCallback(claim, forged))
                    .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
        }
        Hrm013PayrollTaskReceiptRelay.requireStoredCallback(claim, valid);
        assertThat(Hrm013PayrollTaskReceiptRelay.terminalDrain(claim, valid)
                .drainContinuationEventId()).isEqualTo(valid.callbackEventId());
        var wrongLineage = new Hrm013PayrollTaskDispatchStore.CallbackClaim(
                claim.tenantId(), claim.callbackEventId(), UUID.randomUUID(),
                claim.casePublicId(), claim.taskPublicId(), claim.correlationId(),
                claim.exactCallbackBytes(), claim.callbackPayloadSha256(),
                claim.originatingPayloadSha256(), claim.grantUseMode(),
                claim.drainRootWorkItemId(), claim.drainRootCreatedAt(),
                claim.drainRootPayloadSha256(), claim.leaseOwner(),
                claim.attemptCount(), claim.taskType(), claim.taskStatus(),
                claim.ownerRevision(), claim.occurredAt(),
                claim.terminalParentCreatedAt());
        assertThatThrownBy(() ->
                Hrm013PayrollTaskReceiptRelay.terminalDrain(wrongLineage, valid))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    private static Hrm013PayrollTaskDispatchStore.CallbackClaim claim(
            Instant now, int attempt) {
        long tenantId = 41L;
        UUID dispatch = UUID.fromString("6af2c61a-69c3-40ec-b4eb-e8c86cc7bdf8");
        UUID callback = Hrm013WorkloadProofV1.terminalChildEventId(
                tenantId, dispatch, 1,
                Hrm013SeparationBridgeV1.HRM_008_PAYROLL.bindingId());
        UUID caseId = UUID.fromString("70e83679-d7c2-4c99-841d-572554458806");
        UUID taskId = UUID.fromString("fc17bb85-34f4-45c8-a3b9-b2150cc97399");
        UUID correlation = UUID.fromString("71100634-8b05-434e-8dc1-697089c8590c");
        Instant parentAt = now.minusSeconds(60);
        String origin = "b".repeat(64);
        var body = Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        callback, caseId, taskId, "PAYROLL_SETTLEMENT", "COMPLETED",
                        1, UUID.randomUUID(), origin, now, "0".repeat(64), correlation));
        return new Hrm013PayrollTaskDispatchStore.CallbackClaim(
                tenantId, callback, dispatch, caseId, taskId, correlation,
                Hrm013SeparationBridgeV1.encode(body), body.payloadSha256(), origin,
                Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK,
                dispatch, parentAt, origin, "worker-a", attempt,
                "PAYROLL_SETTLEMENT", "COMPLETED", 1, now, parentAt);
    }

    private static Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 seal(
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 original,
            UUID callbackEventId, UUID caseId, UUID taskId, String taskType,
            String status, long ownerRevision, String origin, Instant occurredAt,
            UUID correlationId) {
        return Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        callbackEventId, caseId, taskId, taskType, status,
                        ownerRevision, original.externalReceiptPublicId(), origin,
                        occurredAt, "0".repeat(64), correlationId));
    }
}
