package com.dwp.services.people.hr;

import com.dwp.services.people.security.PeopleRequestContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

/** Assembles the permission-aware, partially available HR home projection. */
final class HrHomeOverviewAssembler {

    private static final Logger log = LoggerFactory.getLogger(HrHomeOverviewAssembler.class);

    private final HrRepository repository;

    HrHomeOverviewAssembler(HrRepository repository) {
        this.repository = repository;
    }

    boolean canLoad(PeopleRequestContext.Actor actor, String domain) {
        String resource = HrAuthorization.DOMAIN_RESOURCES.get(domain);
        return resource != null && actor.hasPermission(resource, "VIEW", "MANAGE");
    }

    HrDtos.HomeOverview assemble(
            PeopleRequestContext.Actor actor,
            HrRepository.WorkerIdentity worker,
            HrRepository.WorkerSchedule schedule,
            String timeZone,
            LocalDate asOf) {
        Long tenantId = actor.tenantId();
        long workerId = worker.workerId();
        HomeLoad<HrDtos.TimeCard> time = loadAuthorized(
                actor, "TIME", null,
                () -> repository.currentTimeCard(tenantId, workerId, asOf),
                card -> card == null ? HrDtos.HomeDataOrigin.NONE : origin(card.dataOrigin()));
        HomeLoad<List<HrDtos.LeaveBalance>> absence = loadAuthorized(
                actor, "ABSENCE", List.of(),
                () -> repository.leaveBalances(tenantId, workerId, asOf),
                balances -> origins(balances.stream()
                        .map(HrDtos.LeaveBalance::dataOrigin).toList()));
        HomeLoad<BenefitsHome> benefits = loadAuthorized(
                actor, "BENEFITS", new BenefitsHome(List.of(), 0),
                () -> new BenefitsHome(
                        repository.enrollmentWindows(tenantId, workerId),
                        Math.toIntExact(repository.activeBenefits(tenantId, workerId))),
                value -> value.windows().isEmpty() && value.activeCount() == 0
                        ? HrDtos.HomeDataOrigin.NONE : HrDtos.HomeDataOrigin.UNKNOWN);
        HomeLoad<HrDtos.PayCycle> pay = loadAuthorized(
                actor, "PAY", null,
                () -> repository.nextPayCycle(tenantId, workerId),
                cycle -> cycle == null ? HrDtos.HomeDataOrigin.NONE : origin(cycle.dataOrigin()));
        HomeLoad<TalentHome> talent = loadAuthorized(
                actor, "TALENT", new TalentHome(List.of(), 0, 0),
                () -> new TalentHome(
                        repository.activeJourneys(tenantId, workerId),
                        Math.toIntExact(repository.activeGoals(tenantId, workerId)),
                        Math.toIntExact(repository.requiredLearning(tenantId, workerId))),
                value -> value.journeys().isEmpty()
                        && value.activeGoalCount() == 0
                        && value.requiredLearningCount() == 0
                        ? HrDtos.HomeDataOrigin.NONE : HrDtos.HomeDataOrigin.UNKNOWN);
        HomeLoad<TeamHome> team = unavailableTeam();

        Map<String, HrDtos.HomeDomainState> states = new LinkedHashMap<>();
        states.put("TIME", time.state());
        states.put("ABSENCE", absence.state());
        states.put("BENEFITS", benefits.state());
        states.put("PAY", pay.state());
        states.put("TALENT", talent.state());
        states.put("TEAM", team.state());
        boolean reference = states.values().stream().anyMatch(state ->
                state.dataOrigin() == HrDtos.HomeDataOrigin.REFERENCE
                        || state.dataOrigin() == HrDtos.HomeDataOrigin.MIXED)
                || schedule != null && "REFERENCE".equals(schedule.dataOrigin());
        List<HrDtos.EnrollmentWindow> windows = benefits.value().windows();
        int teamTimePending = team.value().timePendingCount();
        int teamAbsencePending = team.value().absencePendingCount();
        return new HrDtos.HomeOverview(
                asOf, Instant.now(), timeZone,
                schedule == null ? null : schedule.standardDayMinutes(),
                employee(actor, worker), time.value(), absence.value(), pay.value(), windows,
                talent.value().journeys(), benefits.value().activeCount(),
                Math.toIntExact(windows.stream()
                        .filter(window -> "OPEN".equals(window.lifecycleState())).count()),
                talent.value().activeGoalCount(), talent.value().requiredLearningCount(),
                teamTimePending + teamAbsencePending, teamTimePending, teamAbsencePending,
                Map.copyOf(states), reference);
    }

    private <T> HomeLoad<T> loadAuthorized(
            PeopleRequestContext.Actor actor,
            String domain,
            T fallback,
            Supplier<T> supplier,
            Function<T, HrDtos.HomeDataOrigin> originResolver) {
        if (!canLoad(actor, domain)) {
            return new HomeLoad<>(fallback, new HrDtos.HomeDomainState(
                    HrDtos.HomeAvailability.UNAVAILABLE,
                    HrDtos.HomeDataOrigin.NONE,
                    domain + "_ENTITLEMENT_REQUIRED"));
        }
        try {
            T value = supplier.get();
            return new HomeLoad<>(value, new HrDtos.HomeDomainState(
                    HrDtos.HomeAvailability.AVAILABLE, originResolver.apply(value), null));
        } catch (DataAccessException exception) {
            log.warn("Unable to assemble {} data for the HCM home", domain, exception);
            return new HomeLoad<>(fallback, new HrDtos.HomeDomainState(
                    HrDtos.HomeAvailability.UNAVAILABLE,
                    HrDtos.HomeDataOrigin.UNKNOWN,
                    domain + "_QUERY_FAILED"));
        }
    }

    private static HomeLoad<TeamHome> unavailableTeam() {
        // There is no authoritative TEAM owner query for the legacy home aggregate.
        return new HomeLoad<>(new TeamHome(0, 0), new HrDtos.HomeDomainState(
                HrDtos.HomeAvailability.UNAVAILABLE,
                HrDtos.HomeDataOrigin.NONE,
                "TEAM_OWNER_API_REQUIRED"));
    }

    private static HrDtos.EmployeeContext employee(
            PeopleRequestContext.Actor actor, HrRepository.WorkerIdentity worker) {
        if (!actor.hasPermission("DATA.WORKFORCE", "VIEW", "MANAGE")) {
            return new HrDtos.EmployeeContext(
                    worker.personId(), worker.displayName(), null, null, null, 0);
        }
        return new HrDtos.EmployeeContext(
                worker.personId(), worker.displayName(), worker.businessTitle(),
                worker.organizationName(), worker.managerDisplayName(), worker.directReportCount());
    }

    private HrDtos.HomeDataOrigin origins(List<String> values) {
        List<HrDtos.HomeDataOrigin> origins = values.stream().map(this::origin).distinct().toList();
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

    private record HomeLoad<T>(T value, HrDtos.HomeDomainState state) { }
    private record BenefitsHome(List<HrDtos.EnrollmentWindow> windows, int activeCount) { }
    private record TalentHome(
            List<HrDtos.Journey> journeys, int activeGoalCount, int requiredLearningCount) { }
    private record TeamHome(int timePendingCount, int absencePendingCount) { }
}
