package com.dwp.services.people.hr.performance.populationfreeze;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PopulationFreezeServiceTest {

    private static final Instant NOW = Instant.parse("2027-04-01T00:00:00Z");
    private static final long TENANT_ID = 77L;
    private static final long ACTOR_ID = 19L;

    private final UUID subjectId = UUID.randomUUID();
    private final UUID cycleId = UUID.randomUUID();
    private final UUID cycleVersionId = UUID.randomUUID();
    private final UUID previewId = UUID.randomUUID();
    private final UUID ruleId = UUID.randomUUID();
    private final UUID snapshotId = UUID.randomUUID();
    private final UUID commandId = UUID.randomUUID();
    private final UUID freezeId = UUID.randomUUID();
    private final PopulationFreezeRepository repository = mock(PopulationFreezeRepository.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final HcmHighRiskCommandGuard highRisk = mock(HcmHighRiskCommandGuard.class);
    private final PopulationFreezeAuthorityPort authority = mock(PopulationFreezeAuthorityPort.class);
    private final PopulationFreezeCanonicalizer canonicalizer =
            new PopulationFreezeCanonicalizer(new ObjectMapper().findAndRegisterModules());
    private PopulationFreezeService service;

    @BeforeEach
    void setUp() {
        PeopleRequestContext.set(ACTOR_ID, TENANT_ID, subjectId, Set.of("OPERATOR"), Set.of(
                "APP.HRIS:VIEW", "DATA.HR_TALENT:APPROVE"));
        when(authority.authorize(any())).thenReturn(authorityEvidence());
        service = new PopulationFreezeService(
                repository, canonicalizer, audit,
                Clock.fixed(NOW, ZoneOffset.UTC), () -> authority, highRisk);
    }

    @AfterEach
    void tearDown() {
        PeopleRequestContext.clear();
    }

    @Test
    void freezesTheExactPreviewAndReturnsItsImmutableReceipt() {
        PopulationFreezeRepository.FreezeCandidate candidate = candidate();
        PopulationFreezeDtos.FreezePopulationRequest request = request(candidate.contentHash());
        PopulationFreezeDtos.PopulationFreeze freeze = freeze(candidate);
        PopulationFreezeDtos.CommandReceipt receipt = receipt(
                "SUCCEEDED", freeze.populationFreezeId());
        when(repository.receiptByIdempotency(
                TENANT_ID, subjectId, PopulationFreezeService.ORIGINATING_ACTION, "freeze-1"))
                .thenReturn(Optional.empty());
        when(repository.candidate(TENANT_ID, cycleId, previewId))
                .thenReturn(Optional.of(candidate));
        when(repository.reserveReceipt(
                anyLong(), anyLong(), any(), any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(true);
        when(repository.createFreeze(
                anyLong(), anyLong(), any(), any(), any())).thenReturn(freeze);
        when(repository.receiptById(TENANT_ID, commandId))
                .thenReturn(Optional.empty(), Optional.of(
                        new PopulationFreezeRepository.ReceiptRecord(
                        receipt, "freeze-1", "a".repeat(64))));

        PopulationFreezeDtos.FreezeCommandResult result = service.freeze(
                cycleId, 4L, request, "freeze-1", "corr-1", headers());

        assertThat(result.freeze()).isEqualTo(freeze);
        assertThat(result.receipt().state()).isEqualTo("SUCCEEDED");
        verify(repository).createFreeze(
                org.mockito.ArgumentMatchers.eq(TENANT_ID),
                org.mockito.ArgumentMatchers.eq(ACTOR_ID),
                org.mockito.ArgumentMatchers.eq(commandId), any(),
                org.mockito.ArgumentMatchers.eq(candidate));
        verify(highRisk).requireExact(
                org.mockito.ArgumentMatchers.eq(
                        HcmPopulationFreezeAuthorityAdapter.FREEZE_CAPABILITY_CONTRACT),
                org.mockito.ArgumentMatchers.eq("PERFORMANCE_CYCLE"),
                org.mockito.ArgumentMatchers.eq(cycleId.toString()),
                org.mockito.ArgumentMatchers.eq(4L),
                org.mockito.ArgumentMatchers.eq(
                        "/api/people/v1/hris/performance/cycles/" + cycleId
                                + "/population-freezes"),
                org.mockito.ArgumentMatchers.eq(request),
                org.mockito.ArgumentMatchers.eq(headers()));
        verify(audit).record(any());
    }

    @Test
    void rejectsChangedPreviewMembersBeforeReservingAReceipt() {
        PopulationFreezeRepository.FreezeCandidate candidate = candidate();
        when(repository.receiptByIdempotency(anyLong(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.candidate(TENANT_ID, cycleId, previewId))
                .thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service.freeze(
                cycleId, 4L, request("f".repeat(64)), "freeze-2", null, headers()))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT);

        verify(repository, never()).reserveReceipt(
                anyLong(), anyLong(), any(), any(), any(), any(), anyLong(), any(), any());
        verify(repository, never()).createFreeze(
                anyLong(), anyLong(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void rejectsAFormerReadyPreviewWhenAnotherPreviewIsCurrent() {
        PopulationFreezeRepository.FreezeCandidate candidate = candidate(
                UUID.randomUUID(), true);
        when(repository.receiptByIdempotency(anyLong(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.candidate(TENANT_ID, cycleId, previewId))
                .thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service.freeze(
                cycleId, 4L, request(candidate.contentHash()),
                "freeze-old-preview", null, headers()))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT);

        verify(repository, never()).reserveReceipt(
                anyLong(), anyLong(), any(), any(), any(), any(), anyLong(), any(), any());
        verifyNoInteractions(highRisk, audit);
    }

    @Test
    void rejectsPreviewMembersOutsideTheResolvedTargetPopulationBeforeReceipt() {
        PopulationFreezeRepository.FreezeCandidate candidate = candidate();
        PopulationFreezeAuthorityPort.TargetPopulation scopedPopulation =
                new PopulationFreezeAuthorityPort.TargetPopulation(
                        false, null, Set.of(UUID.randomUUID()), Set.of("DIRECTORY"), "scope-2");
        when(authority.authorize(any())).thenReturn(authorityEvidence(scopedPopulation));
        when(repository.receiptByIdempotency(anyLong(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.candidate(TENANT_ID, cycleId, previewId))
                .thenReturn(Optional.of(candidate));

        assertThatThrownBy(() -> service.freeze(
                cycleId, 4L, request(candidate.contentHash()),
                "freeze-out-of-scope", null, headers()))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(repository, never()).reserveReceipt(
                anyLong(), anyLong(), any(), any(), any(), any(), anyLong(), any(), any());
        verifyNoInteractions(highRisk, audit);
    }

    @Test
    void sameIdempotencyKeyAndDigestReturnsTheOriginalFreezeWithoutRepeatingEffects() {
        PopulationFreezeRepository.FreezeCandidate candidate = candidate();
        PopulationFreezeDtos.FreezePopulationRequest request = request(candidate.contentHash());
        String hash = canonicalizer.commandHash(
                request, 4L, TENANT_ID, ACTOR_ID, subjectId, cycleId);
        PopulationFreezeDtos.PopulationFreeze freeze = freeze(candidate);
        PopulationFreezeDtos.CommandReceipt receipt = receipt("SUCCEEDED", freezeId);
        when(repository.receiptByIdempotency(
                TENANT_ID, subjectId, PopulationFreezeService.ORIGINATING_ACTION, "freeze-3"))
                .thenReturn(Optional.of(new PopulationFreezeRepository.ReceiptRecord(
                        receipt, "freeze-3", hash)));
        when(repository.freeze(TENANT_ID, freezeId)).thenReturn(Optional.of(freeze));

        PopulationFreezeDtos.FreezeCommandResult replay = service.freeze(
                cycleId, 4L, request, "freeze-3", null, headers());

        assertThat(replay.freeze()).isEqualTo(freeze);
        verify(repository, never()).candidate(anyLong(), any(), any());
        verify(repository, never()).reserveReceipt(
                anyLong(), anyLong(), any(), any(), any(), any(), anyLong(), any(), any());
        verify(repository, never()).createFreeze(
                anyLong(), anyLong(), any(), any(), any());
        verifyNoInteractions(highRisk);
        verifyNoInteractions(audit);
    }

    @Test
    void concurrentIdempotentWinnerIsReplayedWithoutConsumingStepUpTwice() {
        PopulationFreezeRepository.FreezeCandidate candidate = candidate();
        PopulationFreezeDtos.FreezePopulationRequest request = request(candidate.contentHash());
        String hash = canonicalizer.commandHash(
                request, 4L, TENANT_ID, ACTOR_ID, subjectId, cycleId);
        PopulationFreezeDtos.PopulationFreeze freeze = freeze(candidate);
        PopulationFreezeRepository.ReceiptRecord winner =
                new PopulationFreezeRepository.ReceiptRecord(
                        receipt("SUCCEEDED", freezeId), "freeze-race", hash);
        when(repository.receiptByIdempotency(
                TENANT_ID, subjectId, PopulationFreezeService.ORIGINATING_ACTION,
                "freeze-race"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(repository.candidate(TENANT_ID, cycleId, previewId))
                .thenReturn(Optional.of(candidate));
        when(repository.reserveReceipt(
                anyLong(), anyLong(), any(), any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(false);
        when(repository.freeze(TENANT_ID, freezeId)).thenReturn(Optional.of(freeze));

        PopulationFreezeDtos.FreezeCommandResult replay = service.freeze(
                cycleId, 4L, request, "freeze-race", null, headers());

        assertThat(replay.freeze()).isEqualTo(freeze);
        verifyNoInteractions(highRisk);
        verify(repository, never()).createFreeze(
                anyLong(), anyLong(), any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void reusedIdempotencyKeyWithDifferentDigestFailsClosed() {
        PopulationFreezeRepository.FreezeCandidate candidate = candidate();
        when(repository.receiptByIdempotency(
                TENANT_ID, subjectId, PopulationFreezeService.ORIGINATING_ACTION, "freeze-4"))
                .thenReturn(Optional.of(new PopulationFreezeRepository.ReceiptRecord(
                        receipt("SUCCEEDED", freezeId), "freeze-4", "0".repeat(64))));

        assertThatThrownBy(() -> service.freeze(
                cycleId, 4L, request(candidate.contentHash()), "freeze-4", null, headers()))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);

        verify(repository, never()).candidate(anyLong(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void reusedCommandIdWithDifferentIdempotencyKeyFailsWithoutEffects() {
        PopulationFreezeRepository.FreezeCandidate candidate = candidate();
        when(repository.receiptByIdempotency(
                TENANT_ID, subjectId, PopulationFreezeService.ORIGINATING_ACTION,
                "freeze-command-reuse"))
                .thenReturn(Optional.empty());
        when(repository.receiptById(TENANT_ID, commandId))
                .thenReturn(Optional.of(new PopulationFreezeRepository.ReceiptRecord(
                        receipt("SUCCEEDED", freezeId), "another-key", "0".repeat(64))));

        assertThatThrownBy(() -> service.freeze(
                cycleId, 4L, request(candidate.contentHash()),
                "freeze-command-reuse", null, headers()))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);

        verify(repository, never()).candidate(anyLong(), any(), any());
        verify(repository, never()).reserveReceipt(
                anyLong(), anyLong(), any(), any(), any(), any(), anyLong(), any(), any());
        verifyNoInteractions(highRisk, audit);
    }

    @Test
    void concurrentCommandIdCollisionMapsToCanonicalConflictWithoutEffects() {
        PopulationFreezeRepository.FreezeCandidate candidate = candidate();
        when(repository.receiptByIdempotency(
                TENANT_ID, subjectId, PopulationFreezeService.ORIGINATING_ACTION,
                "freeze-command-race"))
                .thenReturn(Optional.empty());
        when(repository.receiptById(TENANT_ID, commandId))
                .thenReturn(Optional.empty(), Optional.of(
                        new PopulationFreezeRepository.ReceiptRecord(
                                receipt("ACCEPTED", null), "winning-key", "0".repeat(64))));
        when(repository.candidate(TENANT_ID, cycleId, previewId))
                .thenReturn(Optional.of(candidate));
        when(repository.reserveReceipt(
                anyLong(), anyLong(), any(), any(), any(), any(), anyLong(), any(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.freeze(
                cycleId, 4L, request(candidate.contentHash()),
                "freeze-command-race", null, headers()))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_CONFLICT);

        verify(repository, never()).createFreeze(
                anyLong(), anyLong(), any(), any(), any());
        verifyNoInteractions(highRisk, audit);
    }

    @Test
    void missingCanonicalAuthorityFailsBeforeRepositoryAccess() {
        service = new PopulationFreezeService(
                repository, canonicalizer, audit,
                Clock.fixed(NOW, ZoneOffset.UTC), () -> null, highRisk);

        assertThatThrownBy(() -> service.freeze(
                cycleId, 4L, request("a".repeat(64)), "freeze-5", null, headers()))
                .isInstanceOf(BaseException.class)
                .extracting(error -> ((BaseException) error).getErrorCode())
                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);

        verifyNoInteractions(repository, audit);
    }

    private PopulationFreezeRepository.FreezeCandidate candidate() {
        return candidate(previewId, true);
    }

    private PopulationFreezeRepository.FreezeCandidate candidate(
            UUID latestReadyPreviewId,
            boolean includeCurrentMembers) {
        List<PopulationFreezeRepository.PreviewMember> members = List.of(
                new PopulationFreezeRepository.PreviewMember(
                        UUID.randomUUID(), UUID.randomUUID(), "ACTIVE", UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), null, "INCLUDED"),
                new PopulationFreezeRepository.PreviewMember(
                        UUID.randomUUID(), UUID.randomUUID(), "ACTIVE", UUID.randomUUID(),
                        null, null, null, "EXCLUDED"));
        String hash = canonicalizer.previewContentHash(members);
        return new PopulationFreezeRepository.FreezeCandidate(
                cycleId, "VALIDATED", 1, 4L, cycleVersionId, 1,
                previewId, ruleId, snapshotId, 31L, 4L, "READY",
                1, 1, hash, NOW.plusSeconds(3_600), latestReadyPreviewId,
                includeCurrentMembers ? members : List.of());
    }

    private PopulationFreezeDtos.FreezePopulationRequest request(String hash) {
        return new PopulationFreezeDtos.FreezePopulationRequest(
                commandId, previewId, ruleId, snapshotId, 31L, hash,
                "Reviewed the exact population and confirmed the immutable freeze.");
    }

    private PopulationFreezeDtos.PopulationFreeze freeze(
            PopulationFreezeRepository.FreezeCandidate candidate) {
        return new PopulationFreezeDtos.PopulationFreeze(
                freezeId, cycleId, cycleVersionId, previewId, ruleId, snapshotId,
                31L, 4L, "FROZEN", candidate.participantCount(),
                candidate.reviewerAssignmentCount(), candidate.contentHash(), 1L, NOW);
    }

    private PopulationFreezeDtos.CommandReceipt receipt(String state, UUID resultRef) {
        return new PopulationFreezeDtos.CommandReceipt(
                commandId, "FREEZE_POPULATION", PopulationFreezeService.ORIGINATING_ACTION,
                cycleId, 4L, resultRef == null ? null : 1L, state, resultRef,
                null, NOW, resultRef == null ? null : NOW);
    }

    private PopulationFreezeAuthorityPort.AuthorityEvidence authorityEvidence() {
        return authorityEvidence(new PopulationFreezeAuthorityPort.TargetPopulation(
                true, null, Set.of(), Set.of("DIRECTORY", "EMPLOYMENT"), "scope-1"));
    }

    private PopulationFreezeAuthorityPort.AuthorityEvidence authorityEvidence(
            PopulationFreezeAuthorityPort.TargetPopulation targetPopulation) {
        return new PopulationFreezeAuthorityPort.AuthorityEvidence(
                TENANT_ID, ACTOR_ID, subjectId, PopulationFreezeService.APPLICATION_ENTITLEMENT,
                PopulationFreezeService.RESOURCE, PopulationFreezeService.AUTHORITY_ACTION,
                PopulationFreezeService.ORIGINATING_ACTION,
                PopulationFreezeService.AUTHORITY_PURPOSE, "a".repeat(64), targetPopulation,
                Map.of(
                        "REVIEW_ASSIGNMENT", PopulationFreezeAuthorityPort.FieldDecision.VIEW,
                        "WORK_QUEUE", PopulationFreezeAuthorityPort.FieldDecision.VIEW,
                        "DUE_STATUS", PopulationFreezeAuthorityPort.FieldDecision.VIEW),
                11L, 23L);
    }

    private HcmStepUpHeaders headers() {
        return new HcmStepUpHeaders(
                "signed-population-freeze-challenge", "freeze-1",
                "psr-" + "b".repeat(64), 4L);
    }
}
