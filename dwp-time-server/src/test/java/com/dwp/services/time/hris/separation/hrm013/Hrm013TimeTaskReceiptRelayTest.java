package com.dwp.services.time.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Hrm013TimeTaskReceiptRelayTest {
    @Test
    void retryUsesStableBoundedFullJitterForEachAttempt() {
        UUID callback = UUID.fromString("6d06397d-57e2-48d4-b12a-a8e167496f32");
        for (int attempt = 1; attempt <= 10; attempt++) {
            long cap = Math.min(300L, 1L << Math.min(attempt, 9));
            long first = Hrm013TimeTaskReceiptRelay.boundedFullJitterSeconds(
                    callback, attempt);
            assertThat(first).isBetween(0L, cap);
            assertThat(Hrm013TimeTaskReceiptRelay.boundedFullJitterSeconds(
                    callback, attempt)).isEqualTo(first);
        }
        assertThatThrownBy(() -> Hrm013TimeTaskReceiptRelay
                .boundedFullJitterSeconds(callback, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void terminalReceiptIsCanonicalAndBoundToTheTimeTaskLineage() {
        UUID event = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID external = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        String originating = "a".repeat(64);
        var receipt = Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        event, caseId, taskId, "TIME_FINALIZATION", "COMPLETED", 2,
                        external, originating, Instant.parse("2026-10-27T01:00:00Z"),
                        "0".repeat(64), correlation));

        Hrm013SeparationBridgeV1.requireBodyBinding(
                Hrm013SeparationBridgeV1.HRM_008_TIME, receipt);
        byte[] exact = Hrm013SeparationBridgeV1.encode(receipt);
        var decoded = Hrm013SeparationBridgeV1.decodeTaskReceipt(exact);

        assertThat(decoded).isEqualTo(receipt);
        assertThat(decoded.ownerRevision()).isEqualTo(2);
        assertThat(decoded.originatingPayloadSha256()).isEqualTo(originating);

        long tenantId = 41;
        String path = Hrm013SeparationBridgeV1.HRM_008_TIME.path(caseId, taskId);
        String command = Hrm013SeparationBridgeV1.commandDigest(
                Hrm013SeparationBridgeV1.HRM_008_TIME, path, tenantId, exact);
        var accepted = new Hrm013SeparationBridgeV1.SeparationCallbackReceiptV1(
                UUID.randomUUID(), event, "TASK_RECEIPT", caseId, taskId, 2,
                "ACCEPTED", correlation, command,
                Instant.parse("2026-10-27T01:00:01Z"));
        Hrm013TimeTaskReceiptRelay.requireAcceptedReceipt(
                tenantId, path, exact, receipt, accepted);

        var rejected = new Hrm013SeparationBridgeV1.SeparationCallbackReceiptV1(
                UUID.randomUUID(), event, "TASK_RECEIPT", caseId, taskId, 2,
                "REJECTED", correlation, command,
                Instant.parse("2026-10-27T01:00:01Z"));
        assertThatThrownBy(() -> Hrm013TimeTaskReceiptRelay.requireAcceptedReceipt(
                tenantId, path, exact, receipt, rejected))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);

        var wrongDigest = new Hrm013SeparationBridgeV1.SeparationCallbackReceiptV1(
                UUID.randomUUID(), event, "TASK_RECEIPT", caseId, taskId, 2,
                "ACCEPTED", correlation, "f".repeat(64),
                Instant.parse("2026-10-27T01:00:01Z"));
        assertThatThrownBy(() -> Hrm013TimeTaskReceiptRelay.requireAcceptedReceipt(
                tenantId, path, exact, receipt, wrongDigest))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);

        var record = new Hrm013TimeTaskDispatchStore.CallbackRecord(
                tenantId, UUID.randomUUID(), UUID.randomUUID(), event, caseId, taskId, 2,
                "COMPLETED", external, originating,
                Instant.parse("2026-10-27T00:00:00Z"), receipt.occurredAt(), correlation,
                exact, receipt.payloadSha256(), "NEW_WORK", null, 1);
        Hrm013TimeTaskReceiptRelay.requireStoredCallback(record, receipt);
        var tampered = new Hrm013TimeTaskDispatchStore.CallbackRecord(
                tenantId, record.outboxId(), record.dispatchEventId(), event, caseId,
                taskId, 2, "COMPLETED", external, originating,
                record.terminalParentCreatedAt(), receipt.occurredAt(), correlation,
                exact, "e".repeat(64), "NEW_WORK", null, 1);
        assertThatThrownBy(() -> Hrm013TimeTaskReceiptRelay
                .requireStoredCallback(tampered, receipt))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }
}
