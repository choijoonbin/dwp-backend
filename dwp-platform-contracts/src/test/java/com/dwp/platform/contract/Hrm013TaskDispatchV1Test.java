package com.dwp.platform.contract;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hrm013TaskDispatchV1Test {

    private static final long TENANT_ID = 1;
    private static final UUID CASE_ID = uuid(100);
    private static final UUID TASK_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
    private static final UUID CORRELATION_ID = uuid(102);
    private static final UUID WORKER_ID = uuid(103);
    private static final UUID RELATIONSHIP_ID = uuid(104);
    private static final UUID PERSON_ID = uuid(105);
    private static final Instant DISPATCHED_AT = Instant.parse("2026-10-27T01:00:00Z");

    @Test
    void implementsTheFrozenUuidV5GoldenVectorAndThreeProfiles() {
        assertThat(Hrm013TaskDispatchV1.dispatchEventId(
                1, TASK_ID, 1, "SVC-PEP-TIM-004"))
                .isEqualTo(UUID.fromString("9ddb8198-a9c2-5493-a3a6-1c19fa9a327a"));
        assertThat(Hrm013TaskDispatchV1.PROFILES).containsExactly(
                Hrm013TaskDispatchV1.TIME,
                Hrm013TaskDispatchV1.PAYROLL,
                Hrm013TaskDispatchV1.IDENTITY);
        assertThat(Hrm013TaskDispatchV1.TIME.callbackPurpose())
                .isEqualTo("HRIS_SEPARATION_TIME_TASK_RECEIPT");
        assertThat(Hrm013TaskDispatchV1.PAYROLL.authorityResolverBindingId())
                .isEqualTo("SVC-AUT-HRM-016");
        assertThat(Hrm013TaskDispatchV1.IDENTITY.authorityResolverBindingId())
                .isEqualTo("LOCAL_AUTH_LEDGER");
        assertThat(Hrm013TaskDispatchV1.requireProfile(
                "SVC-AUT-HRM-014", "dwp-auth-server",
                "HRIS_SEPARATION_IDENTITY_TASK_DISPATCH",
                "IDENTITY_ACCESS_REVOCATION"))
                .isEqualTo(Hrm013TaskDispatchV1.IDENTITY);
    }

    @Test
    void roundTripsTimePayrollAndIdentityBodiesWithExactOwnerBinding() {
        var time = timeDispatch(Hrm013TaskDispatchV1.TIME);
        byte[] timeBytes = Hrm013TaskDispatchV1.encode(
                Hrm013TaskDispatchV1.TIME, TENANT_ID, time);
        assertThat(Hrm013TaskDispatchV1.decode(
                Hrm013TaskDispatchV1.TIME, TENANT_ID, timeBytes)).isEqualTo(time);

        var payroll = timeDispatch(Hrm013TaskDispatchV1.PAYROLL);
        byte[] payrollBytes = Hrm013TaskDispatchV1.encode(
                Hrm013TaskDispatchV1.PAYROLL, TENANT_ID, payroll);
        assertThat(Hrm013TaskDispatchV1.decode(
                Hrm013TaskDispatchV1.PAYROLL, TENANT_ID, payrollBytes))
                .isEqualTo(payroll);

        var identity = identityDispatch();
        byte[] identityBytes = Hrm013TaskDispatchV1.encode(
                Hrm013TaskDispatchV1.IDENTITY, TENANT_ID, identity);
        assertThat(Hrm013TaskDispatchV1.decode(
                Hrm013TaskDispatchV1.IDENTITY, TENANT_ID, identityBytes))
                .isEqualTo(identity);

        assertThatThrownBy(() -> Hrm013TaskDispatchV1.encode(
                Hrm013TaskDispatchV1.PAYROLL, TENANT_ID, time))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
        assertThatThrownBy(() -> Hrm013TaskDispatchV1.encode(
                Hrm013TaskDispatchV1.TIME, TENANT_ID, identity))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    @Test
    void rejectsUnknownDuplicateMissingNullCoercionNoncanonicalAndOversize() {
        String canonical = new String(Hrm013TaskDispatchV1.encode(
                Hrm013TaskDispatchV1.TIME, TENANT_ID,
                timeDispatch(Hrm013TaskDispatchV1.TIME)), StandardCharsets.UTF_8);

        assertRejected(canonical.substring(0, canonical.length() - 1)
                + ",\"tenantId\":1}");
        assertRejected(canonical.replaceFirst("\\{",
                "{\"casePublicId\":\"" + CASE_ID + "\","));
        assertRejected(canonical.replaceFirst(
                "\"separationType\":\"VOLUNTARY\",", ""));
        assertRejected(canonical.replace(
                "\"separationType\":\"VOLUNTARY\"",
                "\"separationType\":null"));
        assertRejected(canonical.replace(
                "\"taskVersion\":1", "\"taskVersion\":\"1\""));
        assertRejected(canonical + "\n");

        byte[] oversized = new byte[Hrm013TaskDispatchV1.MAXIMUM_REQUEST_BYTES + 1];
        assertThatThrownBy(() -> Hrm013TaskDispatchV1.decode(
                Hrm013TaskDispatchV1.TIME, TENANT_ID, oversized))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    @Test
    void bindsAdmissionReceiptToExactRequestPayloadAndBodyDigests() {
        var request = timeDispatch(Hrm013TaskDispatchV1.TIME);
        byte[] requestBytes = Hrm013TaskDispatchV1.encode(
                Hrm013TaskDispatchV1.TIME, TENANT_ID, request);
        var receipt = Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1(
                        request.dispatchEventId(), request.taskPublicId(),
                        request.payloadSha256(),
                        Hrm013TaskDispatchV1.requestBodySha256(requestBytes),
                        DISPATCHED_AT.plusSeconds(1), "0".repeat(64),
                        request.correlationId()));

        byte[] receiptBytes = Hrm013TaskDispatchV1.encode(receipt);
        assertThat(Hrm013TaskDispatchV1.decodeAdmissionReceipt(receiptBytes))
                .isEqualTo(receipt);
        Hrm013TaskDispatchV1.requireAdmissionReceiptBinding(
                request, requestBytes, receipt);

        var wrong = Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.Hrm013TaskDispatchAdmissionReceiptV1(
                        request.dispatchEventId(), uuid(999), request.payloadSha256(),
                        Hrm013TaskDispatchV1.requestBodySha256(requestBytes),
                        DISPATCHED_AT.plusSeconds(1), "0".repeat(64),
                        request.correlationId()));
        assertThatThrownBy(() -> Hrm013TaskDispatchV1
                .requireAdmissionReceiptBinding(request, requestBytes, wrong))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    @Test
    void bindsTheSeparateHrm008PurposeOwnerRevisionAndOriginatingReceipt() {
        var dispatch = timeDispatch(Hrm013TaskDispatchV1.TIME);
        var callback = Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        uuid(110), CASE_ID, TASK_ID, "TIME_FINALIZATION", "COMPLETED",
                        1, uuid(111), dispatch.payloadSha256(),
                        DISPATCHED_AT.plusSeconds(3), "0".repeat(64), CORRELATION_ID));
        Hrm013TaskDispatchV1.requireCallbackReceiptBinding(
                Hrm013TaskDispatchV1.TIME, dispatch,
                Hrm013SeparationBridgeV1.HRM_008_TIME, callback);

        assertThatThrownBy(() -> Hrm013TaskDispatchV1.requireCallbackReceiptBinding(
                Hrm013TaskDispatchV1.TIME, dispatch,
                Hrm013SeparationBridgeV1.HRM_008_PAYROLL, callback))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    @Test
    void commandDigestBindsAllNineFrozenValuesAndSafeErrorsStayOpaque() {
        var dispatch = identityDispatch();
        byte[] bytes = Hrm013TaskDispatchV1.encode(
                Hrm013TaskDispatchV1.IDENTITY, TENANT_ID, dispatch);
        String digest = Hrm013TaskDispatchV1.commandDigest(
                Hrm013TaskDispatchV1.IDENTITY, TENANT_ID, bytes);
        assertThat(digest).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(digest).isNotEqualTo(Hrm013TaskDispatchV1.commandDigest(
                Hrm013TaskDispatchV1.IDENTITY, TENANT_ID + 1, bytes));

        UUID correlation = uuid(120);
        var safe = Hrm013SafeErrorV1.map(new RuntimeException(
                "private-key grantSetId personPublicId"), correlation);
        assertThat(safe.bodyUtf8()).isEqualTo(
                "{\"correlationId\":\"" + correlation + "\"}");
        assertThat(safe.bodyUtf8())
                .doesNotContain("private-key", "grantSetId", "personPublicId");
    }

    private static Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch timeDispatch(
            Hrm013TaskDispatchV1.Profile profile) {
        UUID eventId = Hrm013TaskDispatchV1.dispatchEventId(
                TENANT_ID, TASK_ID, 1, profile.bindingId());
        return Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.TimeAndPayrollTaskDispatch(
                        Hrm013TaskDispatchV1.SCHEMA_NAME,
                        Hrm013TaskDispatchV1.SCHEMA_VERSION,
                        eventId, CASE_ID, TASK_ID, profile.taskType(), 1, 1,
                        DISPATCHED_AT, "0".repeat(64), CORRELATION_ID,
                        WORKER_ID, RELATIONSHIP_ID, "VOLUNTARY",
                        LocalDate.parse("2026-11-01"),
                        LocalDate.parse("2026-10-31")));
    }

    private static Hrm013TaskDispatchV1.IdentityTaskDispatch identityDispatch() {
        UUID eventId = Hrm013TaskDispatchV1.dispatchEventId(
                TENANT_ID, TASK_ID, 1, Hrm013TaskDispatchV1.IDENTITY.bindingId());
        return Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.IdentityTaskDispatch(
                        Hrm013TaskDispatchV1.SCHEMA_NAME,
                        Hrm013TaskDispatchV1.SCHEMA_VERSION,
                        eventId, CASE_ID, TASK_ID,
                        Hrm013TaskDispatchV1.IDENTITY.taskType(), 1, 1,
                        DISPATCHED_AT, "0".repeat(64), CORRELATION_ID,
                        PERSON_ID, "VOLUNTARY", LocalDate.parse("2026-11-01")));
    }

    private static void assertRejected(String json) {
        assertThatThrownBy(() -> Hrm013TaskDispatchV1.decode(
                Hrm013TaskDispatchV1.TIME, TENANT_ID,
                json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    private static UUID uuid(long value) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", value));
    }
}
