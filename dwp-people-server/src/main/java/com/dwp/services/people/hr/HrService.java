package com.dwp.services.people.hr;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import com.dwp.services.people.security.HcmPepContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class HrService {

    private static final Logger log = LoggerFactory.getLogger(HrService.class);

    private final HrRepository repository;
    private final HcmPopulationRepository populationRepository;
    private final HcmPopulationScopeService populationScopes;
    private final AuditOutboxRecorder audit;
    private final HcmWorkspaceService workspaces;

    @org.springframework.beans.factory.annotation.Autowired
    public HrService(
            HrRepository repository,
            HcmPopulationRepository populationRepository,
            HcmPopulationScopeService populationScopes,
            AuditOutboxRecorder audit,
            HcmWorkspaceService workspaces) {
        this.repository = repository;
        this.populationRepository = populationRepository;
        this.populationScopes = populationScopes;
        this.audit = audit;
        this.workspaces = workspaces;
    }

    HrService(
            HrRepository repository,
            HcmPopulationRepository populationRepository,
            HcmPopulationScopeService populationScopes,
            AuditOutboxRecorder audit) {
        this(repository, populationRepository, populationScopes, audit,
                new HcmWorkspaceService(repository, populationRepository, populationScopes));
    }

    public HrDtos.HomeOverview home() {
        Context context = context();
        Long tenantId = context.actor().tenantId();
        long workerId = context.worker().workerId();
        HomeLoad<HrDtos.TimeCard> time = loadHomeDomain(
                "TIME", null,
                () -> repository.currentTimeCard(tenantId, workerId, context.asOf()),
                card -> card == null
                        ? HrDtos.HomeDataOrigin.NONE
                        : origin(card.dataOrigin()));
        HomeLoad<List<HrDtos.LeaveBalance>> absence = loadHomeDomain(
                "ABSENCE", List.of(),
                () -> repository.leaveBalances(tenantId, workerId, context.asOf()),
                balances -> origins(balances.stream().map(HrDtos.LeaveBalance::dataOrigin).toList()));
        HomeLoad<BenefitsHome> benefits = loadHomeDomain(
                "BENEFITS", new BenefitsHome(List.of(), 0),
                () -> new BenefitsHome(
                        repository.enrollmentWindows(tenantId, workerId),
                        Math.toIntExact(repository.activeBenefits(tenantId, workerId))),
                value -> value.windows().isEmpty() && value.activeCount() == 0
                        ? HrDtos.HomeDataOrigin.NONE
                        : HrDtos.HomeDataOrigin.UNKNOWN);
        HomeLoad<HrDtos.PayCycle> pay = loadHomeDomain(
                "PAY", null,
                () -> repository.nextPayCycle(tenantId, workerId),
                cycle -> cycle == null
                        ? HrDtos.HomeDataOrigin.NONE
                        : origin(cycle.dataOrigin()));
        HomeLoad<TalentHome> talent = loadHomeDomain(
                "TALENT", new TalentHome(List.of(), 0, 0),
                () -> new TalentHome(
                        repository.activeJourneys(tenantId, workerId),
                        Math.toIntExact(repository.activeGoals(tenantId, workerId)),
                        Math.toIntExact(repository.requiredLearning(tenantId, workerId))),
                value -> value.journeys().isEmpty()
                                && value.activeGoalCount() == 0
                                && value.requiredLearningCount() == 0
                        ? HrDtos.HomeDataOrigin.NONE
                        : HrDtos.HomeDataOrigin.UNKNOWN);
        HomeLoad<TeamHome> team = loadHomeDomain(
                "TEAM", new TeamHome(0, 0),
                () -> new TeamHome(0, 0),
                value -> HrDtos.HomeDataOrigin.NONE);

        Map<String, HrDtos.HomeDomainState> domainStates = new LinkedHashMap<>();
        domainStates.put("TIME", time.state());
        domainStates.put("ABSENCE", absence.state());
        domainStates.put("BENEFITS", benefits.state());
        domainStates.put("PAY", pay.state());
        domainStates.put("TALENT", talent.state());
        domainStates.put("TEAM", team.state());
        boolean reference = domainStates.values().stream()
                .anyMatch(state -> state.dataOrigin() == HrDtos.HomeDataOrigin.REFERENCE
                        || state.dataOrigin() == HrDtos.HomeDataOrigin.MIXED)
                || context.schedule() != null
                        && "REFERENCE".equals(context.schedule().dataOrigin());
        List<HrDtos.EnrollmentWindow> enrollmentWindows = benefits.value().windows();
        int teamTimePending = team.value().timePendingCount();
        int teamAbsencePending = team.value().absencePendingCount();
        return new HrDtos.HomeOverview(
                context.asOf(), Instant.now(), context.timeZone(),
                context.schedule() == null ? null : context.schedule().standardDayMinutes(),
                employee(context.worker()), time.value(), absence.value(), pay.value(),
                enrollmentWindows,
                talent.value().journeys(),
                benefits.value().activeCount(),
                Math.toIntExact(enrollmentWindows.stream()
                        .filter(window -> "OPEN".equals(window.lifecycleState()))
                        .count()),
                talent.value().activeGoalCount(),
                talent.value().requiredLearningCount(),
                teamTimePending + teamAbsencePending,
                teamTimePending, teamAbsencePending,
                Map.copyOf(domainStates), reference);
    }

    @Transactional(readOnly = true)
    public HrDtos.TimeWorkspace time() {
        Context context = context();
        HrDtos.TimeCard card = repository.currentTimeCard(
                context.actor().tenantId(), context.worker().workerId(), context.asOf());
        return new HrDtos.TimeWorkspace(
                employee(context.worker()), card,
                repository.timeEntries(
                        context.actor().tenantId(), context.worker().workerId(),
                        card == null ? null : card.timeCardId()),
                repository.timeExceptions(
                        context.actor().tenantId(), context.worker().workerId(),
                        card == null ? null : card.timeCardId()),
                List.of());
    }

    @Transactional(readOnly = true)
    public HrDtos.TeamWorkspace team() {
        return workspaces.team();
    }

    @Transactional(readOnly = true)
    public HrDtos.TeamTimeWorkspace teamTime() {
        return workspaces.teamTime();
    }

    @Transactional(readOnly = true)
    public HrDtos.TeamAbsenceWorkspace teamAbsence() {
        return workspaces.teamAbsence();
    }

    @Transactional
    public HrDtos.TimeWorkspace upsertTimeEntry(
            UUID cardId,
            LocalDate workDate,
            HrDtos.UpsertTimeEntryRequest request,
            String correlationId) {
        Context context = context();
        if (!repository.upsertTimeEntry(
                context.actor().tenantId(), context.worker().workerId(), cardId,
                workDate, request, context.actor().userId())) {
            throw conflict("The time card changed, is no longer open, or does not include this date.");
        }
        record(context.actor(), "hr.time-entry.saved", "TIME_CARD", cardId,
                correlationId, Map.of("workDate", workDate, "minutes", request.minutes()), "STANDARD");
        return time();
    }

    @Transactional
    public HrDtos.TimeWorkspace submitTimeCard(
            UUID cardId,
            long version,
            String correlationId) {
        Context context = context();
        if (!repository.submitTimeCard(
                context.actor().tenantId(), context.worker().workerId(), cardId,
                version, context.actor().userId())) {
            throw conflict("Resolve blocking exceptions and refresh the open time card before submitting.");
        }
        record(context.actor(), "hr.time-card.submitted", "TIME_CARD", cardId,
                correlationId, Map.of("version", version), "EXTENDED");
        return time();
    }

    @Transactional
    public HrDtos.ApprovalItem decideTimeCard(
            UUID cardId,
            HrDtos.DecisionRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requireDomainPermission(actor, "TIME", "APPROVE", "MANAGE");
        HcmPopulationScopeService.ResolvedPopulation population =
                populationScopes.requireOperationsForMutation("READ");
        populationScopes.requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                "TIME_TARGET_POPULATION", "ORG_UNIT/LEGAL_ENTITY");
        return decideTimeCard(cardId, request, correlationId, actor, population);
    }

    @Transactional
    public HrDtos.ApprovalItem decideTeamTimeCard(
            UUID cardId,
            HrDtos.DecisionRequest request,
            String correlationId) {
        Context context = context();
        requireDomainPermission(context.actor(), "TIME", "APPROVE", "MANAGE");
        HcmPopulationScopeService.ResolvedPopulation population =
                populationScopes.requireTeamForMutation();
        requireTeamScope(population);
        return decideTimeCard(cardId, request, correlationId, context.actor(), population);
    }

    private HrDtos.ApprovalItem decideTimeCard(
            UUID cardId,
            HrDtos.DecisionRequest request,
            String correlationId,
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population) {
        populationScopes.requireField(population, "EMPLOYMENT");
        HrRepository.TimeCardTarget target = repository.timeCardTarget(
                actor.tenantId(), cardId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!populationRepository.lockWorkerInPopulation(
                actor.tenantId(), population.scope(), target.workerId())) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        if (target.version() != request.version()) {
            throw conflict("The time card changed after it was loaded.");
        }
        String status = "APPROVE".equals(request.decision()) ? "APPROVED" : "REJECTED";
        if (!repository.decideTimeCard(
                actor.tenantId(), cardId, status, request.note(),
                request.version(), actor.userId())) {
            throw conflict("Only a submitted time card can be decided.");
        }
        record(actor, "hr.time-card." + status.toLowerCase(), "TIME_CARD", cardId,
                correlationId, Map.of("status", status, "note", request.note()), "EXTENDED");
        return new HrDtos.ApprovalItem(
                cardId, "TIME", target.personId(), target.displayName(), target.businessTitle(),
                target.recordedMinutes() + " minutes recorded", status, Instant.now(),
                request.version() + 1, null);
    }

    @Transactional(readOnly = true)
    public HrDtos.AbsenceWorkspace absence() {
        Context context = context();
        return new HrDtos.AbsenceWorkspace(
                employee(context.worker()),
                repository.leaveBalances(
                        context.actor().tenantId(), context.worker().workerId(), context.asOf()),
                repository.leaveRequests(context.actor().tenantId(), context.worker().workerId()),
                List.of(),
                List.of());
    }

    @Transactional
    public HrDtos.LeaveRequest createLeaveRequest(
            HrDtos.CreateLeaveRequest request,
            String correlationId) {
        Context context = context();
        if (!request.endAt().isAfter(request.startAt())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The leave end must be after the start.");
        }
        long intervalMinutes = Duration.between(request.startAt(), request.endAt()).toMinutes();
        if (request.requestedMinutes() > intervalMinutes) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The requested leave minutes cannot exceed the selected interval.");
        }
        if (repository.hasOverlappingLeaveRequest(
                context.actor().tenantId(), context.worker().workerId(),
                request.startAt(), request.endAt())) {
            throw conflict("The selected interval overlaps an existing submitted or approved leave request.");
        }
        HrDtos.LeaveRequest created;
        try {
            created = repository.createLeaveRequest(
                    context.actor().tenantId(), context.worker().workerId(), request,
                    context.actor().userId())
                    .orElseThrow(() -> conflict(
                            "The leave plan is unavailable or the requested duration exceeds the available balance."));
        } catch (DataIntegrityViolationException exception) {
            if (causedByConstraint(exception, "ex_abs_leave_request_active_overlap")) {
                throw conflict(
                        "The selected interval overlaps an existing submitted or approved leave request.");
            }
            throw exception;
        }
        record(context.actor(), "hr.leave-request.submitted", "LEAVE_REQUEST",
                created.requestId(), correlationId,
                Map.of("planId", request.planId(), "requestedMinutes", request.requestedMinutes()),
                "EXTENDED");
        return created;
    }

    @Transactional
    public HrDtos.ApprovalItem decideLeaveRequest(
            UUID requestId,
            HrDtos.DecisionRequest request,
            String correlationId) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        requireDomainPermission(actor, "ABSENCE", "APPROVE", "MANAGE");
        HcmPopulationScopeService.ResolvedPopulation population =
                populationScopes.requireOperationsForMutation("READ");
        populationScopes.requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                "ABSENCE_TARGET_POPULATION", "ORG_UNIT/LEGAL_ENTITY");
        return decideLeaveRequest(requestId, request, correlationId, actor, population);
    }

    @Transactional
    public HrDtos.ApprovalItem decideTeamLeaveRequest(
            UUID requestId,
            HrDtos.DecisionRequest request,
            String correlationId) {
        Context context = context();
        requireDomainPermission(context.actor(), "ABSENCE", "APPROVE", "MANAGE");
        HcmPopulationScopeService.ResolvedPopulation population =
                populationScopes.requireTeamForMutation();
        requireTeamScope(population);
        return decideLeaveRequest(
                requestId, request, correlationId, context.actor(), population);
    }

    private HrDtos.ApprovalItem decideLeaveRequest(
            UUID requestId,
            HrDtos.DecisionRequest request,
            String correlationId,
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population) {
        populationScopes.requireField(population, "EMPLOYMENT");
        HrRepository.LeaveRequestTarget target = repository.leaveRequestTarget(
                actor.tenantId(), requestId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!populationRepository.lockWorkerInPopulation(
                actor.tenantId(), population.scope(), target.workerId())) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        if (target.version() != request.version()) {
            throw conflict("The leave request changed after it was loaded.");
        }
        String status = "APPROVE".equals(request.decision()) ? "APPROVED" : "REJECTED";
        if (!repository.decideLeaveRequest(
                actor.tenantId(), requestId, target, status, request.note(),
                actor.userId())) {
            throw conflict("Only a submitted leave request can be decided.");
        }
        record(actor, "hr.leave-request." + status.toLowerCase(),
                "LEAVE_REQUEST", requestId, correlationId,
                Map.of("status", status, "note", request.note()), "EXTENDED");
        return new HrDtos.ApprovalItem(
                requestId, "ABSENCE", target.personId(), target.displayName(),
                target.businessTitle(), target.planName() + " · " + target.requestedMinutes()
                        + " minutes", status, Instant.now(), request.version() + 1, null);

    }

    @Transactional
    public HrDtos.AbsenceWorkspace withdrawLeaveRequest(
            UUID requestId,
            HrDtos.WithdrawLeaveRequest request,
            String correlationId) {
        Context context = context();
        HrRepository.LeaveRequestTarget target = repository.leaveRequestTarget(
                context.actor().tenantId(), requestId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (target.workerId() != context.worker().workerId()) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        if (target.version() != request.version()) {
            throw conflict("The leave request changed after it was loaded.");
        }
        if (!repository.withdrawLeaveRequest(
                context.actor().tenantId(), requestId, target,
                request.note(), context.actor().userId())) {
            throw conflict("Only a submitted leave request can be withdrawn.");
        }
        record(context.actor(), "hr.leave-request.withdrawn", "LEAVE_REQUEST",
                requestId, correlationId,
                Map.of("status", "CANCELLED", "note", request.note()), "EXTENDED");
        return absence();
    }

    @Transactional(readOnly = true)
    public HrDtos.BenefitsWorkspace benefits() {
        Context context = context();
        return new HrDtos.BenefitsWorkspace(
                employee(context.worker()),
                repository.benefitPlans(context.actor().tenantId(), context.worker().workerId()),
                repository.enrollmentWindows(
                        context.actor().tenantId(), context.worker().workerId()), true);
    }

    @Transactional(readOnly = true)
    public HrDtos.PayWorkspace pay() {
        Context context = context();
        return new HrDtos.PayWorkspace(
                employee(context.worker()), repository.nextPayCycle(
                        context.actor().tenantId(), context.worker().workerId()),
                repository.payStatements(context.actor().tenantId(), context.worker().workerId()),
                true);
    }

    @Transactional(readOnly = true)
    public HrDtos.TalentWorkspace talent() {
        Context context = context();
        return new HrDtos.TalentWorkspace(
                employee(context.worker()),
                repository.journeys(context.actor().tenantId(), context.worker().workerId()),
                repository.goals(context.actor().tenantId(), context.worker().workerId()),
                repository.learning(context.actor().tenantId(), context.worker().workerId()));
    }

    @Transactional
    public HrDtos.TalentWorkspace updateGoal(
            UUID goalId,
            HrDtos.UpdateGoalRequest request,
            String correlationId) {
        Context context = context();
        if (!repository.updateGoal(
                context.actor().tenantId(), context.worker().workerId(), goalId,
                request, context.actor().userId())) {
            throw conflict("The goal changed or is no longer editable.");
        }
        record(context.actor(), "hr.goal.progress-updated", "TALENT_GOAL", goalId,
                correlationId, Map.of(
                        "progressPercent", request.progressPercent(), "status", request.status()),
                "STANDARD");
        return talent();
    }

    @Transactional(readOnly = true)
    public HrDtos.DomainOperations operations(String requestedDomain) {
        return workspaces.operations(requestedDomain);
    }

    @Transactional(readOnly = true)
    public HrDtos.WorkforceOperationsOverview operationsOverview() {
        return workspaces.operationsOverview();
    }

    private void requireTeamScope(HcmPopulationScopeService.ResolvedPopulation population) {
        populationScopes.requireTrustedScope(
                population, "hcm.team", "TARGET_POPULATION",
                "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION",
                "TEAM/ORG_UNIT");
        populationScopes.requireField(population, "DIRECTORY");
        populationScopes.requireField(population, "EMPLOYMENT");
    }

    private Context context() {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (actor.personPublicId() == null) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "A verified workforce identity is required for HR self-service.");
        }
        HrRepository.WorkerIdentity worker = repository.worker(
                actor.tenantId(), actor.personPublicId())
                .orElseThrow(() -> new BaseException(ErrorCode.FORBIDDEN,
                        "The authenticated identity is not linked to an active worker."));
        HcmPepContext.Evidence pep = HcmPepContext.current();
        if (pep != null && pep.authority().routeContractKey().startsWith("route.hcm.personal.")) {
            populationScopes.requireSelfScope();
        }
        LocalDate utcDate = LocalDate.now(ZoneOffset.UTC);
        HrRepository.WorkerSchedule schedule = loadSchedule(actor, worker, utcDate);
        ZoneId zone = zoneId(schedule == null ? null : schedule.timeZone());
        LocalDate asOf = LocalDate.now(zone);
        if (!asOf.equals(utcDate)) {
            HrRepository.WorkerSchedule localSchedule = loadSchedule(actor, worker, asOf);
            if (localSchedule != null) {
                schedule = localSchedule;
                zone = zoneId(schedule.timeZone());
                asOf = LocalDate.now(zone);
            }
        }
        return new Context(actor, worker, schedule, zone.getId(), asOf);
    }

    private HrRepository.WorkerSchedule loadSchedule(
            PeopleRequestContext.Actor actor,
            HrRepository.WorkerIdentity worker,
            LocalDate asOf) {
        try {
            return repository.workerSchedule(actor.tenantId(), worker.workerId(), asOf)
                    .orElse(null);
        } catch (DataAccessException exception) {
            log.warn("Unable to resolve HR work schedule for tenant {} worker {}",
                    actor.tenantId(), worker.workerId(), exception);
            return null;
        }
    }

    private ZoneId zoneId(String value) {
        if (value == null || value.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(value);
        } catch (RuntimeException exception) {
            log.warn("Ignoring invalid HR work schedule time zone {}", value);
            return ZoneOffset.UTC;
        }
    }

    private <T> HomeLoad<T> loadHomeDomain(
            String domain,
            T fallback,
            Supplier<T> supplier,
            Function<T, HrDtos.HomeDataOrigin> originResolver) {
        try {
            T value = supplier.get();
            return new HomeLoad<>(value, new HrDtos.HomeDomainState(
                    HrDtos.HomeAvailability.AVAILABLE,
                    originResolver.apply(value), null));
        } catch (DataAccessException exception) {
            log.warn("Unable to assemble {} data for the HCM home", domain, exception);
            return new HomeLoad<>(fallback, new HrDtos.HomeDomainState(
                    HrDtos.HomeAvailability.UNAVAILABLE,
                    HrDtos.HomeDataOrigin.UNKNOWN,
                    domain + "_QUERY_FAILED"));
        }
    }

    private HrDtos.HomeDataOrigin origins(List<String> values) {
        List<HrDtos.HomeDataOrigin> origins = values.stream()
                .map(this::origin)
                .distinct()
                .toList();
        if (origins.isEmpty()) return HrDtos.HomeDataOrigin.NONE;
        return origins.size() == 1 ? origins.getFirst() : HrDtos.HomeDataOrigin.MIXED;
    }

    private HrDtos.HomeDataOrigin origin(String value) {
        if (value == null || value.isBlank()) return HrDtos.HomeDataOrigin.UNKNOWN;
        if ("LOCAL_SEED".equalsIgnoreCase(value)) return HrDtos.HomeDataOrigin.REFERENCE;
        try {
            return HrDtos.HomeDataOrigin.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            return HrDtos.HomeDataOrigin.UNKNOWN;
        }
    }

    private void requireDomainPermission(
            PeopleRequestContext.Actor actor,
            String domain,
            String... actions) {
        if (!hasDomainPermission(actor, domain, actions)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Delegated " + domain.toLowerCase() + " administration permission is required.");
        }
    }

    private boolean hasDomainPermission(
            PeopleRequestContext.Actor actor,
            String domain,
            String... actions) {
        String resource = HrAuthorization.DOMAIN_RESOURCES.get(domain);
        return actor.hasPermission(resource, actions)
                || (actor.permissions().isEmpty()
                    && actor.hasAnyRole("ADMIN", "HR_ADMIN", HrAuthorization.role(domain)));
    }

    private HrDtos.EmployeeContext employee(HrRepository.WorkerIdentity worker) {
        return new HrDtos.EmployeeContext(
                worker.personId(), worker.displayName(), worker.businessTitle(),
                worker.organizationName(), worker.managerDisplayName(), worker.directReportCount());
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private boolean causedByConstraint(Throwable exception, String constraintName) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(constraintName)) {
                return true;
            }
            if (current == current.getCause()) {
                break;
            }
            current = current.getCause();
        }
        return false;
    }

    private void record(
            PeopleRequestContext.Actor actor,
            String action,
            String targetType,
            UUID targetId,
            String correlationId,
            Map<String, Object> metadata,
            String retentionClass) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action(action)
                .outcome("SUCCESS")
                .severity("MEDIUM")
                .riskScore(40)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-people-server")
                .sourceModule("hr-domain-operations")
                .targetType(targetType)
                .targetId(targetId.toString())
                .correlationId(correlationId)
                .metadata(metadata)
                .retentionClass(retentionClass)
                .build());
    }

    private record Context(
            PeopleRequestContext.Actor actor,
            HrRepository.WorkerIdentity worker,
            HrRepository.WorkerSchedule schedule,
            String timeZone,
            LocalDate asOf) {
    }

    private record HomeLoad<T>(
            T value,
            HrDtos.HomeDomainState state) {
    }

    private record BenefitsHome(
            List<HrDtos.EnrollmentWindow> windows,
            int activeCount) {
    }

    private record TalentHome(
            List<HrDtos.Journey> journeys,
            int activeGoalCount,
            int requiredLearningCount) {
    }

    private record TeamHome(
            int timePendingCount,
            int absencePendingCount) {
    }
}
