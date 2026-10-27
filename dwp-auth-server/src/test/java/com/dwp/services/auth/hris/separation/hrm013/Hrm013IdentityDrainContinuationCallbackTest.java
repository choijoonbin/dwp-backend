package com.dwp.services.auth.hris.separation.hrm013;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013TaskDispatchV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Hrm013IdentityDrainContinuationCallbackTest {
    @Test
    void completionCarriesAnExactTerminalChildDrainLineage() {
        long tenant = 91;
        UUID task = UUID.randomUUID();
        UUID root = Hrm013TaskDispatchV1.dispatchEventId(
                tenant, task, 1, Hrm013TaskDispatchV1.IDENTITY.bindingId());
        Instant dispatchedAt = Instant.parse("2026-10-27T01:00:00Z");
        var dispatch = Hrm013TaskDispatchV1.seal(
                new Hrm013TaskDispatchV1.IdentityTaskDispatch(
                        Hrm013TaskDispatchV1.SCHEMA_NAME,
                        Hrm013TaskDispatchV1.SCHEMA_VERSION, root,
                        UUID.randomUUID(), task, "IDENTITY_ACCESS_REVOCATION",
                        1, 1, dispatchedAt, "0".repeat(64), UUID.randomUUID(),
                        UUID.randomUUID(), "TERMINATION", LocalDate.parse("2026-11-01")));
        UUID child = Hrm013WorkloadProofV1.terminalChildEventId(
                tenant, root, 1, "SVC-PEP-HRM-008");
        var callback = Hrm013SeparationBridgeV1.seal(
                new Hrm013SeparationBridgeV1.SeparationTaskReceiptV2(
                        child, dispatch.casePublicId(), dispatch.taskPublicId(),
                        dispatch.taskType(), "COMPLETED", 1, UUID.randomUUID(),
                        dispatch.payloadSha256(), dispatchedAt.plusSeconds(60),
                        "0".repeat(64), dispatch.correlationId()));
        var drain = new Hrm013WorkloadProofV1.DrainClaims(
                root, dispatchedAt, dispatch.payloadSha256(),
                Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD,
                child, 1, callback.payloadSha256());
        var authority = new Hrm013WorkloadProofV1.BusinessAuthority(
                Hrm013WorkloadProofV1.GrantUseMode.DRAIN_EXISTING, 7, 11);

        var command = new Hrm013IdentityTaskDispatchStore.CompletionCommand(
                tenant, root, child, UUID.randomUUID(), "COMPLETED",
                authority, drain);

        Hrm013IdentityTaskDispatchStore.requireCompletionDrainLineage(
                tenant, dispatch, callback, authority, drain);
        assertThat(command.authorityMode().drainExisting()).isTrue();
        assertThat(command.drainClaims().drainContinuationKind())
                .isEqualTo(Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD);
        assertThat(command.drainClaims().drainContinuationEventId()).isEqualTo(child);
        var wrong = new Hrm013WorkloadProofV1.DrainClaims(
                root, dispatchedAt, dispatch.payloadSha256(),
                Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD,
                child, 1, "f".repeat(64));
        assertThatThrownBy(() ->
                Hrm013IdentityTaskDispatchStore.requireCompletionDrainLineage(
                        tenant, dispatch, callback, authority, wrong))
                .isInstanceOf(Hrm013SafeErrorV1.Failure.class);
    }
}
