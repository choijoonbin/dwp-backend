package com.dwp.services.time.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Hrm013TimeDrainContinuationCallbackTest {
    @Test
    void terminalChildIdentityIsDeterministicAndDrainEvidenceRemainsDurable()
            throws IOException {
        long tenantId = 41;
        UUID root = UUID.randomUUID();
        UUID first = Hrm013WorkloadProofV1.terminalChildEventId(
                tenantId, root, 2, "SVC-PEP-HRM-008");
        UUID replay = Hrm013WorkloadProofV1.terminalChildEventId(
                tenantId, root, 2, "SVC-PEP-HRM-008");
        UUID nextRevision = Hrm013WorkloadProofV1.terminalChildEventId(
                tenantId, root, 3, "SVC-PEP-HRM-008");
        var drain = new Hrm013WorkloadProofV1.DrainClaims(
                root, Instant.parse("2026-10-27T00:00:00Z"), "a".repeat(64),
                Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD,
                first, 2, "b".repeat(64));

        assertThat(first).isEqualTo(replay).isNotEqualTo(nextRevision);
        assertThat(drain.drainContinuationKind())
                .isEqualTo(Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD);
        assertThat(source("Hrm013TimeTaskDispatchStore.java"))
                .contains("drain_claims_json")
                .contains("Hrm013WorkloadProofV1.terminalChildEventId")
                .contains("Math.addExact(dispatch.taskOwnerRevision(), 1)");
        assertThat(source("Hrm013TimeTaskReceiptRelay.java"))
                .contains("authority.authorizeCallback")
                .contains("authority.recheckCallback")
                .contains("current.drainAdmission().drainAuthorizationExpiresAt()");
    }

    @Test
    void acceptedNewWorkBuildsOneFrozenTerminalChildAndRejectsWrongIdentity() {
        long tenantId = 52;
        UUID dispatch = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        Instant rootCreatedAt = Instant.parse("2026-10-27T00:00:00Z");
        String rootPayload = "a".repeat(64);
        UUID callbackId = Hrm013WorkloadProofV1.terminalChildEventId(
                tenantId, dispatch, 2,
                Hrm013SeparationBridgeV1.HRM_008_TIME.bindingId());
        var callback = Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        callbackId, caseId, taskId, "TIME_FINALIZATION", "COMPLETED",
                        2, UUID.randomUUID(), rootPayload, rootCreatedAt.plusSeconds(10),
                        "0".repeat(64), correlation));
        byte[] exact = Hrm013SeparationBridgeV1.encode(callback);
        var record = new Hrm013TimeTaskDispatchStore.CallbackRecord(
                tenantId, UUID.randomUUID(), dispatch, callbackId, caseId, taskId, 2,
                "COMPLETED", callback.externalReceiptPublicId(), rootPayload,
                rootCreatedAt, callback.occurredAt(), correlation, exact,
                callback.payloadSha256(), "NEW_WORK", null, 1);

        var drain = Hrm013TimeTaskReceiptRelay.terminalDrain(record, null, callback);

        assertThat(drain.drainRootWorkItemId()).isEqualTo(dispatch);
        assertThat(drain.drainRootCreatedAt()).isEqualTo(rootCreatedAt);
        assertThat(drain.drainContinuationEventId()).isEqualTo(callbackId);
        assertThat(drain.drainContinuationKind())
                .isEqualTo(Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD);

        var wrong = Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        UUID.randomUUID(), caseId, taskId, "TIME_FINALIZATION", "COMPLETED",
                        2, callback.externalReceiptPublicId(), rootPayload,
                        callback.occurredAt(), "0".repeat(64), correlation));
        assertThatThrownBy(() -> Hrm013TimeTaskReceiptRelay
                .terminalDrain(record, null, wrong))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }

    private static String source(String name) throws IOException {
        Path path = Path.of("dwp-time-server/src/main/java/com/dwp/services/time/hris/"
                + "separation/hrm013/" + name);
        if (!Files.exists(path)) path = Path.of("..").resolve(path);
        return Files.readString(path);
    }
}
