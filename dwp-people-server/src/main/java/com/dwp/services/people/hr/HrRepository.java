package com.dwp.services.people.hr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class HrRepository {

    private final HrWorkerRepository workers;
    private final HrTimeRepository time;
    private final HrAbsenceRepository absence;
    private final HrBenefitsPayRepository benefitsPay;
    private final HrTalentRepository talent;

    public HrRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.workers = new HrWorkerRepository(jdbc);
        this.time = new HrTimeRepository(jdbc);
        this.absence = new HrAbsenceRepository(jdbc);
        this.benefitsPay = new HrBenefitsPayRepository(jdbc, objectMapper);
        this.talent = new HrTalentRepository(jdbc);
    }

    public Optional<WorkerIdentity> worker(Long tenantId, UUID personPublicId) {
        return workers.worker(tenantId, personPublicId);
    }

    public boolean manages(Long tenantId, String managerAssignmentKey, long workerId) {
        return workers.manages(tenantId, managerAssignmentKey, workerId);
    }

    public Optional<WorkerSchedule> workerSchedule(Long tenantId, long workerId, LocalDate asOf) {
        return time.workerSchedule(tenantId, workerId, asOf);
    }

    public HrDtos.TimeCard currentTimeCard(Long tenantId, long workerId, LocalDate asOf) {
        return time.currentTimeCard(tenantId, workerId, asOf);
    }

    public List<HrDtos.TimeEntry> timeEntries(Long tenantId, long workerId, UUID cardId) {
        return time.timeEntries(tenantId, workerId, cardId);
    }

    public List<HrDtos.TimeException> timeExceptions(Long tenantId, long workerId, UUID cardId) {
        return time.timeExceptions(tenantId, workerId, cardId);
    }

    public boolean upsertTimeEntry(
            Long tenantId, long workerId, UUID cardId, LocalDate workDate,
            HrDtos.UpsertTimeEntryRequest request, Long actorId) {
        return time.upsertTimeEntry(tenantId, workerId, cardId, workDate, request, actorId);
    }

    public boolean submitTimeCard(
            Long tenantId, long workerId, UUID cardId, long version, Long actorId) {
        return time.submitTimeCard(tenantId, workerId, cardId, version, actorId);
    }

    public Optional<TimeCardTarget> timeCardTarget(Long tenantId, UUID cardId) {
        return time.timeCardTarget(tenantId, cardId);
    }

    public boolean decideTimeCard(
            Long tenantId, UUID cardId, String status, String note, long version, Long actorId) {
        return time.decideTimeCard(tenantId, cardId, status, note, version, actorId);
    }

    public List<HrDtos.LeaveBalance> leaveBalances(
            Long tenantId, long workerId, LocalDate asOf) {
        return absence.leaveBalances(tenantId, workerId, asOf);
    }

    public List<HrDtos.LeaveRequest> leaveRequests(Long tenantId, long workerId) {
        return absence.leaveRequests(tenantId, workerId);
    }

    public boolean hasOverlappingLeaveRequest(
            Long tenantId, long workerId, Instant startAt, Instant endAt) {
        return absence.hasOverlappingLeaveRequest(tenantId, workerId, startAt, endAt);
    }

    public Optional<HrDtos.LeaveRequest> createLeaveRequest(
            Long tenantId, long workerId, HrDtos.CreateLeaveRequest request, Long actorId) {
        return absence.createLeaveRequest(tenantId, workerId, request, actorId);
    }

    public Optional<LeaveRequestTarget> leaveRequestTarget(Long tenantId, UUID requestId) {
        return absence.leaveRequestTarget(tenantId, requestId);
    }

    public boolean decideLeaveRequest(
            Long tenantId, UUID requestId, LeaveRequestTarget target,
            String status, String note, Long actorId) {
        return absence.decideLeaveRequest(tenantId, requestId, target, status, note, actorId);
    }

    public boolean withdrawLeaveRequest(
            Long tenantId, UUID requestId, LeaveRequestTarget target,
            String note, Long actorId) {
        return absence.withdrawLeaveRequest(tenantId, requestId, target, note, actorId);
    }

    public List<HrDtos.BenefitPlan> benefitPlans(Long tenantId, long workerId) {
        return benefitsPay.benefitPlans(tenantId, workerId);
    }

    public List<HrDtos.EnrollmentWindow> enrollmentWindows(Long tenantId, long workerId) {
        return benefitsPay.enrollmentWindows(tenantId, workerId);
    }

    public HrDtos.PayCycle nextPayCycle(Long tenantId, long workerId) {
        return benefitsPay.nextPayCycle(tenantId, workerId);
    }

    public List<HrDtos.PayStatement> payStatements(Long tenantId, long workerId) {
        return benefitsPay.payStatements(tenantId, workerId);
    }

    public List<HrDtos.Journey> activeJourneys(Long tenantId, long workerId) {
        return talent.activeJourneys(tenantId, workerId);
    }

    public List<HrDtos.Journey> journeys(Long tenantId, long workerId) {
        return talent.journeys(tenantId, workerId);
    }

    public List<HrDtos.Goal> goals(Long tenantId, long workerId) {
        return talent.goals(tenantId, workerId);
    }

    public List<HrDtos.Learning> learning(Long tenantId, long workerId) {
        return talent.learning(tenantId, workerId);
    }

    public boolean updateGoal(
            Long tenantId, long workerId, UUID goalId,
            HrDtos.UpdateGoalRequest request, Long actorId) {
        return talent.updateGoal(tenantId, workerId, goalId, request, actorId);
    }

    public long activeBenefits(Long tenantId, long workerId) {
        return benefitsPay.activeBenefits(tenantId, workerId);
    }

    public long activeGoals(Long tenantId, long workerId) {
        return talent.activeGoals(tenantId, workerId);
    }

    public long requiredLearning(Long tenantId, long workerId) {
        return talent.requiredLearning(tenantId, workerId);
    }

    public record WorkerIdentity(
            long workerId,
            UUID personId,
            String displayName,
            String assignmentKey,
            String businessTitle,
            String organizationName,
            UUID managerPersonId,
            String managerDisplayName,
            int directReportCount) {
    }

    public record WorkerSchedule(
            String timeZone,
            Integer standardDayMinutes,
            String dataOrigin) {
    }

    public record TimeCardTarget(
            long timeCardId,
            long workerId,
            String status,
            long version,
            UUID personId,
            String displayName,
            String businessTitle,
            int recordedMinutes) {
    }

    public record LeaveRequestTarget(
            long leaveRequestId,
            long workerId,
            long leavePlanId,
            int requestedMinutes,
            String status,
            long version,
            UUID personId,
            String displayName,
            String businessTitle,
            String planName) {
    }
}
