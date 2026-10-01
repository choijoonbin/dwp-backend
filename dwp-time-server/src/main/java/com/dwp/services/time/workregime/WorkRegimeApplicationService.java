package com.dwp.services.time.workregime;

import static com.dwp.services.time.workregime.WorkRegimeApiModels.SIMULATION_PURPOSE;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.time.workregime.WorkRegimeApiModels.ArrangementView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.AssignmentView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.CreateDraftRequest;
import com.dwp.services.time.workregime.WorkRegimeApiModels.CreateDraftView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.PolicyPackView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.PolicyTraceView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.ReceiptView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.ResolutionView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.SegmentView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.SimulationCommandView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.SimulationRequest;
import com.dwp.services.time.workregime.WorkRegimeApiModels.StudioView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.WorkPlanView;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.Command;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.ReceiptStore;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.TerminalOutcome;
import com.dwp.services.time.workregime.WorkRegimeModels.AssignmentPlan;
import com.dwp.services.time.workregime.WorkRegimeModels.Authority;
import com.dwp.services.time.workregime.WorkRegimeModels.CommandReceipt;
import com.dwp.services.time.workregime.WorkRegimeModels.Duty;
import com.dwp.services.time.workregime.WorkRegimeModels.EffectivePeriod;
import com.dwp.services.time.workregime.WorkRegimeModels.LifecycleAction;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyCandidate;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyResolution;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyState;
import com.dwp.services.time.workregime.WorkRegimeModels.RulePack;
import com.dwp.services.time.workregime.WorkRegimeModels.RulePackState;
import com.dwp.services.time.workregime.WorkRegimeModels.SimulationResult;
import com.dwp.services.time.workregime.WorkRegimeModels.SimulationState;
import com.dwp.services.time.workregime.WorkRegimeModels.WorkRegimeRevision;
import com.dwp.services.time.workregime.WorkRegimeOwnerAuthoritySource.VerifiedRequest;
import com.dwp.services.time.workregime.WorkRegimeRepository.DraftWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.CommandEvidence;
import com.dwp.services.time.workregime.WorkRegimeRepository.PolicyTermWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.SimulationWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.StoredSimulation;
import com.dwp.services.time.workregime.WorkRegimeRepository.TargetAuthorizationGuard;
import com.dwp.services.time.workregime.WorkRegimeRepository.TargetBindingEvidence;
import com.dwp.services.time.workregime.WorkRegimeRepository.TenantExtensionWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.TransitionWrite;
import com.dwp.services.time.workregime.WorkRegimeRepository.WorkPlanRecord;
import com.dwp.services.time.workregime.WorkRegimeTargetPopulationResolver.PopulationAccess;
import com.dwp.services.time.workregime.WorkRegimeTargetPopulationResolver.TargetMembershipEvidence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRulesProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Callable owner use cases behind the default-off TIM integration boundary. */
@Service
@ConditionalOnProperty(name = "dwp.time.work-regime-api.enabled", havingValue = "true")
public final class WorkRegimeApplicationService {

    private final WorkRegimeRepository repository;
    private final WorkRegimeCommandCoordinator commands;
    private final WorkPolicyResolver resolver = new WorkPolicyResolver();
    private final ScheduleSimulationEngine simulator = new ScheduleSimulationEngine();
    private final WorkRegimeLifecycleGuard lifecycle = new WorkRegimeLifecycleGuard();
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final WorkRegimeTargetPopulationResolver targetPopulationResolver;

    public WorkRegimeApplicationService(
            WorkRegimeRepository repository,
            ReceiptStore receipts,
            ObjectMapper objectMapper,
            WorkRegimeTargetPopulationResolver targetPopulationResolver) {
        this(
                repository,
                receipts,
                objectMapper,
                Clock.systemUTC(),
                targetPopulationResolver);
    }

