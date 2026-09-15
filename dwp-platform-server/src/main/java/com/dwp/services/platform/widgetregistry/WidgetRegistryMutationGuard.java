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
    private final Clock clock;

    @Autowired
    public WidgetRegistryMutationGuard(WidgetRuntimeControlRepository controls) {
        this(controls, Clock.systemUTC());
    }

    WidgetRegistryMutationGuard(WidgetRuntimeControlRepository controls, Clock clock) {
        this.controls = controls;
        this.clock = clock;
    }

    public void requireAllowed(Long tenantId, String providerProductKey, UUID definitionId, UUID versionId) {
        boolean disabled = controls.findActiveDisabled(now()).stream()
                .filter(control -> "CATALOG_MUTATIONS".equals(control.getControlScope()))
                .anyMatch(control -> matches(control, tenantId, providerProductKey, definitionId, versionId));
        if (disabled) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Widget Registry catalog mutations are disabled by an active safety control.");
        }
    }

    boolean runtimeDenied(
            String scope, Long tenantId, String providerProductKey, UUID definitionId, UUID versionId) {
        return controls.findActiveDisabled(now()).stream()
                .filter(control -> scope.equals(control.getControlScope()))
                .anyMatch(control -> matches(control, tenantId, providerProductKey, definitionId, versionId));
    }

    private boolean matches(
            WidgetRuntimeControl control,
            Long tenantId,
            String providerProductKey,
            UUID definitionId,
            UUID versionId) {
        return switch (control.getTargetType()) {
            case "GLOBAL" -> true;
            case "TENANT" -> control.getTenantId() != null && control.getTenantId().equals(tenantId);
            case "PROVIDER" -> control.getProviderProductKey() != null
                    && control.getProviderProductKey().equals(providerProductKey);
            case "DEFINITION" -> definitionId != null
                    && definitionId.toString().equals(control.getTargetId());
            case "VERSION" -> versionId != null && versionId.toString().equals(control.getTargetId());
            default -> true;
        };
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
