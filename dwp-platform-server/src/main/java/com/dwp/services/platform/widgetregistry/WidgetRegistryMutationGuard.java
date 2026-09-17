package com.dwp.services.platform.widgetregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class WidgetRegistryMutationGuard {
    private final WidgetRuntimeControlRepository controls;
    private final WidgetRuntimeEnableApprovalRepository approvals;
    private final Clock clock;

    @Autowired
    public WidgetRegistryMutationGuard(
            WidgetRuntimeControlRepository controls,
            WidgetRuntimeEnableApprovalRepository approvals) {
        this(controls, approvals, Clock.systemUTC());
    }

    WidgetRegistryMutationGuard(
            WidgetRuntimeControlRepository controls,
            WidgetRuntimeEnableApprovalRepository approvals,
            Clock clock) {
        this.controls = controls;
        this.approvals = approvals;
        this.clock = clock;
    }

    WidgetRegistryMutationGuard(WidgetRuntimeControlRepository controls, Clock clock) {
        this(controls, null, clock);
    }

    public void requireAllowed(Long tenantId, String providerProductKey, UUID definitionId, UUID versionId) {
        boolean disabled = controls.findActiveDisabled(now()).stream()
                .filter(control -> "CATALOG_MUTATIONS".equals(control.getControlScope()))
                .anyMatch(control -> matches(
                        control, tenantId, providerProductKey, definitionId, versionId, null, null));
        if (disabled) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Widget Registry catalog mutations are disabled by an active safety control.");
        }
    }

    public boolean runtimeDenied(
            String scope, Long tenantId, String providerProductKey, UUID definitionId, UUID versionId) {
        return runtimeDenied(
                scope, tenantId, providerProductKey, definitionId, versionId, null, null);
    }

    public boolean runtimeDenied(
            String scope,
            Long tenantId,
            String providerProductKey,
            UUID definitionId,
            UUID versionId,
            String mode,
            String actionContractId) {
        return controls.findActiveDisabled(now()).stream()
                .filter(control -> scope.equals(control.getControlScope()))
                .anyMatch(control -> matches(
                        control, tenantId, providerProductKey, definitionId, versionId,
                        mode, actionContractId));
    }

    public boolean runtimeActionApproved(
            Long tenantId,
            String providerProductKey,
            String actionContractId) {
        return controls.findEnabledActionApprovals(now()).stream()
                .filter(control -> approvals != null && approvals.existsByControlIdAndApprovalState(
                        control.getControlId(), "CONSUMED"))
                .anyMatch(control -> matches(
                        control, tenantId, providerProductKey, null, null,
                        null, actionContractId));
    }

    private boolean matches(
            WidgetRuntimeControl control,
            Long tenantId,
            String providerProductKey,
            UUID definitionId,
            UUID versionId,
            String mode,
            String actionContractId) {
        if (control.getTenantId() != null && !control.getTenantId().equals(tenantId)) return false;
        return switch (control.getTargetType()) {
            case "GLOBAL" -> true;
            case "TENANT" -> control.getTenantId() != null && control.getTenantId().equals(tenantId);
            case "PROVIDER" -> control.getProviderProductKey() != null
                    && control.getProviderProductKey().equals(providerProductKey);
            case "DEFINITION" -> definitionId != null
                    && definitionId.toString().equals(control.getTargetId());
            case "VERSION" -> versionId != null && versionId.toString().equals(control.getTargetId());
            case "MODE" -> mode != null && mode.equals(control.getTargetId());
            case "ACTION" -> actionContractId != null
                    && actionContractId.equals(control.getTargetId())
                    && control.getProviderProductKey() != null
                    && control.getProviderProductKey().equals(providerProductKey);
            default -> true;
        };
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
