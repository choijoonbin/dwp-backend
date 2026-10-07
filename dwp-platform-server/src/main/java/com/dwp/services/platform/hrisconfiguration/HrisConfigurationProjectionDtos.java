package com.dwp.services.platform.hrisconfiguration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class HrisConfigurationProjectionDtos {

    private HrisConfigurationProjectionDtos() {
    }

    public enum ProjectionState {
        COMPLETE,
        PARTIAL,
        UNAVAILABLE
    }

    public enum SourceState {
        AVAILABLE,
        UNAVAILABLE,
        STALE,
        REVOKED,
        CONFIGURATION_REQUIRED
    }

    public record SourceStatus(String source, SourceState state, String reasonCode) {
    }

    public record MenuItem(
            String navigationKey,
            String itemType,
            String label,
            String description,
            String route,
            String iconKey,
            String requiredResourceKey,
            String requiredPermissionCode,
            List<MenuItem> children) {
    }

    public record WidgetItem(
            UUID templateId,
            String templateKey,
            String templateName,
            String widgetKey,
            String size,
            String height,
            long templateVersion) {
    }

    public record Projection(
            Long tenantId,
            Long subjectId,
            OffsetDateTime evaluatedAt,
            String projectionVersion,
            ProjectionState state,
            boolean readOnly,
            boolean commandAuthorizationReusable,
            List<String> allowedActions,
            List<MenuItem> menus,
            List<WidgetItem> widgets,
            List<SourceStatus> sources) {
    }
}
