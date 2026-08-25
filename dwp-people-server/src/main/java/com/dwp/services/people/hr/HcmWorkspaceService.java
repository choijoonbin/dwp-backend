package com.dwp.services.people.hr;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Read model assembler for the dedicated team and operations workbenches. */
@Service
public class HcmWorkspaceService {

    private final HrRepository repository;
    private final HcmPopulationRepository populations;
    private final HcmPopulationScopeService scopes;

    public HcmWorkspaceService(
            HrRepository repository,
            HcmPopulationRepository populations,
            HcmPopulationScopeService scopes) {
        this.repository = repository;
        this.populations = populations;
        this.scopes = scopes;
    }

    @Transactional(readOnly = true)
    public HrDtos.TeamWorkspace team() {
        WorkerContext context = context();
        HcmPopulationScopeService.ResolvedPopulation population = scopes.requireTeam();
        requireTeamScope(population);
        List<HrDtos.ApprovalItem> time = populations.teamQueue(
                context.actor().tenantId(), population.scope(), "TIME");
        List<HrDtos.ApprovalItem> absence = populations.teamQueue(
                context.actor().tenantId(), population.scope(), "ABSENCE");
        return new HrDtos.TeamWorkspace(
                employee(context.worker()),
                populations.teamMembers(context.actor().tenantId(), population.scope()),
                time.size(), absence.size(), population.scope().dataBoundary());
    }

    @Transactional(readOnly = true)
    public HrDtos.TeamTimeWorkspace teamTime() {
        WorkerContext context = context();
        requireDomainPermission(context.actor(), "TIME", "VIEW", "MANAGE", "APPROVE");
        HcmPopulationScopeService.ResolvedPopulation population = scopes.requireTeam();
        requireTeamScope(population);
        return new HrDtos.TeamTimeWorkspace(
                employee(context.worker()), populations.teamQueue(
                context.actor().tenantId(), population.scope(), "TIME"),
                population.scope().dataBoundary());
    }

    @Transactional(readOnly = true)
    public HrDtos.TeamAbsenceWorkspace teamAbsence() {
        WorkerContext context = context();
        requireDomainPermission(context.actor(), "ABSENCE", "VIEW", "MANAGE", "APPROVE");
        HcmPopulationScopeService.ResolvedPopulation population = scopes.requireTeam();
        requireTeamScope(population);
        return new HrDtos.TeamAbsenceWorkspace(
                employee(context.worker()), populations.teamQueue(
                context.actor().tenantId(), population.scope(), "ABSENCE"),
                populations.teamAbsences(context.actor().tenantId(), population.scope()),
                population.scope().dataBoundary());
    }

