package com.dwp.services.auth.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.dwp.services.auth.hrisservicegrant.HrisServiceGrantQuiesceService.LocalAdmission;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class Hrm013IdentityTaskReceiptRelayTest {
    @Test
    void scheduledRelayUsesAnIndependentKeysetCursorAndIsolatesTenantFailure() {
        Instant now = Instant.parse("2026-10-27T03:00:00Z");
        var service = mock(Hrm013IdentityTaskDispatchService.class);
        List<Long> attemptedTenants = new ArrayList<>();
        when(service.claimReceipt(anyLong(), eq(now))).thenAnswer(invocation -> {
            long tenantId = invocation.getArgument(0);
            attemptedTenants.add(tenantId);
            if (tenantId == 50L) {
                throw new IllegalStateException("tenant-local storage failure");
            }
            return Optional.empty();
        });
        List<Long> requestedCursors = new ArrayList<>();
        Hrm013IdentityTaskDispatchService.TenantWorkSource tenants = (after, limit) -> {
            requestedCursors.add(after);
            assertThat(limit).isEqualTo(100);
            if (after == 0L) {
                return LongStream.rangeClosed(1, 100).boxed().toList();
            }
            if (after == 100L) return List.of(101L);
            if (after == 101L) return List.of();
            throw new AssertionError("unexpected keyset cursor " + after);
        };
        var relay = new Hrm013IdentityTaskReceiptRelay(
                service,
                ignored -> URI.create("https://people.internal/"),
                ignored -> { throw new AssertionError("no receipt should be sent"); },
                (callbackEventId, retryCount) -> Duration.ofSeconds(1),
                tenants, Clock.fixed(now, ZoneOffset.UTC));

        assertThat(relay.relayScheduledBatch()).isZero();
        assertThat(relay.relayScheduledBatch()).isZero();
        assertThat(relay.relayScheduledBatch()).isZero();

        List<Long> firstHundred = LongStream.rangeClosed(1, 100).boxed().toList();
        List<Long> expected = new ArrayList<>(firstHundred);
        expected.add(101L);
        expected.addAll(firstHundred);
        assertThat(attemptedTenants).containsExactlyElementsOf(expected);
        assertThat(attemptedTenants).containsSubsequence(49L, 50L, 51L);
        assertThat(requestedCursors).containsExactly(0L, 100L, 101L, 0L);
    }

    @Test
    void exactAcceptedReceiptMarksTheDurableClaimDelivered() {
        Instant now = Instant.parse("2026-10-27T03:00:00Z");
        long tenant = 91;
        UUID callback = UUID.randomUUID();
        UUID dispatch = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        var body = Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        callback, caseId, taskId, "IDENTITY_ACCESS_REVOCATION",
                        "COMPLETED", 1, UUID.randomUUID(), "a".repeat(64), now,
                        "0".repeat(64), correlation));
        byte[] bodyBytes = Hrm013SeparationBridgeV1.encode(body);
        UUID grantSet = UUID.randomUUID();
        var claim = new Hrm013IdentityTaskDispatchStore.ReceiptClaim(
                tenant, callback, dispatch, caseId, taskId, correlation,
                bodyBytes, body.payloadSha256(), grantSet, 4, 7, 11,
                "identity-key-v4", 1, "relay-node-a", UUID.randomUUID(),
                now.plusSeconds(30), now.minusSeconds(2), "0".repeat(64),
                new Hrm013WorkloadProofV1.BusinessAuthority(
                        Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK, 7, 11),
                null);

        var service = mock(Hrm013IdentityTaskDispatchService.class);
        when(service.claimReceipt(tenant, now)).thenReturn(Optional.of(claim));
        var lease = mock(Hrm013IdentityWorkloadCredentialProvider
                .AtomicGrantCredentialLease.class);
        String path = Hrm013SeparationBridgeV1.HRM_008_IDENTITY.path(caseId, taskId);
        String commandDigest = Hrm013SeparationBridgeV1.commandDigest(
                Hrm013SeparationBridgeV1.HRM_008_IDENTITY, path, tenant, bodyBytes);
        var receipt = new Hrm013SeparationBridgeV1.SeparationCallbackReceiptV1(
                UUID.randomUUID(), callback, "TASK_RECEIPT", caseId, taskId, 1,
                "ACCEPTED", correlation, commandDigest, now.plusSeconds(1));
        byte[] receiptBytes = Hrm013SeparationBridgeV1.encode(receipt);
        var response = new Hrm013IdentityTaskReceiptRelay.OutboundResponse(
                200, Map.of("Content-Type", List.of("application/json")),
                receiptBytes, new Hrm013IdentityTaskReceiptRelay.TlsEvidence(
                        "TLSv1.3", true, true,
                        "spiffe://dwp/service/dwp-people-server", "people.internal"));
        var proofProfile = Hrm013WorkloadProofV1.hrm008(
                Hrm013WorkloadProofV1.TaskOwner.IDENTITY, caseId, taskId);
        var headers = Hrm013WorkloadProofV1.headers(
                proofProfile, "a.b.c", correlation);
        var attempt = new Hrm013IdentityTaskDispatchStore.AuthorizedReceiptAttempt(
                headers, lease, mock(LocalAdmission.class), claim,
                now.plusSeconds(30));
        when(service.authorizeReceiptAttempt(claim, body)).thenReturn(attempt);
        when(service.recheckReceiptAttempt(attempt)).thenReturn(attempt);

        var relay = new Hrm013IdentityTaskReceiptRelay(
                service,
                ignored -> URI.create("https://people.internal/"),
                ignored -> response,
                (callbackEventId, retryCount) -> Duration.ofSeconds(1),
                (after, limit) -> List.of(tenant),
                Clock.fixed(now, ZoneOffset.UTC));

        assertThat(relay.relayOne(tenant))
                .contains(Hrm013IdentityTaskReceiptRelay.Disposition.DELIVERED);
        verify(service).recheckReceiptAttempt(attempt);
        verify(service).receiptDelivered(claim, now);
    }
}
