package com.dwp.services.provider;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.entitlement.EntitlementRepository;
import com.dwp.services.provider.operation.ProviderOperation;
import com.dwp.services.provider.operation.ProviderOperationRepository;
import com.dwp.services.provider.security.ProviderRequestContext;
import com.dwp.services.provider.tenant.ProviderTenant;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

final class ProviderEstateControl {

    private final ProviderTenantRepository tenantRepository;
    private final EntitlementRepository entitlementRepository;
    private final ProviderOperationRepository operationRepository;
    private final ProviderEstateRepository estateRepository;
    private final ProviderOperationsRepository operationsRepository;
    private final ProviderControlPlaneContext context;

    ProviderEstateControl(
            ProviderTenantRepository tenantRepository,
            EntitlementRepository entitlementRepository,
            ProviderOperationRepository operationRepository,
            ProviderEstateRepository estateRepository,
            ProviderOperationsRepository operationsRepository,
            ProviderControlPlaneContext context) {
        this.tenantRepository = tenantRepository;
        this.entitlementRepository = entitlementRepository;
        this.operationRepository = operationRepository;
        this.estateRepository = estateRepository;
        this.operationsRepository = operationsRepository;
        this.context = context;
    }

    ProviderDtos.OperatorProfile operatorProfile() {
        ProviderRequestContext.Actor actor = ProviderRequestContext.require();
        return new ProviderDtos.OperatorProfile(
                actor.operatorId(), actor.userId(), actor.displayName(), actor.roles(), actor.permissions());
    }

    ProviderDtos.EstateOverview overview() {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        return estateRepository.overview();
    }

    ProviderDtos.CommandCenter commandCenter() {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        return operationsRepository.commandCenter(estateRepository.overview());
    }

    ProviderDtos.ServiceHealthOverview serviceHealth() {
        ProviderRequestContext.requirePermission("HEALTH_READ");
        return operationsRepository.serviceHealth();
    }

    ProviderDtos.ReliabilityControlOverview reliabilityControl() {
        ProviderRequestContext.requirePermission("RELIABILITY_READ");
        return operationsRepository.reliabilityControl();
    }

    ProviderDtos.AuditInsights auditInsights() {
        ProviderRequestContext.requirePermission("AUDIT_READ");
        return operationsRepository.auditInsights();
    }

    ProviderDtos.PageResult<ProviderDtos.TenantSummary> tenants(
            String query,
            String state,
            String region,
            String serviceTier,
            String isolationModel,
            int requestedPage,
            int requestedSize) {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        int pageNumber = Math.max(0, requestedPage);
        int size = Math.min(100, Math.max(1, requestedSize));
        Specification<ProviderTenant> specification = Specification.unrestricted();
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            List<UUID> matchingOrganizations = estateRepository.organizationIdsMatching(pattern);
            specification = specification.and((root, ignored, builder) -> matchingOrganizations.isEmpty()
                    ? builder.or(
                            builder.like(builder.lower(root.get("tenantKey")), pattern),
                            builder.like(builder.lower(root.get("displayName")), pattern))
                    : builder.or(
                            builder.like(builder.lower(root.get("tenantKey")), pattern),
                            builder.like(builder.lower(root.get("displayName")), pattern),
                            root.get("organizationId").in(matchingOrganizations)));
        }
        if (state != null && !state.isBlank()) {
            specification = specification.and((root, ignored, builder) ->
                    builder.equal(root.get("lifecycleState"), state.trim().toUpperCase(Locale.ROOT)));
        }
        if (region != null && !region.isBlank()) {
            specification = specification.and((root, ignored, builder) ->
                    builder.equal(root.get("dataRegion"), region.trim().toLowerCase(Locale.ROOT)));
        }
        if (serviceTier != null && !serviceTier.isBlank()) {
            specification = specification.and((root, ignored, builder) ->
                    builder.equal(root.get("serviceTier"), serviceTier.trim().toUpperCase(Locale.ROOT)));
        }
        if (isolationModel != null && !isolationModel.isBlank()) {
            specification = specification.and((root, ignored, builder) ->
                    builder.equal(root.get("isolationModel"), isolationModel.trim().toUpperCase(Locale.ROOT)));
        }
        Page<ProviderTenant> page = tenantRepository.findAll(
                specification,
                PageRequest.of(pageNumber, size, Sort.by("tenantKey").ascending()));
        return new ProviderDtos.PageResult<>(
                page.stream().map(context::tenantSummary).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    ProviderDtos.TenantSummary tenant(UUID tenantId) {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        return context.tenantSummary(context.requireTenant(tenantId));
    }

    List<ProviderDtos.RegionSummary> regions() {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        return estateRepository.regions();
    }

    List<ProviderDtos.EntitlementSummary> entitlementCatalog() {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        return entitlementRepository.findByLifecycleStateOrderByEntitlementKeyAsc("ACTIVE")
                .stream().map(entitlement -> context.entitlementSummary(entitlement, null)).toList();
    }

    List<ProviderDtos.SupportScopeSummary> supportScopes() {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        return operationsRepository.supportScopes();
    }

    ProviderDtos.PageResult<ProviderDtos.OperationSummary> operations(int page, int size) {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Page<ProviderOperation> result = operationRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(safePage, safeSize));
        return new ProviderDtos.PageResult<>(
                result.stream().map(context::operationSummary).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    List<ProviderDtos.OperationApprovalSummary> operationApprovals(String state) {
        ProviderRequestContext.requirePermission("ESTATE_READ");
        String normalized = state == null || state.isBlank()
                ? null
                : state.trim().toUpperCase(Locale.ROOT);
        if (normalized != null
                && !Set.of("PENDING", "APPROVED", "REJECTED", "CANCELLED", "EXPIRED").contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Unknown approval state.");
        }
        return operationsRepository.operationApprovals(normalized);
    }

    List<ProviderDtos.AuditEventSummary> auditEvents(UUID tenantId, int limit) {
        ProviderRequestContext.requirePermission("AUDIT_READ");
        if (tenantId != null) context.requireTenant(tenantId);
        return estateRepository.auditEvents(tenantId, limit);
    }
}
