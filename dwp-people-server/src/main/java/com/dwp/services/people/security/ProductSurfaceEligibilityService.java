package com.dwp.services.people.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductSurfaceEligibilityService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProductSurfaceEligibilityService.class);

    private final ObjectProvider<ProductSurfaceEligibilityPort> eligibilityPorts;

    public ProductSurfaceEligibilityService(
            ObjectProvider<ProductSurfaceEligibilityPort> eligibilityPorts) {
        this.eligibilityPorts = eligibilityPorts;
    }

    public ProductSurfaceEligibilityDtos.EligibilityResult evaluate(
            ProductSurfaceEligibilityDtos.EvaluateRequest request) {
        PeopleRequestContext.Actor actor = PeopleRequestContext.require();
        if (!request.tenantId().equals(actor.tenantId())
                || !request.actorId().equals(actor.userId())) {
            return ProductSurfaceEligibilityDtos.EligibilityResult.unavailable();
        }

        try {
            List<ProductSurfaceEligibilityPort> ports = eligibilityPorts.orderedStream().toList();
            if (ports.size() != 1) {
                LOGGER.warn("Product eligibility requires exactly one evaluator adapter; found {}.",
                        ports.size());
                return ProductSurfaceEligibilityDtos.EligibilityResult.unavailable();
            }
            ProductSurfaceEligibilityDtos.EligibilityResult result =
                    ports.getFirst().evaluate(request);
            return valid(result, request)
                    ? result
                    : ProductSurfaceEligibilityDtos.EligibilityResult.unavailable();
        } catch (RuntimeException exception) {
            LOGGER.warn("Product eligibility evaluation failed closed for {}: {}",
                    request.surfaceKey(), exception.toString());
            return ProductSurfaceEligibilityDtos.EligibilityResult.unavailable();
        }
    }

    private boolean valid(
            ProductSurfaceEligibilityDtos.EligibilityResult result,
            ProductSurfaceEligibilityDtos.EvaluateRequest request) {
        if (result == null || result.decision() == null) return false;
        if (result.decision() == ProductSurfaceEligibilityDtos.Decision.AUTHORITY_UNAVAILABLE) {
            return true;
        }
        if (blank(result.productRelationshipRevision())
                || blank(result.targetPopulationRevision())) {
            return false;
        }
        if (result.decision() != ProductSurfaceEligibilityDtos.Decision.ALLOWED) {
            return result.scopes().isEmpty();
        }
        if (result.scopes().isEmpty() || result.revalidateAt() == null) return false;
        java.util.Set<String> candidates = request.candidateScopes().stream()
                .map(ProductSurfaceEligibilityDtos.CandidateScope::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        java.util.Set<String> derived = new java.util.HashSet<>();
        if (result.scopes().stream().anyMatch(scope -> scope == null
                || blank(scope.sourceScopeKey())
                || !candidates.contains(scope.sourceScopeKey())
                || blank(scope.key())
                || !derived.add(scope.key()))) {
            return false;
        }
        long defaults = result.scopes().stream()
                .filter(ProductSurfaceEligibilityDtos.EligibleScope::isDefault)
                .count();
        return defaults <= 1 && (result.scopes().size() != 1 || defaults == 1);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
