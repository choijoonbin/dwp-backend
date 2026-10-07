package com.dwp.services.people.hr.performance;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PerformanceCycleServiceTest {

    private static final long TENANT = 91L;
    private static final long ACTOR = 17L;
    private static final UUID SUBJECT = UUID.fromString(
            "90000000-0000-0000-0000-000000000001");
    private static final UUID CYCLE_ID = UUID.fromString(
            "90000000-0000-0000-0000-000000000002");
    private static final UUID VERSION_ID = UUID.fromString(
            "90000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2027-03-01T00:00:00Z");

    private final PerformanceCycleQueryRepository queries = mock(
            PerformanceCycleQueryRepository.class);
    private final PerformanceCycleCommandRepository commands = mock(
            PerformanceCycleCommandRepository.class);
    private final PerformanceParticipantPreviewService previewService = mock(
            PerformanceParticipantPreviewService.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final HcmHighRiskCommandGuard highRisk = mock(HcmHighRiskCommandGuard.class);
    private final PerformanceCycleAuthorityPort authority = request ->
            new PerformanceCycleAuthorityPort.AuthorityEvidence(
                    request.tenantId(), request.actorId(), request.subjectPrincipalPublicId(),
                    request.applicationEntitlement(), request.resource(), request.action(),
                    request.operation(), request.purposeCode(), "c".repeat(64), 11, 23);
    private final PerformanceCycleService service = new PerformanceCycleService(
            queries,
            commands,
            previewService,
            new PerformanceCycleCanonicalizer(new ObjectMapper().findAndRegisterModules()),
            audit,
            Clock.fixed(NOW, ZoneOffset.UTC),
            () -> authority,
            highRisk);

    @BeforeEach
    void acceptsReceiptReservationByDefault() {
        when(commands.insertAcceptedReceipt(
                anyLong(), anyLong(), any(), any(), anyString(), anyString(),
                anyString(), any(), anyLong(), anyString(), any()))
                .thenReturn(true);
    }

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void scopesReadsToTrustedTenant() {
        actor("DATA.HR_TALENT:VIEW");
        when(queries.cycle(TENANT, CYCLE_ID)).thenReturn(Optional.of(cycle("DRAFT", 3, ACTOR)));

        PerformanceCycleDtos.CycleDetail result = service.cycle(CYCLE_ID);

        assertThat(result.cycleId()).isEqualTo(CYCLE_ID);
        verify(queries).cycle(TENANT, CYCLE_ID);
        verify(queries, never()).cycle(92L, CYCLE_ID);
    }

    @Test
    void rejectsMissingOrMismatchedCanonicalAuthorityEvidenceBeforeQuery() {
        actor("APP.HRIS:VIEW", "DATA.HR_TALENT:VIEW");
        List<PerformanceCycleAuthorityPort> invalidPorts = List.of(
                request -> null,
                request -> evidence(request, "APP.OTHER", request.operation(),
                        "c".repeat(64), 11, 23),
                request -> evidence(request, "APP.HRIS", "performance.cycle.other",
                        "c".repeat(64), 11, 23),
                request -> evidence(request, "APP.HRIS", request.operation(),
                        "not-a-digest", 11, 23),
                request -> evidence(request, "APP.HRIS", request.operation(),
                        "c".repeat(64), 0, 23),
                request -> evidence(request, "APP.HRIS", request.operation(),
                        "c".repeat(64), 11, 0));

        for (PerformanceCycleAuthorityPort invalidPort : invalidPorts) {
            PerformanceCycleQueryRepository localQueries = mock(
                    PerformanceCycleQueryRepository.class);
            PerformanceCycleService localService = new PerformanceCycleService(
                    localQueries,
                    mock(PerformanceCycleCommandRepository.class),
                    previewService,
                    new PerformanceCycleCanonicalizer(
                            new ObjectMapper().findAndRegisterModules()),
                    audit,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    () -> invalidPort,
                    highRisk);

            assertThatThrownBy(localService::cycles)
                    .isInstanceOf(BaseException.class)
                    .extracting(error -> ((BaseException) error).getErrorCode())
                    .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
            verifyNoInteractions(localQueries);
        }
    }

    @Test
    void rejectsDraftVersionConflictBeforeMutation() {
        actor("DATA.HR_TALENT:UPDATE");
        when(queries.receiptByIdempotency(
                TENANT, SUBJECT, "performance.cycle.update", "update-1"))
                .thenReturn(Optional.empty());
        when(queries.cycle(TENANT, CYCLE_ID)).thenReturn(Optional.of(cycle("DRAFT", 5, ACTOR)));
        PerformanceCycleDtos.UpdateCycleRequest request = updateRequest(4);

        assertThatThrownBy(() -> service.update(CYCLE_ID, request, "update-1", null))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT);
        verify(commands, never()).update(
                anyLong(), anyLong(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyList(), anyString());
    }

    @Test
    void enforcesAuthorPublisherSeparation() {
        actor("DATA.HR_TALENT:APPROVE");
        UUID commandId = UUID.randomUUID();
        when(queries.receiptByIdempotency(
                TENANT, SUBJECT, "performance.cycle.publish", "publish-1"))
                .thenReturn(Optional.empty());
        when(queries.cycle(TENANT, CYCLE_ID))
                .thenReturn(Optional.of(cycle("VALIDATED", 2, ACTOR)));
        var request = new PerformanceCycleDtos.PublishCycleRequest(
                commandId, 2, UUID.randomUUID(), 8, UUID.randomUUID(),
                "Approved after independent calibration review.");

        assertThatThrownBy(() -> service.publish(
                CYCLE_ID, request, "publish-1", null, stepUpHeaders("publish-1", 2)))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.SOD_CONFLICT);
        verifyNoInteractions(highRisk);
    }

    @Test
    void rejectsStalePreviewEvenForIndependentPublisher() {
        actor("DATA.HR_TALENT:APPROVE");
        UUID commandId = UUID.randomUUID();
        UUID previewId = UUID.randomUUID();
        PerformanceCycleDtos.CycleDetail current = cycle("VALIDATED", 2, 99L);
        when(queries.receiptByIdempotency(
                TENANT, SUBJECT, "performance.cycle.publish", "publish-stale"))
                .thenReturn(Optional.empty());
        when(queries.cycle(TENANT, CYCLE_ID)).thenReturn(Optional.of(current));
        when(queries.preview(TENANT, previewId)).thenReturn(Optional.of(
                new PerformanceCycleDtos.PopulationPreview(
                        previewId, VERSION_ID, UUID.randomUUID(), 8,
                        current.version().populationRuleVersionId(), 2,
                        "STALE", 1, 1, "b".repeat(64), 2,
                        NOW.minusSeconds(60), NOW.plusSeconds(600), List.of())));
        var request = new PerformanceCycleDtos.PublishCycleRequest(
                commandId, 2, previewId, 8, UUID.randomUUID(),
                "Approved after independent calibration review.");

        assertThatThrownBy(() -> service.publish(
                CYCLE_ID, request, "publish-stale", null,
                stepUpHeaders("publish-stale", 2)))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT);
        verify(commands, never()).publish(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList());
        verifyNoInteractions(highRisk);
    }

    @Test
    void rejectsPublishWhenStepUpGuardFailsBeforeAnyCommandMutation() {
        actor("DATA.HR_TALENT:APPROVE");
        UUID previewId = UUID.randomUUID();
        PerformanceCycleDtos.CycleDetail current = cycle("VALIDATED", 2, 99L);
        var request = publishRequest(previewId);
        HcmStepUpHeaders headers = stepUpHeaders("publish-step-up", 2);
        when(queries.receiptByIdempotency(
                TENANT, SUBJECT, "performance.cycle.publish", "publish-step-up"))
                .thenReturn(Optional.empty());
        when(queries.cycle(TENANT, CYCLE_ID)).thenReturn(Optional.of(current));
        when(queries.preview(TENANT, previewId))
                .thenReturn(Optional.of(readyPreview(current, previewId)));
        doThrow(new BaseException(
                ErrorCode.STEP_UP_REQUIRED, "A command-bound challenge is required."))
                .when(highRisk).require(anyString(), anyString(), anyString(),
                        anyLong(), anyString(), any(), any());

        assertThatThrownBy(() -> service.publish(
                CYCLE_ID, request, "publish-step-up", null, headers))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.STEP_UP_REQUIRED);

        verify(commands, never()).insertAcceptedReceipt(
                anyLong(), anyLong(), any(), any(), anyString(), anyString(),
                anyString(), any(), anyLong(), anyString(), any());
        verify(commands, never()).publish(
                anyLong(), anyLong(), any(), any(), anyLong(), any(), any(), any(),
                anyLong(), anyString(), any(), any(),
                org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void verifiesExactPublishCommandBeforeReceiptAndCycleMutation() {
        actor("DATA.HR_TALENT:APPROVE");
        UUID previewId = UUID.randomUUID();
        PerformanceCycleDtos.CycleDetail current = cycle("VALIDATED", 2, 99L);
        var request = publishRequest(previewId);
        HcmStepUpHeaders headers = stepUpHeaders("publish-verified", 2);
        String requestHash = commandHash("performance.cycle.publish", CYCLE_ID, request);
        var receipt = new PerformanceCycleDtos.CommandReceipt(
                request.commandId(), "PUBLISH", "performance.cycle.publish",
                CYCLE_ID, 2, 3L, "SUCCEEDED", CYCLE_ID, null, NOW, NOW);
        var receiptRecord = new PerformanceCycleQueryRepository.ReceiptRecord(
                receipt, "publish-verified", requestHash);
        when(queries.receiptByIdempotency(
                TENANT, SUBJECT, "performance.cycle.publish", "publish-verified"))
                .thenReturn(Optional.empty());
        when(queries.cycle(TENANT, CYCLE_ID)).thenReturn(Optional.of(current));
        when(queries.preview(TENANT, previewId))
                .thenReturn(Optional.of(readyPreview(current, previewId)));
        when(queries.receiptById(TENANT, request.commandId()))
                .thenReturn(Optional.of(receiptRecord));

        PerformanceCycleDtos.CycleCommandResult result = service.publish(
                CYCLE_ID, request, "publish-verified", null, headers);

        assertThat(result.receipt()).isSameAs(receipt);
        var ordered = inOrder(highRisk, commands);
        ordered.verify(highRisk).require(
                "hcm.operations.talent.approve",
                "PERFORMANCE_CYCLE",
                CYCLE_ID.toString(),
                current.aggregateVersion(),
                "/api/people/v1/hris/performance/cycles/" + CYCLE_ID + "/publish",
                request,
                headers);
        ordered.verify(commands).insertAcceptedReceipt(
                anyLong(), anyLong(), any(), any(), anyString(), anyString(),
                anyString(), any(), anyLong(), anyString(), any());
        ordered.verify(commands).publish(
                anyLong(), anyLong(), any(), any(), anyLong(), any(), any(), any(),
                anyLong(), anyString(), any(), any(),
                org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void replaysSameSuccessfulReceiptWithoutRepeatingMutation() {
        actor("DATA.HR_TALENT:CREATE");
        PerformanceCycleDtos.CreateCycleRequest request = createRequest();
        String hash = commandHash("performance.cycle.create", null, request);
        PerformanceCycleDtos.CommandReceipt receipt = new PerformanceCycleDtos.CommandReceipt(
                request.commandId(), "CREATE_DRAFT", "performance.cycle.create",
                CYCLE_ID, 0, 1L, "SUCCEEDED", CYCLE_ID, null, NOW, NOW);
        PerformanceCycleQueryRepository.ReceiptRecord record =
                new PerformanceCycleQueryRepository.ReceiptRecord(receipt, "create-1", hash);
        when(queries.receiptByIdempotency(
                TENANT, SUBJECT, "performance.cycle.create", "create-1"))
                .thenReturn(Optional.of(record));
        when(queries.reconcileUnknown(TENANT, record)).thenReturn(receipt);
        when(queries.cycle(TENANT, CYCLE_ID)).thenReturn(Optional.of(cycle("DRAFT", 1, ACTOR)));

        PerformanceCycleDtos.CycleCommandResult result =
                service.create(request, "create-1", null);

        assertThat(result.receipt()).isSameAs(receipt);
        assertThat(result.cycle().cycleId()).isEqualTo(CYCLE_ID);
        verify(commands, never()).create(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsSameIdempotencyKeyReplayedAgainstDifferentPathCycle() {
        actor("DATA.HR_TALENT:UPDATE");
        PerformanceCycleDtos.UpdateCycleRequest request = updateRequest(3);
        UUID otherCycle = UUID.randomUUID();
        String hash = commandHash("performance.cycle.update", CYCLE_ID, request);
        PerformanceCycleDtos.CommandReceipt receipt = new PerformanceCycleDtos.CommandReceipt(
                request.commandId(), "UPDATE_DRAFT", "performance.cycle.update",
                otherCycle, 3, 4L, "SUCCEEDED", otherCycle, null, NOW, NOW);
        var record = new PerformanceCycleQueryRepository.ReceiptRecord(
                receipt, "update-reused", hash);
        when(queries.receiptByIdempotency(
                TENANT, SUBJECT, "performance.cycle.update", "update-reused"))
                .thenReturn(Optional.of(record));

        assertThatThrownBy(() -> service.update(
                CYCLE_ID, request, "update-reused", null))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);
        verify(commands, never()).update(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void replaysDatabaseReceiptWinnerWhenReservationLosesRace() {
        actor("DATA.HR_TALENT:CREATE");
        PerformanceCycleDtos.CreateCycleRequest request = createRequest();
        String hash = commandHash("performance.cycle.create", null, request);
        PerformanceCycleDtos.CommandReceipt receipt = new PerformanceCycleDtos.CommandReceipt(
                request.commandId(), "CREATE_DRAFT", "performance.cycle.create",
                CYCLE_ID, 0, 1L, "SUCCEEDED", CYCLE_ID, null, NOW, NOW);
        var record = new PerformanceCycleQueryRepository.ReceiptRecord(
                receipt, "create-race", hash);
        when(queries.receiptByIdempotency(
                TENANT, SUBJECT, "performance.cycle.create", "create-race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(record));
        when(commands.insertAcceptedReceipt(
                anyLong(), anyLong(), any(), any(), anyString(), anyString(),
                anyString(), any(), anyLong(), anyString(), any()))
                .thenReturn(false);
        when(queries.reconcileUnknown(TENANT, record)).thenReturn(receipt);
        when(queries.cycle(TENANT, CYCLE_ID)).thenReturn(Optional.of(cycle("DRAFT", 1, ACTOR)));

        PerformanceCycleDtos.CycleCommandResult result =
                service.create(request, "create-race", null);

        assertThat(result.receipt()).isSameAs(receipt);
        assertThat(result.cycle().cycleId()).isEqualTo(CYCLE_ID);
        verify(commands, never()).create(
                anyLong(), anyLong(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyList(), anyString());
        verify(audit, never()).record(any());
    }

    @Test
    void replaysSamePreviewCommandWithoutRegeneratingPreview() {
        actor("DATA.HR_TALENT:UPDATE");
        UUID previewId = UUID.randomUUID();
        var request = new PerformanceCycleDtos.PreviewPopulationRequest(
                UUID.randomUUID(), 2, NOW);
        String hash = commandHash("performance.cycle.preview", CYCLE_ID, request);
        PerformanceCycleDtos.CommandReceipt receipt = new PerformanceCycleDtos.CommandReceipt(
                request.commandId(), "PREVIEW_PARTICIPANTS", "performance.cycle.preview",
                CYCLE_ID, 2, 1L, "SUCCEEDED", previewId, null, NOW, NOW);
        var record = new PerformanceCycleQueryRepository.ReceiptRecord(
                receipt, "preview-1", hash);
        var preview = new PerformanceCycleDtos.PopulationPreview(
                previewId, VERSION_ID, UUID.randomUUID(), 8, UUID.randomUUID(), 2,
                "READY", 1, 1, "b".repeat(64), 1,
                NOW.minusSeconds(60), NOW.plusSeconds(600), List.of());
        when(queries.receiptByIdempotency(
                TENANT, SUBJECT, "performance.cycle.preview", "preview-1"))
                .thenReturn(Optional.of(record));
        when(queries.reconcileUnknown(TENANT, record)).thenReturn(receipt);
        when(queries.preview(TENANT, previewId)).thenReturn(Optional.of(preview));

        PerformanceCycleDtos.PreviewCommandResult result =
                service.preview(CYCLE_ID, request, "preview-1", null);

        assertThat(result.receipt()).isSameAs(receipt);
        assertThat(result.preview()).isSameAs(preview);
        verify(previewService, never()).prepare(anyLong(), any(), any());
        verify(commands, never()).createPreview(
                anyLong(), anyLong(), any(), any(), anyLong(), any(), any());
    }

    private void actor(String... permissions) {
        PeopleRequestContext.set(
                ACTOR, TENANT, SUBJECT, Set.of(), Set.of(permissions));
    }

    private PerformanceCycleDtos.CycleDetail cycle(String state, long revision, long author) {
        PerformanceCycleDtos.CycleVersion version = new PerformanceCycleDtos.CycleVersion(
                VERSION_ID, 1, state, revision,
                NOW.plusSeconds(3_600), null, "UTC", UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(64), author, null, null,
                List.of(new PerformanceCycleDtos.CycleStage(
                        UUID.randomUUID(), "SELF", "SELF_REVIEW", 1,
                        NOW.plusSeconds(3_600), NOW.plusSeconds(7_200), true, Map.of())));
        return new PerformanceCycleDtos.CycleDetail(
                CYCLE_ID, "FY27", "FY27 Review", state, 1, revision,
                UUID.randomUUID(), NOW, author, NOW, author, version, List.of());
    }

    private PerformanceCycleDtos.CreateCycleRequest createRequest() {
        return new PerformanceCycleDtos.CreateCycleRequest(
                UUID.randomUUID(), "FY27", "FY27 Review", UUID.randomUUID(),
                NOW.plusSeconds(3_600), null, "UTC", UUID.randomUUID(), UUID.randomUUID(),
                List.of(stage()));
    }

    private PerformanceCycleDtos.UpdateCycleRequest updateRequest(long expectedRevision) {
        return new PerformanceCycleDtos.UpdateCycleRequest(
                UUID.randomUUID(), expectedRevision, "FY27 Review", UUID.randomUUID(),
                NOW.plusSeconds(3_600), null, "UTC", UUID.randomUUID(), UUID.randomUUID(),
                List.of(stage()));
    }

    private PerformanceCycleDtos.PublishCycleRequest publishRequest(UUID previewId) {
        return new PerformanceCycleDtos.PublishCycleRequest(
                UUID.randomUUID(), 2, previewId, 8, UUID.randomUUID(),
                "Approved after independent calibration review.");
    }

    private PerformanceCycleDtos.PopulationPreview readyPreview(
            PerformanceCycleDtos.CycleDetail current,
            UUID previewId) {
        return new PerformanceCycleDtos.PopulationPreview(
                previewId, VERSION_ID, UUID.randomUUID(), 8,
                current.version().populationRuleVersionId(), current.aggregateVersion(),
                "READY", 1, 1, "b".repeat(64), 2,
                NOW.minusSeconds(60), NOW.plusSeconds(600), List.of());
    }

    private HcmStepUpHeaders stepUpHeaders(String idempotencyKey, long expectedVersion) {
        return new HcmStepUpHeaders(
                "signed-challenge", idempotencyKey,
                "psr-" + "a".repeat(64), expectedVersion);
    }

    private PerformanceCycleDtos.StageInput stage() {
        return new PerformanceCycleDtos.StageInput(
                "SELF", "SELF_REVIEW", 1,
                NOW.plusSeconds(3_600), NOW.plusSeconds(7_200), true, Map.of());
    }

    private String commandHash(String action, UUID aggregateId, Object request) {
        PerformanceCycleCanonicalizer canonicalizer = new PerformanceCycleCanonicalizer(
                new ObjectMapper().findAndRegisterModules());
        return PerformanceCycleCanonicalizer.sha256(
                canonicalizer.requestHash(request)
                        + "|" + action
                        + "|" + TENANT
                        + "|" + ACTOR
                        + "|" + SUBJECT
                        + "|" + (aggregateId == null ? "" : aggregateId));
    }

    private PerformanceCycleAuthorityPort.AuthorityEvidence evidence(
            PerformanceCycleAuthorityPort.AuthorityRequest request,
            String applicationEntitlement,
            String operation,
            String scopeDigest,
            long fieldPolicyRevision,
            long authorizationRevision) {
        return new PerformanceCycleAuthorityPort.AuthorityEvidence(
                request.tenantId(), request.actorId(), request.subjectPrincipalPublicId(),
                applicationEntitlement, request.resource(), request.action(),
                operation, request.purposeCode(), scopeDigest,
                fieldPolicyRevision, authorizationRevision);
    }
}
