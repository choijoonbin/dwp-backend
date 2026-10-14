package com.dwp.services.people.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.core.security.HcmEligibilityScopeKey;
import com.dwp.core.security.ProductSurfaceScopeKey;
import com.dwp.services.people.hr.HcmPopulationRepository;
import com.dwp.services.people.hr.HcmPopulationScopeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static com.dwp.services.people.security.HrisNavigationLegalEntityContextDtos.*;

/** Recomputes the selected HCM scope and legal-employer boundary at the People owner. */
@Service
public class HrisNavigationLegalEntityContextService {

    private static final List<String> OPERATIONS_SCOPE_SOURCES = List.of(
            "ORG_UNIT/LEGAL_ENTITY",
            "WORKFORCE_TARGET_POPULATION",
            "TIME_TARGET_POPULATION",
            "ABSENCE_TARGET_POPULATION",
            "BENEFITS_TARGET_POPULATION",
            "PAY_TARGET_POPULATION",
            "TALENT_TARGET_POPULATION");

    private final HcmPopulationRepository populationRepository;
    private final HcmPopulationScopeService populationService;
    private final HrisNavigationLegalEntityContextRepository repository;

    public HrisNavigationLegalEntityContextService(
            HcmPopulationRepository populationRepository,
            HcmPopulationScopeService populationService,
            HrisNavigationLegalEntityContextRepository repository) {
        this.populationRepository = populationRepository;
        this.populationService = populationService;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public VerifyResult verify(VerifyRequest request) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (actor.tenantId() == null || actor.userId() == null
                || actor.personPublicId() == null
                || actor.tenantId().longValue() != request.tenantId().longValue()
                || actor.userId().longValue() != request.actorId().longValue()
                || !HcmEligibilityScopeKey.isCanonical(request.contextScopeKey())) {
            throw forbidden("HRIS_NAV_LEGAL_ENTITY_ACTOR_CONTEXT_MISMATCH");
        }

        HcmPopulationScopeService.ResolvedPopulation operations =
                populationService.findOperations("READ").orElseThrow(() ->
                        forbidden("HRIS_NAV_LEGAL_ENTITY_OPERATIONS_POPULATION_UNAVAILABLE"));
        if (!selectedScopeMatches(request, operations)) {
            throw forbidden("HRIS_NAV_LEGAL_ENTITY_SELECTED_SCOPE_STALE");
        }
        if (!repository.isCurrentOperationsLegalEmployer(
                request.tenantId(), operations.scope(), request.legalEmployerPublicId())) {
            throw forbidden("HRIS_NAV_LEGAL_ENTITY_OUTSIDE_OPERATIONS_POPULATION");
        }

        String evidence = digest(String.join("\n",
                Long.toString(request.tenantId()),
                Long.toString(request.actorId()),
                request.contextSurface().name(),
                request.contextScopeKey(),
                request.legalEmployerPublicId().toString(),
                operations.relationshipRevision(),
                operations.targetPopulationRevision()));
        return new VerifyResult(
                true, request.tenantId(), request.actorId(), request.contextSurface(),
                request.contextScopeKey(), request.legalEmployerPublicId(),
                "people-owner-sha256:" + evidence);
    }

    private boolean selectedScopeMatches(
            VerifyRequest request,
            HcmPopulationScopeService.ResolvedPopulation operations) {
        return switch (request.contextSurface()) {
            case MANAGEMENT -> managementScope(request);
            case OPERATIONS -> operationsScope(request, operations);
        };
    }

    private boolean managementScope(VerifyRequest request) {
        String revision = populationRepository.tenantRevision(request.tenantId())
                .orElseThrow(() -> forbidden(
                        "HRIS_NAV_LEGAL_ENTITY_CONFIGURATION_POPULATION_UNAVAILABLE"));
        String source = ProductSurfaceScopeKey.key(
                request.tenantId(), request.actorId(), "hcm", "hcm.management",
                "RS_HCM_CONFIG", "RESOURCE_SET");
        return request.contextScopeKey().equals(HcmEligibilityScopeKeys.derived(
                request.tenantId(), request.actorId(), "hcm.management", source,
                "auth-responsibility", "config:" + revision));
    }

    private boolean operationsScope(
            VerifyRequest request,
            HcmPopulationScopeService.ResolvedPopulation operations) {
        for (String resolver : OPERATIONS_SCOPE_SOURCES) {
            String source = ProductSurfaceScopeKey.key(
                    request.tenantId(), request.actorId(), "hcm", "hcm.operations",
                    resolver, "TARGET_POPULATION");
            String derived = HcmEligibilityScopeKeys.derived(
                    request.tenantId(), request.actorId(), "hcm.operations", source,
                    operations.relationshipRevision(), operations.targetPopulationRevision());
            if (request.contextScopeKey().equals(derived)) return true;
        }
        return false;
    }

    private String digest(String material) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private BaseException forbidden(String message) {
        return new BaseException(ErrorCode.FORBIDDEN, message);
    }
}
