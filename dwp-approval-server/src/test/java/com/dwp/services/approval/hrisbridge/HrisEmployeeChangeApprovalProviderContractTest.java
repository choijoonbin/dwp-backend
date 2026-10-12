package com.dwp.services.approval.hrisbridge;

import com.dwp.platform.contracts.hris.approval.v1.HrisApprovalBridgeV1;
import com.dwp.platform.contracts.hris.generated.ApprovalRequestV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dwp.platform.contracts.hris.approval.v1.HrisApprovalBridgeV1.CREATE_AND_SUBMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HrisEmployeeChangeApprovalProviderContractTest {

    private static final Instant NOW = Instant.parse("2026-10-12T03:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicInteger executions = new AtomicInteger();
    private final HrisApprovalBridgeV1.CreateAndSubmitDispatcher dispatcher =
            new HrisApprovalBridgeV1.CreateAndSubmitDispatcher(
                    mapper,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    new HrisApprovalBridgeV1.InMemoryReplayStore(),
                    new HrisApprovalBridgeV1.InMemoryAtomicIdempotencyStore<>(),
                    (tenantId, request) -> {
                        executions.incrementAndGet();
                        return new HrisApprovalBridgeV1.CreateAndSubmitReceiptV1(
                                request.requestId(), 1, "PENDING", "b".repeat(64));
                    });

    @Test
    void atomicallyCreatesAndSubmitsPdx010AndReplaysTheOriginalReceipt() {
        ApprovalRequestV1 request = request("a".repeat(64));
        var outbound = HrisApprovalBridgeV1.createAndSubmitRequest(
                mapper, 7, request);

        var first = dispatcher.dispatch(
                evidence(outbound, uuid(10), uuid(11)),
                Set.of("Content-Type", "X-DWP-Workload-Proof"), request);
        var replay = dispatcher.dispatch(
                evidence(outbound, uuid(12), uuid(13)),
                Set.of("Content-Type", "X-DWP-Workload-Proof"), request);

        assertThat(first).isSameAs(replay);
        assertThat(first.requestId()).isEqualTo(request.requestId());
        assertThat(first.state()).isEqualTo("PENDING");
        assertThat(executions).hasValue(1);
        assertThat(outbound.endpoint()).isEqualTo(CREATE_AND_SUBMIT);
        assertThat(outbound.path())
                .isEqualTo("/internal/hris-approval/v1/approval-requests")
                .doesNotEndWith("/submit");
    }

    @Test
    void rejectsIdempotencyDigestMismatchAndTransportReplay() {
        ApprovalRequestV1 original = request("a".repeat(64));
        var first = HrisApprovalBridgeV1.createAndSubmitRequest(mapper, 7, original);
        dispatcher.dispatch(evidence(first, uuid(20), uuid(21)), Set.of(), original);

        ApprovalRequestV1 changed = request("c".repeat(64));
        var changedOutbound = HrisApprovalBridgeV1.createAndSubmitRequest(
                mapper, 7, changed);
        assertThatThrownBy(() -> dispatcher.dispatch(
                evidence(changedOutbound, uuid(22), uuid(23)), Set.of(), changed))
                .isInstanceOfSatisfying(HrisApprovalBridgeV1.BridgeFailure.class,
                        failure -> {
                            assertThat(failure.httpStatus()).isEqualTo(409);
                            assertThat(failure.reason())
                                    .isEqualTo("IDEMPOTENCY_KEY_PAYLOAD_MISMATCH");
                        });

        var replayEvidence = evidence(first, uuid(24), uuid(25));
        dispatcher.dispatch(replayEvidence, Set.of(), original);
        assertThatThrownBy(() -> dispatcher.dispatch(
                replayEvidence, Set.of(), original))
                .isInstanceOfSatisfying(HrisApprovalBridgeV1.BridgeFailure.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo("TRANSPORT_PROOF_REPLAYED"));
    }

    @Test
    void failsClosedForBrowserHeadersWrongPurposeAndLongLivedProof() {
        ApprovalRequestV1 request = request("d".repeat(64));
        var outbound = HrisApprovalBridgeV1.createAndSubmitRequest(mapper, 8, request);

        assertThatThrownBy(() -> dispatcher.dispatch(
                evidence(outbound, uuid(30), uuid(31)),
                Set.of("Authorization"), request))
                .isInstanceOfSatisfying(HrisApprovalBridgeV1.BridgeFailure.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo("BROWSER_OR_END_USER_HEADER_FORBIDDEN"));

        var valid = evidence(outbound, uuid(32), uuid(33));
        var wrongPurpose = copy(valid, "HRIS_APPROVAL_DRAFT_CREATE",
                valid.expiresAt(), valid.bodySha256(), true);
        assertThatThrownBy(() -> dispatcher.dispatch(
                wrongPurpose, Set.of(), request))
                .isInstanceOfSatisfying(HrisApprovalBridgeV1.BridgeFailure.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo("EXACT_INTERNAL_BINDING_EVIDENCE_REQUIRED"));

        var tooLong = copy(valid, valid.purpose(), NOW.plusSeconds(31),
                valid.bodySha256(), true);
        assertThatThrownBy(() -> dispatcher.dispatch(tooLong, Set.of(), request))
                .isInstanceOfSatisfying(HrisApprovalBridgeV1.BridgeFailure.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo("FRESH_NON_REPLAYABLE_PROOF_REQUIRED"));

        var wrongDigest = copy(valid, valid.purpose(), valid.expiresAt(),
                "e".repeat(64), true);
        assertThatThrownBy(() -> dispatcher.dispatch(wrongDigest, Set.of(), request))
                .isInstanceOfSatisfying(HrisApprovalBridgeV1.BridgeFailure.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo("EXACT_INTERNAL_BINDING_EVIDENCE_REQUIRED"));

        var tenantMismatch = copy(valid, valid.purpose(), valid.expiresAt(),
                valid.bodySha256(), false);
        assertThatThrownBy(() -> dispatcher.dispatch(
                tenantMismatch, Set.of(), request))
                .isInstanceOfSatisfying(HrisApprovalBridgeV1.BridgeFailure.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo("EXACT_INTERNAL_BINDING_EVIDENCE_REQUIRED"));
    }

    private static ApprovalRequestV1 request(String payloadDigest) {
        return new ApprovalRequestV1(
                uuid(1),
                "hris.employee-change.v1",
                1,
                "employee-change:00000000-0000-0000-0000-000000000101",
                4,
                "person:00000000-0000-0000-0000-000000000102",
                "EMPLOYEE_CHANGE_APPLY",
                "hris-change:00000000-0000-0000-0000-000000000103",
                payloadDigest,
                "PENDING",
                NOW.minusSeconds(2),
                uuid(2),
                "hris-change-00000000-0000-0000-0000-000000000104");
    }

    private static HrisApprovalBridgeV1.AdmissionEvidence evidence(
            HrisApprovalBridgeV1.OutboundRequest<?> request,
            UUID proofId,
            UUID nonce) {
        return new HrisApprovalBridgeV1.AdmissionEvidence(
                request.endpoint().bindingId(),
                request.endpoint().method(),
                request.path(),
                request.endpoint().callerWorkloadId(),
                request.endpoint().audience(),
                request.tenantId(),
                request.endpoint().purpose(),
                request.bodySha256(),
                NOW.minusSeconds(1),
                NOW.plusSeconds(20),
                proofId,
                nonce,
                HrisApprovalBridgeV1.WorkloadAuthentication.CLIENT_CREDENTIAL_JWT,
                true, true, true, true, true, false, true);
    }

    private static HrisApprovalBridgeV1.AdmissionEvidence copy(
            HrisApprovalBridgeV1.AdmissionEvidence value,
            String purpose,
            Instant expiresAt,
            String bodySha256,
            boolean targetTenantMatched) {
        return new HrisApprovalBridgeV1.AdmissionEvidence(
                value.bindingId(), value.method(), value.path(),
                value.callerWorkloadId(), value.audience(), value.tenantId(),
                purpose, bodySha256, value.issuedAt(), expiresAt,
                value.proofId(), value.requestNonce(), value.authentication(),
                value.workloadAuthenticated(), value.audiencePinned(),
                value.signedTenantClaim(), value.ownerTenantGrantRevalidated(),
                targetTenantMatched, value.callerTenantOverridePresent(),
                value.tls13());
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
