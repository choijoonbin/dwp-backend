package com.dwp.services.people.hr;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class HrDtos {

    private HrDtos() {
    }

    public record EmployeeContext(
            UUID personId,
            String displayName,
            String businessTitle,
            String organizationName,
            String managerDisplayName,
            int directReportCount) {
    }

    public record TimeCard(
            UUID timeCardId,
            LocalDate periodStart,
            LocalDate periodEnd,
            String status,
            int scheduledMinutes,
            int recordedMinutes,
            int exceptionCount,
            String dataOrigin,
            long version) {
    }

    public record TimeEntry(
            UUID timeEntryId,
            LocalDate workDate,
            String entryType,
            int minutes,
            String workMode,
            String note,
            long version) {
    }

    public record TimeException(
            UUID exceptionId,
            String exceptionCode,
            String severity,
            LocalDate occurredOn,
            String message,
            String lifecycleState,
            String resolutionNote) {
    }

    public record TimeWorkspace(
            EmployeeContext employee,
            TimeCard card,
            List<TimeEntry> entries,
            List<TimeException> exceptions,
            List<ApprovalItem> teamQueue) {
    }

    public record LeaveBalance(
            UUID planId,
            String planKey,
            String planName,
            int grantedMinutes,
            int usedMinutes,
            int pendingMinutes,
            int availableMinutes,
            LocalDate asOf,
            String dataOrigin) {
    }

    public record LeaveRequest(
            UUID requestId,
            UUID planId,
            String planName,
            Instant startAt,
            Instant endAt,
            int requestedMinutes,
            String status,
            String reason,
            Instant submittedAt,
            String decisionNote,
            Instant cancelledAt,
            String cancellationNote,
            long version) {
    }

    public record TeamAbsence(
            UUID requestId,
            UUID personId,
            String employeeName,
            String employeeTitle,
            String planName,
            Instant startAt,
            Instant endAt,
            String status) {
    }

    public record AbsenceWorkspace(
            EmployeeContext employee,
            List<LeaveBalance> balances,
            List<LeaveRequest> requests,
            List<ApprovalItem> teamQueue,
            List<TeamAbsence> teamCalendar) {
    }

    public record BenefitPlan(
            UUID planId,
            String planType,
            String name,
            String providerName,
            String coverageLevel,
            String status,
            LocalDate effectiveStart,
            LocalDate effectiveEnd) {
    }

    public record EnrollmentWindow(
            UUID windowId,
            String name,
            String windowType,
            Instant opensAt,
            Instant closesAt,
            String lifecycleState) {
    }

    public record BenefitsWorkspace(
            EmployeeContext employee,
            List<BenefitPlan> plans,
            List<EnrollmentWindow> windows,
            boolean referenceData) {
    }

    public record PayCycle(
            UUID payCycleId,
            String name,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate payDate,
            String status,
            boolean timeValidated,
            boolean absenceValidated,
            boolean sourceConfirmed,
            String dataOrigin) {
    }

    public record PayStatement(
            UUID statementId,
            String periodLabel,
            String availabilityState,
            Instant publishedAt,
            boolean downloadable) {
    }

    public record PayWorkspace(
            EmployeeContext employee,
            PayCycle nextCycle,
            List<PayStatement> statements,
            boolean monetaryDataRedacted) {
    }

    public record Journey(
            UUID journeyId,
            String name,
            String journeyType,
            int progressPercent,
            LocalDate targetDate,
            String status) {
    }

    public record Goal(
            UUID goalId,
            String title,
            String goalType,
            int progressPercent,
            LocalDate dueDate,
            String status,
            long version) {
    }

    public record Learning(
            UUID learningId,
            String title,
            String providerName,
            boolean required,
            int progressPercent,
            LocalDate dueDate,
            String status) {
    }

    public record TalentWorkspace(
            EmployeeContext employee,
            List<Journey> journeys,
            List<Goal> goals,
            List<Learning> learning) {
    }

    public record ApprovalItem(
            UUID itemId,
            String domain,
            UUID personId,
            String employeeName,
            String employeeTitle,
            String summary,
            String status,
            Instant submittedAt,
            long version) {
    }

    public record TeamMember(
            UUID personId,
            String displayName,
            String businessTitle,
            String organizationName,
            int directReportCount) {
    }

    public enum DataBoundary {
        TEAM,
        ORGANIZATION_SET,
        TEAM_AND_ORGANIZATION_SET,
        TENANT
    }

    public record TeamWorkspace(
            EmployeeContext manager,
            List<TeamMember> members,
            int timePendingCount,
            int absencePendingCount,
            DataBoundary dataBoundary) {
    }

    public record TeamTimeWorkspace(
            EmployeeContext manager,
            List<ApprovalItem> teamQueue,
            DataBoundary dataBoundary) {
    }

    public record TeamAbsenceWorkspace(
            EmployeeContext manager,
            List<ApprovalItem> teamQueue,
            List<TeamAbsence> teamCalendar,
            DataBoundary dataBoundary) {
    }

    public record DomainMetric(String key, long value, String severity) {
    }

    public record DomainOperations(
            String domain,
            Instant generatedAt,
            List<DomainMetric> metrics,
            List<ApprovalItem> workQueue,
            DataBoundary dataBoundary) {
    }

    public record DomainOperationsSummary(
            String domain,
            List<DomainMetric> metrics,
            int pendingCount) {
    }

    public record WorkforceOperationsOverview(
            Instant generatedAt,
            DataBoundary dataBoundary,
            List<String> fieldGroups,
            List<DomainOperationsSummary> domains) {
    }

    public enum HomeAvailability {
        AVAILABLE,
        UNAVAILABLE
    }

    public enum HomeDataOrigin {
        SOURCE,
        MANUAL,
        REFERENCE,
        MIXED,
        NONE,
        UNKNOWN
    }

    public record HomeDomainState(
            HomeAvailability availability,
            HomeDataOrigin dataOrigin,
            String reasonCode) {
    }

    public record HomeOverview(
            LocalDate asOf,
            Instant generatedAt,
            String timeZone,
            Integer standardDayMinutes,
            EmployeeContext employee,
            TimeCard time,
            List<LeaveBalance> leaveBalances,
            PayCycle pay,
            List<EnrollmentWindow> enrollmentWindows,
            List<Journey> journeys,
            int activeBenefitCount,
            int openBenefitWindowCount,
            int activeGoalCount,
            int requiredLearningCount,
            int teamPendingCount,
            int teamTimePendingCount,
            int teamAbsencePendingCount,
            Map<String, HomeDomainState> domainStates,
            boolean referenceDataPresent) {
    }

    public record UpsertTimeEntryRequest(
            @Min(1) @Max(1440) int minutes,
            @NotBlank @Pattern(regexp = "OFFICE|REMOTE|FIELD|HYBRID") String workMode,
            @Size(max = 1000) String note,
            @Min(0) long cardVersion) {
    }

    public record CreateLeaveRequest(
            @NotNull UUID planId,
            @NotNull Instant startAt,
            @NotNull Instant endAt,
            @Min(1) int requestedMinutes,
            @Size(max = 1000) String reason) {
    }

    public record WithdrawLeaveRequest(
            @NotBlank @Size(min = 3, max = 1000) String note,
            @Min(0) long version) {
    }

    public record DecisionRequest(
            @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
            @NotBlank @Size(min = 3, max = 1000) String note,
            @Min(0) long version) {
    }

    public record UpdateGoalRequest(
            @Min(0) @Max(100) int progressPercent,
            @NotBlank @Pattern(regexp = "ACTIVE|AT_RISK|COMPLETED|CANCELLED") String status,
            @Min(0) long version) {
    }
}
