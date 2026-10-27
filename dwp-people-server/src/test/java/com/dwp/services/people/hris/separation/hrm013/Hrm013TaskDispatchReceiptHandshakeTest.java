package com.dwp.services.people.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hrm013TaskDispatchReceiptHandshakeTest {
    private static final long TENANT_ID = 73L;
    private static final Instant ADMITTED_AT =
            Instant.parse("2026-10-27T06:30:00Z");
    private static final String ADMISSION_PROOF = "owner-admission-proof";
    private static final Map<Hrm013TaskDispatchV1.Profile, URI> ENDPOINTS = Map.of(
            Hrm013TaskDispatchV1.TIME,
            URI.create("https://time.hrm013.internal"
                    + Hrm013TaskDispatchV1.TIME.path()),
            Hrm013TaskDispatchV1.PAYROLL,
            URI.create("https://payroll.hrm013.internal"
                    + Hrm013TaskDispatchV1.PAYROLL.path()),
            Hrm013TaskDispatchV1.IDENTITY,
            URI.create("https://auth.hrm013.internal"
                    + Hrm013TaskDispatchV1.IDENTITY.path()));

    @Test
    void allThreeProfilesCompleteAdmissionToCallbackReceiptHandshake() {
        Hrm013TaskDispatchClient client = ownerBackedClient();

        for (Hrm013TaskDispatchV1.Profile profile
                : Hrm013TaskDispatchV1.PROFILES) {
            var fence = Hrm013RuntimeTestSupport.fence(profile, TENANT_ID);
            var delivery = client.send(
                    fence,
                    Hrm013RuntimeTestSupport.headers(fence.correlationId()));
            var dispatch = Hrm013TaskDispatchV1.decode(
                    profile, TENANT_ID, fence.exactRequestBody());
            var admission = Hrm013TaskDispatchV1.decodeAdmissionReceipt(
                    delivery.exactBody());

            Hrm013TaskDispatchV1.requireAdmissionReceiptBinding(
                    profile, TENANT_ID, dispatch, fence.exactRequestBody(), admission);
            assertThat(delivery.responseBodySha256()).isEqualTo(
                    Hrm013SeparationBridgeV1.sha256(delivery.exactBody()));
            assertThat(delivery.receiptSha256()).isEqualTo(
                    admission.receiptSha256());
            assertThat(delivery.admittedAt()).isEqualTo(ADMITTED_AT);

            var callback = taskReceipt(dispatch, admission);
            byte[] exactCallback = Hrm013SeparationBridgeV1.encode(callback);
            var decodedCallback = Hrm013SeparationBridgeV1.decodeTaskReceipt(
                    exactCallback);
            var callbackProfile = callbackProfile(profile);

            Hrm013TaskDispatchV1.requireCallbackReceiptBinding(
                    profile, dispatch, callbackProfile, decodedCallback);
            assertThat(decodedCallback.originatingPayloadSha256()).isEqualTo(
                    admission.payloadSha256());

            String path = callbackProfile.path(
                    decodedCallback.casePublicId(), decodedCallback.taskPublicId());
            String commandDigest = Hrm013SeparationBridgeV1.commandDigest(
                    callbackProfile, path, TENANT_ID, exactCallback);
            var accepted = new Hrm013SeparationBridgeV1.SeparationCallbackReceiptV1(
                    UUID.randomUUID(), decodedCallback.callbackEventId(),
                    "TASK_RECEIPT", decodedCallback.casePublicId(),
                    decodedCallback.taskPublicId(), decodedCallback.ownerRevision(),
                    "ACCEPTED", decodedCallback.correlationId(), commandDigest,
                    ADMITTED_AT.plusSeconds(2));

            assertThat(Hrm013SeparationBridgeV1.decodeCallbackReceipt(
                    Hrm013SeparationBridgeV1.encode(accepted)))
                    .isEqualTo(accepted);
        }
    }

    @Test
    void crossOwnerCallbackSubstitutionFailsClosed() {
        var packet = Hrm013RuntimeTestSupport.dispatch(
                Hrm013TaskDispatchV1.TIME, TENANT_ID);
        byte[] exactDispatch = Hrm013TaskDispatchV1.encode(
                Hrm013TaskDispatchV1.TIME, TENANT_ID, packet.body());
        var admission = admission(packet.body(), exactDispatch);
        var callback = taskReceipt(packet.body(), admission);

        assertThatThrownBy(() ->
                Hrm013TaskDispatchV1.requireCallbackReceiptBinding(
                        Hrm013TaskDispatchV1.TIME, packet.body(),
                        Hrm013SeparationBridgeV1.HRM_008_PAYROLL, callback))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    private static Hrm013TaskDispatchClient ownerBackedClient() {
        return new Hrm013TaskDispatchClient(
                ENDPOINTS::get,
                request -> {
                    Hrm013TaskDispatchV1.Profile profile = profile(request.endpoint());
                    var dispatch = Hrm013TaskDispatchV1.decode(
                            profile, TENANT_ID, request.exactBody());
                    var receipt = admission(dispatch, request.exactBody());
                    return new Hrm013TaskDispatchClient.Exchange(
                            202, Hrm013TaskDispatchV1.encode(receipt),
                            ADMISSION_PROOF, "TLSv1.3",
                            request.expectedPeerSpiffeId(), request.expectedDnsName(),
                            false, ADMITTED_AT);
                },
                (profile, fence, receipt, exchange) ->
                        ADMISSION_PROOF.equals(exchange.responseProof())
                                && fence.dispatchEventId().equals(
                                receipt.dispatchEventId())
                                && fence.correlationId().equals(
                                receipt.correlationId()));
    }

    private static Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1
            admission(Hrm013TaskDispatchV1.DispatchBody dispatch,
            byte[] exactDispatch) {
        return Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1(
                        dispatch.dispatchEventId(), dispatch.taskPublicId(),
                        dispatch.payloadSha256(),
                        Hrm013TaskDispatchV1.requestBodySha256(exactDispatch),
                        ADMITTED_AT, Hrm013RuntimeTestSupport.ZERO_SHA,
                        dispatch.correlationId()));
    }

    private static Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 taskReceipt(
            Hrm013TaskDispatchV1.DispatchBody dispatch,
            Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1 admission) {
        return Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        UUID.randomUUID(), dispatch.casePublicId(),
                        dispatch.taskPublicId(), dispatch.taskType(), "COMPLETED",
                        dispatch.taskOwnerRevision(), UUID.randomUUID(),
                        admission.payloadSha256(), ADMITTED_AT.plusSeconds(1),
                        Hrm013RuntimeTestSupport.ZERO_SHA,
                        dispatch.correlationId()));
    }

    private static Hrm013TaskDispatchV1.Profile profile(URI endpoint) {
        return Hrm013TaskDispatchV1.PROFILES.stream()
                .filter(candidate -> candidate.path().equals(endpoint.getPath()))
                .findFirst()
                .orElseThrow();
    }

    private static Hrm013SeparationBridgeV1.BindingProfile callbackProfile(
            Hrm013TaskDispatchV1.Profile profile) {
        if (profile.equals(Hrm013TaskDispatchV1.TIME)) {
            return Hrm013SeparationBridgeV1.HRM_008_TIME;
        }
        if (profile.equals(Hrm013TaskDispatchV1.PAYROLL)) {
            return Hrm013SeparationBridgeV1.HRM_008_PAYROLL;
        }
        if (profile.equals(Hrm013TaskDispatchV1.IDENTITY)) {
            return Hrm013SeparationBridgeV1.HRM_008_IDENTITY;
        }
        throw new IllegalArgumentException("unknown HRM-013 task dispatch profile");
    }
}
