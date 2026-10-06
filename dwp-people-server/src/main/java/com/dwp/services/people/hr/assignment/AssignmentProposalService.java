package com.dwp.services.people.hr.assignment;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.HcmHighRiskCommandGuard;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmStepUpHeaders;
import com.dwp.services.people.security.PeopleRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class AssignmentProposalService {

    private static final String SUBMIT_PUBLIC_PATH_PREFIX =
            "/api/people/v1/workforce/assignment-proposals/";

    private final AssignmentProposalRepository repository;
    private final AssignmentProposalCanonicalizer canonicalizer;
    private final AssignmentProposalValidator validator;
    private final AssignmentProposalAuthority authority;
    private final HcmPopulationScopeService populationScopes;
    private final HcmPopulationRepository populations;
    private final HcmHighRiskCommandGuard highRisk;
    private final AuditOutboxRecorder audit;
    private final ObjectMapper objectMapper;

    public AssignmentProposalService(
            AssignmentProposalRepository repository,
            AssignmentProposalCanonicalizer canonicalizer,
            AssignmentProposalValidator validator,
            AssignmentProposalAuthority authority,
            HcmPopulationScopeService populationScopes,
            HcmPopulationRepository populations,
            HcmHighRiskCommandGuard highRisk,
            AuditOutboxRecorder audit,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.canonicalizer = canonicalizer;
        this.validator = validator;
        this.authority = authority;
        this.populationScopes = populationScopes;
        this.populations = populations;
        this.highRisk = highRisk;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AssignmentProposalDtos.AssignmentDetail assignment(UUID assignmentId) {
        authority.requireRead(AssignmentProposalAuthority.ASSIGNMENT_DETAIL_ROUTE);
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        HcmPopulationScopeService.ResolvedPopulation population = readPopulation();
        AssignmentProposalRepository.TargetAssignment target = repository
                .target(actor.tenantId(), assignmentId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireTargetScope(actor, population, target, false);
        return detail(target, canReadWorkerIdentifiers(population));
    }

    @Transactional(readOnly = true)
    public List<AssignmentProposalDtos.TimelineEntry> timeline(UUID assignmentId) {
        authority.requireRead(AssignmentProposalAuthority.ASSIGNMENT_TIMELINE_ROUTE);
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        HcmPopulationScopeService.ResolvedPopulation population = readPopulation();
        AssignmentProposalRepository.TargetAssignment target = repository
                .target(actor.tenantId(), assignmentId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireTargetScope(actor, population, target, false);
        return repository.timeline(actor.tenantId(), target, population.scope());
    }

    @Transactional(readOnly = true)
    public AssignmentProposalDtos.Proposal proposal(UUID proposalId) {
        authority.requireRead(AssignmentProposalAuthority.PROPOSAL_DETAIL_ROUTE);
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        HcmPopulationScopeService.ResolvedPopulation population = readPopulation();
        AssignmentProposalRepository.ProposalRow row = requireProposal(
                actor.tenantId(), proposalId);
        currentTarget(actor, population, row, false);
        return proposal(row, canReadWorkerIdentifiers(population));
    }

    @Transactional
    public AssignmentProposalDtos.CommandResult create(
            AssignmentProposalDtos.CreateRequest request,
            String idempotencyKey,
            String correlationId) {
        authority.requireCommand("CREATE");
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        HcmPopulationScopeService.ResolvedPopulation population = mutationPopulation();
        String key = idempotencyKey(idempotencyKey);
        String changeType = request.changeType().trim().toUpperCase(Locale.ROOT);
        String reasonCode = reasonCode(request.reasonCode());
        Map<String, Object> changes = canonicalizer.changes(request.proposedChanges());
        Map<String, Object> commandPayload = new LinkedHashMap<>();
        commandPayload.put("action", "CREATE");
        commandPayload.put("commandId", request.commandId());
        commandPayload.put("targetAssignmentId", request.targetAssignmentId());
        commandPayload.put("changeType", changeType);
        commandPayload.put("effectiveDate", request.effectiveDate());
        commandPayload.put("reasonCode", reasonCode);
        commandPayload.put("proposedChanges", changes);
        commandPayload.put("expectedAssignmentVersion", request.expectedAssignmentVersion());
        String requestHash = canonicalizer.sha256(commandPayload);
        AssignmentProposalRepository.TargetAssignment visibleTarget = repository
                .target(actor.tenantId(), request.targetAssignmentId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireTargetScope(actor, population, visibleTarget, false);
        Claim claim = claim(actor, population, "CREATE", request.commandId(), key, requestHash);
        if (!claim.fresh()) return replay(claim.receipt(), population);

        AssignmentProposalRepository.TargetAssignment target = repository
                .targetForMutation(actor.tenantId(), request.targetAssignmentId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireTargetScope(actor, population, target, true);
        if (request.expectedAssignmentVersion() != target.assignmentVersion()) {
            throw versionConflict("The target assignment changed before proposal creation.");
        }
        requireProspectiveTarget(changeType, target);
        AssignmentProposalValidator.ReferenceValidation references = repository.references(
                actor.tenantId(), request.effectiveDate(), reasonCode, changes);
        List<AssignmentProposalDtos.ValidationFinding> findings = validator.validate(
                changeType, request.effectiveDate(), changes,
                target.validationTarget(), references);
        validator.requireNoBlocking(findings);

        UUID proposalId = UUID.randomUUID();
        String contentHash = canonicalizer.sha256(Map.of(
                "targetAssignmentId", request.targetAssignmentId(),
                "targetWorkerVersion", target.workerVersion(),
                "targetRelationshipVersion", target.relationshipVersion(),
                "targetAssignmentVersion", target.assignmentVersion(),
                "changeType", changeType,
                "effectiveDate", request.effectiveDate(),
                "reasonCode", reasonCode,
                "proposedChanges", changes));
        AssignmentProposalRepository.ProposalRow created = repository.create(
                actor.tenantId(), target, proposalId, changeType,
                request.effectiveDate(), reasonCode,
                canonicalizer.canonicalJson(changes), contentHash, actor.userId());
        return finish(actor, population, claim.receipt(), created, "CREATED", correlationId);
    }

    @Transactional
    public AssignmentProposalDtos.CommandResult validate(
            UUID proposalId,
            AssignmentProposalDtos.VersionCommand request,
            String idempotencyKey,
            String correlationId) {
        authority.requireCommand("VALIDATE");
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        HcmPopulationScopeService.ResolvedPopulation population = mutationPopulation();
        String key = idempotencyKey(idempotencyKey);
        AssignmentProposalRepository.ProposalRow before = requireProposal(
                actor.tenantId(), proposalId);
        currentTarget(actor, population, before, false);
        String requestHash = canonicalizer.sha256(Map.of(
                "action", "VALIDATE", "commandId", request.commandId(),
                "proposalId", proposalId, "expectedVersion", request.expectedVersion()));
        Claim claim = claim(actor, population, "VALIDATE", request.commandId(), key, requestHash);
        if (!claim.fresh()) return replay(claim.receipt(), population);

        requireExpected(before, request.expectedVersion(), "DRAFT");
        AssignmentProposalRepository.TargetAssignment target = lockedTarget(
                actor, population, before);
        requireProspectiveTarget(before.changeType(), target);
        Map<String, Object> changes = canonicalizer.changes(before.proposedChanges());
        AssignmentProposalValidator.ReferenceValidation references = repository.references(
                actor.tenantId(), before.effectiveDate(), before.reasonCode(), changes);
        List<AssignmentProposalDtos.ValidationFinding> findings = validator.validate(
                before.changeType(), before.effectiveDate(), changes,
                target.validationTarget(), references);
        validator.requireNoBlocking(findings);
        String validationHash = validationHash(before, target, findings);
        AssignmentProposalRepository.ProposalRow validated = repository.validate(
                actor.tenantId(), proposalId, request.expectedVersion(), validationHash,
                canonicalizer.canonicalJson(findings), actor.userId())
                .orElseThrow(() -> versionConflict(
                        "The proposal changed before validation completed."));
        return finish(actor, population, claim.receipt(), validated, "VALIDATED", correlationId);
    }

    @Transactional
    public AssignmentProposalDtos.CommandResult submit(
            UUID proposalId,
            AssignmentProposalDtos.VersionCommand request,
            String idempotencyKey,
            String correlationId,
            HcmStepUpHeaders headers) {
        authority.requireCommand("SUBMIT");
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        HcmPopulationScopeService.ResolvedPopulation population = mutationPopulation();
        String key = idempotencyKey(idempotencyKey);
        requireHeaderKey(key, headers);
        AssignmentProposalRepository.ProposalRow before = requireProposal(
                actor.tenantId(), proposalId);
        currentTarget(actor, population, before, false);
        Map<String, Object> receiptPayload = Map.of(
                "action", "SUBMIT", "commandId", request.commandId(),
                "proposalId", proposalId, "expectedVersion", request.expectedVersion());
        String requestHash = canonicalizer.sha256(receiptPayload);
        Claim claim = claim(actor, population, "SUBMIT", request.commandId(), key, requestHash);
        if (!claim.fresh()) return replay(claim.receipt(), population);

        requireExpected(before, request.expectedVersion(), "VALIDATED");
        AssignmentProposalRepository.TargetAssignment target = lockedTarget(
                actor, population, before);
        requireProspectiveTarget(before.changeType(), target);
        Map<String, Object> changes = canonicalizer.changes(before.proposedChanges());
        AssignmentProposalValidator.ReferenceValidation references = repository.references(
                actor.tenantId(), before.effectiveDate(), before.reasonCode(), changes);
        List<AssignmentProposalDtos.ValidationFinding> findings = validator.validate(
                before.changeType(), before.effectiveDate(), changes,
                target.validationTarget(), references);
        validator.requireNoBlocking(findings);
        if (!validationHash(before, target, findings).equals(before.validationSha256())) {
            throw versionConflict(
                    "Assignment or reference evidence changed after proposal validation.");
        }
        Map<String, Object> commandPayload = Map.of(
                "commandId", request.commandId(),
                "expectedVersion", request.expectedVersion());
        highRisk.requireExact(
                AssignmentProposalAuthority.SUBMIT_CAPABILITY,
                "ASSIGNMENT_PROPOSAL", proposalId.toString(), before.version(),
                SUBMIT_PUBLIC_PATH_PREFIX + proposalId + "/submit",
                commandPayload, headers);
        AssignmentProposalRepository.ProposalRow submitted = repository.submit(
                actor.tenantId(), proposalId, request.expectedVersion(), actor.userId())
                .orElseThrow(() -> versionConflict(
                        "The proposal changed before submission completed."));
        return finish(actor, population, claim.receipt(), submitted, "SUBMITTED", correlationId);
    }

    @Transactional
    public AssignmentProposalDtos.CommandResult cancel(
            UUID proposalId,
            AssignmentProposalDtos.CancelCommand request,
            String idempotencyKey,
            String correlationId) {
        authority.requireCommand("CANCEL");
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        HcmPopulationScopeService.ResolvedPopulation population = mutationPopulation();
        String key = idempotencyKey(idempotencyKey);
        AssignmentProposalRepository.ProposalRow before = requireProposal(
                actor.tenantId(), proposalId);
        currentTarget(actor, population, before, false);
        String reason = request.reason().trim();
        String requestHash = canonicalizer.sha256(Map.of(
                "action", "CANCEL", "commandId", request.commandId(),
                "proposalId", proposalId, "expectedVersion", request.expectedVersion(),
                "reason", reason));
        Claim claim = claim(actor, population, "CANCEL", request.commandId(), key, requestHash);
        if (!claim.fresh()) return replay(claim.receipt(), population);

        if (request.expectedVersion() != before.version()) {
            throw versionConflict("The proposal changed before cancellation.");
        }
        if ("CANCELLED".equals(before.lifecycleState())) {
            throw invalidState("The proposal is already cancelled.");
        }
        lockedTargetForCancellation(actor, population, before);
        AssignmentProposalRepository.ProposalRow cancelled = repository.cancel(
                actor.tenantId(), proposalId, request.expectedVersion(), reason, actor.userId())
                .orElseThrow(() -> versionConflict(
                        "The proposal changed before cancellation completed."));
        return finish(actor, population, claim.receipt(), cancelled, "CANCELLED", correlationId);
    }

    private HcmPopulationScopeService.ResolvedPopulation readPopulation() {
        HcmPopulationScopeService.ResolvedPopulation population =
                populationScopes.requireOperations("READ");
        requireTrustedPopulation(population);
        return population;
    }

    private HcmPopulationScopeService.ResolvedPopulation mutationPopulation() {
        HcmPopulationScopeService.ResolvedPopulation population =
                populationScopes.requireOperationsForMutation("READ");
        requireTrustedPopulation(population);
        return population;
    }

    private void requireTrustedPopulation(
            HcmPopulationScopeService.ResolvedPopulation population) {
        populationScopes.requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                "WORKFORCE_TARGET_POPULATION", "ORG_UNIT", "LEGAL_ENTITY");
        populationScopes.requireField(population, "EMPLOYMENT");
    }

    private void requirePopulation(
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population,
            long workerId,
            boolean lock) {
        boolean contained = lock
                ? populations.lockWorkerInPopulation(actor.tenantId(), population.scope(), workerId)
                : populations.containsWorker(actor.tenantId(), population.scope(), workerId);
        if (!contained) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The assignment is outside the current target population.");
        }
    }

    private void requireTargetScope(
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population,
            AssignmentProposalRepository.TargetAssignment target,
            boolean lockPopulation) {
        requirePopulation(actor, population, target.workerId(), lockPopulation);
        HcmPopulationRepository.PopulationScope scope = population.scope();
        boolean assignmentContained = scope.tenantWide()
                || (scope.managerAssignmentKey() != null
                    && scope.managerAssignmentKey().equals(target.managerAssignmentKey()))
                || (target.organizationPublicId() != null
                    && scope.organizationIds().contains(target.organizationPublicId()));
        if (!assignmentContained) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The target assignment is outside the current target population.");
        }
    }

    private AssignmentProposalRepository.TargetAssignment currentTarget(
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population,
            AssignmentProposalRepository.ProposalRow proposal,
            boolean lockPopulation) {
        AssignmentProposalRepository.TargetAssignment target = repository
                .target(actor.tenantId(), proposal.assignmentPublicId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (target.workerId() != proposal.workerId()) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The proposal target no longer belongs to its original worker.");
        }
        requireTargetScope(actor, population, target, lockPopulation);
        return target;
    }

    private AssignmentProposalRepository.TargetAssignment lockedTarget(
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population,
            AssignmentProposalRepository.ProposalRow proposal) {
        AssignmentProposalRepository.TargetAssignment target = repository
                .targetForMutation(actor.tenantId(), proposal.assignmentPublicId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (target.workerId() != proposal.workerId()) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The proposal target no longer belongs to its original worker.");
        }
        requireTargetScope(actor, population, target, true);
        if (target.workerVersion() != proposal.targetWorkerVersion()
                || target.relationshipVersion() != proposal.targetRelationshipVersion()
                || target.assignmentVersion() != proposal.targetAssignmentVersion()) {
            throw versionConflict(
                    "The assignment lineage changed after the proposal was created.");
        }
        return target;
    }

    private AssignmentProposalRepository.TargetAssignment lockedTargetForCancellation(
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population,
            AssignmentProposalRepository.ProposalRow proposal) {
        AssignmentProposalRepository.TargetAssignment target = repository
                .targetForMutation(actor.tenantId(), proposal.assignmentPublicId())
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (target.workerId() != proposal.workerId()) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The proposal target no longer belongs to its original worker.");
        }
        requireTargetScope(actor, population, target, true);
        return target;
    }

    private Claim claim(
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population,
            String action,
            UUID commandId,
            String idempotencyKey,
            String requestHash) {
        UUID receiptId = UUID.randomUUID();
        String decisionRevision = HcmPepContext.current() == null
                ? null : HcmPepContext.current().decisionRevision();
        String populationRevision = population.targetPopulationRevision();
        if (populationRevision.length() > 500) {
            populationRevision = populationRevision.substring(0, 500);
        }
        AssignmentProposalRepository.ReceiptRow inserted = repository.claimReceipt(
                actor.tenantId(), receiptId, commandId, actor.userId(),
                actor.personPublicId(), action, idempotencyKey, requestHash,
                populationRevision, decisionRevision).orElse(null);
        if (inserted != null) return new Claim(inserted, true);
        AssignmentProposalRepository.ReceiptRow existing = repository.receipt(
                actor.tenantId(), actor.userId(), action, idempotencyKey)
                .orElseGet(() -> repository.receiptByCommand(actor.tenantId(), commandId)
                        .orElseThrow(() -> new BaseException(
                                ErrorCode.RESOURCE_CONFLICT,
                                "The assignment command identity is already in use.")));
        if (!action.equals(existing.action())
                || !Objects.equals(actor.userId(), existing.subjectUserId())
                || !Objects.equals(actor.personPublicId(), existing.subjectPrincipalPublicId())
                || !idempotencyKey.equals(existing.idempotencyKey())
                || !commandId.equals(existing.commandId())
                || !requestHash.equals(existing.requestSha256())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key or command id is bound to another request.");
        }
        if (!"SUCCEEDED".equals(existing.lifecycleState()) || existing.resultJson() == null) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The prior assignment command has not completed.");
        }
        return new Claim(existing, false);
    }

    private AssignmentProposalDtos.CommandResult replay(
            AssignmentProposalRepository.ReceiptRow receipt,
            HcmPopulationScopeService.ResolvedPopulation population) {
        try {
            AssignmentProposalDtos.Proposal result = objectMapper.readValue(
                    receipt.resultJson(), AssignmentProposalDtos.Proposal.class);
            return new AssignmentProposalDtos.CommandResult(
                    receipt.publicId(), true,
                    applyIdentifierBoundary(result, canReadWorkerIdentifiers(population)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored assignment command result is invalid.", exception);
        }
    }

    private AssignmentProposalDtos.CommandResult finish(
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population,
            AssignmentProposalRepository.ReceiptRow receipt,
            AssignmentProposalRepository.ProposalRow row,
            String eventType,
            String correlationId) {
        AssignmentProposalDtos.Proposal result = proposal(row, true);
        String resultPayload = canonicalizer.canonicalJson(result);
        String eventPayload = canonicalizer.canonicalJson(Map.of(
                "proposalId", row.publicId(),
                "targetAssignmentId", row.assignmentPublicId(),
                "changeType", row.changeType(),
                "effectiveDate", row.effectiveDate(),
                "lifecycleState", row.lifecycleState(),
                "proposalVersion", row.version(),
                "contentSha256", row.contentSha256()));
        repository.appendLifecycle(
                actor.tenantId(), receipt.internalId(), row,
                eventType, eventPayload, actor.userId());
        repository.completeReceipt(
                actor.tenantId(), receipt.internalId(), row, resultPayload);
        recordAudit(actor, row, eventType, correlationId);
        return new AssignmentProposalDtos.CommandResult(
                receipt.publicId(), false,
                applyIdentifierBoundary(result, canReadWorkerIdentifiers(population)));
    }

    private void recordAudit(
            PeopleRequestContext.Actor actor,
            AssignmentProposalRepository.ProposalRow proposal,
            String eventType,
            String correlationId) {
        int risk = "SUBMITTED".equals(eventType) ? 75 : 45;
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action("assignment.proposal." + eventType.toLowerCase(Locale.ROOT))
                .outcome("SUCCESS")
                .severity("SUBMITTED".equals(eventType) ? "HIGH" : "MEDIUM")
                .riskScore(risk)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-people-server")
                .sourceModule("employment-assignments")
                .targetType("ASSIGNMENT_PROPOSAL")
                .targetId(proposal.publicId().toString())
                .correlationId(correlationId)
                .metadata(Map.of(
                        "eventType", eventType,
                        "changeType", proposal.changeType(),
                        "effectiveDate", proposal.effectiveDate().toString(),
                        "targetAssignmentId", proposal.assignmentPublicId().toString(),
                        "proposalVersion", proposal.version(),
                        "assignmentLedgerMutated", false))
                .retentionClass("EXTENDED")
                .build());
    }

    private String validationHash(
            AssignmentProposalRepository.ProposalRow proposal,
            AssignmentProposalRepository.TargetAssignment target,
            List<AssignmentProposalDtos.ValidationFinding> findings) {
        return canonicalizer.sha256(Map.of(
                "contentSha256", proposal.contentSha256(),
                "targetWorkerVersion", target.workerVersion(),
                "targetRelationshipVersion", target.relationshipVersion(),
                "targetAssignmentVersion", target.assignmentVersion(),
                "findings", findings));
    }

    private void requireExpected(
            AssignmentProposalRepository.ProposalRow proposal,
            long expectedVersion,
            String expectedState) {
        if (proposal.version() != expectedVersion) {
            throw versionConflict("The assignment proposal version is stale.");
        }
        if (!expectedState.equals(proposal.lifecycleState())) {
            throw invalidState("The assignment proposal must be " + expectedState + '.');
        }
    }

    private void requireProspectiveTarget(
            String changeType,
            AssignmentProposalRepository.TargetAssignment target) {
        if (!"CORRECTION".equals(changeType)
                && (!List.of("ACTIVE", "SUSPENDED", "PENDING")
                        .contains(target.assignmentStatus())
                    || target.effectiveStartDate().isAfter(LocalDate.now())
                    || (target.effectiveEndDate() != null
                        && target.effectiveEndDate().isBefore(LocalDate.now())))) {
            throw invalidState(
                    "Only a current assignment can receive a prospective change proposal.");
        }
    }

    private void requireHeaderKey(String idempotencyKey, HcmStepUpHeaders headers) {
        if (headers == null || headers.idempotencyKey() == null
                || !idempotencyKey.equals(headers.idempotencyKey())) {
            throw new BaseException(ErrorCode.STEP_UP_CHALLENGE_MISMATCH,
                    "The submit idempotency header is not bound to the command.");
        }
    }

    private String idempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 200
                || !value.equals(value.trim())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "A canonical Idempotency-Key of at most 200 characters is required.");
        }
        return value;
    }

    private String reasonCode(String value) {
        String result = value.trim().toUpperCase(Locale.ROOT);
        if (!result.matches("[A-Z][A-Z0-9._-]{0,79}")) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The assignment reason code is invalid.");
        }
        return result;
    }

    private AssignmentProposalRepository.ProposalRow requireProposal(
            Long tenantId, UUID proposalId) {
        return repository.proposal(tenantId, proposalId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private AssignmentProposalDtos.Proposal proposal(
            AssignmentProposalRepository.ProposalRow row,
            boolean identifiers) {
        return applyIdentifierBoundary(new AssignmentProposalDtos.Proposal(
                row.publicId(), row.assignmentPublicId(), row.workerPublicId(),
                row.relationshipPublicId(), row.assignmentKey(), row.workerNumber(),
                row.personDisplayName(), row.changeType(), row.effectiveDate(),
                row.reasonCode(), row.proposedChanges(), row.lifecycleState(),
                row.validationFindings(), row.targetWorkerVersion(),
                row.targetRelationshipVersion(), row.targetAssignmentVersion(),
                row.version(), row.validatedAt(), row.submittedAt(), row.cancelledAt(),
                row.cancellationReason(), row.createdAt(), row.updatedAt()), identifiers);
    }

    private AssignmentProposalDtos.AssignmentDetail detail(
            AssignmentProposalRepository.TargetAssignment row,
            boolean identifiers) {
        return new AssignmentProposalDtos.AssignmentDetail(
                row.assignmentPublicId(), row.workerPublicId(), row.relationshipPublicId(),
                identifiers ? row.assignmentKey() : null,
                identifiers ? row.workerNumber() : null, row.personDisplayName(),
                row.assignmentStatus(), row.primaryAssignment(), row.effectiveStartDate(),
                row.effectiveEndDate(), row.organizationPublicId(), row.organizationName(),
                row.jobProfileKey(), row.jobName(), row.locationKey(), row.locationName(),
                row.managerAssignmentPublicId(), row.businessTitle(), row.workerHours(),
                row.fullTimeEquivalent(), row.changeReasonCode(), row.workerVersion(),
                row.relationshipVersion(), row.assignmentVersion());
    }

    private AssignmentProposalDtos.Proposal applyIdentifierBoundary(
            AssignmentProposalDtos.Proposal result,
            boolean identifiers) {
        if (identifiers) return result;
        return new AssignmentProposalDtos.Proposal(
                result.proposalId(), result.targetAssignmentId(), result.targetWorkerId(),
                result.targetWorkRelationshipId(), null, null, result.personDisplayName(),
                result.changeType(), result.effectiveDate(), result.reasonCode(),
                result.proposedChanges(), result.lifecycleState(), result.validationFindings(),
                result.targetWorkerVersion(), result.targetRelationshipVersion(),
                result.targetAssignmentVersion(), result.version(), result.validatedAt(),
                result.submittedAt(), result.cancelledAt(), result.cancellationReason(),
                result.createdAt(), result.updatedAt());
    }

    private boolean canReadWorkerIdentifiers(
            HcmPopulationScopeService.ResolvedPopulation population) {
        return population.scope().fieldGroups().contains("WORKER_IDENTIFIERS");
    }

    private BaseException versionConflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }

    private BaseException invalidState(String message) {
        return new BaseException(ErrorCode.INVALID_STATE, message);
    }

    private record Claim(
            AssignmentProposalRepository.ReceiptRow receipt,
            boolean fresh) {
    }

}
