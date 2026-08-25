package com.dwp.services.people.workforce;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Owner-service boundary for the organization design candidate projection. */
@Service
public class WorkforceCandidateService {

    private static final String ROUTE = "route.hcm.management.org-design.page";

    private final WorkforceCandidateRepository repository;
    private final HcmPopulationScopeService populationScopes;

    public WorkforceCandidateService(
            WorkforceCandidateRepository repository,
            HcmPopulationScopeService populationScopes) {
        this.repository = repository;
        this.populationScopes = populationScopes;
    }

    @Transactional(readOnly = true)
    public List<WorkforceCandidateDtos.OrganizationCandidate> list() {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        HcmPepContext.Evidence evidence = HcmPepContext.current();
        if (evidence == null) {
            if (!actor.hasAnyRole("ADMIN", "HR_ADMIN")) {
                throw forbidden();
            }
        } else {
            if (!ROUTE.equals(evidence.authority().routeContractKey())) {
                throw forbidden();
            }
            populationScopes.requireConfigurationScope();
        }
        return repository.list(actor.tenantId());
    }

    private BaseException forbidden() {
        return new BaseException(
                ErrorCode.FORBIDDEN, "Organization design permission is required.");
    }
}