    WorkRegimeApplicationService(
            WorkRegimeRepository repository,
            ReceiptStore receipts,
            ObjectMapper objectMapper,
            Clock clock,
            WorkRegimeTargetPopulationResolver targetPopulationResolver) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.commands = new WorkRegimeCommandCoordinator(receipts, clock);
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.targetPopulationResolver = Objects.requireNonNull(
                targetPopulationResolver, "targetPopulationResolver must not be null");
    }

    public StudioView list(VerifiedRequest request, LocalDate effectiveOn) {
        Objects.requireNonNull(effectiveOn, "effectiveOn must not be null");
        Authority authority = request.authority();
        requireOwnerDuty(authority);
        PopulationAccess access = requireCurrentAccess(request);
        TargetAuthorizationGuard guard = targetAuthorization(request, access);
        List<WorkPlanView> views = new ArrayList<>();
        boolean populationPlanUnavailable = false;
        for (WorkPlanRecord record : repository.findEffective(guard, effectiveOn)) {
            try {
                if (!covers(authority, record.revision().scopeRef())) continue;
                requireCurrentTargetMembership(access, record);
                views.add(toWorkPlan(authority, access, guard, record, effectiveOn));
            } catch (RuntimeException unavailable) {
                populationPlanUnavailable = true;
            }
        }
        views.sort(Comparator.comparing(WorkPlanView::workPlanId));
        List<String> failures = populationPlanUnavailable
                ? List.of("TARGET_POPULATION_PLAN_UNAVAILABLE") : List.of();
        String queryState = views.isEmpty()
                ? (failures.isEmpty() ? "EMPTY" : "UNAVAILABLE")
                : (failures.isEmpty() ? "COMPLETE" : "PARTIAL");
        return new StudioView(
                queryState,
                failures.isEmpty() ? "CURRENT" : "STALE",
                clock.instant(),
                failures.stream().sorted().toList(),
                views);
    }

    public CreateDraftView createDraft(
            VerifiedRequest verified,
            UUID idempotencyKey,
            CreateDraftRequest request) {
        Authority authority = verified.authority();
        requireDuty(authority, Duty.TIME_CONFIG_AUTHOR);
        PopulationAccess access = requireCurrentAccess(verified);
        TargetAuthorizationGuard guard = targetAuthorization(verified, access);
        requireScope(authority, request.scopeRef());
        if (request.scopeType() != WorkRegimeModels.ScopeType.POPULATION
                || !access.scopePublicRef().equals(request.scopeRef())) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        EffectivePeriod period = new EffectivePeriod(
                request.effectiveStart(), request.effectiveEnd());
        TargetMembershipEvidence membership = requireCurrentTargetMembership(
                access,
                request.workerPublicId(),
                request.peopleAssignmentPublicId(),
                request.assignmentSnapshotRevision(),
                period);
        validateZone(request.timeZone());

        UUID workPlanId = stableId(
                authority.tenantId(), LifecycleAction.CREATE_DRAFT, idempotencyKey, "REGIME");
        UUID assignmentId = stableId(
                authority.tenantId(), LifecycleAction.CREATE_DRAFT, idempotencyKey, "ASSIGNMENT");
        String canonicalExtension = canonicalExtension(request);
        TenantExtensionWrite extension = request.tenantExtension() == null ? null
                : new TenantExtensionWrite(
                        request.tenantExtension().schemaRef(),
                        request.tenantExtension().schemaVersion(),
                        canonicalExtension,
                        digest(canonicalExtension));
        String templateDigest = digest(request.segments());
        String resolutionDigest = digest(new ResolutionEvidence(
                request.rulePackPublicId(), request.jurisdiction(),
                request.jurisdictionSubdivision(), request.policyRevision(),
                request.scopeType().name(), request.scopeRef(), request.priority(), period));
        TargetBindingEvidence targetBindingEvidence = targetBindingEvidence(
                authority, verified, access, membership);
        String sourceDigest = digest(new AssignmentEvidence(
                request.workerPublicId(), request.peopleAssignmentPublicId(),
                request.assignmentSnapshotRevision(), period, request.timeZone(),
                access.populationPublicId(), access.populationRevision(),
                membership.membershipRevision(), access.grantRevision(),
                access.populationDigest(), membership.membershipDigest(),
                access.grantDigest()));
        List<PolicyTermWrite> terms = request.terms().stream().map(term -> new PolicyTermWrite(
                        term.extensionKind(), term.parameterName(), term.valueType(),
                        term.stringValue(), term.integerValue(), term.decimalValue(),
                        term.booleanValue(), term.dateValue())).toList();
        List<WorkRegimeModels.LocalSegment> segments = request.segments().stream()
                .map(segment -> new WorkRegimeModels.LocalSegment(
                        segment.key(), segment.dayOfWeek(), segment.kind(), segment.start(),
                        segment.end(), segment.endDayOffset(), segment.overlapPolicy())).toList();
        String requestDigest = digest(new CreateCommandEvidence(authority.actorId(), request));
        Command command = command(
                verified, idempotencyKey, LifecycleAction.CREATE_DRAFT,
                workPlanId, request.scopeRef(), null, requestDigest);
        CommandReceipt replay = commands.findReplay(command).orElse(null);
        if (replay != null) {
            return new CreateDraftView(
                    WorkRegimeViewMapper.receipt(replay), replay.aggregateId().toString());
        }
        requireRulePack(
                authority.tenantId(), request.rulePackPublicId(), request.jurisdiction(),
                request.policyRevision(), period);
        CommandReceipt receipt = commands.executeWithReceipt(command, (ignored, running) -> {
            DraftWrite write = new DraftWrite(
                    authority.tenantId(), workPlanId, assignmentId,
                    request.workerPublicId(), request.peopleAssignmentPublicId(),
                    request.assignmentSnapshotRevision(), request.regimeKey(), 1L,
                    request.displayName(), request.arrangementKind(), extension,
                    request.scopeType(), request.scopeRef(), request.priority(), period,
                    request.timeZone(), request.rulePackPublicId(), request.jurisdiction(),
                    request.jurisdictionSubdivision(), request.policyRevision(), resolutionDigest,
                    request.templateSchemaVersion(), templateDigest, sourceDigest,
                    authority.actorId(), verified.correlationId(), terms, segments,
                    targetBindingEvidence,
                    evidence(verified, running.receiptId(), guard, command));
            WorkPlanRecord created = repository.createDraft(write);
            return TerminalOutcome.succeeded(
                    "DRAFT_CREATED", created.revision().artifactDigest());
        });
        return new CreateDraftView(
                WorkRegimeViewMapper.receipt(receipt), receipt.aggregateId().toString());
    }

    public SimulationCommandView simulate(
            VerifiedRequest verified,
            UUID workPlanId,
            UUID idempotencyKey,
            SimulationRequest request) {
        Authority authority = verified.authority();
        requireDuty(authority, Duty.TIME_CONFIG_AUTHOR);
        OwnedPlan owned = ownedPlan(verified, workPlanId);
        WorkPlanRecord plan = owned.plan();
        if (!SIMULATION_PURPOSE.equals(request.purpose())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }

        EffectivePeriod preview = previewPeriod(plan.assignmentPeriod());
        String requestDigest = digest(new SimulationCommandEvidence(
                workPlanId, authority.actorId(), request));
        Command command = command(
                verified, idempotencyKey, LifecycleAction.SIMULATE,
                workPlanId, plan.revision().scopeRef(), request.expectedVersion(), requestDigest);
        CommandReceipt replay = commands.findReplay(command).orElse(null);
        if (replay != null) return simulationCommand(owned.guard(), replay);
        requireVersion(plan, request.expectedVersion());
        requireRevision(request.assignmentSnapshotRevision(), plan.peopleAssignmentRevision(),
                ErrorCode.OBJECT_VERSION_CONFLICT);
        requireRevision(request.policyRevision(), plan.policyRevision(),
                ErrorCode.OBJECT_VERSION_CONFLICT);
        CommandReceipt receipt = commands.executeWithReceipt(command, (ignored, running) -> {
            PolicyResolution resolution = resolveForSimulation(
                    authority, owned.guard(), plan, preview.from());
            if (!resolution.resolved()) {
                return TerminalOutcome.rejected(
                        resolution.code().name(), digest(resolution.code().name()));
            }
            List<AssignmentPlan> assignments = requireAuthorizedAssignments(
                    owned.access(),
                    repository.findSimulationAssignments(
                            owned.guard(), workPlanId, preview),
                    preview);
            SimulationResult result;
            try {
                result = simulator.simulate(
                        resolution, assignments, preview, clock.instant(), tzdbVersion(plan.zoneId()));
            } catch (ScheduleSimulationEngine.SimulationRejectedException rejected) {
                return TerminalOutcome.rejected(
                        rejected.code().name(), digest(rejected.code().name()));
            }
            String resultDigest = digest(result);
            SimulationWrite simulation = new SimulationWrite(
                    authority.tenantId(), UUID.randomUUID(), running.receiptId(),
                    idempotencyKey, workPlanId, preview, requestDigest, resultDigest,
                    plan.zoneId(), result.tzdbVersion(), plan.rulePackPublicId(),
                    plan.policyRevision(), plan.resolutionDigest(), result,
                    result.calculatedAt(), authority.actorId(),
                    evidence(verified, running.receiptId(), owned.guard(), command));
            if (result.state() == SimulationState.SUCCEEDED) {
                WorkRegimeRevision next = lifecycle.transition(
                        plan.revision(), LifecycleAction.SIMULATE,
                        authority, request.expectedVersion());
                repository.saveSimulationAndTransition(
                        simulation,
                        transition(
                                verified, owned.guard(), next,
                                request.expectedVersion(), running.receiptId(), command));
            } else {
                repository.saveSimulation(simulation);
            }
            return TerminalOutcome.succeeded(
                    result.state() == SimulationState.SUCCEEDED
                            ? "SIMULATION_SUCCEEDED" : "SIMULATION_BLOCKED",
                    resultDigest);
        });
        return simulationCommand(owned.guard(), receipt);
    }

    public ReceiptView transition(
            VerifiedRequest verified,
            UUID workPlanId,
            UUID idempotencyKey,
            LifecycleAction action,
            long expectedVersion) {
        if (action == LifecycleAction.SIMULATE
                || action == LifecycleAction.CREATE_DRAFT
                || action == LifecycleAction.REVISE_DRAFT
                || action == LifecycleAction.ASSIGN) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        Authority authority = verified.authority();
        requireDuty(
                authority,
                action == LifecycleAction.APPLY_APPROVAL || action == LifecycleAction.PUBLISH
                        ? Duty.TIME_CONFIG_APPROVER : Duty.TIME_CONFIG_AUTHOR);
        OwnedPlan owned = ownedPlan(verified, workPlanId);
        WorkPlanRecord plan = owned.plan();
        String requestDigest = digest(new TransitionEvidence(
                workPlanId, authority.actorId(), action, expectedVersion));
        Command command = command(
                verified, idempotencyKey, action, workPlanId,
                plan.revision().scopeRef(), expectedVersion, requestDigest);
        CommandReceipt replay = commands.findReplay(command).orElse(null);
        if (replay != null) return WorkRegimeViewMapper.receipt(replay);
        requireVersion(plan, expectedVersion);
        CommandReceipt receipt = commands.executeWithReceipt(command, (ignored, running) -> {
            if (action == LifecycleAction.VALIDATE || action == LifecycleAction.PUBLISH) {
                PolicyResolution resolution = action == LifecycleAction.VALIDATE
                        ? resolveForValidation(
                                authority, owned.guard(), plan,
                                plan.revision().period().from())
                        : resolveForAuthoring(
                                authority, owned.guard(), plan,
                                plan.revision().period().from());
                if (!resolution.resolved()
                        || !resolution.rulePack().period().contains(plan.revision().period())) {
                    String code = resolution.resolved()
                            ? "PACK_OUT_OF_RANGE" : resolution.code().name();
                    return TerminalOutcome.rejected(code, digest(code));
                }
            }
            WorkRegimeRevision next;
            try {
                next = lifecycle.transition(plan.revision(), action, authority, expectedVersion);
            } catch (WorkRegimeLifecycleGuard.LifecycleDeniedException denied) {
                return TerminalOutcome.rejected(
                        denied.code().name(), digest(denied.code().name()));
            }
            repository.transition(transition(
                    verified, owned.guard(), next, expectedVersion, running.receiptId(), command));
            return TerminalOutcome.succeeded(
                    action.name() + "_SUCCEEDED",
                    digest(next.state().name() + ":" + next.version()));
        });
        return WorkRegimeViewMapper.receipt(receipt);
    }

    public SimulationCommandView receipt(VerifiedRequest verified, UUID receiptId) {
        Authority authority = verified.authority();
        requireOwnerDuty(authority);
        PopulationAccess access = requireCurrentAccess(verified);
        TargetAuthorizationGuard guard = targetAuthorization(verified, access);
        CommandReceipt receipt = commands.receipt(authority.tenantId(), receiptId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireReceiptAccess(authority, receipt);
        OwnedPlan owned = ownedPlan(verified, receipt.aggregateId());
        CommandReceipt recovered = commands.recover(
                authority.tenantId(), receiptId,
                candidate -> reconcile(candidate, owned.guard()));
        return simulationCommand(owned.guard(), recovered);
    }

    private TerminalOutcome reconcile(
            CommandReceipt receipt, TargetAuthorizationGuard guard) {
        if (receipt.operation() == LifecycleAction.SIMULATE) {
            StoredSimulation stored = repository.findSimulationByReceipt(
                    guard, receipt.receiptId(), receipt.aggregateId()).orElse(null);
            if (stored != null) {
                String code = stored.result().state() == SimulationState.SUCCEEDED
                        ? "SIMULATION_SUCCEEDED" : "SIMULATION_BLOCKED";
                return TerminalOutcome.succeeded(code, stored.resultDigest());
            }
        } else {
            var evidence = repository.findCommandOutcome(
                    guard, receipt.receiptId(),
                    receipt.aggregateId(), receipt.operation()).orElse(null);
            if (evidence != null) {
                return TerminalOutcome.succeeded(
                        evidence.resultCode(), evidence.resultDigest());
            }
        }
        return TerminalOutcome.failed("COMMAND_NOT_APPLIED", null);
    }

    private WorkPlanView toWorkPlan(
            Authority authority,
            PopulationAccess access,
            TargetAuthorizationGuard guard,
            WorkPlanRecord plan,
            LocalDate effectiveOn) {
        PolicyResolution resolution = resolveForAuthoring(authority, guard, plan, effectiveOn);
        List<SegmentView> segments = List.of();
        if (resolution.resolved()) {
            EffectivePeriod day = new EffectivePeriod(effectiveOn, effectiveOn.plusDays(1));
            List<AssignmentPlan> assignments = requireAuthorizedAssignments(
                    access,
                    repository.findSimulationAssignments(
                            guard, plan.revision().publicId(), day),
                    day);
            SimulationResult result = simulator.simulate(
                    resolution, assignments, day, clock.instant(), tzdbVersion(plan.zoneId()));
            segments = result.segments().stream().map(WorkRegimeViewMapper::segment).toList();
        }
        List<RulePack> packs = repository.findRulePacks(
                authority.tenantId(), plan.jurisdiction(), plan.policyRevision());
        String packState = WorkRegimeViewMapper.packState(plan, effectiveOn, packs);
        List<PolicyCandidate> candidates = repository.findPolicyCandidates(
                guard, plan.jurisdiction(), plan.policyRevision());
        Set<UUID> consideredPolicyIds = Set.copyOf(resolution.consideredPolicyIds());
        List<PolicyTraceView> trace = candidates.stream()
                .filter(candidate -> candidate.tenantId() == authority.tenantId())
                .filter(candidate -> consideredPolicyIds.contains(candidate.publicId()))
                .filter(candidate -> candidate.period().contains(effectiveOn))
                .map(candidate -> new PolicyTraceView(
                        WorkRegimeViewMapper.precedenceLevel(candidate),
                        candidate.scopeRef(),
                        resolution.resolved()
                                && candidate.publicId().equals(resolution.selected().publicId())
                                ? "APPLIED" : "SHADOWED",
                        candidate.publicId().toString(),
                        Long.toString(candidate.revision())))
                .toList();
        return new WorkPlanView(
                plan.revision().publicId().toString(), plan.displayName(),
                plan.revision().version(), plan.revision().state().name(),
                new ArrangementView(plan.arrangementKind().name(), plan.extensionCode()),
                plan.revision().period().from(), plan.revision().period().to(), plan.zoneId(),
                new AssignmentView(
                        plan.assignmentPublicId().toString(),
                        "Assignment " + plan.assignmentPublicId().toString().substring(0, 8),
                        Long.toString(plan.peopleAssignmentRevision()), "CURRENT"),
                new PolicyPackView(
                        packState, plan.jurisdiction(), effectiveOn,
                        "CURRENT".equals(packState)
                                ? Long.toString(plan.policyRevision()) : null),
                new ResolutionView(
                        WorkRegimeViewMapper.resolutionState(resolution.code()),
                        resolution.resolved()
                                ? WorkRegimeViewMapper.precedenceLevel(resolution.selected()) : null,
                        trace),
                segments,
                WorkRegimeViewMapper.availableActions(
                        authority, plan.revision().state(), resolution.resolved()));
    }

    private List<AssignmentPlan> requireAuthorizedAssignments(
            PopulationAccess access,
            List<AssignmentPlan> assignments,
            EffectivePeriod requiredPeriod) {
        for (AssignmentPlan assignment : assignments) {
            if (assignment.tenantId() != access.tenantId()) {
                throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE);
            }
            requireCurrentTargetMembership(
                    access,
                    assignment.workerId(),
                    assignment.peopleAssignmentId(),
                    assignment.peopleAssignmentRevision(),
                    requiredPeriod);
        }
        return List.copyOf(assignments);
    }

    private PolicyResolution resolveForAuthoring(
            Authority authority,
            TargetAuthorizationGuard guard,
            WorkPlanRecord plan,
            LocalDate date) {
        return resolver.resolveForAuthoring(
                authority.tenantId(), plan.jurisdiction(), date, plan.policyRevision(),
                plan.revision().revision(), authority.scopeRefs(),
                repository.findRulePacks(
                        authority.tenantId(), plan.jurisdiction(), plan.policyRevision()),
                repository.findPolicyCandidates(
                        guard, plan.jurisdiction(), plan.policyRevision()),
                plan.revision().publicId());
    }

    private PolicyResolution resolveForValidation(
            Authority authority,
            TargetAuthorizationGuard guard,
            WorkPlanRecord plan,
            LocalDate date) {
        return resolver.resolveForValidation(
                authority.tenantId(), plan.jurisdiction(), date, plan.policyRevision(),
                plan.revision().revision(), authority.scopeRefs(),
                repository.findRulePacks(
                        authority.tenantId(), plan.jurisdiction(), plan.policyRevision()),
                repository.findPolicyCandidates(
                        guard, plan.jurisdiction(), plan.policyRevision()),
                plan.revision().publicId());
    }

    private PolicyResolution resolveForSimulation(
            Authority authority,
            TargetAuthorizationGuard guard,
            WorkPlanRecord plan,
            LocalDate date) {
        return resolver.resolveForSimulation(
                authority.tenantId(), plan.jurisdiction(), date, plan.policyRevision(),
                plan.revision().revision(), authority.scopeRefs(),
                repository.findRulePacks(
                        authority.tenantId(), plan.jurisdiction(), plan.policyRevision()),
                repository.findPolicyCandidates(
                        guard, plan.jurisdiction(), plan.policyRevision()),
                plan.revision().publicId());
    }

    private OwnedPlan ownedPlan(VerifiedRequest verified, UUID publicId) {
        Authority authority = verified.authority();
        PopulationAccess access = requireCurrentAccess(verified);
        TargetAuthorizationGuard guard = targetAuthorization(verified, access);
        WorkPlanRecord record = repository.findByPublicId(guard, publicId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireScope(authority, record.revision().scopeRef());
        requireCurrentTargetMembership(access, record);
        return new OwnedPlan(access, guard, record);
    }

    private TargetAuthorizationGuard targetAuthorization(
            VerifiedRequest verified, PopulationAccess access) {
        return new TargetAuthorizationGuard(
                access.tenantId(),
                access.actorId(),
                verified.contextScopeKey(),
                access.populationPublicId(),
                access.populationRevision(),
                access.grantRevision(),
                access.populationDigest(),
                access.grantDigest(),
                clock.instant());
    }

    private PopulationAccess requireCurrentAccess(VerifiedRequest verified) {
        Authority authority = verified.authority();
        PopulationAccess access;
        try {
            access = targetPopulationResolver.resolveActorAccess(
                            authority.tenantId(),
                            authority.actorId(),
                            verified.contextScopeKey(),
                            clock.instant())
                    .orElseThrow(() -> new BaseException(
                            ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                            "Current TIM target-population authority is unavailable."));
        } catch (BaseException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Current TIM target-population authority could not be resolved.",
                    unavailable);
        }
        boolean exact = access.tenantId() == authority.tenantId()
                && access.actorId() == authority.actorId()
                && access.gatewayScopeKey().equals(verified.contextScopeKey())
                && access.validUntil().isAfter(clock.instant())
                && authority.scopeRefs().equals(Set.of(access.scopePublicRef()));
        if (!exact) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Current TIM target-population authority is stale or mismatched.");
        }
        return access;
    }

    private TargetMembershipEvidence requireCurrentTargetMembership(
            PopulationAccess access, WorkPlanRecord plan) {
        TargetBindingEvidence saved = plan.targetBindingEvidence();
        if (!saved.populationPublicId().equals(access.populationPublicId())
                || !plan.revision().scopeRef().equals(access.scopePublicRef())
                || saved.populationRevision() != access.populationRevision()
                || !saved.populationDigest().equals(access.populationDigest())) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        TargetMembershipEvidence current = requireCurrentTargetMembership(
                access,
                plan.workerPublicId(),
                plan.peopleAssignmentPublicId(),
                plan.peopleAssignmentRevision(),
                plan.assignmentPeriod());
        if (saved.populationRevision() != current.populationRevision()
                || !saved.populationDigest().equals(current.populationDigest())
                || saved.membershipRevision() != current.membershipRevision()
                || !saved.membershipDigest().equals(current.membershipDigest())) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Stored TIM target-population binding evidence is stale or mismatched.");
        }
        return current;
    }

    private TargetMembershipEvidence requireCurrentTargetMembership(
            PopulationAccess access,
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long peopleAssignmentRevision,
            EffectivePeriod period) {
        TargetMembershipEvidence membership;
        try {
            membership = targetPopulationResolver.resolveTargetMembership(
                            access.tenantId(),
                            access.populationPublicId(),
                            workerPublicId,
                            peopleAssignmentPublicId,
                            peopleAssignmentRevision,
                            period,
                            clock.instant())
                    .orElseThrow(() -> new BaseException(
                            ErrorCode.FORBIDDEN,
                            "The selected People assignment is outside the current target population."));
        } catch (BaseException denied) {
            throw denied;
        } catch (RuntimeException unavailable) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Current TIM target-population membership could not be resolved.",
                    unavailable);
        }
        if (membership.tenantId() != access.tenantId()
                || !membership.populationPublicId().equals(access.populationPublicId())
                || !membership.workerPublicId().equals(workerPublicId)
                || !membership.peopleAssignmentPublicId().equals(peopleAssignmentPublicId)
                || membership.peopleAssignmentRevision() != peopleAssignmentRevision
                || !membership.effectivePeriod().contains(period)
                || membership.populationRevision() != access.populationRevision()
                || !membership.populationDigest().equals(access.populationDigest())) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "TIM target-population projections are inconsistent.");
        }
        return membership;
    }

    private TargetBindingEvidence targetBindingEvidence(
            Authority authority,
            VerifiedRequest verified,
            PopulationAccess access,
            TargetMembershipEvidence membership) {
        return new TargetBindingEvidence(
                access.populationPublicId(),
                access.populationRevision(),
                membership.workerPublicId(),
                membership.peopleAssignmentPublicId(),
                membership.peopleAssignmentRevision(),
                membership.membershipRevision(),
                authority.actorId(),
                verified.contextScopeKey(),
                access.grantRevision(),
                access.populationDigest(),
                membership.membershipDigest(),
                access.grantDigest(),
                clock.instant());
    }

    private void requireRulePack(
            long tenantId,
            UUID publicId,
            String jurisdiction,
            long revision,
            EffectivePeriod period) {
        List<RulePack> exact = repository.findRulePacks(tenantId, jurisdiction, revision).stream()
                .filter(pack -> pack.publicId().equals(publicId))
                .toList();
        if (exact.size() != 1) throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        RulePack pack = exact.getFirst();
        if (pack.state() != RulePackState.PUBLISHED || !pack.signatureVerified()
                || !pack.period().contains(period)) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    private TransitionWrite transition(
            VerifiedRequest verified,
            TargetAuthorizationGuard guard,
            WorkRegimeRevision next,
            long expectedVersion,
            UUID receiptId,
            Command command) {
        return new TransitionWrite(
                next.tenantId(), next.publicId(), expectedVersion, next.state(),
                next.state() == PolicyState.APPROVED
                        ? next.approvalActorId() : null,
                next.state() == PolicyState.APPROVED ? receiptId : null,
                next.state() == PolicyState.PUBLISHED
                        ? verified.authority().actorId() : null,
                next.state() == PolicyState.PUBLISHED ? receiptId : null,
                verified.authority().actorId(), evidence(verified, receiptId, guard, command));
    }

    private static CommandEvidence evidence(
            VerifiedRequest verified,
            UUID receiptId,
            TargetAuthorizationGuard guard,
            Command command) {
        Authority authority = verified.authority();
        return new CommandEvidence(
                receiptId, verified.correlationId(), authority.actorId(),
                authority.purpose(), authority.decisionId(),
                command.idempotencyKey(), command.operation(), command.aggregateId(),
                command.expectedVersion(), command.requestDigest(), guard);
    }

    private Command command(
            VerifiedRequest verified,
            UUID idempotencyKey,
            LifecycleAction action,
            UUID aggregateId,
            String scopePublicRef,
            Long expectedVersion,
            String requestDigest) {
        return new Command(
                verified.authority(), idempotencyKey, action, aggregateId,
                scopePublicRef, expectedVersion, requestDigest, verified.correlationId());
    }

    private SimulationCommandView simulationCommand(
            TargetAuthorizationGuard guard, CommandReceipt receipt) {
        StoredSimulation stored = receipt.operation() == LifecycleAction.SIMULATE
                ? repository.findSimulationByReceipt(
                        guard, receipt.receiptId(), receipt.aggregateId()).orElse(null)
                : null;
        return new SimulationCommandView(
                WorkRegimeViewMapper.receipt(receipt),
                stored == null ? null : WorkRegimeViewMapper.simulation(stored));
    }

    private static EffectivePeriod previewPeriod(EffectivePeriod period) {
        LocalDate proposedEnd = period.from().plusDays(7);
        LocalDate end = period.to() != null && period.to().isBefore(proposedEnd)
                ? period.to() : proposedEnd;
        return new EffectivePeriod(period.from(), end);
    }

    private String canonicalExtension(CreateDraftRequest request) {
        if (request.tenantExtension() == null) return null;
        var fields = new TreeMap<String, Object>();
        request.tenantExtension().fields().forEach(
                field -> fields.put(field.fieldName(), field.typedValue()));
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException invalid) {
            throw new BaseException(
                    ErrorCode.INVALID_FORMAT, "Invalid typed extension fields", invalid);
        }
    }

    private String digest(Object value) {
        try {
            byte[] bytes = value instanceof String text
                    ? text.getBytes(StandardCharsets.UTF_8)
                    : objectMapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not create canonical TIM digest", failure);
        }
    }

    private static void validateZone(String zoneId) {
        ZoneId parsed = ZoneId.of(zoneId);
        if (parsed instanceof ZoneOffset) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private static void requireVersion(WorkPlanRecord plan, long expectedVersion) {
        if (plan.revision().version() != expectedVersion) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT);
        }
    }

    private static void requireRevision(String actual, long expected, ErrorCode code) {
        if (!Long.toString(expected).equals(actual)) throw new BaseException(code);
    }

    private static void requireScope(Authority authority, String scopeRef) {
        if (!covers(authority, scopeRef)) throw new BaseException(ErrorCode.FORBIDDEN);
    }

    private static boolean covers(Authority authority, String scopeRef) {
        return authority.scopeRefs().contains(scopeRef);
    }

    private static void requireDuty(Authority authority, Duty duty) {
        if (!authority.duties().contains(duty)) throw new BaseException(ErrorCode.FORBIDDEN);
    }

    private static void requireOwnerDuty(Authority authority) {
        if (authority.duties().stream().noneMatch(Set.of(
                Duty.TIME_CONFIG_AUTHOR,
                Duty.TIME_CONFIG_APPROVER,
                Duty.TIME_OPERATOR,
                Duty.TIME_AUDITOR)::contains)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }

    private static void requireReceiptAccess(
            Authority authority, CommandReceipt receipt) {
        boolean denied = receipt.operation() == LifecycleAction.REVISE_DRAFT
                || receipt.operation() == LifecycleAction.ASSIGN
                || authority.revoked()
                || authority.actorId() != receipt.actorId()
                || !authority.purpose().equals(receipt.purpose())
                || !authority.scopeRefs().contains(receipt.scopePublicRef())
                || authority.duties().stream().noneMatch(Set.of(
                        Duty.TIME_CONFIG_AUTHOR,
                        Duty.TIME_CONFIG_APPROVER,
                        Duty.TIME_OPERATOR,
                        Duty.TIME_AUDITOR)::contains);
        if (denied) throw new BaseException(ErrorCode.NOT_FOUND);
    }

    private static UUID stableId(
            long tenantId, LifecycleAction action, UUID idempotencyKey, String kind) {
        String value = tenantId + ":" + action.name() + ":" + idempotencyKey + ":" + kind;
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String tzdbVersion(String zoneId) {
        var versions = ZoneRulesProvider.getVersions(zoneId);
        return versions.isEmpty() ? "JDK_UNVERSIONED" : versions.lastKey();
    }

    private record ResolutionEvidence(
            UUID rulePackPublicId,
            String jurisdiction,
            String subdivision,
            long policyRevision,
            String scopeType,
            String scopeRef,
            int priority,
            EffectivePeriod period) {
    }

    private record AssignmentEvidence(
            UUID workerPublicId,
            UUID peopleAssignmentPublicId,
            long peopleAssignmentRevision,
            EffectivePeriod period,
            String zoneId,
            UUID populationPublicId,
            long populationRevision,
            long membershipRevision,
            long grantRevision,
            String populationDigest,
            String membershipDigest,
            String grantDigest) {
    }

    private record CreateCommandEvidence(long actorId, CreateDraftRequest request) {
    }

    private record OwnedPlan(
            PopulationAccess access,
            TargetAuthorizationGuard guard,
            WorkPlanRecord plan) {
    }

    private record SimulationCommandEvidence(
            UUID workPlanId, long actorId, SimulationRequest request) {
    }

    private record TransitionEvidence(
            UUID workPlanId, long actorId, LifecycleAction action, long expectedVersion) {
    }
}
