package com.dwp.services.auth.hrisperformancefeature;

import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.dwp.services.auth.service.ProductSurfaceAuthorityService;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PerformanceCommandAuthorityAdapter implements PerformanceCommandAuthority {
    private final JdbcTemplate jdbc;
    private final ProductSurfaceAuthorityService authority;
    private final ProductAuthorizationContractRepository registry;

    public PerformanceCommandAuthorityAdapter(JdbcTemplate jdbc,
            ProductSurfaceAuthorityService authority, ProductAuthorizationContractRepository registry) {
        this.jdbc = jdbc;
        this.authority = authority;
        this.registry = registry;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Evidence evaluate(PerformanceFeatureContract.Request request) {
        var subjects = jdbc.query("""
                SELECT actor.user_id FROM com_users actor
                  JOIN com_tenants tenant ON tenant.tenant_id = actor.tenant_id
                 WHERE actor.tenant_id = ? AND actor.public_id = ?
                   AND actor.status = 'ACTIVE' AND actor.identity_plane = 'TENANT'
                   AND tenant.status = 'ACTIVE'
                """, (row, index) -> row.getLong(1), request.tenantId(),
                UUID.fromString(request.subjectPrincipalPublicId()));
        if (subjects.size() != 1) throw new IllegalStateException("Current subject unavailable.");
        var bundle = registry.findActive("product-surfaces").orElseThrow();
        var result = authority.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(
                request.tenantId(), subjects.getFirst(), "hcm", "hcm.management",
                ProductSurfaceAuthorityDtos.AccessMode.ELEVATED,
                request.exactSharedActionRouteContractKey(), null, null, null, null, List.of()));
        boolean allowed = result.decision() == ProductSurfaceAuthorityDtos.Decision.ALLOWED
                && !result.effectiveReadOnly() && result.routeGrantRef() != null
                && result.effectiveGrants().stream().anyMatch(grant -> !grant.readOnly());
        return new Evidence(allowed, PerformanceFeatureContract.authorizationRevision(result.authRevision()),
                bundle.version(), result.policyRevision(), result.tenantControlRevision(),
                result.revalidateAt() == null ? null : result.revalidateAt().toInstant());
    }
}
