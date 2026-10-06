package com.dwp.services.people.hr.assignment;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssignmentProposalServiceTest {

    private static final UUID ACTOR_PERSON_ID =
            UUID.fromString("f8358440-a680-4589-a46f-e96067a78901");

    private final AssignmentProposalRepository repository =
            mock(AssignmentProposalRepository.class);
    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();
    private final AssignmentProposalCanonicalizer canonicalizer =
            new AssignmentProposalCanonicalizer(objectMapper);
    private final AssignmentProposalValidator validator =
            new AssignmentProposalValidator();
    private final AssignmentProposalAuthority authority =
            mock(AssignmentProposalAuthority.class);
    private final HcmPopulationScopeService populationScopes =
            mock(HcmPopulationScopeService.class);
    private final HcmPopulationRepository populations =
            mock(HcmPopulationRepository.class);
    private final HcmHighRiskCommandGuard highRisk =
            mock(HcmHighRiskCommandGuard.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final AssignmentProposalService service = new AssignmentProposalService(
            repository, canonicalizer, validator, authority, populationScopes,
            populations, highRisk, audit, objectMapper);

    @AfterEach
    void clear() {
        PeopleRequestContext.clear();
    }

    @Test
    void submitUsesExactStepUpCasReceiptOutboxAndAuditWithoutApplyingTheLedger() {
        PeopleRequestContext.set(
                17L, 7L, ACTOR_PERSON_ID, Set.of("HR_ADMIN"),
                Set.of("DATA.WORKFORCE:MANAGE"));
        HcmPopulationScopeService.ResolvedPopulation population = population();
        when(populationScopes.requireOperationsForMutation("READ")).thenReturn(population);
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        AssignmentProposalRepository.TargetAssignment target = target();
        String contentHash = "a".repeat(64);
        String validationHash = canonicalizer.sha256(Map.of(
                "contentSha256", contentHash,
                "targetWorkerVersion", target.workerVersion(),
                "targetRelationshipVersion", target.relationshipVersion(),
                "targetAssignmentVersion", target.assignmentVersion(),
                "findings", List.of()));
        AssignmentProposalRepository.ProposalRow before = proposal(
                proposalId, target, "VALIDATED", 1L, contentHash, validationHash);
        AssignmentProposalRepository.ProposalRow submitted = proposal(
                proposalId, target, "SUBMITTED", 2L, contentHash, validationHash);
        when(repository.proposal(7L, proposalId)).thenReturn(Optional.of(before));
        when(repository.target(7L, target.assignmentPublicId()))
                .thenReturn(Optional.of(target));
        when(repository.targetForMutation(7L, target.assignmentPublicId()))
                .thenReturn(Optional.of(target));
        when(populations.lockWorkerInPopulation(
                7L, population.scope(), target.workerId())).thenReturn(true);
        when(populations.containsWorker(
                7L, population.scope(), target.workerId())).thenReturn(true);
        when(repository.references(
                eq(7L), eq(before.effectiveDate()), eq(before.reasonCode()), any()))
                .thenReturn(new AssignmentProposalValidator.ReferenceValidation(
                        true, Set.of()));
        UUID receiptId = UUID.randomUUID();
        AssignmentProposalRepository.ReceiptRow receipt =
                new AssignmentProposalRepository.ReceiptRow(
                        91L, receiptId, commandId, 17L, null, "SUBMIT", "idem-1",
                        "request", "IN_PROGRESS", null, null, null);
        when(repository.claimReceipt(
                eq(7L), any(), eq(commandId), eq(17L), any(), eq("SUBMIT"),
                eq("idem-1"), any(), any(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(Optional.of(receipt));
        when(repository.submit(7L, proposalId, 1L, 17L))
                .thenReturn(Optional.of(submitted));
        HcmStepUpHeaders headers = new HcmStepUpHeaders(
                "signed", "idem-1", "decision-v1", 1L);

        AssignmentProposalDtos.CommandResult result = service.submit(
                proposalId,
                new AssignmentProposalDtos.VersionCommand(commandId, 1L),
                "idem-1", "corr-1", headers);

        assertThat(result.replayed()).isFalse();
        assertThat(result.proposal().lifecycleState()).isEqualTo("SUBMITTED");
        assertThat(result.proposal().version()).isEqualTo(2L);
        verify(highRisk).requireExact(
                eq(AssignmentProposalAuthority.SUBMIT_CAPABILITY),
                eq("ASSIGNMENT_PROPOSAL"), eq(proposalId.toString()), eq(1L),
                eq("/api/people/v1/workforce/assignment-proposals/"
                        + proposalId + "/submit"), any(), eq(headers));
        verify(repository).submit(7L, proposalId, 1L, 17L);
        verify(repository).appendLifecycle(
                eq(7L), eq(91L), eq(submitted), eq("SUBMITTED"), any(), eq(17L));
        verify(repository).completeReceipt(
                eq(7L), eq(91L), eq(submitted), any());
        verify(audit).record(any());
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(highRisk).requireExact(
                eq(AssignmentProposalAuthority.SUBMIT_CAPABILITY),
                eq("ASSIGNMENT_PROPOSAL"), eq(proposalId.toString()), eq(1L),
                eq("/api/people/v1/workforce/assignment-proposals/"
                        + proposalId + "/submit"), payload.capture(), eq(headers));
        assertThat(payload.getValue()).isEqualTo(Map.of(
                "commandId", commandId,
                "expectedVersion", 1L));
        assertThat(result.proposal().assignmentKey()).isNull();
        assertThat(result.proposal().workerNumber()).isNull();
    }

    @Test
    void successfulValidateRetryReplaysBeforeCurrentStateAndVersionChecks() {
        PeopleRequestContext.set(17L, 7L, ACTOR_PERSON_ID, Set.of("HR_ADMIN"),
                Set.of("DATA.WORKFORCE:MANAGE"));
        HcmPopulationScopeService.ResolvedPopulation population = population();
        when(populationScopes.requireOperationsForMutation("READ")).thenReturn(population);
        AssignmentProposalRepository.TargetAssignment target = target();
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        AssignmentProposalRepository.ProposalRow completed = proposal(
                proposalId, target, "VALIDATED", 1L, "a".repeat(64), "b".repeat(64));
        prepareReplayTarget(population, target, completed);
        String hash = canonicalizer.sha256(Map.of(
                "action", "VALIDATE", "commandId", commandId,
                "proposalId", proposalId, "expectedVersion", 0L));
        stubReplay("VALIDATE", "idem-validate", commandId, hash, completed);

        AssignmentProposalDtos.CommandResult result = service.validate(
                proposalId, new AssignmentProposalDtos.VersionCommand(commandId, 0L),
                "idem-validate", "corr");

        assertThat(result.replayed()).isTrue();
        assertThat(result.proposal().lifecycleState()).isEqualTo("VALIDATED");
        verify(repository, never()).targetForMutation(any(), any());
        verify(repository, never()).validate(any(), any(), any(Long.class), any(), any(), any(Long.class));
    }

    @Test
    void successfulSubmitRetryReplaysWithoutReconsumingStepUp() {
        PeopleRequestContext.set(17L, 7L, ACTOR_PERSON_ID, Set.of("HR_ADMIN"),
                Set.of("DATA.WORKFORCE:MANAGE"));
        HcmPopulationScopeService.ResolvedPopulation population = population();
        when(populationScopes.requireOperationsForMutation("READ")).thenReturn(population);
        AssignmentProposalRepository.TargetAssignment target = target();
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        AssignmentProposalRepository.ProposalRow completed = proposal(
                proposalId, target, "SUBMITTED", 2L, "a".repeat(64), "b".repeat(64));
        prepareReplayTarget(population, target, completed);
        String hash = canonicalizer.sha256(Map.of(
                "action", "SUBMIT", "commandId", commandId,
                "proposalId", proposalId, "expectedVersion", 1L));
        stubReplay("SUBMIT", "idem-submit", commandId, hash, completed);

        AssignmentProposalDtos.CommandResult result = service.submit(
                proposalId, new AssignmentProposalDtos.VersionCommand(commandId, 1L),
                "idem-submit", "corr",
                new HcmStepUpHeaders("expired-is-not-rechecked", "idem-submit", "rev", 1L));

        assertThat(result.replayed()).isTrue();
        assertThat(result.proposal().lifecycleState()).isEqualTo("SUBMITTED");
        verify(highRisk, never()).requireExact(any(), any(), any(), any(Long.class), any(), any(), any());
        verify(repository, never()).submit(any(), any(), any(Long.class), any(Long.class));
    }

    @Test
    void successfulCancelRetryReplaysBeforeCancelledStateCheck() {
        PeopleRequestContext.set(17L, 7L, ACTOR_PERSON_ID, Set.of("HR_ADMIN"),
                Set.of("DATA.WORKFORCE:MANAGE"));
        HcmPopulationScopeService.ResolvedPopulation population = population();
        when(populationScopes.requireOperationsForMutation("READ")).thenReturn(population);
        AssignmentProposalRepository.TargetAssignment target = target();
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        AssignmentProposalRepository.ProposalRow completed = proposal(
                proposalId, target, "CANCELLED", 2L, "a".repeat(64), "b".repeat(64));
        prepareReplayTarget(population, target, completed);
        String hash = canonicalizer.sha256(Map.of(
                "action", "CANCEL", "commandId", commandId,
                "proposalId", proposalId, "expectedVersion", 1L,
                "reason", "duplicate"));
        stubReplay("CANCEL", "idem-cancel", commandId, hash, completed);

        AssignmentProposalDtos.CommandResult result = service.cancel(
                proposalId, new AssignmentProposalDtos.CancelCommand(
                        commandId, 1L, "duplicate"),
                "idem-cancel", "corr");

        assertThat(result.replayed()).isTrue();
        assertThat(result.proposal().lifecycleState()).isEqualTo("CANCELLED");
        verify(repository, never()).cancel(any(), any(), any(Long.class), any(), any(Long.class));
    }

    @Test
    void successfulCreateRetryReplaysBeforeChangedAssignmentVersionCheck() {
        PeopleRequestContext.set(17L, 7L, ACTOR_PERSON_ID, Set.of("HR_ADMIN"),
                Set.of("DATA.WORKFORCE:MANAGE"));
        HcmPopulationScopeService.ResolvedPopulation population = population();
        when(populationScopes.requireOperationsForMutation("READ")).thenReturn(population);
        AssignmentProposalRepository.TargetAssignment target = target();
        UUID commandId = UUID.randomUUID();
        Map<String, Object> changes = Map.of(
                "organizationId", UUID.randomUUID().toString());
        AssignmentProposalDtos.CreateRequest request = new AssignmentProposalDtos.CreateRequest(
                commandId, target.assignmentPublicId(), "TRANSFER",
                LocalDate.now().plusDays(10), "transfer", changes, 4L);
        when(repository.target(7L, target.assignmentPublicId())).thenReturn(Optional.of(target));
        when(populations.containsWorker(7L, population.scope(), target.workerId()))
                .thenReturn(true);
        AssignmentProposalRepository.ProposalRow completed = proposal(
                UUID.randomUUID(), target, "DRAFT", 0L, "a".repeat(64), null);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("action", "CREATE");
        payload.put("commandId", commandId);
        payload.put("targetAssignmentId", target.assignmentPublicId());
        payload.put("changeType", "TRANSFER");
        payload.put("effectiveDate", request.effectiveDate());
        payload.put("reasonCode", "TRANSFER");
        payload.put("proposedChanges", canonicalizer.changes(changes));
        payload.put("expectedAssignmentVersion", 4L);
        stubReplay("CREATE", "idem-create", commandId,
                canonicalizer.sha256(payload), completed);

        AssignmentProposalDtos.CommandResult result = service.create(
                request, "idem-create", "corr");

        assertThat(result.replayed()).isTrue();
        verify(repository, never()).targetForMutation(any(), any());
        verify(repository, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(Long.class));
    }

    @Test
    void replayRejectsACommandReceiptOwnedByAnotherLargeUserId() {
        long currentUser = 10_000L;
        long otherUser = 20_000L;
        PeopleRequestContext.set(currentUser, 7L, ACTOR_PERSON_ID, Set.of("HR_ADMIN"),
                Set.of("DATA.WORKFORCE:MANAGE"));
        HcmPopulationScopeService.ResolvedPopulation population = population();
        when(populationScopes.requireOperationsForMutation("READ")).thenReturn(population);
        AssignmentProposalRepository.TargetAssignment target = target();
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        AssignmentProposalRepository.ProposalRow completed = proposal(
                proposalId, target, "VALIDATED", 1L, "a".repeat(64), "b".repeat(64));
        prepareReplayTarget(population, target, completed);
        String hash = canonicalizer.sha256(Map.of(
                "action", "VALIDATE", "commandId", commandId,
                "proposalId", proposalId, "expectedVersion", 0L));
        when(repository.claimReceipt(eq(7L), any(), eq(commandId), eq(currentUser),
                eq(ACTOR_PERSON_ID), eq("VALIDATE"), eq("idem-cross"), eq(hash),
                any(), any())).thenReturn(Optional.empty());
        when(repository.receipt(7L, currentUser, "VALIDATE", "idem-cross"))
                .thenReturn(Optional.empty());
        when(repository.receiptByCommand(7L, commandId)).thenReturn(Optional.of(
                receipt(otherUser, UUID.randomUUID(), commandId, "VALIDATE",
                        "idem-cross", hash, completed)));

        assertThatThrownBy(() -> service.validate(
                proposalId, new AssignmentProposalDtos.VersionCommand(commandId, 0L),
                "idem-cross", "corr"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        verify(repository, never()).validate(any(), any(), any(Long.class), any(), any(), any(Long.class));
    }

    @Test
    void cancelKeepsTargetLockButDoesNotRequireCreationTimeLineageVersions() {
        PeopleRequestContext.set(17L, 7L, ACTOR_PERSON_ID, Set.of("HR_ADMIN"),
                Set.of("DATA.WORKFORCE:MANAGE"));
        HcmPopulationScopeService.ResolvedPopulation population = population();
        when(populationScopes.requireOperationsForMutation("READ")).thenReturn(population);
        AssignmentProposalRepository.TargetAssignment original = target();
        AssignmentProposalRepository.TargetAssignment changed = withVersions(
                original, 30L, 40L, 50L);
        UUID proposalId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        AssignmentProposalRepository.ProposalRow before = proposal(
                proposalId, original, "DRAFT", 0L, "a".repeat(64), null);
        AssignmentProposalRepository.ProposalRow cancelled = withState(
                before, "CANCELLED", 1L);
        when(repository.proposal(7L, proposalId)).thenReturn(Optional.of(before));
        when(repository.target(7L, original.assignmentPublicId()))
                .thenReturn(Optional.of(changed));
        when(repository.targetForMutation(7L, original.assignmentPublicId()))
                .thenReturn(Optional.of(changed));
        when(populations.containsWorker(7L, population.scope(), original.workerId()))
                .thenReturn(true);
        when(populations.lockWorkerInPopulation(7L, population.scope(), original.workerId()))
                .thenReturn(true);
        when(repository.claimReceipt(eq(7L), any(), eq(commandId), eq(17L),
                eq(ACTOR_PERSON_ID), eq("CANCEL"), eq("idem-cancel"), any(), any(), any()))
                .thenReturn(Optional.of(inProgress(commandId, "CANCEL", "idem-cancel")));
        when(repository.cancel(7L, proposalId, 0L, "stale target", 17L))
                .thenReturn(Optional.of(cancelled));

        AssignmentProposalDtos.CommandResult result = service.cancel(
                proposalId, new AssignmentProposalDtos.CancelCommand(
                        commandId, 0L, "stale target"),
                "idem-cancel", "corr");

        assertThat(result.proposal().lifecycleState()).isEqualTo("CANCELLED");
        verify(repository).targetForMutation(7L, original.assignmentPublicId());
        verify(repository).cancel(7L, proposalId, 0L, "stale target", 17L);
    }

    @Test
    void prospectiveCreateRejectsAnEndedActiveSlice() {
        PeopleRequestContext.set(17L, 7L, ACTOR_PERSON_ID, Set.of("HR_ADMIN"),
                Set.of("DATA.WORKFORCE:MANAGE"));
        HcmPopulationScopeService.ResolvedPopulation population = population();
        when(populationScopes.requireOperationsForMutation("READ")).thenReturn(population);
        AssignmentProposalRepository.TargetAssignment ended = withDates(
                target(), LocalDate.of(2020, 1, 1), LocalDate.of(2021, 1, 1));
        UUID commandId = UUID.randomUUID();
        AssignmentProposalDtos.CreateRequest request = new AssignmentProposalDtos.CreateRequest(
                commandId, ended.assignmentPublicId(), "TRANSFER",
                LocalDate.now().plusDays(10), "TRANSFER",
                Map.of("organizationId", UUID.randomUUID().toString()),
                ended.assignmentVersion());
        when(repository.target(7L, ended.assignmentPublicId())).thenReturn(Optional.of(ended));
        when(repository.targetForMutation(7L, ended.assignmentPublicId()))
                .thenReturn(Optional.of(ended));
        when(populations.containsWorker(7L, population.scope(), ended.workerId()))
                .thenReturn(true);
        when(populations.lockWorkerInPopulation(7L, population.scope(), ended.workerId()))
                .thenReturn(true);
        when(repository.claimReceipt(eq(7L), any(), eq(commandId), eq(17L),
                eq(ACTOR_PERSON_ID), eq("CREATE"), eq("idem-ended"), any(), any(), any()))
                .thenReturn(Optional.of(inProgress(commandId, "CREATE", "idem-ended")));

        assertThatThrownBy(() -> service.create(request, "idem-ended", "corr"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));
        verify(repository, never()).references(any(), any(), any(), any());
        verify(repository, never()).create(any(), any(), any(), any(), any(), any(), any(), any(), any(Long.class));
    }

    @Test
    void assignmentAndProposalReadsRedactWorkerIdentifiersAndTimelineCarriesScope() {
        PeopleRequestContext.set(17L, 7L, ACTOR_PERSON_ID, Set.of("HR_ADMIN"),
                Set.of("DATA.WORKFORCE:VIEW"));
        HcmPopulationScopeService.ResolvedPopulation population = population();
        when(populationScopes.requireOperations("READ")).thenReturn(population);
        AssignmentProposalRepository.TargetAssignment target = target();
        when(repository.target(7L, target.assignmentPublicId())).thenReturn(Optional.of(target));
        when(populations.containsWorker(7L, population.scope(), target.workerId()))
                .thenReturn(true);
        UUID proposalId = UUID.randomUUID();
        AssignmentProposalRepository.ProposalRow proposal = proposal(
                proposalId, target, "DRAFT", 0L, "a".repeat(64), null);
        when(repository.proposal(7L, proposalId)).thenReturn(Optional.of(proposal));
        when(repository.timeline(7L, target, population.scope())).thenReturn(List.of());

        AssignmentProposalDtos.AssignmentDetail detail =
                service.assignment(target.assignmentPublicId());
        AssignmentProposalDtos.Proposal result = service.proposal(proposalId);
        assertThat(service.timeline(target.assignmentPublicId())).isEmpty();

        assertThat(detail.assignmentKey()).isNull();
        assertThat(detail.workerNumber()).isNull();
        assertThat(result.assignmentKey()).isNull();
        assertThat(result.workerNumber()).isNull();
        verify(repository).timeline(7L, target, population.scope());
    }

    @Test
    void targetAssignmentScopeCannotBeBorrowedFromAnotherAssignmentOfTheWorker() {
        PeopleRequestContext.set(17L, 7L, ACTOR_PERSON_ID, Set.of("HR_ADMIN"),
                Set.of("DATA.WORKFORCE:VIEW"));
        UUID allowedOrganization = UUID.randomUUID();
        HcmPopulationScopeService.ResolvedPopulation population = population(
                false, Set.of(allowedOrganization), "MGR-ALLOWED", false);
        when(populationScopes.requireOperations("READ")).thenReturn(population);
        AssignmentProposalRepository.TargetAssignment target = target();
        when(repository.target(7L, target.assignmentPublicId())).thenReturn(Optional.of(target));
        when(populations.containsWorker(7L, population.scope(), target.workerId()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.assignment(target.assignmentPublicId()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private HcmPopulationScopeService.ResolvedPopulation population() {
        return population(true, Set.of(), null, false);
    }

    private HcmPopulationScopeService.ResolvedPopulation population(
            boolean tenantWide,
            Set<UUID> organizationIds,
            String managerAssignmentKey,
            boolean identifiers) {
        Set<String> fields = identifiers
                ? Set.of("EMPLOYMENT", "WORKER_IDENTIFIERS")
                : Set.of("EMPLOYMENT");
        return new HcmPopulationScopeService.ResolvedPopulation(
                null,
                new HcmPopulationRepository.PopulationScope(
                        0L, managerAssignmentKey, tenantWide, organizationIds,
                        fields, "policy-v1"),
                new HcmPopulationRepository.PopulationEvidence(1L, "population-v1"));
    }

    private AssignmentProposalRepository.TargetAssignment target() {
        return new AssignmentProposalRepository.TargetAssignment(
                11L, UUID.randomUUID(), 12L, UUID.randomUUID(),
                13L, UUID.randomUUID(), "ASG-1", "W-1", "Worker",
                "ACTIVE", true, LocalDate.of(2025, 1, 1), null, 1,
                UUID.randomUUID(), "Engineering", "ENGINEER", "Engineer",
                "SEOUL", "Seoul", "MGR-1", UUID.randomUUID(), "Engineer",
                new BigDecimal("40"), BigDecimal.ONE, "TRANSFER",
                3L, 4L, 5L);
    }

    private AssignmentProposalRepository.TargetAssignment withVersions(
            AssignmentProposalRepository.TargetAssignment target,
            long workerVersion,
            long relationshipVersion,
            long assignmentVersion) {
        return new AssignmentProposalRepository.TargetAssignment(
                target.assignmentId(), target.assignmentPublicId(), target.workerId(),
                target.workerPublicId(), target.relationshipId(),
                target.relationshipPublicId(), target.assignmentKey(), target.workerNumber(),
                target.personDisplayName(), target.assignmentStatus(),
                target.primaryAssignment(), target.effectiveStartDate(), target.effectiveEndDate(),
                target.effectiveSequence(), target.organizationPublicId(),
                target.organizationName(), target.jobProfileKey(), target.jobName(),
                target.locationKey(), target.locationName(), target.managerAssignmentKey(),
                target.managerAssignmentPublicId(), target.businessTitle(), target.workerHours(),
                target.fullTimeEquivalent(), target.changeReasonCode(), workerVersion,
                relationshipVersion, assignmentVersion);
    }

    private AssignmentProposalRepository.TargetAssignment withDates(
            AssignmentProposalRepository.TargetAssignment target,
            LocalDate effectiveStartDate,
            LocalDate effectiveEndDate) {
        return new AssignmentProposalRepository.TargetAssignment(
                target.assignmentId(), target.assignmentPublicId(), target.workerId(),
                target.workerPublicId(), target.relationshipId(),
                target.relationshipPublicId(), target.assignmentKey(), target.workerNumber(),
                target.personDisplayName(), target.assignmentStatus(),
                target.primaryAssignment(), effectiveStartDate, effectiveEndDate,
                target.effectiveSequence(), target.organizationPublicId(),
                target.organizationName(), target.jobProfileKey(), target.jobName(),
                target.locationKey(), target.locationName(), target.managerAssignmentKey(),
                target.managerAssignmentPublicId(), target.businessTitle(), target.workerHours(),
                target.fullTimeEquivalent(), target.changeReasonCode(), target.workerVersion(),
                target.relationshipVersion(), target.assignmentVersion());
    }

    private AssignmentProposalRepository.ProposalRow proposal(
            UUID proposalId,
            AssignmentProposalRepository.TargetAssignment target,
            String state,
            long version,
            String contentHash,
            String validationHash) {
        Instant now = Instant.now();
        return new AssignmentProposalRepository.ProposalRow(
                31L, proposalId, target.assignmentId(), target.assignmentPublicId(),
                target.workerId(), target.workerPublicId(), target.relationshipId(),
                target.relationshipPublicId(), target.assignmentKey(), target.workerNumber(),
                target.personDisplayName(), "TRANSFER", LocalDate.now().plusDays(10),
                "TRANSFER", Map.of("organizationId", UUID.randomUUID().toString()),
                state, List.of(), contentHash, validationHash,
                target.workerVersion(), target.relationshipVersion(),
                target.assignmentVersion(), version, now,
                "SUBMITTED".equals(state) ? now : null, null, null, now, now);
    }

    private AssignmentProposalRepository.ProposalRow withState(
            AssignmentProposalRepository.ProposalRow row,
            String state,
            long version) {
        Instant now = Instant.now();
        return new AssignmentProposalRepository.ProposalRow(
                row.internalId(), row.publicId(), row.assignmentId(),
                row.assignmentPublicId(), row.workerId(), row.workerPublicId(),
                row.relationshipId(), row.relationshipPublicId(), row.assignmentKey(),
                row.workerNumber(), row.personDisplayName(), row.changeType(),
                row.effectiveDate(), row.reasonCode(), row.proposedChanges(), state,
                row.validationFindings(), row.contentSha256(), row.validationSha256(),
                row.targetWorkerVersion(), row.targetRelationshipVersion(),
                row.targetAssignmentVersion(), version, row.validatedAt(), row.submittedAt(),
                "CANCELLED".equals(state) ? now : row.cancelledAt(),
                "CANCELLED".equals(state) ? "cancelled" : row.cancellationReason(),
                row.createdAt(), now);
    }

    private void prepareReplayTarget(
            HcmPopulationScopeService.ResolvedPopulation population,
            AssignmentProposalRepository.TargetAssignment target,
            AssignmentProposalRepository.ProposalRow proposal) {
        when(repository.proposal(7L, proposal.publicId())).thenReturn(Optional.of(proposal));
        when(repository.target(7L, target.assignmentPublicId())).thenReturn(Optional.of(target));
        when(populations.containsWorker(7L, population.scope(), target.workerId()))
                .thenReturn(true);
    }

    private void stubReplay(
            String action,
            String idempotencyKey,
            UUID commandId,
            String requestHash,
            AssignmentProposalRepository.ProposalRow result) {
        when(repository.claimReceipt(
                eq(7L), any(), eq(commandId), eq(17L), eq(ACTOR_PERSON_ID), eq(action),
                eq(idempotencyKey), eq(requestHash), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.receipt(7L, 17L, action, idempotencyKey)).thenReturn(Optional.of(
                receipt(17L, UUID.randomUUID(), commandId, action,
                        idempotencyKey, requestHash, result)));
    }

    private AssignmentProposalRepository.ReceiptRow receipt(
            long subjectUserId,
            UUID receiptId,
            UUID commandId,
            String action,
            String idempotencyKey,
            String requestHash,
            AssignmentProposalRepository.ProposalRow result) {
        return new AssignmentProposalRepository.ReceiptRow(
                91L, receiptId, commandId, subjectUserId, ACTOR_PERSON_ID,
                action, idempotencyKey, requestHash, "SUCCEEDED", result.publicId(),
                result.version(), canonicalizer.canonicalJson(dto(result)));
    }

    private AssignmentProposalRepository.ReceiptRow inProgress(
            UUID commandId,
            String action,
            String idempotencyKey) {
        return new AssignmentProposalRepository.ReceiptRow(
                91L, UUID.randomUUID(), commandId, 17L, ACTOR_PERSON_ID,
                action, idempotencyKey, "pending", "IN_PROGRESS", null, null, null);
    }

    private AssignmentProposalDtos.Proposal dto(
            AssignmentProposalRepository.ProposalRow row) {
        return new AssignmentProposalDtos.Proposal(
                row.publicId(), row.assignmentPublicId(), row.workerPublicId(),
                row.relationshipPublicId(), row.assignmentKey(), row.workerNumber(),
                row.personDisplayName(), row.changeType(), row.effectiveDate(),
                row.reasonCode(), row.proposedChanges(), row.lifecycleState(),
                row.validationFindings(), row.targetWorkerVersion(),
                row.targetRelationshipVersion(), row.targetAssignmentVersion(),
                row.version(), row.validatedAt(), row.submittedAt(), row.cancelledAt(),
                row.cancellationReason(), row.createdAt(), row.updatedAt());
    }
}
