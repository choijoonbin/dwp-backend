package com.dwp.services.notification.domain;

import java.util.Map;
import java.util.UUID;

/**
 * Immutable notification rendering and admission contract.
 *
 * <p>The contract is owned by the notification domain rather than its JDBC repository so policy,
 * rendering, and transaction services do not depend on a persistence implementation.</p>
 */
public record TemplateContract(
        UUID typeVersionId,
        long typeScopeTenantId,
        UUID templateVersionId,
        long templateScopeTenantId,
        UUID templateOverrideRevisionId,
        String typeKey,
        String ownerAppKey,
        String priority,
        String urgency,
        String locale,
        String titleTemplate,
        String previewTemplate,
        String bodyTemplate,
        Map<String, Object> actionTemplate) {
}
