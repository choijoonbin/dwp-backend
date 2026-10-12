package com.dwp.services.people.hris.approval;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HrisApprovalBridgeProviderConsumerContractTest {

    private static final Instant NOW = Instant.parse("2026-10-12T04:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void peopleConsumerPreparesOnlyTheAtomicCreateAndSubmitBinding() {
        ApprovalRequestV1 body = new ApprovalRequestV1(
                uuid(1), "hris.employee-change.v1", 1,
                "employee-change:00000000-0000-0000-0000-000000000101", 2,
                "person:00000000-0000-0000-0000-000000000102",
                "EMPLOYEE_CHANGE_APPLY",
                "hris-change:00000000-0000-0000-0000-000000000103",
                "a".repeat(64), "PENDING", NOW.minusSeconds(1), uuid(2),
                "hris-change-00000000-0000-0000-0000-000000000104");

        var request = HrisApprovalBridgeV1.createAndSubmitRequest(mapper, 17, body);

        assertThat(request.endpoint().bindingId()).isEqualTo("SVC-PEP-APR-001");
        assertThat(request.endpoint().ownerWorkloadId())
                .isEqualTo("dwp-approval-server");
        assertThat(request.endpoint().callerWorkloadId())
                .isEqualTo("dwp-people-server");
        assertThat(request.endpoint().purpose())
                .isEqualTo("HRIS_EMPLOYEE_CHANGE_APPROVAL_REQUEST_SUBMIT");
        assertThat(request.path())
                .isEqualTo("/internal/hris-approval/v1/approval-requests");
        assertThat(request.bodySha256())
                .isEqualTo(HrisApprovalBridgeV1.sha256(request.bodyBytes()));
    }

    @Test
    void peopleProviderAcceptsTheExactCallbackAndKeepsInboxIdempotency() {
        AtomicInteger executions = new AtomicInteger();
        var dispatcher = new HrisApprovalBridgeV1.DecisionCallbackDispatcher<>(
                mapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new HrisApprovalBridgeV1.InMemoryReplayStore(),
                new HrisApprovalBridgeV1.InMemoryAtomicIdempotencyStore<String>(),
                (tenantId, requestPublicId, body) -> {
                    executions.incrementAndGet();
                    return "receipt:" + requestPublicId + ":" + body.decisionEventId();
                });
        UUID requestPublicId = uuid(40);
        var body = callback("APPROVED", "b".repeat(64));
        var outbound = HrisApprovalBridgeV1.decisionCallbackRequest(
                mapper, 17, requestPublicId, body);

        String first = dispatcher.dispatch(
                requestPublicId, evidence(outbound, uuid(50), uuid(51)),
                Set.of("Content-Type", "X-DWP-Workload-Proof"), body);
        String duplicate = dispatcher.dispatch(
                requestPublicId, evidence(outbound, uuid(52), uuid(53)),
                Set.of("Content-Type", "X-DWP-Workload-Proof"), body);
        String mtlsDuplicate = dispatcher.dispatch(
                requestPublicId, mtlsEvidence(outbound, uuid(54), uuid(55)),
                Set.of("Content-Type", "X-DWP-Workload-Proof"), body);

        assertThat(first).isEqualTo(duplicate).isEqualTo(mtlsDuplicate);
        assertThat(executions).hasValue(1);
        assertThat(outbound.endpoint().bindingId()).isEqualTo("SVC-PEP-HRM-001");
        assertThat(outbound.path()).isEqualTo(
                "/internal/hris-people/v1/employee-change-requests/"
                        + requestPublicId + "/approval-decisions");
    }

    @Test
    void callbackRejectsSameEventWithChangedRequestAndMtlsJwtProofMixing() {
        var dispatcher = new HrisApprovalBridgeV1.DecisionCallbackDispatcher<>(
                mapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new HrisApprovalBridgeV1.InMemoryReplayStore(),
                new HrisApprovalBridgeV1.InMemoryAtomicIdempotencyStore<String>(),
                (tenantId, requestPublicId, body) -> "ok");
        UUID requestPublicId = uuid(60);
        var original = callback("APPROVED", "c".repeat(64));
        var first = HrisApprovalBridgeV1.decisionCallbackRequest(
                mapper, 23, requestPublicId, original);
        dispatcher.dispatch(requestPublicId, evidence(first, uuid(61), uuid(62)),
                Set.of(), original);

        var changed = callback("REJECTED", "d".repeat(64));
        var second = HrisApprovalBridgeV1.decisionCallbackRequest(
                mapper, 23, requestPublicId, changed);
        assertThatThrownBy(() -> dispatcher.dispatch(
                requestPublicId, evidence(second, uuid(63), uuid(64)),
                Set.of(), changed))
                .isInstanceOfSatisfying(HrisApprovalBridgeV1.BridgeFailure.class,
                        failure -> assertThat(failure.httpStatus()).isEqualTo(409));

        var jwt = evidence(second, uuid(65), uuid(66));
        var mixed = new HrisApprovalBridgeV1.AdmissionEvidence(
                jwt.bindingId(), jwt.method(), jwt.path(), jwt.callerWorkloadId(),
                jwt.audience(), jwt.tenantId(), jwt.purpose(), jwt.bodySha256(),
                jwt.issuedAt(), jwt.expiresAt(), jwt.proofId(), jwt.requestNonce(),
                HrisApprovalBridgeV1.WorkloadAuthentication.MTLS_SPIFFE,
                true, true, true, true, true, false, true);
        assertThatThrownBy(() -> dispatcher.dispatch(
                requestPublicId, mixed, Set.of(), changed))
                .isInstanceOfSatisfying(HrisApprovalBridgeV1.BridgeFailure.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo("MTLS_SPIFFE_MUST_NOT_BORROW_JWT_AUDIENCE_PROOF"));
    }

    private static HrisApprovalBridgeV1.ApprovalDecisionCallbackV1 callback(
            String decision, String payloadDigest) {
        return new HrisApprovalBridgeV1.ApprovalDecisionCallbackV1(
                uuid(70), uuid(71), decision, NOW.minusSeconds(2), uuid(72),
                5, uuid(73), payloadDigest);
    }

    private static HrisApprovalBridgeV1.AdmissionEvidence evidence(
            HrisApprovalBridgeV1.OutboundRequest<?> request,
            UUID proofId,
            UUID nonce) {
        return new HrisApprovalBridgeV1.AdmissionEvidence(
                request.endpoint().bindingId(), request.endpoint().method(),
                request.path(), request.endpoint().callerWorkloadId(),
                request.endpoint().audience(), request.tenantId(),
                request.endpoint().purpose(), request.bodySha256(),
                NOW.minusSeconds(1), NOW.plusSeconds(20), proofId, nonce,
                HrisApprovalBridgeV1.WorkloadAuthentication.CLIENT_CREDENTIAL_JWT,
                true, true, true, true, true, false, true);
    }

    private static HrisApprovalBridgeV1.AdmissionEvidence mtlsEvidence(
            HrisApprovalBridgeV1.OutboundRequest<?> request,
            UUID proofId,
            UUID nonce) {
        var jwt = evidence(request, proofId, nonce);
        return new HrisApprovalBridgeV1.AdmissionEvidence(
                jwt.bindingId(), jwt.method(), jwt.path(), jwt.callerWorkloadId(),
                jwt.audience(), jwt.tenantId(), jwt.purpose(), jwt.bodySha256(),
                jwt.issuedAt(), jwt.expiresAt(), jwt.proofId(), jwt.requestNonce(),
                HrisApprovalBridgeV1.WorkloadAuthentication.MTLS_SPIFFE,
                true, false, true, true, true, false, true);
    }

    private static UUID uuid(int suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
