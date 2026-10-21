package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class ProductSurfaceAuthorityService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProductSurfaceAuthorityService.class);

    private final ObjectProvider<ProductSurfaceAuthorityPort> authorityPorts;
    private final Hrm012OwnerReceiptAuthorityEvidenceService hrm012Evidence;

    @Autowired
    public ProductSurfaceAuthorityService(
            ObjectProvider<ProductSurfaceAuthorityPort> authorityPorts,
            Hrm012OwnerReceiptAuthorityEvidenceService hrm012Evidence) {
        this.authorityPorts = authorityPorts;
        this.hrm012Evidence = hrm012Evidence;
    }

    public ProductSurfaceAuthorityService(
            ObjectProvider<ProductSurfaceAuthorityPort> authorityPorts) {
        this.authorityPorts = authorityPorts;
        this.hrm012Evidence = null;
    }

    public ProductSurfaceAuthorityDtos.AuthorityResult evaluate(
            ProductSurfaceAuthorityDtos.EvaluateRequest request) {
        ProductSurfaceAuthorityDtos.AuthorityResult result = evaluateValidated(request);
        if (result.decision()
                != ProductSurfaceAuthorityDtos.Decision.OWNER_RECEIPT_AUTH_REQUIRED) {
            return result;
        }
        if (hrm012Evidence == null) {
            return ProductSurfaceAuthorityDtos.AuthorityResult.unavailable(request);
        }
        try {
            return withOwnerReceiptAuthority(result, hrm012Evidence.issue(request, result));
        } catch (RuntimeException exception) {
            LOGGER.warn("HRM-012 owner-receipt evidence issuance failed closed: {}",
                    exception.toString());
            return ProductSurfaceAuthorityDtos.AuthorityResult.unavailable(request);
        }
    }

    ProductSurfaceAuthorityDtos.AuthorityResult evaluateHrm012PreflightForPostReceipt(
            ProductSurfaceAuthorityDtos.EvaluateRequest request) {
        ProductSurfaceAuthorityDtos.AuthorityResult result = evaluateValidated(request);
        return result.decision()
                == ProductSurfaceAuthorityDtos.Decision.OWNER_RECEIPT_AUTH_REQUIRED
                ? result : ProductSurfaceAuthorityDtos.AuthorityResult.unavailable(request);
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult evaluateValidated(
            ProductSurfaceAuthorityDtos.EvaluateRequest request) {
        try {
            List<ProductSurfaceAuthorityPort> ports = authorityPorts.orderedStream().toList();
            if (ports.size() != 1) {
                LOGGER.warn("Product surface authority requires exactly one registry adapter; found {}.",
                        ports.size());
                return ProductSurfaceAuthorityDtos.AuthorityResult.unavailable(request);
            }
            ProductSurfaceAuthorityDtos.AuthorityResult result = ports.getFirst().evaluate(request);
            if (!valid(request, result)) {
                return ProductSurfaceAuthorityDtos.AuthorityResult.unavailable(request);
            }
            return result;
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
        if (result.decision()
                != ProductSurfaceAuthorityDtos.Decision.OWNER_RECEIPT_AUTH_REQUIRED
                && result.ownerReceiptAuthority() != null) return false;
        if (blank(result.authRevision()) || blank(result.policyRevision())
                || blank(result.tenantControlRevision())) return false;
        if (result.decision()
                == ProductSurfaceAuthorityDtos.Decision.OWNER_RECEIPT_AUTH_REQUIRED) {
            return request.ownerReceiptRequest()
                    && "hcm".equals(request.productKey())
                    && "hcm.operations".equals(request.surfaceKey())
                    && "route.hcm.operations.workforce-ingestion.action".equals(
                            request.routeContractKey())
                    && request.activeAccessMode() == ProductSurfaceAuthorityDtos.AccessMode.NORMAL
                    && "OWNER_RECEIPT_AUTH_REQUIRED".equals(result.reasonCode())
                    && result.accessSource() == ProductSurfaceAuthorityDtos.AccessSource.ENTITLEMENT
                    && java.util.Set.of("APP.HCM", "APP.HRIS").contains(result.appResourceKey())
                    && result.contextKey() == null && "work".equals(result.plane())
                    && result.effectiveGrants().isEmpty() && result.scopes().isEmpty()
                    && result.routeGrantRef() == null && result.effectiveReadOnly()
                    && !result.requiresProductEligibility() && result.validUntil() == null
                    && result.expiredAt() == null && result.requiredAssurance() == null
                    && result.requestPolicyRef() == null && result.revalidateAt() != null
                    && result.evidenceRef() == null
                    && result.ownerReceiptAuthority() == null;
        }
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

    private ProductSurfaceAuthorityDtos.AuthorityResult withOwnerReceiptAuthority(
            ProductSurfaceAuthorityDtos.AuthorityResult value, String marker) {
        return new ProductSurfaceAuthorityDtos.AuthorityResult(
                value.decision(), value.reasonCode(), value.authRevision(),
                value.policyRevision(), value.tenantControlRevision(), value.contextKey(),
                value.productKey(), value.surfaceKey(), value.plane(), value.accessMode(),
                value.accessSource(), value.appResourceKey(), value.effectiveGrants(),
                value.scopes(), value.routeGrantRef(), value.effectiveReadOnly(),
                value.requiresProductEligibility(), value.validUntil(), value.expiredAt(),
                value.requiredAssurance(), value.requestPolicyRef(), value.revalidateAt(),
                value.evidenceRef(), marker);
    }

}