    @Transactional(readOnly = true)
    public HrDtos.DomainOperations operations(String requestedDomain) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        String domain = normalizedDomain(requestedDomain);
        requireDomainPermission(actor, domain, "VIEW", "MANAGE");
        HcmPopulationScopeService.ResolvedPopulation population = scopes.requireOperations("READ");
        scopes.requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                domain + "_TARGET_POPULATION", "ORG_UNIT/LEGAL_ENTITY");
        List<HrDtos.ApprovalItem> queue = List.of();
        if ((domain.equals("TIME") || domain.equals("ABSENCE"))
                && population.scope().fieldGroups().contains("EMPLOYMENT")) {
            queue = populations.teamQueue(actor.tenantId(), population.scope(), domain);
        }
        return new HrDtos.DomainOperations(
                domain, Instant.now(), populations.metrics(
                actor.tenantId(), population.scope(), domain),
                queue, population.scope().dataBoundary());
    }

    @Transactional(readOnly = true)
    public HrDtos.WorkforceOperationsOverview operationsOverview() {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        HcmPopulationScopeService.ResolvedPopulation population = scopes.requireOperations("READ");
        scopes.requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                "ORG_UNIT/LEGAL_ENTITY", "WORKFORCE_TARGET_POPULATION",
                "TIME_TARGET_POPULATION", "ABSENCE_TARGET_POPULATION",
                "BENEFITS_TARGET_POPULATION", "PAY_TARGET_POPULATION",
                "TALENT_TARGET_POPULATION");
        List<HrDtos.DomainOperationsSummary> domains = new ArrayList<>();
        if (hasWorkforcePermission(actor)) {
            domains.add(summary(actor, population, "WORKFORCE", 0));
        }
        for (String domain : HrAuthorization.DOMAINS) {
            if (!hasDomainPermission(actor, domain, "VIEW", "MANAGE")) continue;
            int pending = 0;
            if ((domain.equals("TIME") || domain.equals("ABSENCE"))
                    && population.scope().fieldGroups().contains("EMPLOYMENT")) {
                pending = populations.teamQueue(
                        actor.tenantId(), population.scope(), domain).size();
            }
            domains.add(summary(actor, population, domain, pending));
        }
        if (domains.isEmpty()) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "No HCM operations domain is available in the current boundary.");
        }
        return new HrDtos.WorkforceOperationsOverview(
                Instant.now(), population.scope().dataBoundary(),
                population.scope().fieldGroups().stream().sorted().toList(),
                List.copyOf(domains));
    }

    private HrDtos.DomainOperationsSummary summary(
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population,
            String domain,
            int pending) {
        return new HrDtos.DomainOperationsSummary(
                domain, populations.metrics(
                actor.tenantId(), population.scope(), domain), pending);
    }

    private WorkerContext context() {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (actor.personPublicId() == null) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "A verified workforce identity is required for the team workbench.");
        }
        HrRepository.WorkerIdentity worker = repository.worker(
                actor.tenantId(), actor.personPublicId()).orElseThrow(() ->
                new BaseException(ErrorCode.FORBIDDEN,
                        "The authenticated identity is not linked to an active worker."));
        return new WorkerContext(actor, worker);
    }

    private void requireTeamScope(HcmPopulationScopeService.ResolvedPopulation population) {
        scopes.requireTrustedScope(
                population, "hcm.team", "TARGET_POPULATION",
                "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION", "TEAM/ORG_UNIT");
        scopes.requireField(population, "DIRECTORY");
        scopes.requireField(population, "EMPLOYMENT");
    }

    private void requireDomainPermission(
            PeopleRequestContext.Actor actor, String domain, String... actions) {
        if (!hasDomainPermission(actor, domain, actions)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "Delegated " + domain.toLowerCase()
                            + " administration permission is required.");
        }
    }

    private boolean hasDomainPermission(
            PeopleRequestContext.Actor actor, String domain, String... actions) {
        String resource = HrAuthorization.DOMAIN_RESOURCES.get(domain);
        return actor.hasPermission(resource, actions)
                || (actor.permissions().isEmpty()
                && actor.hasAnyRole("ADMIN", "HR_ADMIN", HrAuthorization.role(domain)));
    }

    private boolean hasWorkforcePermission(PeopleRequestContext.Actor actor) {
        return actor.hasPermission("DATA.WORKFORCE", "VIEW", "MANAGE")
                || (actor.permissions().isEmpty()
                && actor.hasAnyRole("ADMIN", "HR_ADMIN", "PEOPLE_ADMIN"));
    }

    private String normalizedDomain(String domain) {
        String normalized = domain == null ? "" : domain.trim().toUpperCase();
        if (!HrAuthorization.DOMAIN_RESOURCES.containsKey(normalized)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unsupported HR domain.");
        }
        return normalized;
    }

    private HrDtos.EmployeeContext employee(HrRepository.WorkerIdentity worker) {
        return new HrDtos.EmployeeContext(
                worker.personId(), worker.displayName(), worker.businessTitle(),
                worker.organizationName(), worker.managerDisplayName(), worker.directReportCount());
    }

    private record WorkerContext(
            PeopleRequestContext.Actor actor,
            HrRepository.WorkerIdentity worker) {
    }
}
