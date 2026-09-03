package com.dwp.services.people.hr;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HrServiceTest {

    private static final long TENANT_ID = 3L;
    private static final long USER_ID = 17L;
    private static final long WORKER_ID = 41L;
    private static final UUID PERSON_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();

    private final HrRepository repository = mock(HrRepository.class);
    private final HcmPopulationRepository populationRepository =
            mock(HcmPopulationRepository.class);
    private final HcmPopulationScopeService populationScopes =
            mock(HcmPopulationScopeService.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final HrService service = new HrService(
            repository, populationRepository, populationScopes, audit);

    @AfterEach
    void clearContext() {
        PeopleRequestContext.clear();
    }

    @Test
    void requesterCanWithdrawSubmittedLeaveAndRestorePendingBalance() {
        PeopleRequestContext.set(USER_ID, TENANT_ID, PERSON_ID, Set.of("USER"), Set.of());
        stubWorker(WORKER_ID);
        HrRepository.LeaveRequestTarget target = target(WORKER_ID, "SUBMITTED", 4L);
        when(repository.leaveRequestTarget(TENANT_ID, REQUEST_ID)).thenReturn(Optional.of(target));
        when(repository.withdrawLeaveRequest(
                TENANT_ID, REQUEST_ID, target, "Plans changed", USER_ID)).thenReturn(true);
        when(repository.leaveBalances(eq(TENANT_ID), eq(WORKER_ID), any(LocalDate.class)))
                .thenReturn(List.of());
        when(repository.leaveRequests(TENANT_ID, WORKER_ID)).thenReturn(List.of());

        HrDtos.AbsenceWorkspace workspace = service.withdrawLeaveRequest(
                REQUEST_ID, new HrDtos.WithdrawLeaveRequest("Plans changed", 4L), "corr-1");

        assertThat(workspace.requests()).isEmpty();
        assertThat(workspace.teamCalendar()).isEmpty();
        verify(repository).withdrawLeaveRequest(
                TENANT_ID, REQUEST_ID, target, "Plans changed", USER_ID);
        verify(audit).record(any(AuditEvent.class));
    }

    @Test
    void requesterCannotWithdrawAnotherWorkersLeave() {
        PeopleRequestContext.set(USER_ID, TENANT_ID, PERSON_ID, Set.of("USER"), Set.of());
        stubWorker(WORKER_ID);
        HrRepository.LeaveRequestTarget target = target(99L, "SUBMITTED", 2L);
        when(repository.leaveRequestTarget(TENANT_ID, REQUEST_ID)).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.withdrawLeaveRequest(
                REQUEST_ID, new HrDtos.WithdrawLeaveRequest("Wrong request", 2L), "corr-2"))
                .isInstanceOf(BaseException.class);

        verify(repository, never()).withdrawLeaveRequest(
                TENANT_ID, REQUEST_ID, target, "Wrong request", USER_ID);
        verify(audit, never()).record(any(AuditEvent.class));
    }

    @Test
    void homeProvidesFreshRoleAwareWorkflowSignalsWithoutAdditionalClientFanOut() {
        PeopleRequestContext.set(USER_ID, TENANT_ID, PERSON_ID, Set.of("USER"), Set.of());
        when(repository.worker(TENANT_ID, PERSON_ID)).thenReturn(Optional.of(
                new HrRepository.WorkerIdentity(
                        WORKER_ID, PERSON_ID, "Minseo Kim", "ASSIGN-1",
                        "Network Operations Lead", "Network Operations",
                        null, null, 2)));
        when(repository.workerSchedule(eq(TENANT_ID), eq(WORKER_ID), any(LocalDate.class)))
                .thenReturn(Optional.of(new HrRepository.WorkerSchedule(
                        "Asia/Seoul", 480, "REFERENCE")));
        when(repository.leaveBalances(eq(TENANT_ID), eq(WORKER_ID), any(LocalDate.class)))
                .thenReturn(List.of());
        HrDtos.EnrollmentWindow openWindow = new HrDtos.EnrollmentWindow(
                UUID.randomUUID(), "Annual enrollment", "OPEN_ENROLLMENT",
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(86_400), "OPEN");
        HrDtos.EnrollmentWindow scheduledWindow = new HrDtos.EnrollmentWindow(
                UUID.randomUUID(), "New hire enrollment", "LIFE_EVENT",
                Instant.now().plusSeconds(86_400), Instant.now().plusSeconds(172_800), "SCHEDULED");
        when(repository.enrollmentWindows(TENANT_ID, WORKER_ID))
                .thenReturn(List.of(openWindow, scheduledWindow));
        HrDtos.Journey journey = new HrDtos.Journey(
                UUID.randomUUID(), "First 90 days", "ONBOARDING", 40,
                LocalDate.now().plusDays(30), "IN_PROGRESS");
        when(repository.activeJourneys(TENANT_ID, WORKER_ID)).thenReturn(List.of(journey));
        when(repository.nextPayCycle(TENANT_ID, WORKER_ID)).thenReturn(new HrDtos.PayCycle(
                UUID.randomUUID(), "2026-08 payroll",
                LocalDate.now().withDayOfMonth(1), LocalDate.now().withDayOfMonth(28),
                LocalDate.now().plusDays(14), "COLLECTING",
                false, false, false, "LOCAL_SEED"));

        HrDtos.HomeOverview home = service.home();

        assertThat(home.generatedAt()).isNotNull();
        assertThat(home.timeZone()).isEqualTo("Asia/Seoul");
        assertThat(home.standardDayMinutes()).isEqualTo(480);
        assertThat(home.enrollmentWindows()).containsExactly(openWindow, scheduledWindow);
        assertThat(home.journeys()).containsExactly(journey);
        assertThat(home.openBenefitWindowCount()).isEqualTo(1);
        assertThat(home.teamTimePendingCount()).isZero();
        assertThat(home.teamAbsencePendingCount()).isZero();
        assertThat(home.teamPendingCount()).isZero();
        assertThat(home.domainStates().get("TEAM").dataOrigin())
                .isEqualTo(HrDtos.HomeDataOrigin.NONE);
        assertThat(home.domainStates().get("TIME").availability())
                .isEqualTo(HrDtos.HomeAvailability.AVAILABLE);
        assertThat(home.domainStates().get("PAY").dataOrigin())
                .isEqualTo(HrDtos.HomeDataOrigin.REFERENCE);
        assertThat(home.referenceDataPresent()).isTrue();
    }

    @Test
    void homeKeepsAvailableDomainsWhenOneDomainQueryFails() {
        PeopleRequestContext.set(USER_ID, TENANT_ID, PERSON_ID, Set.of("USER"), Set.of());
        stubWorker(WORKER_ID);
        when(repository.leaveBalances(eq(TENANT_ID), eq(WORKER_ID), any(LocalDate.class)))
                .thenThrow(new DataAccessResourceFailureException("absence unavailable"));
        when(repository.enrollmentWindows(TENANT_ID, WORKER_ID)).thenReturn(List.of());
        when(repository.activeJourneys(TENANT_ID, WORKER_ID)).thenReturn(List.of());

        HrDtos.HomeOverview home = service.home();

        assertThat(home.leaveBalances()).isEmpty();
        assertThat(home.domainStates().get("ABSENCE").availability())
                .isEqualTo(HrDtos.HomeAvailability.UNAVAILABLE);
        assertThat(home.domainStates().get("TIME").availability())
                .isEqualTo(HrDtos.HomeAvailability.AVAILABLE);
    }

    @Test
    void homeDoesNotQueryTeamQueuesForIndividualContributors() {
        PeopleRequestContext.set(USER_ID, TENANT_ID, PERSON_ID, Set.of("USER"), Set.of());
        stubWorker(WORKER_ID);
        when(repository.leaveBalances(eq(TENANT_ID), eq(WORKER_ID), any(LocalDate.class)))
                .thenReturn(List.of());
        when(repository.enrollmentWindows(TENANT_ID, WORKER_ID)).thenReturn(List.of());
        when(repository.activeJourneys(TENANT_ID, WORKER_ID)).thenReturn(List.of());

        HrDtos.HomeOverview home = service.home();

        assertThat(home.teamPendingCount()).isZero();
        assertThat(home.domainStates().get("TEAM").dataOrigin())
                .isEqualTo(HrDtos.HomeDataOrigin.NONE);
    }

    @Test
    void teamWorkspaceReadsOnlyThroughTheResolvedLivePopulation() {
        PeopleRequestContext.set(USER_ID, TENANT_ID, PERSON_ID, Set.of("MANAGER"), Set.of());
        stubWorker(WORKER_ID);
        HcmPopulationScopeService.ResolvedPopulation population = population(
                "ASSIGN-1", Set.of("DIRECTORY", "EMPLOYMENT"));
        when(populationScopes.requireTeam()).thenReturn(population);
        HrDtos.TeamMember member = new HrDtos.TeamMember(
                UUID.randomUUID(), "Employee", "Analyst", "Operations", 0);
        when(populationRepository.teamMembers(TENANT_ID, population.scope()))
                .thenReturn(List.of(member));
        when(populationRepository.teamQueue(TENANT_ID, population.scope(), "TIME"))
                .thenReturn(List.of(approval("TIME")));
        when(populationRepository.teamQueue(TENANT_ID, population.scope(), "ABSENCE"))
                .thenReturn(List.of(approval("ABSENCE"), approval("ABSENCE")));

        HrDtos.TeamWorkspace result = service.team();

        assertThat(result.members()).containsExactly(member);
        assertThat(result.timePendingCount()).isEqualTo(1);
        assertThat(result.absencePendingCount()).isEqualTo(2);
        assertThat(result.dataBoundary()).isEqualTo(HrDtos.DataBoundary.TEAM);
        verify(populationScopes).requireTrustedScope(
                eq(population), eq("hcm.team"), eq("TARGET_POPULATION"), any(String[].class));
    }

    @Test
    void operationsMetricsNeverFallBackToWholeTenantQueries() {
        PeopleRequestContext.set(USER_ID, TENANT_ID, PERSON_ID, Set.of("TIME_ADMIN"),
                Set.of("DATA.HR_TIME:VIEW"));
        HcmPopulationScopeService.ResolvedPopulation population = population(
                null, Set.of("DIRECTORY", "EMPLOYMENT"));
        when(populationScopes.requireOperations("READ")).thenReturn(population);
        List<HrDtos.DomainMetric> metrics = List.of(
                new HrDtos.DomainMetric("submitted", 2, "ATTENTION"));
        when(populationRepository.metrics(TENANT_ID, population.scope(), "TIME"))
                .thenReturn(metrics);
        when(populationRepository.teamQueue(TENANT_ID, population.scope(), "TIME"))
                .thenReturn(List.of(approval("TIME")));

        HrDtos.DomainOperations result = service.operations("time");

        assertThat(result.metrics()).isEqualTo(metrics);
        assertThat(result.workQueue()).hasSize(1);
        assertThat(result.dataBoundary()).isEqualTo(HrDtos.DataBoundary.ORGANIZATION_SET);
    }

    @Test
    void approveCapabilityCannotMutateAWorkerOutsideTheReadPopulation() {
        PeopleRequestContext.set(USER_ID, TENANT_ID, PERSON_ID, Set.of("TIME_ADMIN"),
                Set.of("DATA.HR_TIME:APPROVE"));
        HcmPopulationScopeService.ResolvedPopulation population = population(
                null, Set.of("DIRECTORY", "EMPLOYMENT"));
        when(populationScopes.requireOperationsForMutation("READ")).thenReturn(population);
        UUID cardId = UUID.randomUUID();
        when(repository.timeCardTarget(TENANT_ID, cardId)).thenReturn(Optional.of(
                new HrRepository.TimeCardTarget(
                        71L, 999L, "SUBMITTED", 3L, UUID.randomUUID(),
                        "Outside Employee", "Analyst", 480)));
        when(populationRepository.lockWorkerInPopulation(TENANT_ID, population.scope(), 999L))
                .thenReturn(false);

        assertThatThrownBy(() -> service.decideTimeCard(
                cardId, new HrDtos.DecisionRequest("APPROVE", "Approved request", 3L),
                "corr-outside"))
                .isInstanceOf(BaseException.class);

        verify(repository, never()).decideTimeCard(
                anyLong(), any(), any(), any(), anyLong(), anyLong());
        verify(audit, never()).record(any(AuditEvent.class));
    }

    private void stubWorker(long workerId) {
        when(repository.worker(TENANT_ID, PERSON_ID)).thenReturn(Optional.of(
                new HrRepository.WorkerIdentity(
                        workerId, PERSON_ID, "Minseo Kim", "ASSIGN-1",
                        "Network Operations Lead", "Network Operations",
                        null, null, 0)));
    }

    private HrRepository.LeaveRequestTarget target(long workerId, String status, long version) {
        return new HrRepository.LeaveRequestTarget(
                71L, workerId, 12L, 480, status, version,
                PERSON_ID, "Minseo Kim", "Network Operations Lead", "Annual leave");
    }

    private HcmPopulationScopeService.ResolvedPopulation population(
            String managerAssignment,
            Set<String> fields) {
        HcmPopulationRepository.ActorWorkforce actor = managerAssignment == null
                ? null : new HcmPopulationRepository.ActorWorkforce(
                        WORKER_ID, PERSON_ID, "Minseo Kim", managerAssignment,
                        "Lead", "Operations", 1L, 2L, 3L);
        HcmPopulationRepository.PopulationScope scope =
                new HcmPopulationRepository.PopulationScope(
                        actor == null ? 0L : WORKER_ID, managerAssignment,
                        false, actor == null ? Set.of(UUID.randomUUID()) : Set.of(),
                        fields, "policy-fingerprint");
        return new HcmPopulationScopeService.ResolvedPopulation(
                actor, scope, new HcmPopulationRepository.PopulationEvidence(
                        2L, "population-revision"));
    }

    private HrDtos.ApprovalItem approval(String domain) {
        return new HrDtos.ApprovalItem(
                UUID.randomUUID(), domain, UUID.randomUUID(), "Employee", "Analyst",
                "Waiting for review", "SUBMITTED", Instant.now(), 1L, null);
    }
}
