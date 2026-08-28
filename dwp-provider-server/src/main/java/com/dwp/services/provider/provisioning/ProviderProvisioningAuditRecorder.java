package com.dwp.services.provider.provisioning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.provider.ProviderOperationsRepository;
import com.dwp.services.provider.audit.ProviderAuditService;
import com.dwp.services.provider.operation.ProviderOperation;
import com.dwp.services.provider.operation.ProviderOperationRepository;
import com.dwp.services.provider.tenant.ProviderTenant;
import com.dwp.services.provider.tenant.ProviderTenantRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class ProviderProvisioningAuditRecorder {

    private final ProviderOperationRepository operationRepository;
    private final ProviderTenantRepository tenantRepository;
    private final ProviderOperationsRepository operationsRepository;
    private final ProviderAuditService auditService;

    public ProviderProvisioningAuditRecorder(
            ProviderOperationRepository operationRepository,
            ProviderTenantRepository tenantRepository,
            ProviderOperationsRepository operationsRepository,
            ProviderAuditService auditService) {
        this.operationRepository = operationRepository;
        this.tenantRepository = tenantRepository;
        this.operationsRepository = operationsRepository;
        this.auditService = auditService;
    }

    public void success(UUID operationId, String correlationId) {
        ProviderOperation operation = operation(operationId);
        if ("MAINTENANCE_SCHEDULE".equals(operation.getOperationType())) {
            UUID maintenanceId = operationsRepository.maintenanceWindowId(operationId)
                    .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
            auditService.success(
                    "provider.maintenance.scheduled", "MAINTENANCE_WINDOW", maintenanceId.toString(),
                    operation.getProviderTenantId(), organizationId(operation.getProviderTenantId()),
                    correlationId,
                    Map.of("operationId", operationId, "planHash", operation.getPlanHash()));
            return;
        }
        ProviderTenant tenant = tenant(operation.getProviderTenantId());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("tenantKey", tenant.getTenantKey());
        snapshot.put("authTenantId", tenant.getAuthTenantId());
        auditService.success(
                "provider.tenant-onboarding.succeeded", "PROVIDER_TENANT",
                tenant.getProviderTenantId().toString(), tenant.getProviderTenantId(),
                tenant.getOrganizationId(), correlationId, snapshot);
    }

    public void failure(
            UUID operationId,
            String stepKey,
            String errorCode,
            int attemptNumber,
            String correlationId) {
        ProviderOperation operation = operation(operationId);
        boolean tenantOnboarding = "TENANT_ONBOARD".equals(operation.getOperationType());
        UUID tenantId = operation.getProviderTenantId();
        auditService.failed(
                tenantOnboarding
                        ? "provider.tenant-onboarding.step-failed"
                        : "provider.maintenance.schedule-failed",
                "PROVIDER_OPERATION", operationId.toString(), tenantId, organizationId(tenantId),
                correlationId,
                Map.of("step", stepKey, "errorCode", errorCode, "attempt", attemptNumber));
    }

    private ProviderOperation operation(UUID operationId) {
        return operationRepository.findById(operationId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private ProviderTenant tenant(UUID tenantId) {
        if (tenantId == null) throw new BaseException(ErrorCode.INVALID_STATE);
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private UUID organizationId(UUID tenantId) {
        if (tenantId == null) return null;
        return tenantRepository.findById(tenantId).map(ProviderTenant::getOrganizationId).orElse(null);
    }
}
