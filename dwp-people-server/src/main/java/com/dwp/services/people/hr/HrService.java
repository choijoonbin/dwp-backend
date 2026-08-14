package com.dwp.services.people.hr;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class HrService {

    private static final Map<String, String> DOMAIN_RESOURCES = Map.of(
            "TIME", "DATA.HR_TIME",
            "ABSENCE", "DATA.HR_ABSENCE",
            "BENEFITS", "DATA.HR_BENEFITS",
            "PAY", "DATA.HR_PAY",
            "TALENT", "DATA.HR_TALENT");

    private final HrRepository repository;
    private final AuditOutboxRecorder audit;

    public HrService(
            HrRepository repository,
            AuditOutboxRecorder audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public HrDtos.HomeOverview home() {
        Context context = context();
        HrDtos.TimeCard card = repository.currentTimeCard(context.actor().tenantId(), context.worker().workerId());
        List<HrDtos.LeaveBalance> balances = repository.leaveBalances(
                context.actor().tenantId(), context.worker().workerId());
        int teamPending = context.worker().directReportCount() == 0
                ? 0
                : repository.teamQueue(
                        context.actor().tenantId(), context.worker().assignmentKey(), "TIME").size()
                  + repository.teamQueue(
                        context.actor().tenantId(), context.worker().assignmentKey(), "ABSENCE").size();
        boolean reference = card != null && "REFERENCE".equals(card.dataOrigin())
                || balances.stream().anyMatch(balance -> "REFERENCE".equals(balance.dataOrigin()));
        return new HrDtos.HomeOverview(
                LocalDate.now(), employee(context.worker()), card, balances,
                repository.nextPayCycle(context.actor().tenantId()),
                Math.toIntExact(repository.activeBenefits(
                        context.actor().tenantId(), context.worker().workerId())),
                Math.toIntExact(repository.openBenefitWindows(context.actor().tenantId())),
                Math.toIntExact(repository.activeGoals(
                        context.actor().tenantId(), context.worker().workerId())),
                Math.toIntExact(repository.requiredLearning(
                        context.actor().tenantId(), context.worker().workerId())),
                teamPending, reference);
    }

    @Transactional(readOnly = true)
    public HrDtos.TimeWorkspace time() {
        Context context = context();
        HrDtos.TimeCard card = repository.currentTimeCard(context.actor().tenantId(), context.worker().workerId());
        return new HrDtos.TimeWorkspace(
                employee(context.worker()), card,
                repository.timeEntries(
                        context.actor().tenantId(), context.worker().workerId(),
                        card == null ? null : card.timeCardId()),
                repository.timeExceptions(
                        context.actor().tenantId(), context.worker().workerId(),
                        card == null ? null : card.timeCardId()),
                teamQueue(context, "TIME"));
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
        Context context = context();
        HrRepository.TimeCardTarget target = repository.timeCardTarget(
                context.actor().tenantId(), cardId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireDecisionAuthority(context, "TIME", target.workerId());
        if (target.version() != request.version()) {
            throw conflict("The time card changed after it was loaded.");
        }
        String status = "APPROVE".equals(request.decision()) ? "APPROVED" : "REJECTED";
        if (!repository.decideTimeCard(
                context.actor().tenantId(), cardId, status, request.note(),
                request.version(), context.actor().userId())) {
            throw conflict("Only a submitted time card can be decided.");
        }
        record(context.actor(), "hr.time-card." + status.toLowerCase(), "TIME_CARD", cardId,
                correlationId, Map.of("status", status, "note", request.note()), "EXTENDED");
        return new HrDtos.ApprovalItem(
                cardId, "TIME", target.personId(), target.displayName(), target.businessTitle(),
                target.recordedMinutes() + " minutes recorded", status, Instant.now(),
                request.version() + 1);
    }

    @Transactional(readOnly = true)
    public HrDtos.AbsenceWorkspace absence() {
        Context context = context();
        return new HrDtos.AbsenceWorkspace(
                employee(context.worker()),
                repository.leaveBalances(context.actor().tenantId(), context.worker().workerId()),
                repository.leaveRequests(context.actor().tenantId(), context.worker().workerId()),
                teamQueue(context, "ABSENCE"),
                teamCoverage(context));
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
        Context context = context();
        HrRepository.LeaveRequestTarget target = repository.leaveRequestTarget(
                context.actor().tenantId(), requestId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireDecisionAuthority(context, "ABSENCE", target.workerId());
        if (target.version() != request.version()) {
            throw conflict("The leave request changed after it was loaded.");
        }
        String status = "APPROVE".equals(request.decision()) ? "APPROVED" : "REJECTED";
        if (!repository.decideLeaveRequest(
                context.actor().tenantId(), requestId, target, status, request.note(),
                context.actor().userId())) {
            throw conflict("Only a submitted leave request can be decided.");
        }
        record(context.actor(), "hr.leave-request." + status.toLowerCase(),
                "LEAVE_REQUEST", requestId, correlationId,
                Map.of("status", status, "note", request.note()), "EXTENDED");
        return new HrDtos.ApprovalItem(
                requestId, "ABSENCE", target.personId(), target.displayName(),
                target.businessTitle(), target.planName() + " · " + target.requestedMinutes()
                        + " minutes", status, Instant.now(), request.version() + 1);
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
                repository.enrollmentWindows(context.actor().tenantId()), true);
    }

    @Transactional(readOnly = true)
    public HrDtos.PayWorkspace pay() {
        Context context = context();
        return new HrDtos.PayWorkspace(
                employee(context.worker()), repository.nextPayCycle(context.actor().tenantId()),
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
        Context context = context();
        String domain = normalizedDomain(requestedDomain);
        requireDomainPermission(context.actor(), domain, "VIEW", "MANAGE");
        return new HrDtos.DomainOperations(
                domain, Instant.now(), repository.metrics(context.actor().tenantId(), domain),
                domain.equals("TIME") || domain.equals("ABSENCE")
                        ? allSubmitted(context, domain)
                        : List.of(),
                "TENANT");
    }

    private List<HrDtos.ApprovalItem> allSubmitted(Context context, String domain) {
        return repository.submittedQueue(context.actor().tenantId(), domain);
    }

    private List<HrDtos.ApprovalItem> teamQueue(Context context, String domain) {
        return context.worker().directReportCount() == 0
                ? List.of()
                : repository.teamQueue(
                        context.actor().tenantId(), context.worker().assignmentKey(), domain);
    }

    private List<HrDtos.TeamAbsence> teamCoverage(Context context) {
        return context.worker().directReportCount() == 0
                ? List.of()
                : repository.teamAbsences(
                        context.actor().tenantId(), context.worker().assignmentKey());
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
        return new Context(actor, worker);
    }

    private void requireDecisionAuthority(Context context, String domain, long targetWorkerId) {
        boolean delegated = hasDomainPermission(context.actor(), domain, "APPROVE", "MANAGE");
        boolean manager = repository.manages(
                context.actor().tenantId(), context.worker().assignmentKey(), targetWorkerId);
        if (!delegated && !manager) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The request is outside the manager or delegated HR population.");
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
        String resource = DOMAIN_RESOURCES.get(domain);
        return actor.hasPermission(resource, actions)
                || (actor.permissions().isEmpty()
                    && actor.hasAnyRole("ADMIN", "HR_ADMIN", domainRole(domain)));
    }

    private String domainRole(String domain) {
        return switch (domain) {
            case "TIME" -> "TIME_ADMIN";
            case "ABSENCE" -> "ABSENCE_ADMIN";
            case "BENEFITS" -> "BENEFITS_ADMIN";
            case "PAY" -> "PAYROLL_ADMIN";
            case "TALENT" -> "TALENT_ADMIN";
            default -> "";
        };
    }

    private String normalizedDomain(String domain) {
        String normalized = domain == null ? "" : domain.trim().toUpperCase();
        if (!DOMAIN_RESOURCES.containsKey(normalized)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unsupported HR domain.");
        }
        return normalized;
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
            HrRepository.WorkerIdentity worker) {
    }
}
