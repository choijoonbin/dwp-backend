package com.dwp.services.people.hris.experience;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.ObjectProvider;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.List;
import java.util.Objects;

/** Revalidates the current route and derives a live owner population. */
@Component
class HrisExperienceReceiptAuthority {

    static final String ROUTE =
            "route.hcm.operations.assignment-command-receipt.data";
    static final String CAPABILITY = "hcm.command-receipt.view";

    private final HcmPopulationScopeService populations;
    private final ObjectProvider<HrisExperienceOriginReceiptAuthoritySource> originSources;

    HrisExperienceReceiptAuthority(
            HcmPopulationScopeService populations,
            ObjectProvider<HrisExperienceOriginReceiptAuthoritySource> originSources) {
        this.populations = populations;
        this.originSources = originSources;
    }

    CurrentAuthority require(
            HrisExperienceCommandReceiptRepository.ReceiptRecord receipt,
            OriginPolicy policy) {
        String purpose = receipt.purpose();
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (actor.tenantId() == null || actor.tenantId() < 1
                || actor.userId() == null || actor.userId() < 1
                || actor.personPublicId() == null) {
            throw denied();
        }
        HcmPepContext.Evidence evidence = HcmPepContext.current();
        HcmV3PepRegistry.RouteAuthority route = evidence == null
                ? null : evidence.authority();
        if (route == null || !ROUTE.equals(route.routeContractKey())
                || !CAPABILITY.equals(route.capabilityContractKey())
                || !"GET".equals(route.method()) || !route.readOnly()
                || evidence.decisionRevision() == null
                || !evidence.decisionRevision().matches("psr-[0-9a-f]{64}")
                || evidence.revalidateAt() == null
                || !evidence.revalidateAt().isAfter(OffsetDateTime.now())) {
            throw denied();
        }
        List<HrisExperienceOriginReceiptAuthoritySource> sources =
                originSources.orderedStream().toList();
        if (sources.size() != 1) throw denied();
        var request = new HrisExperienceOriginReceiptAuthoritySource
                .OriginAuthorityRequest(
                receipt.tenantId(), actor.userId(), actor.personPublicId(),
                receipt.commandType(), receipt.action(), receipt.targetType(), purpose,
                policy.routeContractKey(), policy.capabilityContractKey(),
                policy.method(), policy.readOnly(), receipt.authorizationRevision());
        HrisExperienceOriginReceiptAuthoritySource.CurrentOriginAuthority origin;
        try {
            origin = sources.getFirst().resolve(request).orElseThrow(this::denied);
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw denied();
        }
        if (origin.tenantId() != receipt.tenantId()
                || origin.actorUserId() != actor.userId()
                || !Objects.equals(origin.actorPersonPublicId(), actor.personPublicId())
                || !Objects.equals(origin.commandType(), receipt.commandType())
                || !Objects.equals(origin.action(), receipt.action())
                || !Objects.equals(origin.targetType(), receipt.targetType())
                || !Objects.equals(origin.purposeCode(), purpose)
                || !Objects.equals(origin.routeContractKey(), policy.routeContractKey())
                || !Objects.equals(origin.capabilityContractKey(),
                        policy.capabilityContractKey())
                || !Objects.equals(origin.method(), policy.method())
                || origin.readOnly() != policy.readOnly()
                || origin.authorizationRevision() != receipt.authorizationRevision()) {
            throw denied();
        }

        if ("EMPLOYEE_SELF_SERVICE".equals(purpose)) {
            populations.requireSelfScope();
            return new CurrentAuthority(actor, null,
                    "self:" + evidence.scopeKey(),
                    Set.of("DIRECTORY", "PERSON_PROFILE", "CONTACT"), evidence, origin);
        }
        if ("TEAM_MANAGEMENT".equals(purpose)) {
            HcmPopulationScopeService.ResolvedPopulation population =
                    populations.requireTeam();
            populations.requireTrustedScope(
                    population, "hcm.team", "TARGET_POPULATION",
                    "TEAM/ORG_UNIT",
                    "DIRECT_REPORT_OR_APPROVED_DELEGATION+TARGET_POPULATION");
            return new CurrentAuthority(actor, population,
                    population.targetPopulationRevision(),
                    population.scope().fieldGroups(), evidence, origin);
        }
        HcmPopulationScopeService.ResolvedPopulation population =
                populations.requireOperations("READ");
        populations.requireTrustedScope(
                population, "hcm.operations", "TARGET_POPULATION",
                "ORG_UNIT/LEGAL_ENTITY", "WORKFORCE_TARGET_POPULATION");
        return new CurrentAuthority(actor, population,
                population.targetPopulationRevision(),
                population.scope().fieldGroups(), evidence, origin);
    }

    private BaseException denied() {
        return new BaseException(ErrorCode.NOT_FOUND);
    }

    record CurrentAuthority(
            PeopleRequestContext.Actor actor,
            HcmPopulationScopeService.ResolvedPopulation population,
            String scopeRevision,
            Set<String> fieldGroups,
            HcmPepContext.Evidence evidence,
            HrisExperienceOriginReceiptAuthoritySource.CurrentOriginAuthority origin) {
        CurrentAuthority {
            fieldGroups = Set.copyOf(fieldGroups);
        }

        boolean self() {
            return population == null;
        }
    }

    record OriginPolicy(
            String routeContractKey,
            String capabilityContractKey,
            String method,
            boolean readOnly) {
    }
}
