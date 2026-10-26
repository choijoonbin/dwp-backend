package com.dwp.platform.contract;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hrm013SeparationBridgeV1Test {

    private static final UUID APPROVAL_ID = uuid(1);
    private static final UUID CASE_ID = uuid(2);
    private static final UUID TASK_ID = uuid(3);
    private static final UUID MAKER_ID = uuid(4);
    private static final UUID REQUESTER_ID = uuid(5);
    private static final UUID CALLBACK_ID = uuid(6);
    private static final UUID ACTOR_ID = uuid(7);
    private static final UUID CORRELATION_ID = uuid(8);
    private static final UUID EXTERNAL_RECEIPT_ID = uuid(9);
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-27T00:00:00Z");
    private static final String ORIGIN_DIGEST = "a".repeat(64);

    @Test
    void sealsTheExactApr002AndHrm008ThroughHrm010Profiles() {
        assertThat(Hrm013SeparationBridgeV1.PROFILES).hasSize(7);
        assertThat(Hrm013SeparationBridgeV1.APR_002.path(null, null))
                .isEqualTo("/internal/hris-approval/v1/separation-task-waiver-requests");
        assertThat(Hrm013SeparationBridgeV1.HRM_008_TIME.path(CASE_ID, TASK_ID))
                .isEqualTo("/internal/hris-people/v1/separation-cases/" + CASE_ID
                        + "/tasks/" + TASK_ID + "/receipts");
        assertThat(Hrm013SeparationBridgeV1.HRM_009_PAYROLL.path(CASE_ID, null))
                .endsWith("/reconciliation-results");
        assertThat(Hrm013SeparationBridgeV1.HRM_010.path(CASE_ID, TASK_ID))
                .endsWith("/waiver-decisions");

        assertThat(Hrm013SeparationBridgeV1.requireProfile(
                "SVC-PEP-HRM-008", "dwp-auth-server",
                "HRIS_SEPARATION_IDENTITY_TASK_RECEIPT",
                "IDENTITY_ACCESS_REVOCATION"))
                .isEqualTo(Hrm013SeparationBridgeV1.HRM_008_IDENTITY);
        assertThatThrownBy(() -> Hrm013SeparationBridgeV1.requireProfile(
                "SVC-PEP-HRM-008", "dwp-auth-server",
                "HRIS_SEPARATION_TIME_TASK_RECEIPT", "TIME_FINALIZATION"))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    @Test
    void roundTripsEveryClosedBodyAndReceiptWithStableDigests() {
        var waiver = waiverRequest();
        byte[] waiverBytes = Hrm013SeparationBridgeV1.encode(waiver);
        assertThat(Hrm013SeparationBridgeV1.decodeWaiverRequest(waiverBytes))
                .isEqualTo(waiver);

        var taskReceipt = taskReceipt();
        assertThat(Hrm013SeparationBridgeV1.decodeTaskReceipt(
                Hrm013SeparationBridgeV1.encode(taskReceipt)))
                .isEqualTo(taskReceipt);

        var reconciliation = reconciliation();
        assertThat(Hrm013SeparationBridgeV1.decodeReconciliationResult(
                Hrm013SeparationBridgeV1.encode(reconciliation)))
                .isEqualTo(reconciliation);

        var rejected = rejectedDecision();
        assertThat(Hrm013SeparationBridgeV1.decodeWaiverDecision(
                Hrm013SeparationBridgeV1.encode(rejected)))
                .isEqualTo(rejected);

        var approved = approvedDecision();
        Hrm013SeparationBridgeV1.requireStepUpEvidenceSha256(41, approved);
        assertThat(Hrm013SeparationBridgeV1.decodeWaiverDecision(
                Hrm013SeparationBridgeV1.encode(approved)))
                .isEqualTo(approved);

        String commandDigest = Hrm013SeparationBridgeV1.commandDigest(
                Hrm013SeparationBridgeV1.APR_002,
                Hrm013SeparationBridgeV1.APR_002.path(null, null), 41, waiverBytes);
        String expected = sha256(String.join("\n",
                "SVC-PEP-APR-002", "POST",
                "/internal/hris-approval/v1/separation-task-waiver-requests",
                "dwp-people-server", "dwp-approval-server", "41",
                "HRIS_SEPARATION_TASK_WAIVER_REQUEST", "application/json",
                sha256(waiverBytes)).getBytes(StandardCharsets.UTF_8));
        assertThat(commandDigest).isEqualTo(expected);

        var waiverReceipt = new Hrm013SeparationBridgeV1
                .SeparationWaiverApprovalSubmissionReceiptV1(
                APPROVAL_ID, 1, "PENDING", commandDigest, CORRELATION_ID,
                OCCURRED_AT.plusSeconds(1));
        assertThat(Hrm013SeparationBridgeV1.decodeWaiverReceipt(
                Hrm013SeparationBridgeV1.encode(waiverReceipt)))
                .isEqualTo(waiverReceipt);

        var callbackReceipt = new Hrm013SeparationBridgeV1.SeparationCallbackReceiptV1(
                uuid(30), CALLBACK_ID, "TASK_RECEIPT", CASE_ID, TASK_ID, 1,
                "ACCEPTED", CORRELATION_ID, commandDigest, OCCURRED_AT.plusSeconds(2));
        assertThat(Hrm013SeparationBridgeV1.decodeCallbackReceipt(
                Hrm013SeparationBridgeV1.encode(callbackReceipt)))
                .isEqualTo(callbackReceipt);
    }

    @Test
    void rejectsUnknownDuplicateMissingNullCoercedNoncanonicalAndOversizeJson() {
        String canonical = new String(
                Hrm013SeparationBridgeV1.encode(waiverRequest()),
                StandardCharsets.UTF_8);

        assertMalformed(canonical.substring(0, canonical.length() - 1)
                + ",\"tenantId\":41}");
        assertMalformed(canonical.replaceFirst(
                "\\{", "{\"approvalRequestId\":\"" + APPROVAL_ID + "\","));
        assertMalformed(canonical.replaceFirst(
                "\"reasonCode\":\"MANAGER_REQUEST\",", ""));
        assertMalformed(canonical.replace(
                "\"reasonCode\":\"MANAGER_REQUEST\"", "\"reasonCode\":null"));
        assertMalformed(canonical.replace(
                "\"taskOwnerRevision\":1", "\"taskOwnerRevision\":\"1\""));
        assertMalformed(" " + canonical);

        byte[] oversized = new byte[Hrm013SeparationBridgeV1.MAXIMUM_REQUEST_BYTES + 1];
        assertThatThrownBy(() -> Hrm013SeparationBridgeV1.decodeWaiverRequest(oversized))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(value -> ((Hrm013SafeErrorV1.Failure) value).errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.MALFORMED_REQUEST);
    }

    @Test
    void separatesPayloadOriginAndBodyDigestsAndRejectsDigestDrift() {
        var receipt = taskReceipt();
        byte[] exact = Hrm013SeparationBridgeV1.encode(receipt);
        assertThat(receipt.originatingPayloadSha256()).isEqualTo(ORIGIN_DIGEST);
        assertThat(receipt.payloadSha256()).isNotEqualTo(ORIGIN_DIGEST);
        assertThat(Hrm013SeparationBridgeV1.requestBodySha256(exact))
                .isEqualTo(sha256(exact));

        var changed = receipt.withPayloadSha256("f".repeat(64));
        assertThatThrownBy(() -> Hrm013SeparationBridgeV1.encode(changed))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class)
                .extracting(value -> ((Hrm013SafeErrorV1.Failure) value).errorCode())
                .isEqualTo(Hrm013SafeErrorV1.ErrorCode.DIGEST_CONFLICT);
    }

    @Test
    void safeErrorAllowlistCannotDiscloseSecretGrantOrIdentityDetail() {
        UUID opaque = uuid(99);
        var mapped = Hrm013SafeErrorV1.map(new IllegalStateException(
                "secret=PRIVATE_KEY tenantGrantRevision=812 person=" + ACTOR_ID), opaque);
        assertThat(mapped.httpStatus()).isEqualTo(500);
        assertThat(mapped.bodyUtf8()).isEqualTo("{\"correlationId\":\"" + opaque + "\"}");
        assertThat(mapped.bodyUtf8())
                .doesNotContain("PRIVATE_KEY", "tenantGrantRevision", ACTOR_ID.toString());

        assertThat(Hrm013SafeErrorV1.map(
                Hrm013SafeErrorV1.authorityDenied(), opaque).emptyBody()).isTrue();
        assertThat(Hrm013SafeErrorV1.map(
                Hrm013SafeErrorV1.revisionConflict(), opaque).bodyUtf8())
                .isEqualTo("{\"code\":\"REVISION_CONFLICT\"}");
        assertThat(Hrm013SafeErrorV1.map(
                Hrm013SafeErrorV1.authorityUnavailable(7), opaque)
                .retryAfterSeconds()).isEqualTo(7L);
    }

    private static Hrm013SeparationBridgeV1.SeparationWaiverApprovalRequestV1
    waiverRequest() {
        return Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationWaiverApprovalRequestV1(
                        APPROVAL_ID, CASE_ID, TASK_ID, "TIME_FINALIZATION", MAKER_ID,
                        REQUESTER_ID, "MANAGER_REQUEST", 1, 0, OCCURRED_AT,
                        "0".repeat(64), CORRELATION_ID));
    }

    private static Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 taskReceipt() {
        return Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        CALLBACK_ID, CASE_ID, TASK_ID, "TIME_FINALIZATION", "COMPLETED",
                        1, EXTERNAL_RECEIPT_ID, ORIGIN_DIGEST, OCCURRED_AT,
                        "0".repeat(64), CORRELATION_ID));
    }

    private static Hrm013SeparationBridgeV1.SeparationReconciliationResultV1
    reconciliation() {
        return Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationReconciliationResultV1(
                        CALLBACK_ID, CASE_ID, "CONSISTENT", 1, ORIGIN_DIGEST,
                        OCCURRED_AT, "0".repeat(64), CORRELATION_ID));
    }

    private static Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2
    rejectedDecision() {
        return Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2(
                        CALLBACK_ID, APPROVAL_ID, CASE_ID, TASK_ID, "REJECTED", ACTOR_ID,
                        1, 1, "POLICY_REJECTED", ORIGIN_DIGEST,
                        null, null, null, null, null, null, null,
                        OCCURRED_AT, "0".repeat(64), CORRELATION_ID));
    }

    private static Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2
    approvedDecision() {
        UUID evidenceId = uuid(20);
        UUID nonce = uuid(21);
        Instant verifiedAt = OCCURRED_AT.minusSeconds(5);
        var provisional = new Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2(
                CALLBACK_ID, APPROVAL_ID, CASE_ID, TASK_ID, "APPROVED", ACTOR_ID,
                1, 1, "POLICY_APPROVED", ORIGIN_DIGEST, evidenceId,
                "dwp-people-server", "HRIS_SEPARATION_TASK_WAIVER_DECISION", nonce,
                "0".repeat(64), verifiedAt, OCCURRED_AT.minusSeconds(1),
                OCCURRED_AT, "0".repeat(64), CORRELATION_ID);
        String evidenceDigest = Hrm013SeparationBridgeV1
                .stepUpEvidenceSha256(41, provisional);
        return Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2(
                        CALLBACK_ID, APPROVAL_ID, CASE_ID, TASK_ID, "APPROVED", ACTOR_ID,
                        1, 1, "POLICY_APPROVED", ORIGIN_DIGEST, evidenceId,
                        "dwp-people-server", "HRIS_SEPARATION_TASK_WAIVER_DECISION",
                        nonce, evidenceDigest, verifiedAt, OCCURRED_AT.minusSeconds(1),
                        OCCURRED_AT, "0".repeat(64), CORRELATION_ID));
    }

    private static void assertMalformed(String json) {
        assertThatThrownBy(() -> Hrm013SeparationBridgeV1.decodeWaiverRequest(
                json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    private static UUID uuid(long value) {
        return UUID.fromString("00000000-0000-0000-0000-" + String.format("%012d", value));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
