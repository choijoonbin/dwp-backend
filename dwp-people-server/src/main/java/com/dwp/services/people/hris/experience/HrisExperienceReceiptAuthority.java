package com.dwp.services.people.hris.experience;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
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

    private final ObjectProvider<HrisExperienceOriginReceiptAuthoritySource> originSources;

    HrisExperienceReceiptAuthority(
            ObjectProvider<HrisExperienceOriginReceiptAuthoritySource> originSources) {
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
                || !Set.of("NORMAL", "ELEVATED").contains(evidence.activeAccessMode())
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
                policy.method(), policy.readOnly(), receipt.authorizationRevision(),
                receipt.contextDigest(), receipt.subjectPublicId());
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
                || origin.authorizationRevision() != receipt.authorizationRevision()
                || !Objects.equals(origin.contextDigest(), receipt.contextDigest())) {
            throw denied();
        }

        HrisExperienceOriginReceiptAuthoritySource.CurrentOriginScope scope =
                origin.scope();
        boolean expectedSelf = "EMPLOYEE_SELF_SERVICE".equals(purpose);
        if (scope == null || scope.self() != expectedSelf
                || scope.scopeRevision() == null || scope.scopeRevision().isBlank()
                || scope.fieldGroups().isEmpty()
                || scope.sourceScopeKey() == null
                || !scope.sourceScopeKey().matches("scope-[0-9a-f]{32}")
                || !HcmEligibilityScopeKey.isCanonical(scope.derivedScopeKey())
                || scope.productRelationshipRevision() == null
                || scope.productRelationshipRevision().isBlank()
                || scope.targetPopulationRevision() == null
                || scope.targetPopulationRevision().isBlank()
                || scope.freshUntil() == null
                || !scope.freshUntil().isAfter(OffsetDateTime.now())) {
            throw denied();
        }
        return new CurrentAuthority(
                actor, scope.population(), scope.scopeRevision(), scope.fieldGroups(),
                evidence, origin);
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
