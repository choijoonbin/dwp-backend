package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class ProductSurfaceAuthorityService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProductSurfaceAuthorityService.class);

    private final ObjectProvider<ProductSurfaceAuthorityPort> authorityPorts;

    public ProductSurfaceAuthorityService(
            ObjectProvider<ProductSurfaceAuthorityPort> authorityPorts) {
        this.authorityPorts = authorityPorts;
    }

    public ProductSurfaceAuthorityDtos.AuthorityResult evaluate(
            ProductSurfaceAuthorityDtos.EvaluateRequest request) {
        try {
            List<ProductSurfaceAuthorityPort> ports = authorityPorts.orderedStream().toList();
            if (ports.size() != 1) {
                LOGGER.warn("Product surface authority requires exactly one registry adapter; found {}.",
                        ports.size());
                return ProductSurfaceAuthorityDtos.AuthorityResult.unavailable(request);
            }
            ProductSurfaceAuthorityDtos.AuthorityResult result = ports.getFirst().evaluate(request);
            return valid(request, result)
                    ? result
                    : ProductSurfaceAuthorityDtos.AuthorityResult.unavailable(request);
        } catch (RuntimeException exception) {
            LOGGER.warn("Product surface authority evaluation failed closed for {}/{}: {}",
                    request.productKey(), request.surfaceKey(), exception.toString());
            return ProductSurfaceAuthorityDtos.AuthorityResult.unavailable(request);
        }
    }

    private boolean valid(
            ProductSurfaceAuthorityDtos.EvaluateRequest request,
            ProductSurfaceAuthorityDtos.AuthorityResult result) {
        if (result == null || result.decision() == null
                || result.accessMode() != request.activeAccessMode()) {
            return false;
        }
        if (result.decision() == ProductSurfaceAuthorityDtos.Decision.AUTHORITY_UNAVAILABLE) {
            return true;
        }
        if (!request.productKey().equals(result.productKey())
                || !request.surfaceKey().equals(result.surfaceKey())) {
            return false;
        }
        if (blank(result.authRevision()) || blank(result.policyRevision())) return false;
        if (result.decision() == ProductSurfaceAuthorityDtos.Decision.STEP_UP_REQUIRED) {
            return result.accessSource() != null
                    && !result.effectiveGrants().isEmpty()
                    && !result.scopes().isEmpty()
                    && closed(result)
                    && !blank(result.requiredAssurance())
                    && !blank(result.requestPolicyRef())
                    && (!request.directRouteEvaluation() || !blank(result.routeGrantRef()));
        }
        if (result.decision() != ProductSurfaceAuthorityDtos.Decision.ALLOWED) {
            return result.effectiveGrants().isEmpty() && result.scopes().isEmpty();
        }
        if (blank(result.contextKey())
                || !Set.of("work", "management").contains(result.plane())
                || result.accessSource() == null || result.revalidateAt() == null
                || result.effectiveGrants().isEmpty() || result.scopes().isEmpty()) {
            return false;
        }
        if (request.directRouteEvaluation() && blank(result.routeGrantRef())) {
            return false;
        }
        long defaults = result.scopes().stream()
                .filter(ProductSurfaceAuthorityDtos.EffectiveScope::isDefault)
                .count();
        return defaults <= 1 && (result.scopes().size() != 1 || defaults == 1);
    }

    private boolean closed(ProductSurfaceAuthorityDtos.AuthorityResult result) {
        Set<String> keys = result.scopes().stream()
                .map(ProductSurfaceAuthorityDtos.EffectiveScope::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return result.effectiveGrants().stream().allMatch(grant ->
                !grant.scopeKeys().isEmpty() && keys.containsAll(grant.scopeKeys()));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
