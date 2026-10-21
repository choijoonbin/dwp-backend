package com.dwp.services.platform.hrisconfiguration.automation;

import com.dwp.platform.contracts.hris.generated.AutomationHandlerManifestV1;
import com.dwp.platform.contracts.hris.generated.AutomationInvocationV1;
import com.dwp.platform.contracts.hris.generated.AutomationRunItemReceiptV1;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.LeaseGrant;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.PreparedCompensation;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.PreparedItem;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.PreparedOriginal;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationExecutionRepository.RuntimeRun;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationHandlerRegistryPort.SignedManifest;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.TypedAutomationHandlerPort;
import com.dwp.services.platform.hrisconfiguration.automation.AutomationRuntimePorts.VerifiedArtifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutomationRuntimeServiceTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.parse("2026-10-21T00:00:00Z");
    private static final UUID RUN =
            UUID.fromString("00000000-0000-4000-8000-000000000621");
    private static final UUID DEFINITION =
            UUID.fromString("00000000-0000-4000-8000-000000000622");
    private static final UUID ITEM =
            UUID.fromString("00000000-0000-4000-8000-000000000623");
    private static final UUID SUBJECT =
            UUID.fromString("00000000-0000-4000-8000-000000000624");
    private static final UUID CORRELATION =
            UUID.fromString("00000000-0000-4000-8000-000000000625");
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);
    private static final String DIGEST_C = "c".repeat(64);
    private static final String DIGEST_D = "d".repeat(64);

    private AutomationRepository control;
    private AutomationExecutionRepository execution;
    private AutomationRuntimePorts.SignedArtifactVerifierPort verifier;
    private TypedAutomationHandlerPort handler;
    private SignedManifest signed;
    private AutomationRuntimeService service;

    @BeforeEach
    void setUp() {
        control = mock(AutomationRepository.class);
        execution = mock(AutomationExecutionRepository.class);
        verifier = mock(AutomationRuntimePorts.SignedArtifactVerifierPort.class);
        handler = mock(TypedAutomationHandlerPort.class);
        signed = signedManifest();
        when(handler.identity()).thenReturn(signed.identity());
        AutomationHandlerRegistryPort registry = identity -> Optional.of(signed);
        service = new AutomationRuntimeService(
                control, execution, registry, verifier, List.of(handler), List.of(),
                new ObjectMapper());
        when(execution.databaseNow()).thenReturn(NOW);
        when(verifier.revalidate(eq(signed), eq(DIGEST_A), eq(DIGEST_B),
                eq(DIGEST_C))).thenReturn(verified());
    }

    @Test
    void originalDispatchStopsAtTheLockedDatabaseFenceBeforeCallingTheHandler() {
        UUID token = UUID.fromString("00000000-0000-4000-8000-000000000626");
        RuntimeRun run = runtimeRun(token, true);
        LeaseGrant worker = new LeaseGrant(
                AutomationDomainPolicy.AuthorityKind.WORKER, 42, RUN, 1,
                token, 3, 5, NOW.plusMinutes(1));
        PreparedItem item = new PreparedItem(
                ITEM, 1, "subject:623", NOW.minusSeconds(30).toInstant(),
                "effect-key-623", DIGEST_D);
        PreparedOriginal prepared = new PreparedOriginal(
                invocation(), signed, NOW.plusMinutes(1), List.of(item));
        when(execution.lockRuntimeRun(42, RUN)).thenReturn(run);
        doThrow(new IllegalStateException("AUTOMATION_STALE_EFFECT_AUTHORITY"))
                .when(execution).assertLiveOriginalAuthority(run, worker, item);

        assertThatThrownBy(() -> service.invokeOriginal(
                42, SUBJECT, RUN, worker, prepared))
                .hasMessage("AUTOMATION_STALE_EFFECT_AUTHORITY");

        verify(execution).assertLiveOriginalAuthority(run, worker, item);
        verify(handler, never()).invoke(any(), any());
        verify(execution).databaseNow();
    }

    @Test
    void originalDispatchPassesThePersistedReceiptContextToTheTypedHandler() {
        UUID token = UUID.fromString("00000000-0000-4000-8000-000000000633");
        RuntimeRun run = runtimeRun(token, true);
        LeaseGrant worker = new LeaseGrant(
                AutomationDomainPolicy.AuthorityKind.WORKER, 42, RUN, 1,
                token, 3, 5, NOW.plusMinutes(1));
        PreparedItem item = new PreparedItem(
                ITEM, 1, "subject:623", NOW.minusSeconds(30).toInstant(),
                "effect-key-623", DIGEST_D);
        PreparedOriginal prepared = new PreparedOriginal(
                invocation(), signed, NOW.plusMinutes(1), List.of(item));
        when(execution.lockRuntimeRun(42, RUN)).thenReturn(run);
        when(handler.invoke(any(), any())).thenReturn(List.of());

        assertThat(service.invokeOriginal(
                42, SUBJECT, RUN, worker, prepared)).isEmpty();

        verify(execution).assertLiveOriginalAuthority(run, worker, item);
        verify(handler).invoke(eq(prepared.invocation()), argThat(requests ->
                requests.size() == 1
                        && requests.getFirst().itemId().equals(ITEM)
                        && requests.getFirst().itemSequence() == 1
                        && requests.getFirst().subjectRef().equals("subject:623")
                        && requests.getFirst().runRevision() == 7
                        && requests.getFirst().attemptNumber() == 1
                        && requests.getFirst().acceptedAt().equals(
                                NOW.minusSeconds(30).toInstant())));
    }

    @Test
    void compensationReceivesOnlyThePersistedCommittedOriginalReceipt() {
        UUID token = UUID.fromString("00000000-0000-4000-8000-000000000627");
        RuntimeRun run = runtimeRun(token, false);
        LeaseGrant reconciler = new LeaseGrant(
                AutomationDomainPolicy.AuthorityKind.RECONCILER, 42, RUN, 1,
                token, 4, 5, NOW.plusMinutes(1));
        AutomationRunItemReceiptV1 original = receipt("SUCCEEDED", DIGEST_A);
        PreparedCompensation prepared = new PreparedCompensation(
                invocation(), signed, NOW.plusMinutes(1), ITEM,
                1, "subject:623", NOW.minusSeconds(30).toInstant(), 7, 1,
                "compensation-key-623", DIGEST_D, "effect-key-623",
                canonicalDigest(original));
        AutomationRunItemReceiptV1 compensation = receipt("SUCCEEDED", DIGEST_B);
        when(execution.lockRuntimeRun(42, RUN)).thenReturn(run);
        when(execution.loadCommittedOriginalReceipt(run, ITEM)).thenReturn(original);
        when(handler.compensate(eq(prepared.invocation()), eq(List.of(original)), any()))
                .thenReturn(List.of(compensation));

        assertThat(service.invokeCompensation(
                42, SUBJECT, RUN, reconciler, prepared)).isEqualTo(compensation);

        verify(execution).assertLiveCompensationAuthority(
                run, reconciler, prepared);
        verify(execution).loadCommittedOriginalReceipt(run, ITEM);
        verify(handler).compensate(eq(prepared.invocation()), eq(List.of(original)),
                argThat(request -> request.itemId().equals(ITEM)
                        && request.runRevision() == 7
                        && request.attemptNumber() == 1));
    }

    @Test
    void compensationRejectsAStoredOriginalWhoseCanonicalDigestWasSubstituted() {
        UUID token = UUID.fromString("00000000-0000-4000-8000-000000000634");
        RuntimeRun run = runtimeRun(token, false);
        LeaseGrant reconciler = new LeaseGrant(
                AutomationDomainPolicy.AuthorityKind.RECONCILER, 42, RUN, 1,
                token, 4, 5, NOW.plusMinutes(1));
        PreparedCompensation prepared = new PreparedCompensation(
                invocation(), signed, NOW.plusMinutes(1), ITEM,
                1, "subject:623", NOW.minusSeconds(30).toInstant(), 7, 1,
                "compensation-key-623", DIGEST_D, "effect-key-623", DIGEST_A);
        when(execution.lockRuntimeRun(42, RUN)).thenReturn(run);
        when(execution.loadCommittedOriginalReceipt(run, ITEM))
                .thenReturn(receipt("SUCCEEDED", DIGEST_A));

        assertThatThrownBy(() -> service.invokeCompensation(
                42, SUBJECT, RUN, reconciler, prepared))
                .hasMessage("AUTOMATION_COMPENSATION_ORIGINAL_RECEIPT_INVALID");

        verify(handler, never()).compensate(any(), any(), any());
    }

    @Test
    void compensationRejectsANonTerminalHandlerReceipt() {
        UUID token = UUID.fromString("00000000-0000-4000-8000-000000000635");
        RuntimeRun run = runtimeRun(token, false);
        LeaseGrant reconciler = new LeaseGrant(
                AutomationDomainPolicy.AuthorityKind.RECONCILER, 42, RUN, 1,
                token, 4, 5, NOW.plusMinutes(1));
        AutomationRunItemReceiptV1 original = receipt("SUCCEEDED", DIGEST_A);
        PreparedCompensation prepared = new PreparedCompensation(
                invocation(), signed, NOW.plusMinutes(1), ITEM,
                1, "subject:623", NOW.minusSeconds(30).toInstant(), 7, 1,
                "compensation-key-623", DIGEST_D, "effect-key-623",
                canonicalDigest(original));
        AutomationRunItemReceiptV1 running = new AutomationRunItemReceiptV1(
                RUN, 7, ITEM, 1, "subject:623", "RUNNING", 1,
                NOW.minusSeconds(30).toInstant(), NOW.toInstant(),
                null, null, null, false, null, CORRELATION);
        when(execution.lockRuntimeRun(42, RUN)).thenReturn(run);
        when(execution.loadCommittedOriginalReceipt(run, ITEM)).thenReturn(original);
        when(handler.compensate(eq(prepared.invocation()), eq(List.of(original)), any()))
                .thenReturn(List.of(running));

        assertThatThrownBy(() -> service.invokeCompensation(
                42, SUBJECT, RUN, reconciler, prepared))
                .hasMessage("AUTOMATION_COMPENSATION_RECEIPT_MISMATCH");
    }

    @Test
    void compensationFinalizeRejectsCompletionBeforeAcceptance() {
        UUID token = UUID.fromString("00000000-0000-4000-8000-000000000636");
        RuntimeRun run = runtimeRun(token, false);
        LeaseGrant reconciler = new LeaseGrant(
                AutomationDomainPolicy.AuthorityKind.RECONCILER, 42, RUN, 1,
                token, 4, 5, NOW.plusMinutes(1));
        AutomationRunItemReceiptV1 original = receipt("SUCCEEDED", DIGEST_A);
        PreparedCompensation prepared = new PreparedCompensation(
                invocation(), signed, NOW.plusMinutes(1), ITEM,
                1, "subject:623", NOW.minusSeconds(30).toInstant(), 7, 1,
                "compensation-key-623", DIGEST_D, "effect-key-623",
                canonicalDigest(original));
        AutomationRunItemReceiptV1 reversed = new AutomationRunItemReceiptV1(
                RUN, 7, ITEM, 1, "subject:623", "SUCCEEDED", 1,
                NOW.minusSeconds(30).toInstant(), NOW.minusSeconds(31).toInstant(),
                "result:623", DIGEST_B, null, false, null, CORRELATION);
        when(execution.lockRuntimeRun(42, RUN)).thenReturn(run);
        when(execution.loadCommittedOriginalReceipt(run, ITEM)).thenReturn(original);

        assertThatThrownBy(() -> service.finalizeCompensation(
                42, SUBJECT, RUN, reconciler, prepared, reversed))
                .hasMessage("AUTOMATION_COMPENSATION_RECEIPT_MISMATCH");

        verify(execution, never()).finalizeCompensation(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void materializationRejectsUniqueButNonContiguousOneAndThreeSequence() {
        UUID token = UUID.fromString("00000000-0000-4000-8000-000000000628");
        UUID second = UUID.fromString("00000000-0000-4000-8000-000000000629");
        RuntimeRun run = runtimeRun(token, true, 2);
        LeaseGrant worker = new LeaseGrant(
                AutomationDomainPolicy.AuthorityKind.WORKER, 42, RUN, 1,
                token, 3, 5, NOW.plusMinutes(1));
        when(execution.lockRuntimeRun(42, RUN)).thenReturn(run);
        when(handler.materialize(any())).thenReturn(List.of(
                new AutomationRuntimePorts.AutomationItemPlan(
                        ITEM, 1, "subject:623"),
                new AutomationRuntimePorts.AutomationItemPlan(
                        second, 3, "subject:629")));

        assertThatThrownBy(() -> service.materialize(
                42, SUBJECT, RUN, worker))
                .hasMessage("AUTOMATION_ITEM_SEQUENCE_NOT_CONTIGUOUS");

        verify(execution, never()).materializeItems(any(), any(), any(), any());
    }

    @Test
    void originalDispatchRejectsForgedPreparedInvocationBeforeAuthorityCheck() {
        UUID token = UUID.fromString("00000000-0000-4000-8000-000000000630");
        RuntimeRun run = runtimeRun(token, true);
        LeaseGrant worker = new LeaseGrant(
                AutomationDomainPolicy.AuthorityKind.WORKER, 42, RUN, 1,
                token, 3, 5, NOW.plusMinutes(1));
        PreparedItem item = new PreparedItem(
                ITEM, 1, "subject:623", NOW.minusSeconds(30).toInstant(),
                "effect-key-623", DIGEST_D);
        AutomationInvocationV1 forged = new AutomationInvocationV1(
                RUN, DEFINITION, 1, "hris.payroll", "AutomationInvocation.v1",
                "TENANT", null, NOW.minusMinutes(1).toInstant(), "principal:624",
                "idempotency-key-forged", "input:006", DIGEST_D, 1, CORRELATION);
        PreparedOriginal prepared = new PreparedOriginal(
                forged, signed, NOW.plusMinutes(1), List.of(item));
        when(execution.lockRuntimeRun(42, RUN)).thenReturn(run);

        assertThatThrownBy(() -> service.invokeOriginal(
                42, SUBJECT, RUN, worker, prepared))
                .hasMessage("AUTOMATION_DISPATCH_MANIFEST_CHANGED");

        verify(execution, never()).assertLiveOriginalAuthority(any(), any(), any());
        verify(handler, never()).invoke(any(), any());
    }

    @Test
    void originalFinalizeRejectsReceiptSubjectSubstitution() {
        UUID token = UUID.fromString("00000000-0000-4000-8000-000000000631");
        RuntimeRun run = runtimeRun(token, true);
        LeaseGrant worker = new LeaseGrant(
                AutomationDomainPolicy.AuthorityKind.WORKER, 42, RUN, 1,
                token, 3, 5, NOW.plusMinutes(1));
        PreparedOriginal prepared = new PreparedOriginal(
                invocation(), signed, NOW.plusMinutes(1), List.of(new PreparedItem(
                ITEM, 1, "subject:623", NOW.minusSeconds(30).toInstant(),
                "effect-key-623", DIGEST_D)));
        AutomationRunItemReceiptV1 substituted = new AutomationRunItemReceiptV1(
                RUN, 7, ITEM, 1, "subject:substituted", "SUCCEEDED", 1,
                NOW.minusSeconds(30).toInstant(), NOW.toInstant(),
                "result:623", DIGEST_A, null, false, null, CORRELATION);
        when(execution.lockRuntimeRun(42, RUN)).thenReturn(run);

        assertThatThrownBy(() -> service.finalizeOriginal(
                42, SUBJECT, RUN, worker, prepared, List.of(substituted)))
                .hasMessage("AUTOMATION_HANDLER_RECEIPT_MISMATCH");

        verify(execution, never()).finalizeOriginal(
                any(), any(), any(), any(), any());
    }

    @Test
    void originalFinalizeRejectsUnsafeSuccessWithoutResultDigest() {
        UUID token = UUID.fromString("00000000-0000-4000-8000-000000000632");
        RuntimeRun run = runtimeRun(token, true);
        LeaseGrant worker = new LeaseGrant(
                AutomationDomainPolicy.AuthorityKind.WORKER, 42, RUN, 1,
                token, 3, 5, NOW.plusMinutes(1));
        PreparedOriginal prepared = new PreparedOriginal(
                invocation(), signed, NOW.plusMinutes(1), List.of(new PreparedItem(
                ITEM, 1, "subject:623", NOW.minusSeconds(30).toInstant(),
                "effect-key-623", DIGEST_D)));
        AutomationRunItemReceiptV1 unsafe = receipt("SUCCEEDED", null);
        when(execution.lockRuntimeRun(42, RUN)).thenReturn(run);

        assertThatThrownBy(() -> service.finalizeOriginal(
                42, SUBJECT, RUN, worker, prepared, List.of(unsafe)))
                .hasMessage("AUTOMATION_HANDLER_RECEIPT_DISPOSITION_UNSAFE");

        verify(execution, never()).finalizeOriginal(
                any(), any(), any(), any(), any());
    }

    private RuntimeRun runtimeRun(UUID token, boolean worker) {
        return runtimeRun(token, worker, 1);
    }

    private RuntimeRun runtimeRun(UUID token, boolean worker, int expectedItems) {
        AutomationRepository.RunRecord record = new AutomationRepository.RunRecord(
                UUID.randomUUID(), RUN, 42, UUID.randomUUID(), DEFINITION,
                UUID.randomUUID(), 1, AutomationDtos.RunState.RUNNING,
                AutomationDtos.SelectionMode.ALL_ITEMS, DIGEST_A, 1, 3,
                AutomationDtos.RetryMode.FAILED_ITEMS_ONLY,
                expectedItems, 0, 0,
                null, 7, worker ? null : "CANCEL_COMPENSATION_REQUIRED");
        return new RuntimeRun(
                record, signed, DIGEST_A, DIGEST_B, DIGEST_C, "TENANT", null,
                NOW.minusMinutes(1), "principal:624", "idempotency-key-006",
                "input:006", DIGEST_D, expectedItems, CORRELATION, 1,
                UUID.randomUUID(), DIGEST_A, expectedItems,
                "compensation:policy", DIGEST_B,
                worker ? 3 : 0, worker ? token : null,
                worker ? NOW.plusMinutes(1) : null, worker ? 5L : null,
                worker ? 0 : 4, worker ? null : token,
                worker ? null : NOW.plusMinutes(1), worker ? null : 5L,
                5, worker ? null : NOW.minusSeconds(1),
                worker ? null : "CANCEL_COMPENSATION_REQUIRED");
    }

    private VerifiedArtifact verified() {
        return new VerifiedArtifact(
                signed, "trusted-key-registry-r7", DIGEST_D,
                "compatibility-v1", DIGEST_C, DIGEST_B, NOW.plusMinutes(1));
    }

    private static SignedManifest signedManifest() {
        AutomationHandlerManifestV1 manifest = new AutomationHandlerManifestV1(
                "hris.payroll", "dwp-pay-server", "AutomationInvocation.v1",
                "schema:automation-input.v1", "schema:automation-receipt.v1",
                DIGEST_D, List.of("TENANT"), "FAILED_ITEMS_ONLY", 3, "ACTIVE");
        return new SignedManifest(
                manifest, "signing-key-1", DIGEST_A, DIGEST_B, DIGEST_C, 7);
    }

    private static AutomationInvocationV1 invocation() {
        return new AutomationInvocationV1(
                RUN, DEFINITION, 1, "hris.payroll", "AutomationInvocation.v1",
                "TENANT", null, NOW.minusMinutes(1).toInstant(), "principal:624",
                "idempotency-key-006", "input:006", DIGEST_D, 1, CORRELATION);
    }

    private static AutomationRunItemReceiptV1 receipt(
            String state, String resultDigest) {
        return new AutomationRunItemReceiptV1(
                RUN, 7, ITEM, 1, "subject:623", state, 1,
                NOW.minusSeconds(30).toInstant(), NOW.toInstant(),
                "result:623", resultDigest, null, false, null, CORRELATION);
    }

    private static String canonicalDigest(AutomationRunItemReceiptV1 receipt) {
        return new AutomationCanonicalJson(new ObjectMapper()).digest(receipt);
    }
}
